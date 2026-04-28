// ファイルパス: app/src/main/java/com/nexus/vision/pipeline/RouteCProcessor.kt
package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.vision.ncnn.NcnnSuperResolution

/**
 * Route C プロセッサ（超解像エントリーポイント）
 *
 * NcnnSuperResolution の 3-Stage Fusion Pipeline を使用する。
 * 画像全体を均一に処理し、タイル境界アーティファクトを防止する。
 *
 * EASS パイプラインは Phase 6 で実装済みだが、ルート間の品質差による
 * ブロックノイズ問題のため一時無効化。将来、ルート間の出力品質を
 * 均一化した後に再有効化する。
 *
 * Phase 7: Real-ESRGAN 接続済み（現行）
 * Phase 6: EASS 統合（一時無効化）
 */
class RouteCProcessor(private val context: Context) {

    companion object {
        private const val TAG = "RouteCProcessor"

        // EASS を有効にする場合は true にする（現在は無効）
        private const val ENABLE_EASS = false
    }

    private var sr: NcnnSuperResolution? = null

    /**
     * NcnnSuperResolution モデルを初期化する。
     */
    fun initialize(): Boolean {
        sr = NcnnSuperResolution()
        val result = sr?.initialize(context) ?: false
        if (result) {
            Log.i(TAG, "NcnnSuperResolution initialized (EASS disabled)")
        } else {
            Log.e(TAG, "NcnnSuperResolution init failed")
        }
        return result
    }

    /**
     * 画像を超解像処理する。
     *
     * NcnnSuperResolution.upscale() が内部で自動判定:
     *   - 小画像 (≤128px): 直接 4× AI 超解像
     *   - 大画像 (>128px): Tiled Fusion Pipeline (AI + Guided Filter + DWT + IBP)
     *
     * @param bitmap 入力画像
     * @return 処理結果
     */
    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()

        return try {
            val result = sr?.upscale(bitmap)
            val elapsed = System.currentTimeMillis() - startTime

            if (result != null) {
                val method = when {
                    result.width > bitmap.width * 2 ->
                        "NCNN Real-ESRGAN 4× (Vulkan GPU)"
                    result.width > bitmap.width ->
                        "Tiled Laplacian Synthesis (周波数分離)"
                    else ->
                        "Fusion Pipeline (品質向上)"
                }

                Log.i(TAG, "Success: ${bitmap.width}x${bitmap.height} → " +
                        "${result.width}x${result.height} in ${elapsed}ms [$method]")

                ProcessResult(
                    bitmap = result,
                    method = method,
                    elapsedMs = elapsed,
                    success = true
                )
            } else {
                Log.w(TAG, "Processing returned null, returning original")
                ProcessResult(
                    bitmap = bitmap,
                    method = "passthrough (処理失敗)",
                    elapsedMs = elapsed,
                    success = false
                )
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "Process error: ${e.message}", e)
            ProcessResult(
                bitmap = bitmap,
                method = "passthrough (エラー: ${e.message})",
                elapsedMs = elapsed,
                success = false
            )
        }
    }

    /**
     * リソース解放
     */
    fun release() {
        sr?.release()
        sr = null
    }

    /**
     * 処理結果データクラス
     */
    data class ProcessResult(
        val bitmap: Bitmap,
        val method: String,
        val elapsedMs: Long,
        val success: Boolean
    ) {
        val timeMs: Long get() = elapsedMs
    }
}
