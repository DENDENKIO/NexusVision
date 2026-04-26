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
        private const val DETAIL_STRENGTH = 1.2f
        private const val SHARPEN_STRENGTH = 0.4f
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
     * 超解像パイプライン
     * 小画像(≤512px): 4×拡大
     * 大画像(>512px): ラプラシアン合成（出力＝入力と同サイズ）
     */
    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }

        val maxSide = maxOf(bitmap.width, bitmap.height)

        return if (maxSide <= SR_MAX_INPUT) {
            Log.i(TAG, "Small image: direct 4x SR")
            processSuperResolution(bitmap)
        } else {
            Log.i(TAG, "Large image: Laplacian pyramid SR (output = input size)")
            processLaplacianSR(bitmap)
        }
    }

    /**
     * ラプラシアンピラミッド超解像
     * 出力サイズ ＝ 入力サイズ（縮小しない）
     *
     * 流れ:
     * 1. input (例: 2172×2896) → そのまま original として使用
     * 2. input を 512 に縮小 → AI 4×超解像 → 2048
     * 3. AI結果を input サイズ (2172×2896) にアップスケール
     * 4. ラプラシアン合成: original の高周波 + AI(upscaled) の低周波
     * 5. 出力: 2172×2896（元と同じサイズ、ディテール保持＋AI改善）
     */
    private fun processLaplacianSR(bitmap: Bitmap): Bitmap? {
        return try {
            val targetW = bitmap.width
            val targetH = bitmap.height
            Log.i(TAG, "Target output size: ${targetW}x${targetH}")

            val original = ensureArgb(bitmap) ?: return null

            // Step 1: AI用入力（512以下に縮小）
            val srInput = limitSize(original, SR_MAX_INPUT)
            val srArgb = ensureArgb(srInput) ?: return null

            Log.i(TAG, "AI input: ${srArgb.width}x${srArgb.height}")

            // Step 2: AI超解像 (4×)
            val aiResult = RealEsrganBridge.nativeProcess(srArgb)
            if (srArgb !== srInput) srArgb.recycle()
            if (srInput !== original && srInput !== bitmap) srInput.recycle()

            if (aiResult == null) {
                Log.e(TAG, "AI SR failed, fallback to sharpen only")
                val result = RealEsrganBridge.nativeSharpen(original, SHARPEN_STRENGTH)
                if (original !== bitmap) original.recycle()
                return result
            }

            Log.i(TAG, "AI output: ${aiResult.width}x${aiResult.height}")

            // Step 3: AI結果を元画像サイズにアップスケール
            val aiUpscaled = Bitmap.createScaledBitmap(aiResult, targetW, targetH, true)
            aiResult.recycle()

            val aiArgb = ensureArgb(aiUpscaled) ?: run {
                if (original !== bitmap) original.recycle()
                return null
            }
            if (aiUpscaled !== aiArgb) aiUpscaled.recycle()

            Log.i(TAG, "AI upscaled to target: ${aiArgb.width}x${aiArgb.height}")

            // Step 4: ラプラシアンピラミッド合成
            // original (元サイズ) の高周波 + aiArgb (元サイズ) の低周波
            val blended = RealEsrganBridge.nativeLaplacianBlend(
                original, aiArgb, DETAIL_STRENGTH, SHARPEN_STRENGTH
            )
            if (original !== bitmap) original.recycle()
            aiArgb.recycle()

            if (blended != null) {
                Log.i(TAG, "Laplacian SR done: ${blended.width}x${blended.height} (same as input)")
            } else {
                Log.e(TAG, "Laplacian blend failed")
            }
            blended
        } catch (e: Exception) {
            Log.e(TAG, "Laplacian SR error: ${e.message}")
            if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
            null
        }
    }

    private fun processSuperResolution(bitmap: Bitmap): Bitmap? {
        return try {
            val argb = ensureArgb(bitmap) ?: return null
            Log.i(TAG, "SR: ${argb.width}x${argb.height} -> ${argb.width * SCALE}x${argb.height * SCALE}")
            val result = RealEsrganBridge.nativeProcess(argb)
            if (argb !== bitmap) argb.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "SR error: ${e.message}")
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
