// ファイルパス: app/src/main/java/com/nexus/vision/deor/PHashCalculator.kt

package com.nexus.vision.deor

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 知覚ハッシュ (pHash) 計算
 *
 * アルゴリズム:
 *   1. 32×32 にリサイズ
 *   2. グレースケール変換
 *   3. 2D DCT (Discrete Cosine Transform) を実行
 *   4. 左上 8×8 の低周波係数を抽出
 *   5. 中央値と比較して 64bit ハッシュを生成
 *
 * Phase 3: 基本実装
 * Phase 6: EASS の変化スコア計算で再利用
 */
object PHashCalculator {

    private const val TAG = "PHashCalc"
    private const val RESIZE = 32
    private const val DCT_LOW = 8
    private const val HASH_BITS = 64 // 8×8

    /**
     * Bitmap から 64bit pHash を計算する。
     *
     * @param bitmap 入力画像
     * @return 64bit ハッシュ値
     */
    fun calculate(bitmap: Bitmap): Long {
        // 1. 32×32 にリサイズ
        val resized = Bitmap.createScaledBitmap(bitmap, RESIZE, RESIZE, true)

        // 2. グレースケール行列を構築
        val gray = Array(RESIZE) { y ->
            DoubleArray(RESIZE) { x ->
                val pixel = resized.getPixel(x, y)
                0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
            }
        }

        if (resized !== bitmap) {
            resized.recycle()
        }

        // 3. 2D DCT
        val dct = dct2d(gray)

        // 4. 左上 8×8 の低周波係数を抽出（DC 成分 [0,0] を除く）
        val coefficients = DoubleArray(HASH_BITS)
        var idx = 0
        for (y in 0 until DCT_LOW) {
            for (x in 0 until DCT_LOW) {
                coefficients[idx++] = dct[y][x]
            }
        }

        // 5. 中央値を計算してハッシュ生成
        val sorted = coefficients.sorted()
        val median = (sorted[HASH_BITS / 2 - 1] + sorted[HASH_BITS / 2]) / 2.0

        var hash = 0L
        for (i in 0 until HASH_BITS) {
            if (coefficients[i] > median) {
                hash = hash or (1L shl i)
            }
        }

        Log.d(TAG, "pHash: ${hash.toULong().toString(16)} (${hash.toULong().toString(2).padStart(64, '0')})")
        return hash
    }

    /**
     * 2つの pHash のハミング距離を計算する。
     *
     * @return ハミング距離（0 = 完全一致, 64 = 完全不一致）
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        val xor = hash1 xor hash2
        return java.lang.Long.bitCount(xor)
    }

    /**
     * キャッシュヒット判定（ハミング距離 ≤ threshold）。
     */
    fun isSimilar(hash1: Long, hash2: Long, threshold: Int = 8): Boolean {
        return hammingDistance(hash1, hash2) <= threshold
    }

    // ── 2D DCT 実装 ──

    /**
     * 2D DCT (Type-II) を計算する。
     * N×N の入力行列に対して O(N^3) の素朴な実装。
     * 32×32 なら 32768 回の乗算で ~1ms 以内。
     */
    private fun dct2d(input: Array<DoubleArray>): Array<DoubleArray> {
        val n = input.size
        val temp = Array(n) { DoubleArray(n) }
        val output = Array(n) { DoubleArray(n) }

        // 行方向 1D DCT
        for (y in 0 until n) {
            for (u in 0 until n) {
                var sum = 0.0
                for (x in 0 until n) {
                    sum += input[y][x] * cos((2 * x + 1) * u * PI / (2 * n))
                }
                val alpha = if (u == 0) sqrt(1.0 / n) else sqrt(2.0 / n)
                temp[y][u] = alpha * sum
            }
        }

        // 列方向 1D DCT
        for (u in 0 until n) {
            for (v in 0 until n) {
                var sum = 0.0
                for (y in 0 until n) {
                    sum += temp[y][u] * cos((2 * y + 1) * v * PI / (2 * n))
                }
                val alpha = if (v == 0) sqrt(1.0 / n) else sqrt(2.0 / n)
                output[v][u] = alpha * sum
            }
        }

        return output
    }

    /**
     * 1タイル（128×128 のピクセル配列）に対して DCT エネルギー比を計算する。
     * FECSScorer で使用。
     *
     * @param pixels ARGB ピクセル配列
     * @param width  タイルの幅
     * @param height タイルの高さ
     * @return Triple(E_low, E_mid, E_high) — 各周波数帯域のエネルギー比
     */
    fun calculateDctEnergyRatios(pixels: IntArray, width: Int, height: Int): Triple<Double, Double, Double> {
        // 8×8 にダウンサンプリングして簡易 DCT
        val downSize = 8
        val gray = Array(downSize) { DoubleArray(downSize) }

        val scaleX = width.toDouble() / downSize
        val scaleY = height.toDouble() / downSize

        for (dy in 0 until downSize) {
            for (dx in 0 until downSize) {
                val srcX = (dx * scaleX).toInt().coerceIn(0, width - 1)
                val srcY = (dy * scaleY).toInt().coerceIn(0, height - 1)
                val pixel = pixels[srcY * width + srcX]
                gray[dy][dx] = 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
            }
        }

        val dct = dct2d8x8(gray)

        // エネルギー計算（DC 成分を除外）
        var eLow = 0.0
        var eMid = 0.0
        var eHigh = 0.0

        for (v in 0 until downSize) {
            for (u in 0 until downSize) {
                if (u == 0 && v == 0) continue // DC 成分を除外
                val energy = dct[v][u] * dct[v][u]
                val freq = u + v // マンハッタン距離で周波数帯域を分類
                when {
                    freq <= 3 -> eLow += energy
                    freq <= 6 -> eMid += energy
                    else -> eHigh += energy
                }
            }
        }

        val total = eLow + eMid + eHigh
        if (total == 0.0) return Triple(1.0, 0.0, 0.0)

        return Triple(eLow / total, eMid / total, eHigh / total)
    }

    /**
     * 8×8 の 2D DCT（FECS 用の軽量版）
     */
    private fun dct2d8x8(input: Array<DoubleArray>): Array<DoubleArray> {
        return dct2d(input) // 8×8 なら十分高速
    }
}
