// ファイルパス: app/src/main/java/com/nexus/vision/pipeline/RouteCProcessor.kt
package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class RouteCProcessor(private val context: Context) {
    companion object {
        private const val TAG = "RouteCProcessor"
    }

    private var sr: com.nexus.vision.ncnn.NcnnSuperResolution? = null

    fun initialize(): Boolean {
        sr = com.nexus.vision.ncnn.NcnnSuperResolution()
        return sr?.initialize(context) ?: false
    }

    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()
        val result = sr?.upscale(bitmap)
        val elapsed = System.currentTimeMillis() - startTime

        return if (result != null) {
            val method = when {
                result.width > bitmap.width -> "NCNN Real-ESRGAN 4× (Vulkan GPU)"
                result.width == bitmap.width && result.height == bitmap.height -> "Tiled Laplacian Synthesis (周波数分離)"
                else -> "Unsharp Mask シャープ化 (Native)"
            }
            Log.i(TAG, "Success: ${bitmap.width}x${bitmap.height} -> ${result.width}x${result.height} in ${elapsed}ms [$method]")
            ProcessResult(result, method, elapsed, true)
        } else {
            Log.w(TAG, "Failed, returning original")
            ProcessResult(bitmap, "passthrough (failed)", elapsed, false)
        }
    }

    fun release() {
        sr?.release()
        sr = null
    }

    data class ProcessResult(
        val bitmap: Bitmap,
        val method: String,
        val elapsedMs: Long,
        val success: Boolean
    ) {
        val timeMs: Long get() = elapsedMs
    }
}
