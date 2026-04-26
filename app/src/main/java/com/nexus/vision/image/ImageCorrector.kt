// ファイルパス: app/src/main/java/com/nexus/vision/image/ImageCorrector.kt

package com.nexus.vision.image

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 画像補正ユーティリティ
 *
 * - アンシャープマスク（微小ブレ補正）
 * - Sauvola 適応二値化（照明ムラ対応）
 * - コントラスト比計算
 *
 * Phase 5: 基本実装
 * Phase 6: EASS ルート B/C の前処理で使用
 */
object ImageCorrector {

    private const val TAG = "ImageCorrector"

    // ── アンシャープマスク ──

    /**
     * アンシャープマスクを適用する。
     *
     * output = original + α × (original - blurred)
     *
     * @param bitmap 入力画像
     * @param radius ガウシアンぼかし半径 (1-3 推奨)
     * @param alpha  シャープ強度 (0.5-1.0 推奨)
     * @return シャープ化された Bitmap
     */
    fun unsharpMask(bitmap: Bitmap, radius: Int = 2, alpha: Float = 0.7f): Bitmap {
        val startTime = System.currentTimeMillis()

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. ガウシアンぼかし
        val blurred = gaussianBlur(pixels, width, height, radius)

        // 2. アンシャープマスク適用
        val output = IntArray(width * height)
        for (i in pixels.indices) {
            val origR = Color.red(pixels[i])
            val origG = Color.green(pixels[i])
            val origB = Color.blue(pixels[i])

            val blurR = Color.red(blurred[i])
            val blurG = Color.green(blurred[i])
            val blurB = Color.blue(blurred[i])

            val newR = (origR + alpha * (origR - blurR)).roundToInt().coerceIn(0, 255)
            val newG = (origG + alpha * (origG - blurG)).roundToInt().coerceIn(0, 255)
            val newB = (origB + alpha * (origB - blurB)).roundToInt().coerceIn(0, 255)

            output[i] = Color.argb(Color.alpha(pixels[i]), newR, newG, newB)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "UnsharpMask: ${width}x${height}, radius=$radius, α=$alpha → ${elapsed}ms")

        return result
    }

    // ── Sauvola 適応二値化 ──

    /**
     * Sauvola 適応二値化を適用する。
     *
     * T(x,y) = mean × (1 + k × (stddev / R - 1))
     *
     * @param bitmap     入力画像
     * @param windowSize 局所ウィンドウサイズ (奇数推奨, デフォルト 25)
     * @param k          感度パラメータ (デフォルト 0.2)
     * @param r          標準偏差の正規化定数 (デフォルト 128)
     * @return 二値化された Bitmap
     */
    fun sauvolaBinarize(
        bitmap: Bitmap,
        windowSize: Int = 25,
        k: Float = 0.2f,
        r: Float = 128f
    ): Bitmap {
        val startTime = System.currentTimeMillis()

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // グレースケール変換
        val gray = IntArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            gray[i] = (0.299 * Color.red(pixel) +
                    0.587 * Color.green(pixel) +
                    0.114 * Color.blue(pixel)).roundToInt()
        }

        // 積分画像と二乗積分画像を構築
        val integral = LongArray(width * height)
        val integralSq = LongArray(width * height)
        buildIntegralImages(gray, integral, integralSq, width, height)

        // Sauvola 閾値で二値化
        val halfWindow = windowSize / 2
        val output = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val x1 = max(0, x - halfWindow)
                val y1 = max(0, y - halfWindow)
                val x2 = min(width - 1, x + halfWindow)
                val y2 = min(height - 1, y + halfWindow)

                val count = (x2 - x1 + 1) * (y2 - y1 + 1)

                val sum = getIntegralSum(integral, x1, y1, x2, y2, width)
                val sumSq = getIntegralSum(integralSq, x1, y1, x2, y2, width)

                val mean = sum.toDouble() / count
                val variance = (sumSq.toDouble() / count) - (mean * mean)
                val stddev = sqrt(max(0.0, variance))

                val threshold = mean * (1.0 + k * (stddev / r - 1.0))

                val pixelValue = gray[y * width + x]
                output[y * width + x] = if (pixelValue > threshold) {
                    Color.WHITE
                } else {
                    Color.BLACK
                }
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Sauvola: ${width}x${height}, window=$windowSize, k=$k → ${elapsed}ms")

        return result
    }

    // ── コントラスト比計算 ──

    /**
     * 画像のコントラスト比を計算する (0.0 ~ 1.0)。
     * ヒストグラムの上位/下位 1% を切って最大・最小輝度の差から算出。
     *
     * @return コントラスト比 (低い = ぼやけている)
     */
    fun calculateContrastRatio(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        for (pixel in pixels) {
            val lum = (0.299 * Color.red(pixel) +
                    0.587 * Color.green(pixel) +
                    0.114 * Color.blue(pixel)).roundToInt().coerceIn(0, 255)
            histogram[lum]++
        }

        // 上下 1% を切る
        val cutoff = (totalPixels * 0.01).toInt()
        var lowSum = 0
        var lowVal = 0
        for (i in 0..255) {
            lowSum += histogram[i]
            if (lowSum >= cutoff) {
                lowVal = i
                break
            }
        }

        var highSum = 0
        var highVal = 255
        for (i in 255 downTo 0) {
            highSum += histogram[i]
            if (highSum >= cutoff) {
                highVal = i
                break
            }
        }

        return (highVal - lowVal) / 255f
    }

    // ── Private Helpers ──

    /**
     * 簡易ボックスブラー（ガウシアンの近似）
     */
    private fun gaussianBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val temp = IntArray(pixels.size)
        val output = IntArray(pixels.size)

        // 水平パス
        for (y in 0 until height) {
            for (x in 0 until width) {
                var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, width - 1)
                    val pixel = pixels[y * width + nx]
                    rSum += Color.red(pixel)
                    gSum += Color.green(pixel)
                    bSum += Color.blue(pixel)
                    count++
                }
                temp[y * width + x] = Color.argb(
                    255,
                    rSum / count,
                    gSum / count,
                    bSum / count
                )
            }
        }

        // 垂直パス
        for (y in 0 until height) {
            for (x in 0 until width) {
                var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    val pixel = temp[ny * width + x]
                    rSum += Color.red(pixel)
                    gSum += Color.green(pixel)
                    bSum += Color.blue(pixel)
                    count++
                }
                output[y * width + x] = Color.argb(
                    255,
                    rSum / count,
                    gSum / count,
                    bSum / count
                )
            }
        }

        return output
    }

    /**
     * 積分画像を構築する。
     */
    private fun buildIntegralImages(
        gray: IntArray,
        integral: LongArray,
        integralSq: LongArray,
        width: Int,
        height: Int
    ) {
        for (y in 0 until height) {
            var rowSum = 0L
            var rowSumSq = 0L
            for (x in 0 until width) {
                val idx = y * width + x
                val value = gray[idx].toLong()
                rowSum += value
                rowSumSq += value * value

                integral[idx] = rowSum + if (y > 0) integral[(y - 1) * width + x] else 0L
                integralSq[idx] = rowSumSq + if (y > 0) integralSq[(y - 1) * width + x] else 0L
            }
        }
    }

    /**
     * 積分画像から矩形領域の合計を取得する。
     */
    private fun getIntegralSum(
        integral: LongArray,
        x1: Int, y1: Int, x2: Int, y2: Int,
        width: Int
    ): Long {
        val a = if (x1 > 0 && y1 > 0) integral[(y1 - 1) * width + (x1 - 1)] else 0L
        val b = if (y1 > 0) integral[(y1 - 1) * width + x2] else 0L
        val c = if (x1 > 0) integral[y2 * width + (x1 - 1)] else 0L
        val d = integral[y2 * width + x2]
        return d - b - c + a
    }
}
