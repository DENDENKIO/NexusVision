// ファイルパス: app/src/main/java/com/nexus/vision/worker/BatchEnhanceWorker.kt

package com.nexus.vision.worker

import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.nexus.vision.NexusApplication
import com.nexus.vision.engine.ThermalLevel
import com.nexus.vision.image.RegionDecoder
import com.nexus.vision.ncnn.RealEsrganBridge
import com.nexus.vision.notification.BatchNotificationHelper
import com.nexus.vision.pipeline.RouteCProcessor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WorkManager によるバッチ画像処理 Worker
 */
class BatchEnhanceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BatchEnhanceWorker"
        const val WORK_NAME = "nexus_batch_enhance"

        /**
         * バッチ処理用のデコード最大辺
         * 2048px に制限してタイル数を 1/4 に抑える。
         * タイル数: 約 256〜300（処理時間 約 4〜5 分/枚）
         */
        private const val BATCH_MAX_DECODE_SIDE = 2048

        private const val JPEG_QUALITY = 95
        private const val THERMAL_CHECK_SEVERE_MS = 5_000L
        private const val THERMAL_CHECK_CRITICAL_MS = 15_000L
    }

    private val application = NexusApplication.getInstance()
    private var routeC: RouteCProcessor? = null

    override suspend fun doWork(): Result {
        Log.i(TAG, "BatchEnhanceWorker started")
        
        BatchNotificationHelper.createChannels(applicationContext)

        // 即座にフォアグラウンドにして 10 分制限を回避
        val initialNotification = BatchNotificationHelper.createProgressNotification(
            applicationContext, 0, BatchEnhanceQueue.progress.value.total,
            "準備中...", -1L
        )
        setForeground(
            ForegroundInfo(
                BatchNotificationHelper.NOTIFICATION_ID_PROGRESS,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        )

        // RouteCProcessor の初期化
        routeC = RouteCProcessor(applicationContext)
        val ready = routeC?.initialize() ?: false
        if (!ready) {
            Log.e(TAG, "RouteCProcessor failed to initialize in Worker")
            BatchEnhanceQueue.abort("エンジンの初期化に失敗しました")
            return Result.failure()
        }

        try {
            batchLoop@ while (true) {
                // サーマルレベルチェック
                val thermal = application.thermalMonitor.thermalLevel.value
                when {
                    thermal >= ThermalLevel.EMERGENCY -> {
                        Log.w(TAG, "Thermal level EMERGENCY: Aborting batch")
                        BatchEnhanceQueue.abort("デバイスが高温のため中断しました")
                        BatchNotificationHelper.notifyThermalAbort(
                            applicationContext,
                            BatchEnhanceQueue.progress.value.completed,
                            BatchEnhanceQueue.progress.value.total
                        )
                        return Result.retry()
                    }
                    thermal >= ThermalLevel.SEVERE -> {
                        Log.i(TAG, "Thermal level SEVERE: Pausing...")
                        BatchEnhanceQueue.markPaused(thermal.name)
                        BatchNotificationHelper.notifyThermalPause(applicationContext, thermal.name)
                        delay(10000) // 10秒待って再チェック
                        continue@batchLoop
                    }
                    else -> {
                        if (BatchEnhanceQueue.progress.value.isPaused) {
                            Log.i(TAG, "Thermal recovered: Resuming")
                            BatchEnhanceQueue.resume()
                        }
                    }
                }

                // 次のアイテムを取得
                val item = BatchEnhanceQueue.dequeueNext() ?: break@batchLoop
                
                // 進捗通知更新
                val progress = BatchEnhanceQueue.progress.value
                updateProgressNotification(
                    progress.completed + 1, 
                    progress.total, 
                    item.displayName,
                    progress.estimatedRemainingMs
                )

                val startTime = System.currentTimeMillis()
                
                try {
                    val resultUri = processOneItem(item)
                    val timeMs = System.currentTimeMillis() - startTime
                    
                    if (resultUri != null) {
                        BatchEnhanceQueue.markDone(item, resultUri, timeMs)
                        BatchNotificationHelper.notifyItemComplete(
                            applicationContext,
                            progress.completed + 1,
                            progress.total,
                            item.displayName
                        )
                    } else {
                        BatchEnhanceQueue.markFailed(item, "処理エラー", timeMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing item: ${item.displayName}", e)
                    BatchEnhanceQueue.markFailed(item, e.message ?: "Unknown error", System.currentTimeMillis() - startTime)
                }

                // 連続処理による過熱防止のため、1枚ごとに少し休む
                delay(500)
            }

            Log.i(TAG, "Batch processing completed")
            val final = BatchEnhanceQueue.progress.value
            BatchNotificationHelper.notifyBatchComplete(
                applicationContext,
                final.total,
                final.successCount,
                final.failed
            )
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Batch worker fatal error", e)
            return Result.failure()
        } finally {
            routeC?.release()
        }
    }

    private suspend fun processOneItem(item: BatchEnhanceQueue.BatchItem): Uri? {
        val context = applicationContext
        
        // 1. デコード（バッチ用: 2048px に制限してタイル数を抑制）
        val bitmap = RegionDecoder.decodeSafe(context, item.uri, BATCH_MAX_DECODE_SIDE) ?: return null
        
        // 2. 超解像処理
        val result = routeC?.process(bitmap)
        bitmap.recycle()
        
        if (result == null || !result.success) {
            return null
        }
        
        // 3. 保存（ストリーミング保存を使用）
        val resultUri = saveBitmapStreaming(result.bitmap, "BatchEnhanced")
        result.bitmap.recycle()
        
        return resultUri
    }

    private fun saveBitmapStreaming(bitmap: Bitmap, prefix: String): Uri? {
        val context = applicationContext
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "${prefix}_${timestamp}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NexusVision")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

        try {
            resolver.openFileDescriptor(uri, "w")?.use { pfd ->
                val fd = pfd.detachFd()
                val ctx = RealEsrganBridge.nativeJpegBeginWrite(fd, bitmap.width, bitmap.height, 95)
                if (ctx != 0L) {
                    val batchRows = 64
                    for (row in 0 until bitmap.height step batchRows) {
                        val numRows = if (row + batchRows > bitmap.height) bitmap.height - row else batchRows
                        RealEsrganBridge.nativeJpegWriteRows(ctx, bitmap, row, numRows)
                    }
                    RealEsrganBridge.nativeJpegEndWrite(ctx)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Streaming save failed in worker: ${e.message}")
            resolver.delete(uri, null, null)
            return null
        }
    }

    private suspend fun updateProgressNotification(
        current: Int, total: Int, fileName: String, estimatedMs: Long
    ) {
        val notification = BatchNotificationHelper.createProgressNotification(
            applicationContext, current, total, fileName, estimatedMs
        )
        setForeground(
            ForegroundInfo(
                BatchNotificationHelper.NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        )
    }
}
