#pragma once
#include <cstdint>

/**
 * NEON SIMD 最適化 2D Haar DWT (Lifting Scheme)
 * 
 * Split:   x_e[n] = x[2n],  x_o[n] = x[2n+1]
 * Predict: d[n] = x_o[n] - x_e[n]
 * Update:  s[n] = x_e[n] + d[n] / 2
 * 
 * vld2q_s16 でデインターリーブ → vsubq_s16 → vhaddq_s16
 */
namespace NeonDWT {

/**
 * 1チャンネル float 配列に対する 1レベル 2D Haar DWT (Forward)
 * 入力: float [h][w]  (w, h は偶数)
 * 出力: float [h][w]  (Mallat配置: LL, LH, HL, HH)
 */
void forward_2d_haar_f32(const float* input, int w, int h, float* output);

/**
 * 逆 2D Haar DWT
 */
void inverse_2d_haar_f32(const float* input, int w, int h, float* output);

/**
 * DWT ドメインでの fusion (既存ロジックを置換)
 * img1: 元画像(high-freq source), img2: AI結果(structure source)
 * 出力: 融合結果
 */
int fused_dwt_pipeline(const uint8_t* img1, const uint8_t* img2,
                       int w, int h, uint8_t* output);

}
