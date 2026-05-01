#include "neon_isauvola.h"
#include <arm_neon.h>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <vector>
#include <android/log.h>

#define TAG_ISV "NeonISauvola"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG_ISV, __VA_ARGS__)

// 積分画像構築
static void build_integral_images(const uint8_t* gray, int w, int h,
                                   int64_t* integral, int64_t* integral_sq) {
    for (int y = 0; y < h; y++) {
        int64_t row_sum = 0, row_sum_sq = 0;
        for (int x = 0; x < w; x++) {
            int64_t val = gray[y * w + x];
            row_sum += val;
            row_sum_sq += val * val;

            int idx = (y + 1) * (w + 1) + (x + 1);
            integral[idx] = row_sum + integral[y * (w + 1) + (x + 1)];
            integral_sq[idx] = row_sum_sq + integral_sq[y * (w + 1) + (x + 1)];
        }
    }
}

static inline int64_t rect_sum(const int64_t* sat, int x1, int y1, int x2, int y2, int stride) {
    return sat[(y2+1)*stride+(x2+1)] - sat[y1*stride+(x2+1)] 
         - sat[(y2+1)*stride+x1]     + sat[y1*stride+x1];
}

// 局所最大・最小値 (ウィンドウ内)
static void compute_local_minmax(const uint8_t* gray, int w, int h, int radius,
                                  uint8_t* local_min, uint8_t* local_max) {
    std::vector<uint8_t> tmp_min(w * h), tmp_max(w * h);

    // 行方向
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            uint8_t mn = 255, mx = 0;
            int x0 = std::max(0, x - radius);
            int x1 = std::min(w - 1, x + radius);
            for (int xx = x0; xx <= x1; xx++) {
                uint8_t v = gray[y * w + xx];
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
            tmp_min[y * w + x] = mn;
            tmp_max[y * w + x] = mx;
        }
    }

    // 列方向
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            uint8_t mn = 255, mx = 0;
            int y0 = std::max(0, y - radius);
            int y1 = std::min(h - 1, y + radius);
            for (int yy = y0; yy <= y1; yy++) {
                uint8_t v_min = tmp_min[yy * w + x];
                uint8_t v_max = tmp_max[yy * w + x];
                if (v_min < mn) mn = v_min;
                if (v_max > mx) mx = v_max;
            }
            local_min[y * w + x] = mn;
            local_max[y * w + x] = mx;
        }
    }
}

int neon_isauvola_binarize(const uint8_t* rgba_input, int w, int h, int stride,
                           uint8_t* rgba_output,
                           int window_size, float k, float r,
                           float contrast_threshold) {
    LOGI("ISauvola start: %dx%d, window=%d, k=%.3f", w, h, window_size, k);
    int sz = w * h;

    // Step 0: グレースケール変換 (NEON)
    std::vector<uint8_t> gray(sz);
    {
        const uint8x8_t vR = vdup_n_u8(77);
        const uint8x8_t vG = vdup_n_u8(150);
        const uint8x8_t vB = vdup_n_u8(29);
        for (int y = 0; y < h; y++) {
            const uint8_t* row = rgba_input + y * stride;
            int x = 0;
            for (; x + 7 < w; x += 8) {
                uint8x8x4_t px = vld4_u8(row + x * 4);
                uint16x8_t acc = vmull_u8(px.val[0], vR);
                acc = vmlal_u8(acc, px.val[1], vG);
                acc = vmlal_u8(acc, px.val[2], vB);
                uint8x8_t lum = vshrn_n_u16(acc, 8);
                vst1_u8(gray.data() + y * w + x, lum);
            }
            for (; x < w; x++) {
                int idx = x * 4;
                gray[y * w + x] = (uint8_t)(((uint16_t)row[idx] * 77 +
                    (uint16_t)row[idx+1] * 150 + (uint16_t)row[idx+2] * 29) >> 8);
            }
        }
    }

    // Step 1: コントラストマップ D(x,y)
    int radius = window_size / 2;
    std::vector<uint8_t> local_min_buf(sz), local_max_buf(sz);
    compute_local_minmax(gray.data(), w, h, radius, local_min_buf.data(), local_max_buf.data());

    std::vector<float> contrast_map(sz);
    for (int i = 0; i < sz; i++) {
        float fmax = (float)local_max_buf[i];
        float fmin = (float)local_min_buf[i];
        contrast_map[i] = (fmax - fmin) / (fmax + fmin + 1.0f);
    }

    // Step 2: 積分画像
    int sat_stride = w + 1;
    std::vector<int64_t> integral((size_t)(w+1)*(h+1), 0);
    std::vector<int64_t> integral_sq((size_t)(w+1)*(h+1), 0);
    build_integral_images(gray.data(), w, h, integral.data(), integral_sq.data());

    // Step 3: ISauvola 閾値計算 + 二値化
    int half_w = window_size / 2;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int x0 = std::max(0, x - half_w);
            int y0 = std::max(0, y - half_w);
            int x1 = std::min(w - 1, x + half_w);
            int y1 = std::min(h - 1, y + half_w);
            int count = (x1 - x0 + 1) * (y1 - y0 + 1);

            int64_t sum = rect_sum(integral.data(), x0, y0, x1, y1, sat_stride);
            int64_t sum_sq = rect_sum(integral_sq.data(), x0, y0, x1, y1, sat_stride);

            float mean = (float)sum / count;
            float var = (float)sum_sq / count - mean * mean;
            float stddev = sqrtf(std::max(0.0f, var));

            float threshold = mean * (1.0f + k * (stddev / r - 1.0f));

            uint8_t pixel_val = gray[y * w + x];
            bool sauvola_fg = (float)pixel_val <= threshold;
            bool contrast_fg = contrast_map[y * w + x] >= contrast_threshold;

            // ISauvola: Sauvola AND コントラストマップ
            bool is_foreground = sauvola_fg && contrast_fg;

            int out_idx = (y * w + x) * 4;
            uint8_t out_val = is_foreground ? 0 : 255;
            rgba_output[out_idx + 0] = out_val;
            rgba_output[out_idx + 1] = out_val;
            rgba_output[out_idx + 2] = out_val;
            rgba_output[out_idx + 3] = 255;
        }
    }

    LOGI("ISauvola done: %dx%d", w, h);
    return 0;
}
