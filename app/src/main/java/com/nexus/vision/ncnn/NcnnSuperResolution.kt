// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"
        private const val PARAM_FILE = "models/realesr-animevideov3-x4.param"
        private const val MODEL_FILE = "models/realesr-animevideov3-x4.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 32

        private const val SR_MAX_INPUT = 512
        private const val OUTPUT_SIZE = 2048
        private const val SHARPEN_STRENGTH = 0.6f
        private const val AI_BLEND_WEIGHT = 0.4f  // AI結果の重み（0.4 = 元画像寄り）
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            val result = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "Initialized (Vulkan+FP16, tile=$TILE_SIZE)")
            else Log.e(TAG, "Init failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    /**
     * ガイデッド超解像パイプライン:
     *
     * 1. 元画像を出力サイズにリサイズ（バイキュービック） → guide
     * 2. 元画像をSR_MAX_INPUTに縮小 → AI超解像で出力サイズに → enhanced
     * 3. guide と enhanced をガイデッドブレンド → 最終出力
     */
    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }

        val maxSide = maxOf(bitmap.width, bitmap.height)

        return if (maxSide <= SR_MAX_INPUT) {
            // 小さい画像: 単純に4×超解像（ガイド不要）
            Log.i(TAG, "Small image: direct 4x SR (${bitmap.width}x${bitmap.height})")
            processSuperResolution(bitmap)
        } else {
            // 大きい画像: ガイデッド超解像
            Log.i(TAG, "Large image: guided SR (${bitmap.width}x${bitmap.height})")
            processGuidedSR(bitmap)
        }
    }

    /**
     * ガイデッド超解像（大画像向け）
     */
    private fun processGuidedSR(bitmap: Bitmap): Bitmap? {
        return try {
            // Step 1: AI用の入力を作成（SR_MAX_INPUT以下に縮小）
            val srInput = limitSize(bitmap, SR_MAX_INPUT)
            val srArgb = ensureArgb(srInput) ?: return null

            Log.i(TAG, "Guided SR - AI input: ${srArgb.width}x${srArgb.height}")

            // Step 2: AI超解像実行
            val aiResult = RealEsrganBridge.nativeProcess(srArgb)
            if (srArgb !== srInput) srArgb.recycle()
            if (srInput !== bitmap) srInput.recycle()

            if (aiResult == null) {
                Log.e(TAG, "AI SR failed, falling back to sharpen")
                return processSharpenOnly(bitmap)
            }

            Log.i(TAG, "Guided SR - AI output: ${aiResult.width}x${aiResult.height}")

            // Step 3: 元画像をAI出力と同じサイズにリサイズ（ガイド画像）
            val guideArgb = ensureArgb(bitmap) ?: run {
                aiResult.recycle()
                return null
            }
            val guide = Bitmap.createScaledBitmap(
                guideArgb, aiResult.width, aiResult.height, true
            )
            if (guideArgb !== bitmap) guideArgb.recycle()

            Log.i(TAG, "Guided SR - guide: ${guide.width}x${guide.height}, blend weight=$AI_BLEND_WEIGHT")

            // Step 4: ガイデッドブレンド
            val blended = RealEsrganBridge.nativeGuidedBlend(guide, aiResult, AI_BLEND_WEIGHT)
            guide.recycle()
            aiResult.recycle()

            if (blended != null) {
                Log.i(TAG, "Guided SR done: ${blended.width}x${blended.height}")
            } else {
                Log.e(TAG, "Guided blend failed")
            }
            blended
        } catch (e: Exception) {
            Log.e(TAG, "Guided SR error: ${e.message}")
            null
        }
    }

    /**
     * 単純4×超解像（小画像用）
     */
    private fun processSuperResolution(bitmap: Bitmap): Bitmap? {
        return try {
            val argbInput = ensureArgb(bitmap) ?: return null
            Log.i(TAG, "SR: ${argbInput.width}x${argbInput.height} -> ${argbInput.width * SCALE}x${argbInput.height * SCALE}")
            val result = RealEsrganBridge.nativeProcess(argbInput)
            if (argbInput !== bitmap) argbInput.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "SR error: ${e.message}")
            null
        }
    }

    /**
     * シャープ化のみ（フォールバック）
     */
    private fun processSharpenOnly(bitmap: Bitmap): Bitmap? {
        return try {
            val input = limitSize(bitmap, OUTPUT_SIZE)
            val argbInput = ensureArgb(input) ?: return null
            val result = RealEsrganBridge.nativeSharpen(argbInput, SHARPEN_STRENGTH)
            if (argbInput !== input && argbInput !== bitmap) argbInput.recycle()
            if (input !== bitmap) input.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Sharpen error: ${e.message}")
            null
        }
    }

    fun release() {
        try { RealEsrganBridge.nativeRelease() } catch (e: Exception) { Log.e(TAG, "Release: ${e.message}") }
        initialized = false
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap? {
        return if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap
    }

    private fun limitSize(bitmap: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / max
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(16)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(16)
        Log.i(TAG, "Limit: ${bitmap.width}x${bitmap.height} -> ${newW}x${newH}")
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
