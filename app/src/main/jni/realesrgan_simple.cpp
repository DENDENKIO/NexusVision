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

int RealESRGANSimple::guidedBlend(const unsigned char* guidePixels,
                                    const unsigned char* enhancedPixels,
                                    int w, int h,
                                    unsigned char* outputPixels,
                                    float aiWeight) {
    LOGI("GuidedBlend: %dx%d, aiWeight=%.2f", w, h, aiWeight);

    // ガイデッド超解像の原理:
    // 1. guidePixels（元画像リサイズ版）から高周波成分（エッジ・テクスチャ）を抽出
    // 2. enhancedPixels（AI処理結果）から低周波成分（滑らかな領域の改善）を取得
    // 3. 合成: output = guide_highfreq + enhanced_lowfreq

    size_t pixels = (size_t)w * h;

    // Step 1: ぼかし版を作成（5x5ボックスブラー）
    std::vector<float> guideBlurR(pixels);
    std::vector<float> guideBlurG(pixels);
    std::vector<float> guideBlurB(pixels);

    std::vector<float> enhBlurR(pixels);
    std::vector<float> enhBlurG(pixels);
    std::vector<float> enhBlurB(pixels);

    const int radius = 2;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float gSumR = 0, gSumG = 0, gSumB = 0;
            float eSumR = 0, eSumG = 0, eSumB = 0;
            int count = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        int idx = (ny * w + nx) * 4;
                        gSumR += guidePixels[idx + 0];
                        gSumG += guidePixels[idx + 1];
                        gSumB += guidePixels[idx + 2];
                        eSumR += enhancedPixels[idx + 0];
                        eSumG += enhancedPixels[idx + 1];
                        eSumB += enhancedPixels[idx + 2];
                        count++;
                    }
                }
            }
            size_t pidx = y * w + x;
            guideBlurR[pidx] = gSumR / count;
            guideBlurG[pidx] = gSumG / count;
            guideBlurB[pidx] = gSumB / count;
            enhBlurR[pidx] = eSumR / count;
            enhBlurG[pidx] = eSumG / count;
            enhBlurB[pidx] = eSumB / count;
        }
    }

    // Step 2: 合成
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            size_t pidx = y * w + x;
            int idx = pidx * 4;

            float gR = guidePixels[idx + 0];
            float gG = guidePixels[idx + 1];
            float gB = guidePixels[idx + 2];

            float eR = enhancedPixels[idx + 0];
            float eG = enhancedPixels[idx + 1];
            float eB = enhancedPixels[idx + 2];

            // 元画像のディテール（高周波）= 元 - ぼかし
            float gDetailR = gR - guideBlurR[pidx];
            float gDetailG = gG - guideBlurG[pidx];
            float gDetailB = gB - guideBlurB[pidx];

            // AI結果のベース（低周波）
            float eBaseR = enhBlurR[pidx];
            float eBaseG = enhBlurG[pidx];
            float eBaseB = enhBlurB[pidx];

            // 合成: AIベース + 元ディテール
            float fusedR = eBaseR + gDetailR;
            float fusedG = eBaseG + gDetailG;
            float fusedB = eBaseB + gDetailB;

            // さらにAI結果と直接ブレンド（aiWeightで調整）
            float outR = fusedR * (1.0f - aiWeight) + eR * aiWeight;
            float outG = fusedG * (1.0f - aiWeight) + eG * aiWeight;
            float outB = fusedB * (1.0f - aiWeight) + eB * aiWeight;

            // clamp
            if (outR < 0) outR = 0; if (outR > 255) outR = 255;
            if (outG < 0) outG = 0; if (outG > 255) outG = 255;
            if (outB < 0) outB = 0; if (outB > 255) outB = 255;

            outputPixels[idx + 0] = (unsigned char)(outR + 0.5f);
            outputPixels[idx + 1] = (unsigned char)(outG + 0.5f);
            outputPixels[idx + 2] = (unsigned char)(outB + 0.5f);
            outputPixels[idx + 3] = guidePixels[idx + 3]; // alpha は元画像から
        }
    }

    LOGI("GuidedBlend complete: %dx%d", w, h);
    return 0;
}
