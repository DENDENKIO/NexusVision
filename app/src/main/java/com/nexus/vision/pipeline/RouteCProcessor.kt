// ファイルパス: app/src/main/java/com/nexus/vision/pipeline/RouteCProcessor.kt
package com.nexus.vision.pipeline

import android.graphics.Bitmap
import android.util.Log

class RouteCProcessor {
    companion object {
        private const val TAG = "RouteCProcessor"
    }

    private var sr: com.nexus.vision.ncnn.NcnnSuperResolution? = null

    fun initialize(context: android.content.Context): Boolean {
        sr = com.nexus.vision.ncnn.NcnnSuperResolution()
        return sr?.initialize(context) ?: false
    }

    /** 画像全体の高画質化 */
    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()
        val result = sr?.upscale(bitmap)
        val elapsed = System.currentTimeMillis() - startTime

        return if (result != null) {
            Log.i(TAG, "SR success: ${bitmap.width}x${bitmap.height} -> ${result.width}x${result.height} in ${elapsed}ms")
            ProcessResult(result, "NCNN Real-ESRGAN 4× (CPU)", elapsed, true)
        } else {
            Log.w(TAG, "SR failed, returning original")
            ProcessResult(bitmap, "passthrough (SR failed)", elapsed, false)
        }
    }

    /** デジタルズーム（部分拡大＋超解像） */
    suspend fun digitalZoom(
        bitmap: Bitmap,
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        zoomFactor: Float = 2.0f
    ): ProcessResult {
        val startTime = System.currentTimeMillis()
        val result = sr?.digitalZoom(bitmap, centerX, centerY, zoomFactor)
        val elapsed = System.currentTimeMillis() - startTime

        return if (result != null) {
            Log.i(TAG, "Digital zoom success: ${result.width}x${result.height} in ${elapsed}ms")
            ProcessResult(result, "NCNN Real-ESRGAN DigitalZoom ${zoomFactor}×", elapsed, true)
        } else {
            Log.w(TAG, "Digital zoom failed, returning original")
            ProcessResult(bitmap, "passthrough (zoom failed)", elapsed, false)
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
    )
}
