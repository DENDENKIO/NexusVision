// ファイルパス: app/src/main/java/com/nexus/vision/pipeline/RouteCProcessor.kt
package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.vision.ncnn.NcnnSuperResolution

class RouteCProcessor(context: Context) {

    companion object {
        private const val TAG = "RouteCProcessor"
    }

    private val ncnnSR = NcnnSuperResolution(context)

    suspend fun initialize(): Boolean {
        return ncnnSR.initialize()
    }

    /**
     * 超解像を実行。
     * 成功 → 高解像度 Bitmap
     * 失敗 → 元の Bitmap をそのまま返す（品質を悪化させない）
     */
    suspend fun process(input: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()

        val result = ncnnSR.upscale(input)
        val elapsed = System.currentTimeMillis() - startTime

        return if (result != null) {
            Log.i(TAG, "SR success: ${input.width}x${input.height} -> " +
                "${result.width}x${result.height} (${elapsed}ms)")
            ProcessResult(
                bitmap = result,
                method = "NCNN Real-ESRGAN (CPU)",
                timeMs = elapsed,
                success = true
            )
        } else {
            Log.w(TAG, "SR failed, returning original image (NO degradation)")
            ProcessResult(
                bitmap = input,
                method = "passthrough (SR failed)",
                timeMs = elapsed,
                success = false
            )
        }
    }

    fun release() {
        ncnnSR.release()
    }

    data class ProcessResult(
        val bitmap: Bitmap,
        val method: String,
        val timeMs: Long,
        val success: Boolean
    )
}
