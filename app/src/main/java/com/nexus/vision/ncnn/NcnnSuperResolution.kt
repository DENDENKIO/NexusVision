// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"
        // 軽量モデル（1.2MB、モバイル向け）
        // assets/models/ に配置されていることを前提
        private const val PARAM_FILE = "models/realesr-animevideov3-x4.param"
        private const val MODEL_FILE = "models/realesr-animevideov3-x4.bin"
        private const val SCALE = 4
        // タイルサイズ32（Mali-G68で安全なサイズ）
        private const val TILE_SIZE = 32
        // 入力上限: 4×で出力2048px → 入力512px
        private const val MAX_INPUT_SIDE = 512
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            val result = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "Initialized (Vulkan GPU + FP16, tile=$TILE_SIZE)")
            else Log.e(TAG, "Init failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        return try {
            val input = limitSize(bitmap)
            val argbInput = if (input.config != Bitmap.Config.ARGB_8888) {
                input.copy(Bitmap.Config.ARGB_8888, false).also {
                    if (input !== bitmap) input.recycle()
                }
            } else {
                input
            }

            if (argbInput == null) {
                Log.e(TAG, "ARGB copy failed")
                return null
            }

            Log.i(TAG, "SR: ${argbInput.width}x${argbInput.height} -> ${argbInput.width * SCALE}x${argbInput.height * SCALE}")
            val startTime = System.currentTimeMillis()
            val result = RealEsrganBridge.nativeProcess(argbInput)
            val elapsed = System.currentTimeMillis() - startTime

            if (result != null) {
                Log.i(TAG, "SR done: ${result.width}x${result.height} in ${elapsed}ms")
            } else {
                Log.e(TAG, "nativeProcess returned null (${elapsed}ms)")
            }

            if (argbInput !== bitmap && argbInput !== input) argbInput.recycle()
            if (input !== bitmap) input.recycle()

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

    private fun limitSize(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= MAX_INPUT_SIDE) return bitmap
        val ratio = MAX_INPUT_SIDE.toFloat() / maxSide
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(16)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(16)
        Log.i(TAG, "Limit: ${bitmap.width}x${bitmap.height} -> ${newW}x${newH}")
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
