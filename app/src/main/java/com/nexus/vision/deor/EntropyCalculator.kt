// ファイルパス: app/src/main/java/com/nexus/vision/deor/EntropyCalculator.kt

package com.nexus.vision.deor

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.ln
import kotlin.math.max

/**
 * Shannon エントロピー計算
 *
 * グレースケール変換した画像のピクセル輝度ヒストグラム（0-255）から
 * Shannon エントロピー H(X) = -Σ p(x) * log2(p(x)) を計算する。
 *
 * 結果は 0.0（単色）〜 8.0（完全ランダム）の範囲。
 *
 * Phase 3: 基本実装
 * Phase 6: EASS タイル単位計算で再利用
 */
object EntropyCalculator {

    private const val TAG = "EntropyCalc"
    private const val HISTOGRAM_SIZE = 256
    private val LOG2 = ln(2.0)

    /**
     * Bitmap 全体の Shannon エントロピーを計算する。
     *
     * @param bitmap 入力画像（サイズは問わないが、大きすぎる場合は事前にリサイズ推奨）
     * @return Shannon エントロピー値 (0.0 ~ 8.0)
     */
    fun calculate(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        if (totalPixels == 0) return 0.0

        // ピクセルを一括取得
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // グレースケール輝度ヒストグラムを構築
        val histogram = IntArray(HISTOGRAM_SIZE)
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            // ITU-R BT.601 輝度変換
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            histogram[luminance]++
        }

        return calculateFromHistogram(histogram, totalPixels)
    }

    /**
     * IntArray のピクセルデータから Shannon エントロピーを計算する。
     * タイル処理（EASS）向け。
     *
     * @param pixels ARGB ピクセル配列
     * @return Shannon エントロピー値
     */
    fun calculateFromPixels(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0

        val histogram = IntArray(HISTOGRAM_SIZE)
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            histogram[luminance]++
        }

        return calculateFromHistogram(histogram, pixels.size)
    }

    /**
     * ヒストグラムからエントロピーを計算する。
     */
    fun calculateFromHistogram(histogram: IntArray, totalPixels: Int): Double {
        if (totalPixels == 0) return 0.0

        var entropy = 0.0
        val total = totalPixels.toDouble()

        for (count in histogram) {
            if (count > 0) {
                val probability = count / total
                entropy -= probability * (ln(probability) / LOG2)
            }
        }

        Log.d(TAG, "Entropy: %.4f (pixels=$totalPixels)".format(entropy))
        return entropy
    }

    /**
     * ヒストグラムも同時に返すバージョン。
     * FECS で E_low/E_mid/E_high の計算にも使える。
     */
    fun calculateWithHistogram(bitmap: Bitmap): Pair<Double, IntArray> {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        if (totalPixels == 0) return 0.0 to IntArray(HISTOGRAM_SIZE)

        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(HISTOGRAM_SIZE)
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            histogram[luminance]++
        }

        val entropy = calculateFromHistogram(histogram, totalPixels)
        return entropy to histogram
    }
}
