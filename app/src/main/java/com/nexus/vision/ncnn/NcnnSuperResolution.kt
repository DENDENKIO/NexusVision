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

        // この閾値以下: 4×超解像、超えたら: シャープ化のみ
        private const val SR_MAX_INPUT = 512
        // シャープ化の入力上限（メモリ安全）
        private const val SHARPEN_MAX_INPUT = 2048
        // シャープ強度
        private const val SHARPEN_STRENGTH = 0.6f
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
     * 画像サイズに応じて最適な処理を自動選択
     * 小さい画像 → 4×超解像（拡大＋高画質化）
     * 大きい画像 → シャープ化のみ（元サイズ維持、鮮明化）
     */
    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }

        val maxSide = maxOf(bitmap.width, bitmap.height)

        return if (maxSide <= SR_MAX_INPUT) {
            // 小さい画像: 4×超解像
            Log.i(TAG, "Strategy: 4x SR (${bitmap.width}x${bitmap.height})")
            processSuperResolution(bitmap)
        } else {
            // 大きい画像: シャープ化のみ（縮小しない！）
            Log.i(TAG, "Strategy: Sharpen only (${bitmap.width}x${bitmap.height})")
            processSharpen(bitmap)
        }
    }

    /**
     * 4×超解像（入力はSR_MAX_INPUT以下を想定）
     */
    private fun processSuperResolution(bitmap: Bitmap): Bitmap? {
        return try {
            val argbInput = ensureArgb(bitmap) ?: return null
            Log.i(TAG, "SR: ${argbInput.width}x${argbInput.height} -> ${argbInput.width * SCALE}x${argbInput.height * SCALE}")

            val result = RealEsrganBridge.nativeProcess(argbInput)

            if (result != null) {
                Log.i(TAG, "SR done: ${result.width}x${result.height}")
            } else {
                Log.e(TAG, "SR failed")
            }
            if (argbInput !== bitmap) argbInput.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "SR error: ${e.message}")
            null
        }
    }

    /**
     * シャープ化のみ（元サイズ維持）
     * 大きすぎる場合はSHARPEN_MAX_INPUTに縮小
     */
    private fun processSharpen(bitmap: Bitmap): Bitmap? {
        return try {
            val input = limitSize(bitmap, SHARPEN_MAX_INPUT)
            val argbInput = ensureArgb(input) ?: return null

            Log.i(TAG, "Sharpen: ${argbInput.width}x${argbInput.height}, strength=$SHARPEN_STRENGTH")
            val result = RealEsrganBridge.nativeSharpen(argbInput, SHARPEN_STRENGTH)

            if (result != null) {
                Log.i(TAG, "Sharpen done: ${result.width}x${result.height}")
            } else {
                Log.e(TAG, "Sharpen failed")
            }
            if (argbInput !== bitmap && argbInput !== input) argbInput.recycle()
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
        } else {
            bitmap
        }
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
