#pragma once
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * NEON SIMD 最適化 Shannon エントロピー計算
 * 
 * 入力: RGBA ピクセル配列
 * 出力: Shannon エントロピー [0.0, 8.0]
 * 
 * 内部処理:
 *   1. ITU-R BT.601 輝度変換 (NEON固定小数点)
 *   2. 256ビン ヒストグラム (4-way parallel accumulation)
 *   3. FEA有理関数近似: h(x) = x * (0.6648 / (x + 0.2086) - 0.5754*x) + 0.0206
 */
float neon_shannon_entropy(const uint8_t* rgba_pixels, int width, int height, int stride);

/**
 * ヒストグラム構築のみ (タイル処理用)
 */
void neon_build_histogram(const uint8_t* rgba_pixels, int width, int height, 
                          int stride, uint32_t* histogram_out);

#ifdef __cplusplus
}
#endif
