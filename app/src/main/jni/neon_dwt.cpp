#include "neon_dwt.h"
#include <arm_neon.h>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <vector>
#include <android/log.h>

#define TAG_DWT "NeonDWT"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG_DWT, __VA_ARGS__)

namespace NeonDWT {

// ─── 1D Haar Forward (Lifting Scheme, int16, NEON) ───
static void lifting_forward_row_i16(const int16_t* input, int n,
                                     int16_t* out_low, int16_t* out_high) {
    int half = n / 2;
    int i = 0;

    for (; i + 7 < half; i += 8) {
        int16x8x2_t pairs = vld2q_s16(input + i * 2);
        int16x8_t even = pairs.val[0];  // x_e
        int16x8_t odd  = pairs.val[1];  // x_o

        int16x8_t d = vsubq_s16(odd, even);
        int16x8_t d_half = vshrq_n_s16(d, 1);
        int16x8_t s = vaddq_s16(even, d_half);

        vst1q_s16(out_low + i, s);
        vst1q_s16(out_high + i, d);
    }

    for (; i < half; i++) {
        int16_t e = input[i * 2];
        int16_t o = input[i * 2 + 1];
        out_high[i] = o - e;
        out_low[i] = e + (out_high[i] >> 1);
    }
}

// ─── 1D Haar Inverse (Lifting Scheme, int16, NEON) ───
static void lifting_inverse_row_i16(const int16_t* in_low, const int16_t* in_high,
                                     int half, int16_t* output) {
    int i = 0;

    for (; i + 7 < half; i += 8) {
        int16x8_t s = vld1q_s16(in_low + i);
        int16x8_t d = vld1q_s16(in_high + i);

        int16x8_t d_half = vshrq_n_s16(d, 1);
        int16x8_t even = vsubq_s16(s, d_half);
        int16x8_t odd = vaddq_s16(d, even);

        int16x8x2_t result;
        result.val[0] = even;
        result.val[1] = odd;
        vst2q_s16(output + i * 2, result);
    }

    for (; i < half; i++) {
        int16_t s = in_low[i];
        int16_t d = in_high[i];
        output[i * 2] = s - (d >> 1);
        output[i * 2 + 1] = d + output[i * 2];
    }
}

void forward_2d_haar_f32(const float* input, int w, int h, float* output) {
    int hw = w / 2;
    int hh = h / 2;

    std::vector<int16_t> buf(w * h);
    for (int i = 0; i < w * h; i++) {
        buf[i] = (int16_t)std::max(-32768.0f, std::min(32767.0f, input[i]));
    }

    std::vector<int16_t> row_result(w * h);
    std::vector<int16_t> low_row(hw), high_row(hw);
    for (int y = 0; y < h; y++) {
        lifting_forward_row_i16(buf.data() + y * w, w, low_row.data(), high_row.data());
        memcpy(row_result.data() + y * w, low_row.data(), hw * sizeof(int16_t));
        memcpy(row_result.data() + y * w + hw, high_row.data(), hw * sizeof(int16_t));
    }

    std::vector<int16_t> col_buf(h), col_low(hh), col_high(hh);
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) col_buf[y] = row_result[y * w + x];
        lifting_forward_row_i16(col_buf.data(), h, col_low.data(), col_high.data());
        for (int y = 0; y < hh; y++) {
            output[y * w + x] = (float)col_low[y];
            output[(hh + y) * w + x] = (float)col_high[y];
        }
    }
}

void inverse_2d_haar_f32(const float* input, int w, int h, float* output) {
    int hw = w / 2;
    int hh = h / 2;

    std::vector<int16_t> col_low(hh), col_high(hh), col_result(h);
    std::vector<float> col_inv(w * h);
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < hh; y++) {
            col_low[y] = (int16_t)input[y * w + x];
            col_high[y] = (int16_t)input[(hh + y) * w + x];
        }
        lifting_inverse_row_i16(col_low.data(), col_high.data(), hh, col_result.data());
        for (int y = 0; y < h; y++) col_inv[y * w + x] = (float)col_result[y];
    }

    std::vector<int16_t> row_low(hw), row_high(hw), row_result(w);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < hw; x++) {
            row_low[x] = (int16_t)col_inv[y * w + x];
            row_high[x] = (int16_t)col_inv[y * w + hw + x];
        }
        lifting_inverse_row_i16(row_low.data(), row_high.data(), hw, row_result.data());
        for (int x = 0; x < w; x++) output[y * w + x] = (float)row_result[x];
    }
}

int fused_dwt_pipeline(const uint8_t* img1, const uint8_t* img2,
                       int w, int h, uint8_t* output) {
    int ew = w & ~1;
    int eh = h & ~1;
    int hw = ew / 2;
    int hh = eh / 2;
    size_t sz = (size_t)ew * eh;

    for (size_t i = 0; i < (size_t)w * h; i++) output[i * 4 + 3] = img1[i * 4 + 3];

    for (int ch = 0; ch < 3; ch++) {
        std::vector<float> f1(sz), f2(sz);
        for (int y = 0; y < eh; y++)
            for (int x = 0; x < ew; x++) {
                f1[y * ew + x] = (float)img1[(y * w + x) * 4 + ch];
                f2[y * ew + x] = (float)img2[(y * w + x) * 4 + ch];
            }

        std::vector<float> dwt1(sz), dwt2(sz);
        forward_2d_haar_f32(f1.data(), ew, eh, dwt1.data());
        forward_2d_haar_f32(f2.data(), ew, eh, dwt2.data());

        std::vector<float> fused(sz);
        for (int y = 0; y < eh; y++)
            for (int x = 0; x < ew; x++) {
                bool isTop = y < hh, isLeft = x < hw;
                float v1 = dwt1[y * ew + x], v2 = dwt2[y * ew + x];
                if ((isTop && isLeft) || (!isTop && !isLeft))
                    fused[y * ew + x] = v2;
                else
                    fused[y * ew + x] = (fabsf(v1) >= fabsf(v2)) ? v1 : v2;
            }

        std::vector<float> result(sz);
        inverse_2d_haar_f32(fused.data(), ew, eh, result.data());

        for (int y = 0; y < eh; y++)
            for (int x = 0; x < ew; x++) {
                float v = result[y * ew + x];
                output[(y * w + x) * 4 + ch] = (uint8_t)std::min(255.f, std::max(0.f, v + 0.5f));
            }

        if (ew < w) for (int y = 0; y < h; y++) output[(y*w+w-1)*4+ch] = img1[(y*w+w-1)*4+ch];
        if (eh < h) for (int x = 0; x < w; x++) output[((h-1)*w+x)*4+ch] = img1[((h-1)*w+x)*4+ch];
    }
    return 0;
}

}
