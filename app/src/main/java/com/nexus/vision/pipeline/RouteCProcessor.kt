package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.vision.ncnn.SafmnSuperResolution

/**
 * Route C プロセッサ（超解像エントリーポイント）
 *
 * Phase 14-1: SAFMN++ (軽量・高速) と Real-ESRGAN (高品質・重い) の二段構成。
 * デフォルトは SAFMN++ を使用。フォールバックとして Real-ESRGAN を保持。
 */
class RouteCProcessor(private val context: Context) {

    companion object {
        private const val TAG = "RouteCProcessor"
    }

    enum class SrModel {
        SAFMN_PP,      // Phase 14-1: 軽量高速 (~0.5 MB)
        REAL_ESRGAN    // Phase 7: 重厚高品質 (~67 MB)
    }

    private var esrgan: NcnnSuperResolution? = null
    private var safmn: SafmnSuperResolution? = null
    var activeModel: SrModel = SrModel.REAL_ESRGAN
        private set

    /**
     * 両モデルを初期化。Real-ESRGAN を優先的に使用。
     */
    fun initialize(): Boolean {
        // Real-ESRGAN を初期化
        esrgan = NcnnSuperResolution()
        val esrganOk = esrgan?.initialize(context) ?: false
        if (esrganOk) {
            Log.i(TAG, "Real-ESRGAN initialized (primary)")
            activeModel = SrModel.REAL_ESRGAN
        } else {
            Log.e(TAG, "Real-ESRGAN init failed")
            esrgan = null
        }

        // SAFMN++ も初期化（無効化中だが構造は維持）
        safmn = SafmnSuperResolution()
        val safmnOk = safmn?.initialize(context) ?: false
        if (safmnOk) {
            Log.i(TAG, "SAFMN++ initialized")
        } else {
            Log.d(TAG, "SAFMN++ disabled or init failed")
            safmn = null
        }

        return esrganOk || safmnOk
    }

    /**
     * ユーザーまたは自動ロジックによるモデル切替
     */
    fun switchModel(model: SrModel): Boolean {
        return when (model) {
            SrModel.SAFMN_PP -> {
                if (safmn != null) { activeModel = model; true }
                else { Log.w(TAG, "SAFMN++ not available"); false }
            }
            SrModel.REAL_ESRGAN -> {
                if (esrgan != null) { activeModel = model; true }
                else { Log.w(TAG, "Real-ESRGAN not available"); false }
            }
        }
    }

    /**
     * アクティブなモデルで超解像処理
     */
    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()

        return try {
            val result = when (activeModel) {
                SrModel.SAFMN_PP -> safmn?.upscale(bitmap)
                SrModel.REAL_ESRGAN -> esrgan?.upscale(bitmap)
            }
            val elapsed = System.currentTimeMillis() - startTime

            if (result != null) {
                val method = when (activeModel) {
                    SrModel.SAFMN_PP -> "SAFMN++ 4× (Vulkan GPU, ~0.5MB)"
                    SrModel.REAL_ESRGAN -> "Real-ESRGAN 4× (Vulkan GPU, ~67MB)"
                }
                Log.i(TAG, "[$method] ${bitmap.width}x${bitmap.height} → " +
                        "${result.width}x${result.height} in ${elapsed}ms")

                ProcessResult(
                    bitmap = result,
                    method = method,
                    elapsedMs = elapsed,
                    success = true
                )
            } else {
                // アクティブモデル失敗 → フォールバック
                val fallbackResult = when (activeModel) {
                    SrModel.SAFMN_PP -> esrgan?.upscale(bitmap)
                    SrModel.REAL_ESRGAN -> safmn?.upscale(bitmap)
                }
                val totalElapsed = System.currentTimeMillis() - startTime
                if (fallbackResult != null) {
                    Log.w(TAG, "Primary failed, fallback succeeded in ${totalElapsed}ms")
                    ProcessResult(fallbackResult, "fallback SR", totalElapsed, true)
                } else {
                    ProcessResult(bitmap, "passthrough (両モデル失敗)", totalElapsed, false)
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "Process error: ${e.message}", e)
            ProcessResult(bitmap, "passthrough (エラー)", elapsed, false)
        }
    }

    fun release() {
        esrgan?.release()
        esrgan = null
        safmn?.release()
        safmn = null
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
