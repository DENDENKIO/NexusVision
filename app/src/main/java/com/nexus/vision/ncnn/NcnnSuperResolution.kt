// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"
        private const val PARAM_FILE = "models/realesrgan-x4plus.param"
        private const val MODEL_FILE = "models/realesrgan-x4plus.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 64
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            val result = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "x4plus model initialized")
            else Log.e(TAG, "x4plus model init failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    /**
     * 4×超解像を実行
     * 入力は長辺2048px以下を想定（呼び出し側で制限済み）
     */
    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        return try {
            val argbInput = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            Log.i(TAG, "SR: ${argbInput.width}x${argbInput.height} -> ${argbInput.width * SCALE}x${argbInput.height * SCALE}")
            val startTime = System.currentTimeMillis()
            val result = RealEsrganBridge.nativeProcess(argbInput)
            val elapsed = System.currentTimeMillis() - startTime
            if (result != null) {
                Log.i(TAG, "SR done: ${result.width}x${result.height} in ${elapsed}ms")
            } else {
                Log.e(TAG, "nativeProcess returned null after ${elapsed}ms")
            }
            if (argbInput !== bitmap) argbInput.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "SR error: ${e.message}")
            null
        }
    }

    fun release() {
        try { RealEsrganBridge.nativeRelease() } catch (e: Exception) { Log.e(TAG, "Release error: ${e.message}") }
        initialized = false
    }
}
