#pragma once
#include <cstdint>

/**
 * ISauvola (Improved Sauvola) 二値化
 * 
 * Step 1: コントラスト正規化マップ D(x,y) = (fmax - fmin) / (fmax + fmin + ε)
 * Step 2: Sauvola 閾値 T = μ * (1 + k * (σ/R - 1))  (k = 0.01)
 * Step 3: D(x,y) との論理積でノイズ抑圧
 * 
 * 積分画像による O(1) 局所統計量計算
 * NEON による並列プレフィックスサム
 */
int neon_isauvola_binarize(const uint8_t* rgba_input, int w, int h, int stride,
                           uint8_t* rgba_output,
                           int window_size = 25, float k = 0.01f, float r = 128.0f,
                           float contrast_threshold = 0.15f);
