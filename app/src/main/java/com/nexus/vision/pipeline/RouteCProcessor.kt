// ファイルパス: app/src/main/java/com/nexus/vision/pipeline/RouteCProcessor.kt
package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.vision.image.EASSPipeline

/**
 * Route C プロセッサ（統合超解像エントリーポイント）
 *
 * EASS パイプラインを使用し、タイルごとに最適なルートで処理する。
 * EASS 初期化に失敗した場合は従来の NcnnSuperResolution にフォールバックする。
 *
 * Phase 6: EASS 統合
 * Phase 7: Real-ESRGAN 接続済み
 */
class RouteCProcessor(private val context: Context) {

    companion object {
        private const val TAG = "RouteCProcessor"
    }

    private var eassPipeline: EASSPipeline? = null
    private var fallbackSr: com.nexus.vision.ncnn.NcnnSuperResolution? = null
    private var useEASS = false

    fun initialize(): Boolean {
        // まず EASS を試す
        eassPipeline = EASSPipeline(context)
        useEASS = eassPipeline?.initialize() == true

        if (useEASS) {
            Log.i(TAG, "EASS Pipeline ready")
            return true
        }

        // EASS 失敗時は従来の NcnnSuperResolution にフォールバック
        Log.w(TAG, "EASS init failed, falling back to NcnnSuperResolution")
        fallbackSr = com.nexus.vision.ncnn.NcnnSuperResolution()
        return fallbackSr?.initialize(context) ?: false
    }

    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()

        if (useEASS && eassPipeline != null) {
            return processWithEASS(bitmap, startTime)
        }

        return processWithFallback(bitmap, startTime)
    }

    private suspend fun processWithEASS(bitmap: Bitmap, startTime: Long): ProcessResult {
        return try {
            // scale=1: 等倍で品質向上。大画像(>128px)は EASS で効率的に処理。
            // scale=4: 4倍拡大。小画像向け。
            val maxSide = maxOf(bitmap.width, bitmap.height)
            val scale = if (maxSide <= 128) 4 else 1

            val result = eassPipeline!!.process(bitmap, scale)
            val elapsed = System.currentTimeMillis() - startTime

            Log.i(TAG, "EASS: ${bitmap.width}x${bitmap.height} → ${result.bitmap.width}x${result.bitmap.height} in ${elapsed}ms")
            Log.i(TAG, "  ${result.summary()}")

            ProcessResult(
                bitmap = result.bitmap,
                method = result.summary(),
                elapsedMs = elapsed,
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "EASS failed: ${e.message}, trying fallback")
            // EASS 失敗時にフォールバック
            processWithFallback(bitmap, startTime)
        }
    }

    private suspend fun processWithFallback(bitmap: Bitmap, startTime: Long): ProcessResult {
        val result = fallbackSr?.upscale(bitmap) ?: run {
            // fallbackSr も null の場合は再初期化を試みる
            fallbackSr = com.nexus.vision.ncnn.NcnnSuperResolution()
            fallbackSr?.initialize(context)
            fallbackSr?.upscale(bitmap)
        }

        val elapsed = System.currentTimeMillis() - startTime

        return if (result != null) {
            val method = when {
                result.width > bitmap.width -> "NCNN Real-ESRGAN 4× (Vulkan GPU)"
                result.width == bitmap.width -> "Tiled Laplacian Synthesis (周波数分離)"
                else -> "Unsharp Mask シャープ化 (Native)"
            }
            Log.i(TAG, "Fallback: ${bitmap.width}x${bitmap.height} → ${result.width}x${result.height} in ${elapsed}ms [$method]")
            ProcessResult(result, method, elapsed, true)
        } else {
            Log.w(TAG, "All processing failed, returning original")
            ProcessResult(bitmap, "passthrough (failed)", elapsed, false)
        }
    }

    fun release() {
        eassPipeline?.release()
        eassPipeline = null
        fallbackSr?.release()
        fallbackSr = null
        useEASS = false
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
