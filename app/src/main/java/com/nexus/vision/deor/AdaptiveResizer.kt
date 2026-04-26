// ファイルパス: app/src/main/java/com/nexus/vision/deor/AdaptiveResizer.kt

package com.nexus.vision.deor

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DEOR (Dynamic Entropy-Optimized Resize)
 *
 * エントロピーに基づいて画像を適応的にリサイズする。
 *
 * 閾値テーブル:
 *   H < 2.5     → 128px (≈20ms)
 *   2.5 - 4.0   → 256px (≈21ms)
 *   4.0 - 5.5   → 384px (≈23ms)
 *   5.5 - 7.0   → 512px (≈27ms)
 *   ≥ 7.0       → 768px (≈32ms)
 *
 * Phase 3: 基本実装
 * Phase 4: メインフローで画像前処理として使用
 */
object AdaptiveResizer {

    private const val TAG = "AdaptiveResizer"

    /**
     * エントロピー閾値とターゲットサイズのマッピング
     */
    data class ResizeRule(
        val entropyMin: Double,
        val entropyMax: Double,
        val targetShortSide: Int
    )

    private val RESIZE_RULES = listOf(
        ResizeRule(0.0, 2.5, 128),
        ResizeRule(2.5, 4.0, 256),
        ResizeRule(4.0, 5.5, 384),
        ResizeRule(5.5, 7.0, 512),
        ResizeRule(7.0, Double.MAX_VALUE, 768)
    )

    /**
     * エントロピーからターゲットの短辺サイズを決定する。
     */
    fun determineTargetSize(entropy: Double): Int {
        val rule = RESIZE_RULES.firstOrNull { entropy >= it.entropyMin && entropy < it.entropyMax }
            ?: RESIZE_RULES.last()
        return rule.targetShortSide
    }

    /**
     * 画像のエントロピーを計算してから適応的にリサイズする。
     *
     * @param bitmap     入力画像
     * @param maxSamples エントロピー計算用のサンプリングサイズ（高速化のため）
     * @return リサイズ後の Bitmap と計算されたエントロピー値
     */
    fun resize(bitmap: Bitmap, maxSamples: Int = 256): ResizeResult {
        // 1. エントロピー計算用に小さくサンプリング
        val sampledBitmap = if (bitmap.width > maxSamples || bitmap.height > maxSamples) {
            val scale = min(
                maxSamples.toFloat() / bitmap.width,
                maxSamples.toFloat() / bitmap.height
            )
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }

        // 2. エントロピー計算
        val entropy = EntropyCalculator.calculate(sampledBitmap)

        if (sampledBitmap !== bitmap) {
            sampledBitmap.recycle()
        }

        // 3. ターゲットサイズ決定
        val targetShortSide = determineTargetSize(entropy)

        // 4. リサイズ
        val resized = resizeToShortSide(bitmap, targetShortSide)

        Log.i(
            TAG,
            "DEOR: ${bitmap.width}x${bitmap.height} → ${resized.width}x${resized.height} " +
                    "(H=%.3f, target=${targetShortSide}px)".format(entropy)
        )

        return ResizeResult(
            bitmap = resized,
            entropy = entropy,
            targetShortSide = targetShortSide,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height
        )
    }

    /**
     * 指定された短辺サイズに合わせてリサイズする。
     * アスペクト比は維持。元画像より大きくはしない。
     */
    fun resizeToShortSide(bitmap: Bitmap, targetShortSide: Int): Bitmap {
        val originalShortSide = min(bitmap.width, bitmap.height)

        // 元画像のほうが小さければリサイズしない
        if (originalShortSide <= targetShortSide) {
            return bitmap
        }

        val scale = targetShortSide.toFloat() / originalShortSide
        val newWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * リサイズ結果を保持するデータクラス
     */
    data class ResizeResult(
        val bitmap: Bitmap,
        val entropy: Double,
        val targetShortSide: Int,
        val originalWidth: Int,
        val originalHeight: Int
    )
}
