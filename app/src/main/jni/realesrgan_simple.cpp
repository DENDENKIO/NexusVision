// ファイルパス: app/src/main/jni/realesrgan_simple.cpp
#include "realesrgan_simple.h"
#include <algorithm>
#include <cstring>
#include <cmath>
#include <android/log.h>
#include "datareader.h"

#define TAG "RealESRGAN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

RealESRGANSimple::RealESRGANSimple() {
    net.opt.use_vulkan_compute = false;
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = false;
    net.opt.use_packing_layout = true;
    net.opt.num_threads = 4;
    loaded = false;
}

RealESRGANSimple::~RealESRGANSimple() {
    net.clear();
}

int RealESRGANSimple::load(const unsigned char* paramBuffer, int paramLen,
                            const unsigned char* modelBuffer, int modelLen) {
    // param はテキスト形式 → null 終端が必要
    std::vector<char> paramStr(paramLen + 1);
    memcpy(paramStr.data(), paramBuffer, paramLen);
    paramStr[paramLen] = '\0';

    int ret = net.load_param_mem(paramStr.data());
    if (ret != 0) {
        LOGE("load_param_mem failed: %d", ret);
        return -1;
    }
    LOGI("Param loaded, ret=%d", ret);

    // model はバイナリ形式 → DataReaderFromMemory を使用
    ncnn::DataReaderFromMemory dr(reinterpret_cast<const char*>(modelBuffer));
    ret = net.load_model(dr);
    if (ret != 0) {
        LOGE("load_model from memory failed: %d", ret);
        return -2;
    }

    loaded = true;
    LOGI("Model loaded successfully from memory (param=%d bytes, model=%d bytes)", paramLen, modelLen);
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
    LOGI("Model loaded from files: %s, %s", paramPath.c_str(), modelPath.c_str());
    return 0;
}

int RealESRGANSimple::process(const unsigned char* inputPixels, int w, int h,
                               unsigned char* outputPixels) {
    if (!loaded) {
        LOGE("Model not loaded!");
        return -1;
    }

    const int TILE_SIZE = tileSize;
    const int outW = w * scale;
    const int outH = h * scale;

    const int xtiles = (w + TILE_SIZE - 1) / TILE_SIZE;
    const int ytiles = (h + TILE_SIZE - 1) / TILE_SIZE;

    LOGI("Processing %dx%d -> %dx%d, tiles=%dx%d, tileSize=%d, prepadding=%d",
         w, h, outW, outH, xtiles, ytiles, TILE_SIZE, prepadding);

    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            // タイル境界
            int inTileX0 = xi * TILE_SIZE;
            int inTileY0 = yi * TILE_SIZE;
            int inTileX1 = std::min(inTileX0 + TILE_SIZE, w);
            int inTileY1 = std::min(inTileY0 + TILE_SIZE, h);

            int tileW = inTileX1 - inTileX0;
            int tileH = inTileY1 - inTileY0;

            // パディング付き入力領域
            int padX0 = std::max(inTileX0 - prepadding, 0);
            int padY0 = std::max(inTileY0 - prepadding, 0);
            int padX1 = std::min(inTileX1 + prepadding, w);
            int padY1 = std::min(inTileY1 + prepadding, h);

            int padW = padX1 - padX0;
            int padH = padY1 - padY0;

            // RGBA → RGB 抽出
            std::vector<unsigned char> tileRGB(padW * padH * 3);
            for (int row = 0; row < padH; row++) {
                for (int col = 0; col < padW; col++) {
                    int srcIdx = ((padY0 + row) * w + (padX0 + col)) * 4;
                    int dstIdx = (row * padW + col) * 3;
                    tileRGB[dstIdx + 0] = inputPixels[srcIdx + 0]; // R
                    tileRGB[dstIdx + 1] = inputPixels[srcIdx + 1]; // G
                    tileRGB[dstIdx + 2] = inputPixels[srcIdx + 2]; // B
                }
            }

            // from_pixels: byte[0..255] → float[0..255] 3ch Mat
            ncnn::Mat in = ncnn::Mat::from_pixels(tileRGB.data(),
                                                   ncnn::Mat::PIXEL_RGB,
                                                   padW, padH);

            // 正規化: [0,255] → [0,1]（Real-ESRGAN が期待する範囲）
            const float norm_vals[3] = { 1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f };
            in.substract_mean_normalize(0, norm_vals);

            // 推論
            ncnn::Extractor ex = net.create_extractor();
            ex.input("data", in);

            ncnn::Mat out;
            int ret = ex.extract("output", out);
            if (ret != 0) {
                LOGE("Inference failed for tile (%d,%d): %d", xi, yi, ret);
                return -2;
            }

            // 出力 float[0..1] → byte[0..255]
            int outPadW = out.w;
            int outPadH = out.h;

            ncnn::Mat outScaled;
            outScaled.create_like(out);
            for (int c = 0; c < 3; c++) {
                const float* src = out.channel(c);
                float* dst = outScaled.channel(c);
                int size = out.w * out.h;
                for (int i = 0; i < size; i++) {
                    float v = src[i];
                    v = std::max(0.0f, std::min(1.0f, v));
                    dst[i] = v * 255.0f;
                }
            }

            std::vector<unsigned char> outRGB(outPadW * outPadH * 3);
            outScaled.to_pixels(outRGB.data(), ncnn::Mat::PIXEL_RGB);

            // パディング除去 → 出力バッファへコピー
            int offsetX = (inTileX0 - padX0) * scale;
            int offsetY = (inTileY0 - padY0) * scale;
            int outTileW = tileW * scale;
            int outTileH = tileH * scale;

            for (int row = 0; row < outTileH; row++) {
                for (int col = 0; col < outTileW; col++) {
                    int srcIdx = ((offsetY + row) * outPadW + (offsetX + col)) * 3;
                    int outX = inTileX0 * scale + col;
                    int outY = inTileY0 * scale + row;
                    int dstIdx = (outY * outW + outX) * 4;

                    if (outX < outW && outY < outH) {
                        outputPixels[dstIdx + 0] = outRGB[srcIdx + 0]; // R
                        outputPixels[dstIdx + 1] = outRGB[srcIdx + 1]; // G
                        outputPixels[dstIdx + 2] = outRGB[srcIdx + 2]; // B
                        outputPixels[dstIdx + 3] = 255;                 // A
                    }
                }
            }

            float progress = (float)(yi * xtiles + xi + 1) / (ytiles * xtiles) * 100.0f;
            LOGI("Tile (%d,%d) done, %.1f%%", xi, yi, progress);
        }
    }

    LOGI("Super-resolution complete: %dx%d", outW, outH);
    return 0;
}
