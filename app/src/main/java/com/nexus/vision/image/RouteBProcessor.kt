// ファイルパス: app/src/main/java/com/nexus/vision/image/RouteBProcessor.kt

package com.nexus.vision.image

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.roundToInt

/**
 * ルート B: アンシャープマスク強化 + ヒストグラム均一化
 *
 * 中複雑度タイル (FECS 1.5 - 4.0) に適用。
 * テクスチャはあるが細かいディテールは少ない領域。
 * 処理時間 50-100ms/tile。
 *
 * Phase 6: 基本実装
 * Phase 7: Real-ESRGAN NCNN に差し替え可能
 * Phase 14.6: 蒸留 RRDB に差し替え
 */
object RouteBProcessor {

    private const val TAG = "RouteB"

    /**
     * タイルにルート B 処理を適用する。
     *
     * @param pixels タイルのピクセル配列
     * @param width  タイル幅
     * @param height タイル高さ
     * @param scale  拡大倍率
     * @return 処理済み Bitmap
     */
    fun process(pixels: IntArray, width: Int, height: Int, scale: Int = 1): Bitmap {
        val startTime = System.currentTimeMillis()

        // 1. Bitmap 化
        var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // 2. ヒストグラム均一化（コントラスト改善）
        bitmap = histogramEqualize(bitmap)

        // 3. アンシャープマスク（強め）
        bitmap = ImageCorrector.unsharpMask(bitmap, radius = 2, alpha = 1.0f)

        // 4. スケール
        if (scale > 1) {
            val scaled = Bitmap.createScaledBitmap(
                bitmap, width * scale, height * scale, true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "RouteB: ${width}x${height} scale=$scale → ${bitmap.width}x${bitmap.height} (${elapsed}ms)")

        return bitmap
    }

    /**
     * ヒストグラム均一化（グレースケールベース、カラー保持）
     *
     * HSV の V チャンネルだけを均一化してカラーを保つ。
     */
    private fun histogramEqualize(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // V チャンネルのヒストグラムを構築
        val hsv = FloatArray(3)
        val vValues = IntArray(pixels.size)
        val histogram = IntArray(256)

        for (i in pixels.indices) {
            Color.colorToHSV(pixels[i], hsv)
            val v = (hsv[2] * 255f).roundToInt().coerceIn(0, 255)
            vValues[i] = v
            histogram[v]++
        }

        // CDF (累積分布関数)
        val cdf = IntArray(256)
        cdf[0] = histogram[0]
        for (i in 1..255) {
            cdf[i] = cdf[i - 1] + histogram[i]
        }

        val cdfMin = cdf.firstOrNull { it > 0 } ?: 0
        val total = pixels.size
        val lut = IntArray(256)
        for (i in 0..255) {
            lut[i] = if (total - cdfMin > 0) {
                ((cdf[i] - cdfMin).toFloat() / (total - cdfMin) * 255f).roundToInt().coerceIn(0, 255)
            } else {
                i
            }
        }

        // LUT 適用
        val output = IntArray(pixels.size)
        for (i in pixels.indices) {
            Color.colorToHSV(pixels[i], hsv)
            hsv[2] = lut[vValues[i]] / 255f
            output[i] = Color.HSVToColor(Color.alpha(pixels[i]), hsv)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)
        bitmap.recycle()

        return result
    }
}
