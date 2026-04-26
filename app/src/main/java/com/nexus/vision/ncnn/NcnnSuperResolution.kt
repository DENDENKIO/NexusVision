// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NcnnSuperResolution(private val context: Context) {

    companion object {
        private const val TAG = "NcnnSR"
        private const val PARAM_FILE = "models/realesr-animevideov3-x4.param"
        private const val MODEL_FILE = "models/realesr-animevideov3-x4.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 200
    }

    private var initialized = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) {
            Log.i(TAG, "Already initialized")
            return@withContext true
        }

        try {
            val success = RealEsrganBridge.nativeInit(
                context.assets,
                PARAM_FILE,
                MODEL_FILE,
                SCALE,
                TILE_SIZE
            )
            initialized = success
            if (success) {
                Log.i(TAG, "NCNN RealESRGAN initialized (CPU mode)")
            } else {
                Log.e(TAG, "Failed to initialize NCNN RealESRGAN")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Init error", e)
            false
        }
    }

    suspend fun upscale(input: Bitmap): Bitmap? = withContext(Dispatchers.IO) {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Not initialized, attempting init...")
            if (!initialize()) {
                Log.e(TAG, "Re-init failed")
                return@withContext null
            }
        }

        val rgbaBitmap = if (input.config == Bitmap.Config.ARGB_8888) {
            input
        } else {
            input.copy(Bitmap.Config.ARGB_8888, false)
        }

        try {
            val startTime = System.currentTimeMillis()
            val result = RealEsrganBridge.nativeProcess(rgbaBitmap)
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Upscale: ${input.width}x${input.height} -> " +
                "${result?.width}x${result?.height} in ${elapsed}ms")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Upscale error", e)
            null
        }
    }

    fun release() {
        RealEsrganBridge.nativeRelease()
        initialized = false
    }
}
