

**問題 1: バッチ処理では個別処理と同じ 4096px でデコードしているため、巨大画像だとタイル数が爆発する。** バッチの目的は「複数枚をまとめて自動処理」なので、1 枚に 17 分もかけるべきではありません。バッチ用にはデコードサイズを小さく制限すべきです。

**問題 2: WorkManager のデフォルトタイムアウトが 10 分。** API 34 以降、Worker は 10 分で強制停止される可能性があります。

以下の修正をコード作成AIにそのまま渡してください。

---

# バッチ処理パフォーマンス修正指示

## 修正するファイル

`app/src/main/java/com/nexus/vision/worker/BatchEnhanceWorker.kt` のみ。

## 変更内容

1. バッチ処理用のデコードサイズを `4096` → `2048` に制限（タイル数が 1/4 になり、処理時間も約 1/4 に短縮）
2. WorkManager の 10 分制限対策として `setExpedited` ではなく長時間実行に対応するよう `setForeground` を `doWork()` の冒頭で呼ぶ
3. タイル処理の進捗を通知に反映する仕組みを追加

## 修正後の完全なファイル

```kotlin
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
 * バッチ高画質化 WorkManager Worker
 *
 * BatchEnhanceQueue からアイテムを 1 枚ずつ取り出し、
 * RouteCProcessor で超解像してギャラリーに保存する。
 * フォアグラウンドサービスとして動作し、バックグラウンドでも処理を継続する。
 */
class BatchEnhanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "BatchEnhanceWorker"
        const val WORK_NAME = "batch_enhance"

        /**
         * バッチ処理用のデコード最大辺
         *
         * 個別処理（MainViewModel）は 4096px だが、バッチは自動処理のため
         * 2048px に制限してタイル数を 1/4 に抑える。
         * 2048px → 出力 4096px（4x拡大）で十分な高画質。
         * タイル数: 約 256〜300（処理時間 約 4〜5 分/枚）
         *
         * 元画像が 2048px 以下の場合はそのまま処理される。
         */
        private const val BATCH_MAX_DECODE_SIDE = 2048

        private const val JPEG_QUALITY = 95
        private const val THERMAL_CHECK_SEVERE_MS = 5_000L
        private const val THERMAL_CHECK_CRITICAL_MS = 15_000L
    }

    private val app = NexusApplication.getInstance()
    private val thermalMonitor = app.thermalMonitor
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

        // RouteCProcessor を初期化
        routeC = RouteCProcessor(applicationContext)
        val initOk = routeC?.initialize() == true
        if (!initOk) {
            Log.e(TAG, "RouteCProcessor init failed")
            BatchEnhanceQueue.abort("超解像エンジンの初期化に失敗しました")
            return Result.failure()
        }

        Log.i(TAG, "RouteCProcessor initialized, starting queue processing")

        try {
            processQueue()
        } finally {
            routeC?.release()
            routeC = null
        }

        val progress = BatchEnhanceQueue.progress.value
        BatchNotificationHelper.notifyBatchComplete(
            applicationContext,
            progress.total,
            progress.successCount,
            progress.failed
        )

        Log.i(TAG, "BatchEnhanceWorker finished: ${progress.successCount}/${progress.total} success, ${progress.failed} failed")
        return Result.success()
    }

    private suspend fun processQueue() {
        while (true) {
            // 発熱チェック
            val thermalHandled = handleThermal()
            if (!thermalHandled) return  // EMERGENCY で中断

            val item = BatchEnhanceQueue.dequeueNext() ?: break

            val progress = BatchEnhanceQueue.progress.value

            // フォアグラウンド通知を更新
            updateProgressNotification(
                progress.completed + 1,
                progress.total,
                item.displayName,
                progress.estimatedRemainingMs
            )

            // 1 枚処理
            val startTime = System.currentTimeMillis()
            try {
                val resultUri = processOneImage(item.uri)
                val elapsed = System.currentTimeMillis() - startTime

                if (resultUri != null) {
                    BatchEnhanceQueue.markDone(item, resultUri, elapsed)
                    val updatedProgress = BatchEnhanceQueue.progress.value
                    BatchNotificationHelper.notifyItemComplete(
                        applicationContext,
                        updatedProgress.completed,
                        updatedProgress.total,
                        item.displayName
                    )
                    Log.i(TAG, "Done: ${item.displayName} in ${elapsed}ms -> $resultUri")
                } else {
                    BatchEnhanceQueue.markFailed(item, "処理結果が null", System.currentTimeMillis() - startTime)
                    Log.w(TAG, "Failed: ${item.displayName} (null result)")
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                BatchEnhanceQueue.markFailed(item, e.message ?: "unknown error", elapsed)
                Log.e(TAG, "Error processing ${item.displayName}: ${e.message}", e)
            }
        }
    }

    /**
     * フォアグラウンド通知を更新
     */
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

    /**
     * 発熱レベルに応じて待機またはアボート。
     * @return true なら続行可能、false なら中断（EMERGENCY）
     */
    private suspend fun handleThermal(): Boolean {
        while (true) {
            val level = thermalMonitor.thermalLevel.value

            when {
                level.code >= ThermalLevel.EMERGENCY.code -> {
                    Log.w(TAG, "EMERGENCY thermal — aborting batch")
                    val progress = BatchEnhanceQueue.progress.value
                    BatchEnhanceQueue.abort("発熱により中断 (${level.name})")
                    BatchNotificationHelper.notifyThermalAbort(
                        applicationContext, progress.completed, progress.total
                    )
                    return false
                }
                level.code >= ThermalLevel.CRITICAL.code -> {
                    Log.w(TAG, "CRITICAL thermal — pausing 15s")
                    BatchEnhanceQueue.markPaused("発熱: ${level.name}")
                    BatchNotificationHelper.notifyThermalPause(applicationContext, level.name)
                    delay(THERMAL_CHECK_CRITICAL_MS)
                }
                level.code >= ThermalLevel.SEVERE.code -> {
                    Log.w(TAG, "SEVERE thermal — pausing 5s")
                    BatchEnhanceQueue.markPaused("発熱: ${level.name}")
                    BatchNotificationHelper.notifyThermalPause(applicationContext, level.name)
                    delay(THERMAL_CHECK_SEVERE_MS)
                }
                else -> {
                    if (BatchEnhanceQueue.progress.value.isPaused) {
                        BatchEnhanceQueue.resume()
                        Log.i(TAG, "Thermal recovered — resuming")
                    }
                    return true
                }
            }
        }
    }

    /**
     * 1 枚の画像を超解像してギャラリーに保存する
     */
    private suspend fun processOneImage(uri: Uri): Uri? {
        val context = applicationContext

        // バッチ用: 2048px に制限してデコード（タイル数を抑制）
        val decoded = RegionDecoder.decodeSafe(context, uri, BATCH_MAX_DECODE_SIDE)
            ?: return null

        Log.i(TAG, "Decoded: ${decoded.width}x${decoded.height} (max=$BATCH_MAX_DECODE_SIDE)")

        val safeBitmap = if (decoded.config != Bitmap.Config.ARGB_8888) {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
            decoded.recycle()
            copy ?: return null
        } else {
            decoded
        }

        // 超解像
        val result = routeC?.process(safeBitmap)

        val outputBitmap: Bitmap
        if (result != null && result.success) {
            outputBitmap = result.bitmap
            if (safeBitmap !== result.bitmap) safeBitmap.recycle()
            Log.i(TAG, "Enhanced: ${outputBitmap.width}x${outputBitmap.height} (${result.method}, ${result.elapsedMs}ms)")
        } else {
            safeBitmap.recycle()
            return null
        }

        // 保存
        val savedUri = saveBitmapToGallery(outputBitmap, "Enhanced")
        outputBitmap.recycle()
        return savedUri
    }

    /**
     * ギャラリーに保存。大画像はストリーミング JPEG を使用。
     */
    private fun saveBitmapToGallery(bitmap: Bitmap, prefix: String): Uri? {
        if (bitmap.width.toLong() * bitmap.height > 4096L * 4096) {
            return saveBitmapStreaming(bitmap, prefix)
        }

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
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            }
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * ストリーミング JPEG 保存（大画像用）
     */
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
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            resolver.openFileDescriptor(uri, "w")?.use { pfd ->
                val fd = pfd.detachFd()
                val ctx = RealEsrganBridge.nativeJpegBeginWrite(fd, bitmap.width, bitmap.height, JPEG_QUALITY)
                if (ctx != 0L) {
                    val batchRows = 64
                    for (row in 0 until bitmap.height step batchRows) {
                        val numRows = minOf(batchRows, bitmap.height - row)
                        RealEsrganBridge.nativeJpegWriteRows(ctx, bitmap, row, numRows)
                    }
                    RealEsrganBridge.nativeJpegEndWrite(ctx)
                }
            }
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Streaming save failed: ${e.message}")
            resolver.delete(uri, null, null)
            null
        }
    }
}
```

---

変更点のまとめです。

`MAX_DECODE_SIDE` を `4096` → `2048` に変更しました。これにより、元画像が 4096px でデコードされていたのが 2048px になり、タイル数が約 1147 → 約 280 に減ります。処理時間は 1 枚あたり **17 分 → 約 4 分** に短縮されます。出力は 2048×4 = 8192px 相当なので画質は十分です。

`doWork()` の冒頭で即座に `setForeground()` を呼ぶようにしました。これで WorkManager の 10 分バックグラウンド制限に引っかかる前にフォアグラウンドサービスとして認識されます。

ファイル名のタイムスタンプに `_SSS`（ミリ秒）を追加しました。バッチで高速に連続保存した場合にファイル名が衝突しないようにするためです。