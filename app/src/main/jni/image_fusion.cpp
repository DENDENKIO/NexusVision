// ファイルパス: app/src/main/jni/image_fusion.cpp
#include "image_fusion.h"
#include "neon_dwt.h"
#include <vector>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <android/log.h>
#include <chrono>

#define FTAG "ImageFusion"
#define FLOGI(...) __android_log_print(ANDROID_LOG_INFO, FTAG, __VA_ARGS__)
#define FLOGE(...) __android_log_print(ANDROID_LOG_ERROR, FTAG, __VA_ARGS__)

namespace ImageFusion {

// ─── ヘルパー: bilinear補間で縮小 ───
static void bilinearDownsample(const float* src, int srcW, int srcH,
                               float* dst, int dstW, int dstH) {
    float scaleX = (float)srcW / dstW;
    float scaleY = (float)srcH / dstH;
    for (int y = 0; y < dstH; y++) {
        float srcY = (y + 0.5f) * scaleY - 0.5f;
        int y0 = (int)srcY;
        int y1 = y0 + 1;
        float fy = srcY - y0;
        if (y0 < 0) { y0 = 0; fy = 0; }
        if (y1 >= srcH) y1 = srcH - 1;
        for (int x = 0; x < dstW; x++) {
            float srcX = (x + 0.5f) * scaleX - 0.5f;
            int x0 = (int)srcX;
            int x1 = x0 + 1;
            float fx = srcX - x0;
            if (x0 < 0) { x0 = 0; fx = 0; }
            if (x1 >= srcW) x1 = srcW - 1;
            float v = src[y0 * srcW + x0] * (1 - fx) * (1 - fy)
                    + src[y0 * srcW + x1] * fx * (1 - fy)
                    + src[y1 * srcW + x0] * (1 - fx) * fy
                    + src[y1 * srcW + x1] * fx * fy;
            dst[y * dstW + x] = v;
        }
    }
}

// ─── ヘルパー: bilinear補間で拡大 ───
static void bilinearUpsample(const float* src, int srcW, int srcH,
                             float* dst, int dstW, int dstH) {
    bilinearDownsample(src, srcW, srcH, dst, dstW, dstH);
}

// ─── ヘルパー: bilinear (uint8 RGBA) ───
static void bilinearResizeRGBA(const uint8_t* src, int srcW, int srcH,
                               uint8_t* dst, int dstW, int dstH) {
    float scaleX = (float)srcW / dstW;
    float scaleY = (float)srcH / dstH;
    for (int y = 0; y < dstH; y++) {
        float srcY = (y + 0.5f) * scaleY - 0.5f;
        int y0 = std::max(0, (int)srcY);
        int y1 = std::min(srcH - 1, y0 + 1);
        float fy = srcY - (int)srcY;
        if (fy < 0) fy = 0;
        for (int x = 0; x < dstW; x++) {
            float srcX = (x + 0.5f) * scaleX - 0.5f;
            int x0 = std::max(0, (int)srcX);
            int x1 = std::min(srcW - 1, x0 + 1);
            float fx = srcX - (int)srcX;
            if (fx < 0) fx = 0;
            for (int c = 0; c < 4; c++) {
                float v = src[(y0 * srcW + x0) * 4 + c] * (1 - fx) * (1 - fy)
                        + src[(y0 * srcW + x1) * 4 + c] * fx * (1 - fy)
                        + src[(y1 * srcW + x0) * 4 + c] * (1 - fx) * fy
                        + src[(y1 * srcW + x1) * 4 + c] * fx * fy;
                dst[(y * dstW + x) * 4 + c] = (uint8_t)std::min(255.0f, std::max(0.0f, v + 0.5f));
            }
        }
    }
}

// ─── ヘルパー: Box Filter (積分画像 O(1)) ───
static void boxFilter(const float* input, int w, int h, int radius, float* output) {
    // 積分画像
    size_t sz = (size_t)(w + 1) * (h + 1);
    std::vector<float> sat(sz, 0.0f);

    // 積分画像構築
    for (int y = 0; y < h; y++) {
        float rowSum = 0;
        for (int x = 0; x < w; x++) {
            rowSum += input[y * w + x];
            sat[(y + 1) * (w + 1) + (x + 1)] = rowSum + sat[y * (w + 1) + (x + 1)];
        }
    }

    // Box filter
    for (int y = 0; y < h; y++) {
        int y0 = std::max(0, y - radius);
        int y1 = std::min(h - 1, y + radius);
        for (int x = 0; x < w; x++) {
            int x0 = std::max(0, x - radius);
            int x1 = std::min(w - 1, x + radius);
            float area = (float)((y1 - y0 + 1) * (x1 - x0 + 1));
            float sum = sat[(y1 + 1) * (w + 1) + (x1 + 1)]
                      - sat[y0 * (w + 1) + (x1 + 1)]
                      - sat[(y1 + 1) * (w + 1) + x0]
                      + sat[y0 * (w + 1) + x0];
            output[y * w + x] = sum / area;
        }
    }
}

// ════════════════════════════════════════════
// Stage 1: Fast Guided Filter
// ════════════════════════════════════════════
int guidedFilter(const uint8_t* guidance, const uint8_t* input,
                 int w, int h, uint8_t* output,
                 int r, float eps, int s) {
    if (!guidance || !input || !output || w < 4 || h < 4) return -1;
    auto t0 = std::chrono::steady_clock::now();
    FLOGI("GuidedFilter start: %dx%d, r=%d, eps=%.4f, s=%d", w, h, r, eps, s);

    int sw = w / s;
    int sh = h / s;
    if (sw < 2 || sh < 2) { sw = w; sh = h; s = 1; }
    int rp = r / s; // サブサンプリング後の半径
    if (rp < 1) rp = 1;
    float eps255 = eps * 255.0f * 255.0f; // [0,255]スケール用

    size_t fullSz = (size_t)w * h;
    size_t subSz = (size_t)sw * sh;

    // Alphaを先にコピー
    for (size_t i = 0; i < fullSz; i++) {
        output[i * 4 + 3] = guidance[i * 4 + 3];
    }

    // チャンネルごとに処理 (R=0, G=1, B=2)
    for (int ch = 0; ch < 3; ch++) {
        // 1. フルサイズから1chを抽出 (float)
        std::vector<float> I_full(fullSz), p_full(fullSz);
        for (size_t i = 0; i < fullSz; i++) {
            I_full[i] = (float)guidance[i * 4 + ch];
            p_full[i] = (float)input[i * 4 + ch];
        }

        // 2. サブサンプリング
        std::vector<float> I_sub(subSz), p_sub(subSz);
        bilinearDownsample(I_full.data(), w, h, I_sub.data(), sw, sh);
        bilinearDownsample(p_full.data(), w, h, p_sub.data(), sw, sh);

        // 3. I*I, I*p
        std::vector<float> II_sub(subSz), Ip_sub(subSz);
        for (size_t i = 0; i < subSz; i++) {
            II_sub[i] = I_sub[i] * I_sub[i];
            Ip_sub[i] = I_sub[i] * p_sub[i];
        }

        // 4. Box filter
        std::vector<float> meanI(subSz), meanP(subSz), corrI(subSz), corrIP(subSz);
        boxFilter(I_sub.data(), sw, sh, rp, meanI.data());
        boxFilter(p_sub.data(), sw, sh, rp, meanP.data());
        boxFilter(II_sub.data(), sw, sh, rp, corrI.data());
        boxFilter(Ip_sub.data(), sw, sh, rp, corrIP.data());

        II_sub.clear(); II_sub.shrink_to_fit();
        Ip_sub.clear(); Ip_sub.shrink_to_fit();

        // 5. a, b
        std::vector<float> a_sub(subSz), b_sub(subSz);
        for (size_t i = 0; i < subSz; i++) {
            float varI = corrI[i] - meanI[i] * meanI[i];
            float covIP = corrIP[i] - meanI[i] * meanP[i];
            a_sub[i] = covIP / (varI + eps255);
            b_sub[i] = meanP[i] - a_sub[i] * meanI[i];
        }

        corrI.clear(); corrI.shrink_to_fit();
        corrIP.clear(); corrIP.shrink_to_fit();
        meanI.clear(); meanI.shrink_to_fit();
        meanP.clear(); meanP.shrink_to_fit();
        I_sub.clear(); I_sub.shrink_to_fit();
        p_sub.clear(); p_sub.shrink_to_fit();

        // 6. meanA, meanB
        std::vector<float> meanA(subSz), meanB(subSz);
        boxFilter(a_sub.data(), sw, sh, rp, meanA.data());
        boxFilter(b_sub.data(), sw, sh, rp, meanB.data());

        a_sub.clear(); a_sub.shrink_to_fit();
        b_sub.clear(); b_sub.shrink_to_fit();

        // 7. アップサンプル → 元サイズ
        std::vector<float> A_full(fullSz), B_full(fullSz);
        bilinearUpsample(meanA.data(), sw, sh, A_full.data(), w, h);
        bilinearUpsample(meanB.data(), sw, sh, B_full.data(), w, h);

        meanA.clear(); meanA.shrink_to_fit();
        meanB.clear(); meanB.shrink_to_fit();

        // 8. output = A * I_fullres + B
        for (size_t i = 0; i < fullSz; i++) {
            float val = A_full[i] * I_full[i] + B_full[i];
            output[i * 4 + ch] = (uint8_t)std::min(255.0f, std::max(0.0f, val + 0.5f));
        }
    }

    auto t1 = std::chrono::steady_clock::now();
    int ms = (int)std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    FLOGI("GuidedFilter done: %dms", ms);
    return 0;
}

// ════════════════════════════════════════════
// Stage 2: Haar DWT Fusion
// ════════════════════════════════════════════
int dwtFusion(const uint8_t* img1, const uint8_t* img2,
              int w, int h, uint8_t* output) {
    if (!img1 || !img2 || !output || w < 2 || h < 2) return -1;
    auto t0 = std::chrono::steady_clock::now();
    FLOGI("DWT Fusion (NEON Lifting) start: %dx%d", w, h);

    int ret = NeonDWT::fused_dwt_pipeline(img1, img2, w, h, output);

    auto t1 = std::chrono::steady_clock::now();
    int ms = (int)std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    FLOGI("DWT Fusion (NEON Lifting) done: %dms", ms);
    return ret;
}

// ════════════════════════════════════════════
// Stage 3: Iterative Back-Projection
// ════════════════════════════════════════════
int iterativeBackProjection(uint8_t* highRes, int hrW, int hrH,
                            const uint8_t* lowRes, int lrW, int lrH,
                            float lambda, int iterations) {
    if (!highRes || !lowRes || hrW < 2 || hrH < 2 || lrW < 2 || lrH < 2) return -1;
    auto t0 = std::chrono::steady_clock::now();
    FLOGI("IBP start: HR=%dx%d, LR=%dx%d, lambda=%.2f, iter=%d",
          hrW, hrH, lrW, lrH, lambda, iterations);

    size_t hrSz = (size_t)hrW * hrH;
    size_t lrSz = (size_t)lrW * lrH;

    for (int iter = 0; iter < iterations; iter++) {
        // チャンネルごとに処理
        for (int ch = 0; ch < 3; ch++) {
            // HR → float
            std::vector<float> hrFloat(hrSz);
            for (size_t i = 0; i < hrSz; i++) {
                hrFloat[i] = (float)highRes[i * 4 + ch];
            }

            // 1. Downsample HR → LR サイズ
            std::vector<float> downsampled(lrSz);
            bilinearDownsample(hrFloat.data(), hrW, hrH, downsampled.data(), lrW, lrH);

            // 2. Residual: diff = lowRes - downsampled
            std::vector<float> diff(lrSz);
            for (size_t i = 0; i < lrSz; i++) {
                diff[i] = (float)lowRes[i * 4 + ch] - downsampled[i];
            }
            downsampled.clear(); downsampled.shrink_to_fit();

            // 3. Upsample diff → HR サイズ
            std::vector<float> correction(hrSz);
            bilinearUpsample(diff.data(), lrW, lrH, correction.data(), hrW, hrH);
            diff.clear(); diff.shrink_to_fit();

            // 4. Update: HR += lambda * correction
            for (size_t i = 0; i < hrSz; i++) {
                float val = hrFloat[i] + lambda * correction[i];
                highRes[i * 4 + ch] = (uint8_t)std::min(255.0f, std::max(0.0f, val + 0.5f));
            }
        }
    }

    auto t1 = std::chrono::steady_clock::now();
    int ms = (int)std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    FLOGI("IBP done: %dms (%d iterations)", ms, iterations);
    return 0;
}

// ════════════════════════════════════════════
// 3段統合パイプライン
// ════════════════════════════════════════════
int fusionPipeline(const uint8_t* original, const uint8_t* aiEnhanced,
                   int w, int h,
                   const uint8_t* aiLowRes, int lrW, int lrH,
                   uint8_t* output) {
    if (!original || !aiEnhanced || !aiLowRes || !output) return -1;
    if (w < 4 || h < 4 || lrW < 2 || lrH < 2) return -1;

    auto t0 = std::chrono::steady_clock::now();
    FLOGI("=== Fusion Pipeline start: %dx%d, LR=%dx%d ===", w, h, lrW, lrH);

    size_t bufSize = (size_t)w * h * 4;

    // Stage 1: Guided Filter
    std::vector<uint8_t> guidedBuf(bufSize);
    int ret = guidedFilter(aiEnhanced, original, w, h, guidedBuf.data(), 8, 0.01f, 4);
    if (ret != 0) {
        FLOGE("Stage 1 (GuidedFilter) failed");
        return -1;
    }

    // Stage 2: DWT Fusion
    std::vector<uint8_t> fusedBuf(bufSize);
    ret = dwtFusion(guidedBuf.data(), aiEnhanced, w, h, fusedBuf.data());
    guidedBuf.clear(); guidedBuf.shrink_to_fit();
    if (ret != 0) {
        FLOGE("Stage 2 (DWT Fusion) failed");
        return -1;
    }

    // Stage 3: IBP (in-place on fusedBuf)
    ret = iterativeBackProjection(fusedBuf.data(), w, h,
                                  aiLowRes, lrW, lrH,
                                  0.2f, 2);
    if (ret != 0) {
        FLOGE("Stage 3 (IBP) failed");
        return -1;
    }

    // 出力コピー
    memcpy(output, fusedBuf.data(), bufSize);

    auto t1 = std::chrono::steady_clock::now();
    int totalMs = (int)std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    FLOGI("=== Fusion Pipeline done: %dms total ===", totalMs);
    return 0;
}

} // namespace ImageFusion
