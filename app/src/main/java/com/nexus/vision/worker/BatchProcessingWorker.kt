// ファイルパス: app/src/main/java/com/nexus/vision/worker/BatchProcessingWorker.kt

package com.nexus.vision.worker

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nexus.vision.NexusApplication
import com.nexus.vision.deor.AdaptiveResizer
import com.nexus.vision.deor.PHashCalculator
import com.nexus.vision.engine.NexusEngineManager
import java.io.File
import java.io.FileOutputStream

/**
 * バッチ画像処理 Worker
 *
 * 複数画像を順番に処理し、進捗を通知する。
 *
 * Phase 4: 基本実装（画像分類バッチ）
 * Phase 5: 100MP ダイレクトクロップ連携
 * Phase 6: EASS パイプライン連携
 * Phase 12: ウィジェット進捗表示連携
 */
class BatchProcessingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BatchWorker"

        // 入力データキー
        const val KEY_IMAGE_URIS = "image_uris"
        const val KEY_TASK_TYPE = "task_type"

        // 出力データキー
        const val KEY_RESULTS = "results"
        const val KEY_PROCESSED_COUNT = "processed_count"
        const val KEY_TOTAL_COUNT = "total_count"

        // 進捗データキー
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_FILENAME = "progress_filename"
    }

    private val app = NexusApplication.getInstance()
    private val engineManager = NexusEngineManager.getInstance()
    private val l1Cache = app.l1Cache

    override suspend fun doWork(): Result {
        val uriStrings = inputData.getStringArray(KEY_IMAGE_URIS)
            ?: return Result.failure(workDataOf("error" to "No image URIs provided"))

        val taskType = inputData.getString(KEY_TASK_TYPE) ?: "classify"
        val totalCount = uriStrings.size

        Log.i(TAG, "Starting batch: $totalCount images, task=$taskType")

        // エンジンロード確認
        if (engineManager.state.value !is com.nexus.vision.engine.EngineState.Ready) {
            engineManager.loadEngine()
        }

        val results = mutableListOf<String>()
        var processedCount = 0

        for ((index, uriString) in uriStrings.withIndex()) {
            // キャンセルチェック
            if (isStopped) {
                Log.w(TAG, "Batch cancelled at $index/$totalCount")
                return Result.failure(
                    workDataOf(
                        KEY_PROCESSED_COUNT to processedCount,
                        KEY_TOTAL_COUNT to totalCount,
                        "error" to "Cancelled"
                    )
                )
            }

            // 進捗更新
            setProgress(
                workDataOf(
                    KEY_PROGRESS_CURRENT to (index + 1),
                    KEY_PROGRESS_TOTAL to totalCount,
                    KEY_PROGRESS_FILENAME to uriString.substringAfterLast('/')
                )
            )

            try {
                val result = processImage(Uri.parse(uriString), taskType)
                results.add(result)
                processedCount++
                Log.d(TAG, "Processed ${index + 1}/$totalCount: ${uriString.takeLast(30)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process image ${index + 1}/$totalCount", e)
                results.add("ERROR: ${e.message}")
            }
        }

        Log.i(TAG, "Batch complete: $processedCount/$totalCount succeeded")

        return Result.success(
            workDataOf(
                KEY_RESULTS to results.toTypedArray(),
                KEY_PROCESSED_COUNT to processedCount,
                KEY_TOTAL_COUNT to totalCount
            )
        )
    }

    private suspend fun processImage(uri: Uri, taskType: String): String {
        val inputStream = applicationContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open: $uri")
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        if (bitmap == null) throw IllegalStateException("Decode failed: $uri")

        // pHash + L1 キャッシュ
        val pHash = PHashCalculator.calculate(bitmap)
        val cached = l1Cache.lookup(pHash, category = taskType)
        if (cached != null) {
            bitmap.recycle()
            return cached.resultText
        }

        // DEOR リサイズ
        val resizeResult = AdaptiveResizer.resize(bitmap)

        // 一時ファイルに保存
        val tempFile = File(applicationContext.cacheDir, "batch_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use { out ->
            resizeResult.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }

        if (resizeResult.bitmap !== bitmap) {
            resizeResult.bitmap.recycle()
        }
        bitmap.recycle()

        // エンジン推論
        val prompt = when (taskType) {
            "classify" -> "この画像の内容を簡潔に説明してください。"
            "ocr" -> "この画像内のテキストをすべて読み取ってください。"
            else -> "この画像を分析してください。"
        }

        val result = engineManager.inferImage(tempFile.absolutePath, prompt)
        tempFile.delete()

        val response = result.getOrThrow()

        // L1 キャッシュ保存
        l1Cache.put(
            pHash = pHash,
            resultText = response,
            category = taskType,
            entropy = resizeResult.entropy
        )

        return response
    }
}
