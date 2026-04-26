// ファイルパス: app/src/main/java/com/nexus/vision/image/RouteAProcessor.kt

package com.nexus.vision.image

import android.graphics.Bitmap
import android.util.Log

/**
 * ルート A: バイキュービック補間
 *
 * 低複雑度タイル (FECS < 1.5) に適用。
 * 空・壁・単色背景など。処理時間 < 1ms/tile。
 *
 * Phase 6: 基本実装
 * Phase 14: 最適化候補（スキップ可能）
 */
object RouteAProcessor {

    private const val TAG = "RouteA"

    /**
     * タイルをバイキュービック補間で拡大する。
     *
     * @param pixels タイルのピクセル配列
     * @param width  タイル幅
     * @param height タイル高さ
     * @param scale  拡大倍率（1 = 等倍、2 = 2倍）
     * @return 処理済み Bitmap
     */
    fun process(pixels: IntArray, width: Int, height: Int, scale: Int = 1): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        if (scale <= 1) {
            return bitmap
        }

        // Bitmap.createScaledBitmap は BILINEAR だが、
        // 低複雑度タイルにはこれで十分
        val scaled = Bitmap.createScaledBitmap(
            bitmap, width * scale, height * scale, true
        )
        if (scaled !== bitmap) bitmap.recycle()

        Log.d(TAG, "RouteA: ${width}x${height} → ${scaled.width}x${scaled.height}")
        return scaled
    }
}
