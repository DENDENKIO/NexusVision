// ファイルパス: app/src/main/jni/realesrgan_simple.cpp
#include "realesrgan_simple.h"
#include <algorithm>
#include <cstring>
#include <cmath>
#include <vector>
#include <android/log.h>
#include "datareader.h"
#include "gpu.h"

#define TAG "RealESRGAN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

RealESRGANSimple::RealESRGANSimple(int gpuid) : gpuid(gpuid), loaded(false), useGpu(false) {
    // Vulkan GPU が使えるか確認
    int gpuCount = ncnn::get_gpu_count();
    LOGI("Vulkan GPU count: %d", gpuCount);

    if (gpuCount > 0) {
        if (gpuid < 0) gpuid = 0; // auto -> first GPU
        this->gpuid = gpuid;
        useGpu = true;

        net.opt.use_vulkan_compute = true;
        // FP16で計算（メモリ半減・速度向上、画質差なし）
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = true; // Mali-G68はFP16演算対応
        net.opt.use_packing_layout = true;
        // スレッド: GPU使用時は1で十分（GPU側で並列化）
        net.opt.num_threads = 1;

        LOGI("Vulkan GPU enabled: gpu_id=%d, FP16=ON", gpuid);
    } else {
        // Vulkan非対応 → CPUフォールバック
        useGpu = false;
        net.opt.use_vulkan_compute = false;
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = false;
        net.opt.use_packing_layout = true;
        net.opt.num_threads = 2; // CPU時はスレッド2
        LOGW("No Vulkan GPU, falling back to CPU (FP16 storage)");
    }
}

RealESRGANSimple::~RealESRGANSimple() {
    net.clear();
}

int RealESRGANSimple::load(const unsigned char* paramBuffer, int paramLen,
                            const unsigned char* modelBuffer, int modelLen) {
    // param はテキスト → null終端必要
    std::vector<char> paramStr(paramLen + 1);
    memcpy(paramStr.data(), paramBuffer, paramLen);
    paramStr[paramLen] = '\0';

    int ret = net.load_param_mem(paramStr.data());
    if (ret != 0) {
        LOGE("load_param_mem failed: %d", ret);
        return -1;
    }
    LOGI("Param loaded, ret=%d", ret);

    // model はバイナリ → DataReaderFromMemory
    const unsigned char* modelPtr = modelBuffer;
    ncnn::DataReaderFromMemory dr(modelPtr);
    ret = net.load_model(dr);
    if (ret != 0) {
        LOGE("load_model failed: %d", ret);
        return -2;
    }

    loaded = true;
    LOGI("Model loaded (param=%d bytes, model=%d bytes, gpu=%s)",
         paramLen, modelLen, useGpu ? "Vulkan" : "CPU");
    return 0;
}

int RealESRGANSimple::load(const std::string& paramPath, const std::string& modelPath) {
    int ret = net.load_param(paramPath.c_str());
    if (ret != 0) {
        LOGE("load_param(%s) failed: %d", paramPath.c_str(), ret);
        return -1;
    }

    ret = net.load_model(modelPath.c_str());
    if (ret != 0) {
        LOGE("load_model(%s) failed: %d", modelPath.c_str(), ret);
        return -2;
    }

    loaded = true;
    LOGI("Model loaded from files (gpu=%s)", useGpu ? "Vulkan" : "CPU");
    return 0;
}

int RealESRGANSimple::process(const unsigned char* inputPixels, int w, int h,
                               unsigned char* outputPixels) {
    if (!loaded) {
        LOGE("Model not loaded!");
        return -1;
    }

    const int TILE = tileSize;
    const int outW = w * scale;
    const int outH = h * scale;

    const int xtiles = (w + TILE - 1) / TILE;
    const int ytiles = (h + TILE - 1) / TILE;
    const int totalTiles = xtiles * ytiles;

    LOGI("Processing %dx%d -> %dx%d (tiles=%dx%d=%d, tile=%d, pad=%d, gpu=%s)",
         w, h, outW, outH, xtiles, ytiles, totalTiles, TILE, prepadding,
         useGpu ? "Vulkan" : "CPU");

    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            int inTileX0 = xi * TILE;
            int inTileY0 = yi * TILE;
            int inTileX1 = std::min(inTileX0 + TILE, w);
            int inTileY1 = std::min(inTileY0 + TILE, h);

            int tileW = inTileX1 - inTileX0;
            int tileH = inTileY1 - inTileY0;

            // パディング付き入力
            int padX0 = std::max(inTileX0 - prepadding, 0);
            int padY0 = std::max(inTileY0 - prepadding, 0);
            int padX1 = std::min(inTileX1 + prepadding, w);
            int padY1 = std::min(inTileY1 + prepadding, h);

            int padW = padX1 - padX0;
            int padH = padY1 - padY0;

            // RGBA → RGB
            ncnn::Mat in(padW, padH, 3);
            {
                const float scale_val = 1.0f / 255.0f;
                float* rPtr = in.channel(0);
                float* gPtr = in.channel(1);
                float* bPtr = in.channel(2);

                for (int row = 0; row < padH; row++) {
                    for (int col = 0; col < padW; col++) {
                        int srcIdx = ((padY0 + row) * w + (padX0 + col)) * 4;
                        int dstIdx = row * padW + col;
                        rPtr[dstIdx] = inputPixels[srcIdx + 0] * scale_val;
                        gPtr[dstIdx] = inputPixels[srcIdx + 1] * scale_val;
                        bPtr[dstIdx] = inputPixels[srcIdx + 2] * scale_val;
                    }
                }
            }

            // 推論
            ncnn::Extractor ex = net.create_extractor();
            if (useGpu) {
                ex.set_blob_vkallocator(net.opt.blob_vkallocator);
                ex.set_workspace_vkallocator(net.opt.workspace_vkallocator);
                ex.set_staging_vkallocator(net.opt.staging_vkallocator);
            }

            ex.input("data", in);

            ncnn::Mat out;
            int ret = ex.extract("output", out);
            if (ret != 0) {
                LOGE("Inference failed tile (%d,%d): %d", xi, yi, ret);
                // タイル失敗時: 元ピクセルをコピー（クラッシュしない）
                for (int row = 0; row < tileH * scale; row++) {
                    for (int col = 0; col < tileW * scale; col++) {
                        int outX = inTileX0 * scale + col;
                        int outY = inTileY0 * scale + row;
                        if (outX < outW && outY < outH) {
                            // 元ピクセルを最近傍で引き伸ばし
                            int srcX = std::min(inTileX0 + col / scale, w - 1);
                            int srcY = std::min(inTileY0 + row / scale, h - 1);
                            int srcIdx = (srcY * w + srcX) * 4;
                            int dstIdx = (outY * outW + outX) * 4;
                            memcpy(outputPixels + dstIdx, inputPixels + srcIdx, 4);
                        }
                    }
                }
                LOGW("Tile (%d,%d) fallback to nearest-neighbor", xi, yi);
                continue;
            }

            // 出力 float[0..1] → byte → 出力バッファ
            int outPadW = out.w;
            int outPadH = out.h;

            int offsetX = (inTileX0 - padX0) * scale;
            int offsetY = (inTileY0 - padY0) * scale;
            int outTileW = tileW * scale;
            int outTileH = tileH * scale;

            const float* rOut = out.channel(0);
            const float* gOut = out.channel(1);
            const float* bOut = out.channel(2);

            for (int row = 0; row < outTileH; row++) {
                for (int col = 0; col < outTileW; col++) {
                    int srcIdx = (offsetY + row) * outPadW + (offsetX + col);
                    int outX = inTileX0 * scale + col;
                    int outY = inTileY0 * scale + row;

                    if (outX < outW && outY < outH && srcIdx < outPadW * outPadH) {
                        int dstIdx = (outY * outW + outX) * 4;
                        float r = std::max(0.0f, std::min(1.0f, rOut[srcIdx]));
                        float g = std::max(0.0f, std::min(1.0f, gOut[srcIdx]));
                        float b = std::max(0.0f, std::min(1.0f, bOut[srcIdx]));
                        outputPixels[dstIdx + 0] = (unsigned char)(r * 255.0f + 0.5f);
                        outputPixels[dstIdx + 1] = (unsigned char)(g * 255.0f + 0.5f);
                        outputPixels[dstIdx + 2] = (unsigned char)(b * 255.0f + 0.5f);
                        outputPixels[dstIdx + 3] = 255;
                    }
                }
            }

            int done = yi * xtiles + xi + 1;
            if (done % 4 == 0 || done == totalTiles) {
                LOGI("Progress: %d/%d (%.1f%%)", done, totalTiles,
                     (float)done / totalTiles * 100.0f);
            }
        }
    }

    LOGI("Complete: %dx%d (%s)", outW, outH, useGpu ? "Vulkan GPU" : "CPU");
    return 0;
}

int RealESRGANSimple::nlmDenoise(const unsigned char* inputPixels, int w, int h,
                                  unsigned char* outputPixels,
                                  float strength, int patchSize, int searchSize) {
    LOGI("NLM Denoise: %dx%d, strength=%.1f, patch=%d, search=%d",
         w, h, strength, patchSize, searchSize);
    
    const int halfPatch = patchSize / 2;
    const int halfSearch = searchSize / 2;
    const float h2 = strength * strength;
    
    // エッジ1行分はコピー
    memcpy(outputPixels, inputPixels, (size_t)w * h * 4);
    
    for (int y = halfSearch + halfPatch; y < h - halfSearch - halfPatch; y++) {
        for (int x = halfSearch + halfPatch; x < w - halfSearch - halfPatch; x++) {
            
            float weightSum = 0.0f;
            float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
            
            for (int sy = -halfSearch; sy <= halfSearch; sy++) {
                for (int sx = -halfSearch; sx <= halfSearch; sx++) {
                    int nx = x + sx;
                    int ny = y + sy;
                    
                    // パッチ間距離（SSD）
                    float dist = 0.0f;
                    for (int py = -halfPatch; py <= halfPatch; py++) {
                        for (int px = -halfPatch; px <= halfPatch; px++) {
                            int idx1 = ((y + py) * w + (x + px)) * 4;
                            int idx2 = ((ny + py) * w + (nx + px)) * 4;
                            for (int c = 0; c < 3; c++) {
                                float diff = (float)inputPixels[idx1 + c] -
                                             (float)inputPixels[idx2 + c];
                                dist += diff * diff;
                            }
                        }
                    }
                    
                    int patchArea = patchSize * patchSize * 3;
                    dist /= patchArea;
                    
                    float weight = expf(-dist / h2);
                    weightSum += weight;
                    
                    int srcIdx = (ny * w + nx) * 4;
                    sumR += weight * inputPixels[srcIdx + 0];
                    sumG += weight * inputPixels[srcIdx + 1];
                    sumB += weight * inputPixels[srcIdx + 2];
                }
            }
            
            int dstIdx = (y * w + x) * 4;
            if (weightSum > 0.0f) {
                outputPixels[dstIdx + 0] = (unsigned char)(sumR / weightSum + 0.5f);
                outputPixels[dstIdx + 1] = (unsigned char)(sumG / weightSum + 0.5f);
                outputPixels[dstIdx + 2] = (unsigned char)(sumB / weightSum + 0.5f);
            }
            outputPixels[dstIdx + 3] = inputPixels[dstIdx + 3]; // Alpha
        }
    }
    
    LOGI("NLM Denoise complete: %dx%d", w, h);
    return 0;
}

int RealESRGANSimple::sharpen(const unsigned char* inputPixels, int w, int h,
                               unsigned char* outputPixels, float strength) {
    LOGI("Sharpen: %dx%d, strength=%.2f", w, h, strength);

    // 3x3 Gaussian blur → Unsharp Mask
    // output = original + strength * (original - blurred)
    // strength: 0.3〜1.0 推奨

    if (strength < 0.01f) {
        // 強度0ならコピーのみ
        memcpy(outputPixels, inputPixels, (size_t)w * h * 4);
        return 0;
    }

    // まずコピー（エッジの1px境界はそのまま）
    memcpy(outputPixels, inputPixels, (size_t)w * h * 4);

    // 内部ピクセルにUSMを適用
    for (int y = 1; y < h - 1; y++) {
        for (int x = 1; x < w - 1; x++) {
            for (int c = 0; c < 3; c++) { // R, G, B のみ（Aはスキップ）
                // 3x3 Gaussian近似（ボックスブラー）
                int sum = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        sum += inputPixels[((y + dy) * w + (x + dx)) * 4 + c];
                    }
                }
                float blurred = (float)sum / 9.0f;
                float original = (float)inputPixels[(y * w + x) * 4 + c];
                float detail = original - blurred;
                float sharpened = original + strength * detail;

                // clamp
                if (sharpened < 0.0f) sharpened = 0.0f;
                if (sharpened > 255.0f) sharpened = 255.0f;

                outputPixels[(y * w + x) * 4 + c] = (unsigned char)(sharpened + 0.5f);
            }
            // Alpha はそのまま
            outputPixels[(y * w + x) * 4 + 3] = inputPixels[(y * w + x) * 4 + 3];
        }
    }

    LOGI("Sharpen complete: %dx%d", w, h);
    return 0;
}

int RealESRGANSimple::laplacianBlend(const unsigned char* originalPixels,
                                      const unsigned char* enhancedPixels,
                                      int w, int h,
                                      unsigned char* outputPixels,
                                      float detailStrength,
                                      float sharpenStrength) {
    LOGI("LaplacianBlend: %dx%d, detail=%.2f, sharpen=%.2f", w, h, detailStrength, sharpenStrength);

    // ラプラシアンピラミッド合成の原理:
    // 1. original（元画像リサイズ版）から高周波を抽出: original_highfreq = original - blurred(original)
    // 2. enhanced（AI処理結果）から低周波を抽出: enhanced_lowfreq = blurred(enhanced)
    // 3. 合成: output = enhanced_lowfreq + detailStrength * original_highfreq

    size_t pixels = (size_t)w * h;

    // --- Step 1: 5x5 ガウシアンぼかし準備 ---
    std::vector<float> origR(pixels), origG(pixels), origB(pixels);
    std::vector<float> enhR(pixels), enhG(pixels), enhB(pixels);

    for (size_t i = 0; i < pixels; i++) {
        origR[i] = originalPixels[i * 4 + 0];
        origG[i] = originalPixels[i * 4 + 1];
        origB[i] = originalPixels[i * 4 + 2];
        enhR[i] = enhancedPixels[i * 4 + 0];
        enhG[i] = enhancedPixels[i * 4 + 1];
        enhB[i] = enhancedPixels[i * 4 + 2];
    }

    // 分離可能5x5ガウシアン: [1,4,6,4,1]/16
    static const float kernel[5] = {1.0f/16, 4.0f/16, 6.0f/16, 4.0f/16, 1.0f/16};
    std::vector<float> tmpR(pixels), tmpG(pixels), tmpB(pixels);
    std::vector<float> origBlurR(pixels), origBlurG(pixels), origBlurB(pixels);
    std::vector<float> enhBlurR(pixels), enhBlurG(pixels), enhBlurB(pixels);

    // original のぼかし
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float sR = 0, sG = 0, sB = 0;
            for (int k = -2; k <= 2; k++) {
                int nx = std::max(0, std::min(x + k, w - 1));
                float wt = kernel[k + 2];
                sR += origR[y * w + nx] * wt;
                sG += origG[y * w + nx] * wt;
                sB += origB[y * w + nx] * wt;
            }
            tmpR[y * w + x] = sR;
            tmpG[y * w + x] = sG;
            tmpB[y * w + x] = sB;
        }
    }
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float sR = 0, sG = 0, sB = 0;
            for (int k = -2; k <= 2; k++) {
                int ny = std::max(0, std::min(y + k, h - 1));
                float wt = kernel[k + 2];
                sR += tmpR[ny * w + x] * wt;
                sG += tmpG[ny * w + x] * wt;
                sB += tmpB[ny * w + x] * wt;
            }
            origBlurR[y * w + x] = sR;
            origBlurG[y * w + x] = sG;
            origBlurB[y * w + x] = sB;
        }
    }

    // enhanced のぼかし
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float sR = 0, sG = 0, sB = 0;
            for (int k = -2; k <= 2; k++) {
                int nx = std::max(0, std::min(x + k, w - 1));
                float wt = kernel[k + 2];
                sR += enhR[y * w + nx] * wt;
                sG += enhG[y * w + nx] * wt;
                sB += enhB[y * w + nx] * wt;
            }
            tmpR[y * w + x] = sR;
            tmpG[y * w + x] = sG;
            tmpB[y * w + x] = sB;
        }
    }
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float sR = 0, sG = 0, sB = 0;
            for (int k = -2; k <= 2; k++) {
                int ny = std::max(0, std::min(y + k, h - 1));
                float wt = kernel[k + 2];
                sR += tmpR[ny * w + x] * wt;
                sG += tmpG[ny * w + x] * wt;
                sB += tmpB[ny * w + x] * wt;
            }
            enhBlurR[y * w + x] = sR;
            enhBlurG[y * w + x] = sG;
            enhBlurB[y * w + x] = sB;
        }
    }

    // --- Step 2: 合成 (AI低周波 + 元画像高周波) ---
    for (size_t i = 0; i < pixels; i++) {
        float detR = (origR[i] - origBlurR[i]) * detailStrength;
        float detG = (origG[i] - origBlurG[i]) * detailStrength;
        float detB = (origB[i] - origBlurB[i]) * detailStrength;

        float fR = enhBlurR[i] + detR;
        float fG = enhBlurG[i] + detG;
        float fB = enhBlurB[i] + detB;

        if (fR < 0) fR = 0; if (fR > 255) fR = 255;
        if (fG < 0) fG = 0; if (fG > 255) fG = 255;
        if (fB < 0) fB = 0; if (fB > 255) fB = 255;

        outputPixels[i * 4 + 0] = (unsigned char)(fR + 0.5f);
        outputPixels[i * 4 + 1] = (unsigned char)(fG + 0.5f);
        outputPixels[i * 4 + 2] = (unsigned char)(fB + 0.5f);
        outputPixels[i * 4 + 3] = originalPixels[i * 4 + 3];
    }

    // --- Step 3: アンシャープマスク仕上げ ---
    if (sharpenStrength > 0.01f) {
        std::vector<unsigned char> temp(pixels * 4);
        memcpy(temp.data(), outputPixels, pixels * 4);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                for (int c = 0; c < 3; c++) {
                    int sum = 0;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++)
                            sum += temp[((y+dy)*w + (x+dx))*4 + c];
                    float blur = (float)sum / 9.0f;
                    float orig = (float)temp[(y*w+x)*4 + c];
                    float sharp = orig + sharpenStrength * (orig - blur);
                    if (sharp < 0) sharp = 0; if (sharp > 255) sharp = 255;
                    outputPixels[(y*w+x)*4 + c] = (unsigned char)(sharp + 0.5f);
                }
            }
        }
    }

    LOGI("LaplacianBlend complete: %dx%d", w, h);
    return 0;
}
