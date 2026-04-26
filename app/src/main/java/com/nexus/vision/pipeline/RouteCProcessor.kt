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

    /**
     * Bitmapを超解像する（呼び出し側で適切なサイズに制限済み）
     */
    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()
        val result = sr?.upscale(bitmap)
        val elapsed = System.currentTimeMillis() - startTime

        return if (result != null) {
            Log.i(TAG, "SR success: ${bitmap.width}x${bitmap.height} -> ${result.width}x${result.height} in ${elapsed}ms")
            ProcessResult(result, "NCNN Real-ESRGAN x4plus", elapsed, true)
        } else {
            Log.w(TAG, "SR failed, returning original")
            ProcessResult(bitmap, "passthrough (SR failed)", elapsed, false)
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
