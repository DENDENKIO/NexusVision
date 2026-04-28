
---

# バッチ高画質化機能 実装指示書

## プロジェクト概要

- **リポジトリ**: https://github.com/DENDENKIO/NexusVision/tree/master
- **パッケージ**: `com.nexus.vision`
- **言語**: Kotlin、Compose、API 31〜35、arm64-v8a
- **ビルド**: AGP 8.7.3、NDK 27、CMake 3.22.1

## 今回の目的

複数画像を選択してバックグラウンドで 1 枚ずつ順番に高画質化（超解像）し、進捗通知を出す「バッチ高画質化」機能を追加する。

## 既存コードの前提（変更しないもの）

以下のクラスはすでに存在し動作しています。呼び出すだけで使えます。

- `com.nexus.vision.pipeline.RouteCProcessor` — `initialize(): Boolean`、`suspend process(bitmap: Bitmap): ProcessResult`、`release()`。`ProcessResult` は `bitmap`, `method`, `elapsedMs`, `success` を持つ。
- `com.nexus.vision.image.RegionDecoder` — `getImageSize(context, uri): Pair<Int,Int>?`、`decodeSafe(context, uri, maxSide): Bitmap?`
- `com.nexus.vision.engine.ThermalMonitor` — `thermalLevel: StateFlow<ThermalLevel>`。`NexusApplication.getInstance().thermalMonitor` でアクセス可能。
- `com.nexus.vision.engine.ThermalLevel` — enum。`NONE`, `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`, `EMERGENCY`, `SHUTDOWN`。`code` プロパティで数値比較可能。
- `com.nexus.vision.NexusApplication` — `getInstance()` でシングルトン取得。`thermalMonitor`, `boxStore`, `l1Cache`, `l2Cache` を持つ。
- `com.nexus.vision.ncnn.RealEsrganBridge` — `nativeJpegBeginWrite(fd, w, h, quality): Long`、`nativeJpegWriteRows(ctx, bitmap, startRow, numRows): Int`、`nativeJpegEndWrite(ctx): Int`
- `com.nexus.vision.ui.components.ChatMessage` — `data class ChatMessage(id, role, text, imagePath?, timestamp, isProcessing, processingLabel)`。`Role` は `USER`, `ASSISTANT`, `SYSTEM`。

## 作成するファイル（5 ファイル + 既存 3 ファイル修正）

---

### ファイル 1: `app/src/main/java/com/nexus/vision/worker/BatchEnhanceQueue.kt`

**新規作成**

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/worker/BatchEnhanceQueue.kt

package com.nexus.vision.worker

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * バッチ高画質化のキュー管理
 *
 * 複数画像の URI をキューに登録し、1 枚ずつ処理する。
 * StateFlow で進捗を UI に公開する。
 */
object BatchEnhanceQueue {

    enum class ItemStatus {
        QUEUED,
        PROCESSING,
        DONE,
        FAILED,
        PAUSED
    }

    data class BatchItem(
        val uri: Uri,
        val displayName: String = "",
        var status: ItemStatus = ItemStatus.QUEUED,
        var resultUri: Uri? = null,
        var processingTimeMs: Long = 0L,
        var errorMessage: String? = null
    )

    data class BatchProgress(
        val total: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        val currentIndex: Int = -1,
        val currentFileName: String = "",
        val isRunning: Boolean = false,
        val isPaused: Boolean = false,
        val pauseReason: String = "",
        val lastItemTimeMs: Long = 0L,
        val estimatedRemainingMs: Long = -1L
    ) {
        val successCount: Int get() = completed - failed
    }

    private val _items = mutableListOf<BatchItem>()
    private val _progress = MutableStateFlow(BatchProgress())
    val progress: StateFlow<BatchProgress> = _progress.asStateFlow()

    val items: List<BatchItem> get() = _items.toList()

    /**
     * キューに画像を追加する
     */
    @Synchronized
    fun enqueue(uris: List<Uri>, displayNames: List<String> = emptyList()) {
        _items.clear()
        uris.forEachIndexed { index, uri ->
            val name = displayNames.getOrElse(index) { "image_${index + 1}.jpg" }
            _items.add(BatchItem(uri = uri, displayName = name))
        }
        _progress.value = BatchProgress(
            total = _items.size,
            completed = 0,
            failed = 0,
            currentIndex = -1,
            isRunning = true
        )
    }

    /**
     * 次の未処理アイテムを取得して PROCESSING にする
     */
    @Synchronized
    fun dequeueNext(): BatchItem? {
        val next = _items.firstOrNull { it.status == ItemStatus.QUEUED }
        if (next != null) {
            next.status = ItemStatus.PROCESSING
            val index = _items.indexOf(next)
            _progress.value = _progress.value.copy(
                currentIndex = index,
                currentFileName = next.displayName,
                isPaused = false,
                pauseReason = ""
            )
        }
        return next
    }

    /**
     * 処理完了をマーク
     */
    @Synchronized
    fun markDone(item: BatchItem, resultUri: Uri?, timeMs: Long) {
        item.status = ItemStatus.DONE
        item.resultUri = resultUri
        item.processingTimeMs = timeMs

        val completed = _items.count { it.status == ItemStatus.DONE || it.status == ItemStatus.FAILED }
        val failed = _items.count { it.status == ItemStatus.FAILED }
        val remaining = _items.count { it.status == ItemStatus.QUEUED }

        val estimatedMs = if (timeMs > 0 && remaining > 0) {
            timeMs * remaining
        } else {
            -1L
        }

        _progress.value = _progress.value.copy(
            completed = completed,
            failed = failed,
            lastItemTimeMs = timeMs,
            estimatedRemainingMs = estimatedMs,
            isRunning = remaining > 0
        )
    }

    /**
     * 処理失敗をマーク
     */
    @Synchronized
    fun markFailed(item: BatchItem, error: String, timeMs: Long) {
        item.status = ItemStatus.FAILED
        item.errorMessage = error
        item.processingTimeMs = timeMs

        val completed = _items.count { it.status == ItemStatus.DONE || it.status == ItemStatus.FAILED }
        val failed = _items.count { it.status == ItemStatus.FAILED }
        val remaining = _items.count { it.status == ItemStatus.QUEUED }

        _progress.value = _progress.value.copy(
            completed = completed,
            failed = failed,
            lastItemTimeMs = timeMs,
            isRunning = remaining > 0
        )
    }

    /**
     * 発熱で一時停止
     */
    @Synchronized
    fun markPaused(reason: String) {
        val current = _items.firstOrNull { it.status == ItemStatus.PROCESSING }
        current?.status = ItemStatus.QUEUED  // 処理中のものをキューに戻す
        _progress.value = _progress.value.copy(
            isPaused = true,
            pauseReason = reason
        )
    }

    /**
     * 一時停止から再開
     */
    @Synchronized
    fun resume() {
        _progress.value = _progress.value.copy(
            isPaused = false,
            pauseReason = ""
        )
    }

    /**
     * 強制停止（EMERGENCY 用）
     */
    @Synchronized
    fun abort(reason: String) {
        _items.filter { it.status == ItemStatus.QUEUED || it.status == ItemStatus.PROCESSING }
            .forEach { it.status = ItemStatus.FAILED; it.errorMessage = reason }

        val completed = _items.count { it.status == ItemStatus.DONE || it.status == ItemStatus.FAILED }
        val failed = _items.count { it.status == ItemStatus.FAILED }

        _progress.value = _progress.value.copy(
            completed = completed,
            failed = failed,
            isRunning = false,
            isPaused = false,
            pauseReason = reason
        )
    }

    /**
     * キューをクリア
     */
    @Synchronized
    fun clear() {
        _items.clear()
        _progress.value = BatchProgress()
    }

    /**
     * 全完了かどうか
     */
    fun isAllDone(): Boolean {
        return _items.isNotEmpty() && _items.none {
            it.status == ItemStatus.QUEUED || it.status == ItemStatus.PROCESSING
        }
    }
}
```

---

### ファイル 2: `app/src/main/java/com/nexus/vision/notification/BatchNotificationHelper.kt`

**新規作成**

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/notification/BatchNotificationHelper.kt

package com.nexus.vision.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nexus.vision.R

/**
 * バッチ高画質化の通知管理
 */
object BatchNotificationHelper {

    const val CHANNEL_ID_PROGRESS = "nexus_batch_progress"
    const val CHANNEL_ID_RESULT = "nexus_batch_result"
    const val NOTIFICATION_ID_PROGRESS = 10001
    private var nextResultId = 20001

    /**
     * 通知チャンネルを作成する。Application.onCreate() または Worker 開始時に呼ぶ。
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progressChannel = NotificationChannel(
            CHANNEL_ID_PROGRESS,
            "バッチ処理の進捗",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "高画質化バッチ処理の進行状況を表示します"
            setShowBadge(false)
        }

        val resultChannel = NotificationChannel(
            CHANNEL_ID_RESULT,
            "バッチ処理の結果",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "高画質化バッチ処理の完了・エラーを通知します"
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(resultChannel)
    }

    /**
     * フォアグラウンドサービス用の進捗通知（ongoing）
     */
    fun createProgressNotification(
        context: Context,
        current: Int,
        total: Int,
        fileName: String,
        estimatedRemainingMs: Long = -1L
    ): Notification {
        val remainingText = if (estimatedRemainingMs > 0) {
            val minutes = estimatedRemainingMs / 60_000
            val seconds = (estimatedRemainingMs % 60_000) / 1_000
            " 残り約 ${minutes}分${seconds}秒"
        } else {
            ""
        }

        return NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("バッチ高画質化 $current/$total")
            .setContentText("${fileName} を処理中...$remainingText")
            .setProgress(total, current, false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * 1 枚完了通知
     */
    fun notifyItemComplete(
        context: Context,
        current: Int,
        total: Int,
        fileName: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RESULT)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("✅ 保存完了 ($current/$total)")
            .setContentText("$fileName を Pictures/NexusVision/ に保存しました")
            .setAutoCancel(true)
            .build()
        manager.notify(nextResultId++, notification)
    }

    /**
     * 全完了通知
     */
    fun notifyBatchComplete(
        context: Context,
        total: Int,
        successCount: Int,
        failCount: Int
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 進捗通知を消す
        manager.cancel(NOTIFICATION_ID_PROGRESS)

        val title = if (failCount == 0) {
            "✅ バッチ高画質化完了"
        } else {
            "⚠️ バッチ高画質化完了（一部失敗）"
        }
        val text = if (failCount == 0) {
            "$total 枚すべて保存しました"
        } else {
            "成功: $successCount 枚、失敗: $failCount 枚"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RESULT)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(nextResultId++, notification)
    }

    /**
     * 発熱一時停止通知
     */
    fun notifyThermalPause(context: Context, level: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⏸ 発熱のためバッチ処理を一時停止中")
            .setContentText("熱レベル: $level — 温度が下がると自動で再開します")
            .setOngoing(true)
            .setSilent(true)
            .build()
        manager.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    /**
     * 発熱中断通知
     */
    fun notifyThermalAbort(context: Context, completed: Int, total: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_PROGRESS)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RESULT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🛑 発熱によりバッチ処理を中断しました")
            .setContentText("$completed/$total 枚まで保存済み")
            .setAutoCancel(true)
            .build()
        manager.notify(nextResultId++, notification)
    }
}
```

---

### ファイル 3: `app/src/main/java/com/nexus/vision/worker/BatchEnhanceWorker.kt`

**新規作成**

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/worker/BatchEnhanceWorker.kt

package com.nexus.vision.worker

import android.content.ContentValues
import android.content.Context
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
        private const val MAX_DECODE_SIDE = 4096
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

        // RouteCProcessor を初期化
        routeC = RouteCProcessor(applicationContext)
        val initOk = routeC?.initialize() == true
        if (!initOk) {
            Log.e(TAG, "RouteCProcessor init failed")
            BatchEnhanceQueue.abort("超解像エンジンの初期化に失敗しました")
            return Result.failure()
        }

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
            val notification = BatchNotificationHelper.createProgressNotification(
                applicationContext,
                progress.completed + 1,
                progress.total,
                item.displayName,
                progress.estimatedRemainingMs
            )
            setForeground(ForegroundInfo(BatchNotificationHelper.NOTIFICATION_ID_PROGRESS, notification))

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
     * 発熱レベルに応じて待機またはアボート。
     * @return true なら続行可能、false なら中断（EMERGENCY）
     */
    private suspend fun handleThermal(): Boolean {
        while (true) {
            val level = thermalMonitor.thermalLevel.value

            when {
                level.code >= ThermalLevel.EMERGENCY.code -> {
                    // 即時中断
                    Log.w(TAG, "EMERGENCY thermal — aborting batch")
                    val progress = BatchEnhanceQueue.progress.value
                    BatchEnhanceQueue.abort("発熱により中断 (${level.name})")
                    BatchNotificationHelper.notifyThermalAbort(
                        applicationContext, progress.completed, progress.total
                    )
                    return false
                }
                level.code >= ThermalLevel.CRITICAL.code -> {
                    // 15 秒待機
                    Log.w(TAG, "CRITICAL thermal — pausing 15s")
                    BatchEnhanceQueue.markPaused("発熱: ${level.name}")
                    BatchNotificationHelper.notifyThermalPause(applicationContext, level.name)
                    delay(THERMAL_CHECK_CRITICAL_MS)
                }
                level.code >= ThermalLevel.SEVERE.code -> {
                    // 5 秒待機
                    Log.w(TAG, "SEVERE thermal — pausing 5s")
                    BatchEnhanceQueue.markPaused("発熱: ${level.name}")
                    BatchNotificationHelper.notifyThermalPause(applicationContext, level.name)
                    delay(THERMAL_CHECK_SEVERE_MS)
                }
                else -> {
                    // NONE / LIGHT / MODERATE → 続行
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

        // デコード
        val decoded = RegionDecoder.decodeSafe(context, uri, MAX_DECODE_SIDE)
            ?: return null

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
        } else {
            // 失敗時はリサイクルして null 返却
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
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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

### ファイル 4: `app/src/main/java/com/nexus/vision/ui/MainViewModel.kt`

**既存ファイルを修正**。以下の変更を加えてください。**既存のコードはすべて保持**し、追加分だけ記述します。

**import 追加:**

```kotlin
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nexus.vision.worker.BatchEnhanceQueue
import com.nexus.vision.worker.BatchEnhanceWorker
```

**MainUiState に追加:**

```kotlin
data class MainUiState(
    // ... 既存フィールドはすべて保持 ...
    // ▼ 追加
    val isBatchRunning: Boolean = false,
    val batchProgressText: String = ""
)
```

**MainViewModel クラス内に以下のメソッドとプロパティを追加:**

```kotlin
    // ── バッチ高画質化 ──

    private var batchProgressMessageId: String? = null

    init {
        // ... 既存の init ブロックの末尾に追加 ...

        // バッチ進捗監視
        viewModelScope.launch {
            BatchEnhanceQueue.progress.collect { progress ->
                if (!progress.isRunning && progress.total == 0) {
                    // キューが空
                    _uiState.value = _uiState.value.copy(
                        isBatchRunning = false,
                        batchProgressText = ""
                    )
                    return@collect
                }

                val text = buildString {
                    if (progress.isPaused) {
                        append("⏸ 一時停止中 (${progress.pauseReason})")
                    } else if (progress.isRunning) {
                        append("🔄 ${progress.completed + 1}/${progress.total} 処理中...")
                        if (progress.estimatedRemainingMs > 0) {
                            val min = progress.estimatedRemainingMs / 60_000
                            val sec = (progress.estimatedRemainingMs % 60_000) / 1_000
                            append(" 残り約 ${min}分${sec}秒")
                        }
                    } else {
                        append("✅ バッチ完了: ${progress.successCount}/${progress.total} 成功")
                        if (progress.failed > 0) append("、${progress.failed} 失敗")
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isBatchRunning = progress.isRunning || progress.isPaused,
                    batchProgressText = text
                )

                // チャットの進捗メッセージを更新
                val msgId = batchProgressMessageId
                if (msgId != null) {
                    replaceMessage(
                        msgId,
                        ChatMessage(id = msgId, role = ChatMessage.Role.ASSISTANT, text = text)
                    )
                }
            }
        }
    }

    /**
     * バッチ高画質化を開始する
     */
    fun startBatchEnhance(uris: List<Uri>) {
        if (uris.isEmpty()) return

        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = "バッチ高画質化: ${uris.size} 枚"
            )
        )

        val processingId = addProcessingMessage("🔄 バッチ高画質化を開始します (${uris.size} 枚)...")
        batchProgressMessageId = processingId

        // ファイル名を取得
        val displayNames = uris.map { uri ->
            try {
                val cursor = app.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else "image.jpg"
                } ?: "image.jpg"
            } catch (e: Exception) {
                "image.jpg"
            }
        }

        BatchEnhanceQueue.enqueue(uris, displayNames)

        val workRequest = OneTimeWorkRequestBuilder<BatchEnhanceWorker>()
            .build()

        WorkManager.getInstance(app.applicationContext)
            .enqueueUniqueWork(
                BatchEnhanceWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Log.i(TAG, "Batch enhance started: ${uris.size} images")
    }
```

**sendMessage() メソッドの冒頭に追加（`val text = ...` の後、`if (text.isBlank() && imageUri == null) return` の前）:**

```kotlin
        // バッチ高画質化のテキストコマンド判定
        val isBatchRequest = text.contains("バッチ", ignoreCase = true) &&
                (text.contains("高画質", ignoreCase = true) || text.contains("enhance", ignoreCase = true))

        if (isBatchRequest && imageUri == null) {
            addMessage(ChatMessage(role = ChatMessage.Role.USER, text = text))
            _uiState.value = _uiState.value.copy(inputText = "")
            addMessage(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    text = "複数画像を選択してください。選択後にバッチ高画質化を開始します。"
                )
            )
            // UI 側で複数画像ピッカーを起動するフラグを立てる
            _uiState.value = _uiState.value.copy(requestBatchPicker = true)
            return
        }
```

**MainUiState にさらに追加:**

```kotlin
data class MainUiState(
    // ... 上で追加した isBatchRunning, batchProgressText に加えて ...
    val requestBatchPicker: Boolean = false
)
```

**MainViewModel にピッカー消費メソッド追加:**

```kotlin
    fun consumeBatchPickerRequest() {
        _uiState.value = _uiState.value.copy(requestBatchPicker = false)
    }
```

---

### ファイル 5: `app/src/main/java/com/nexus/vision/ui/MainScreen.kt`

**既存ファイルを修正**。以下の変更を加えてください。

**MainScreen 関数のパラメータに追加:**

```kotlin
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onPickImage: () -> Unit = {},
    onPickMultipleImages: () -> Unit = {},   // ← 追加
    onImageSelected: ((android.net.Uri) -> Unit) -> Unit = {}
)
```

**MainScreen 内の LaunchedEffect ブロックの後に追加:**

```kotlin
    // バッチピッカーのリクエスト監視
    LaunchedEffect(uiState.requestBatchPicker) {
        if (uiState.requestBatchPicker) {
            viewModel.consumeBatchPickerRequest()
            onPickMultipleImages()
        }
    }
```

**Scaffold の bottomBar 内、CropSelector else 分岐内（ChatInput の下）に追加:**

```kotlin
                    // バッチ進捗表示
                    if (uiState.isBatchRunning) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = uiState.batchProgressText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
```

---

### ファイル 6: `app/src/main/java/com/nexus/vision/MainActivity.kt`

**既存ファイルを修正**。以下の変更を加えてください。

**import 追加:**

```kotlin
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
```

**pickMedia の下に追加:**

```kotlin
    // バッチ用: 複数画像選択ランチャー
    private var onMultipleImagesSelected: ((List<android.net.Uri>) -> Unit)? = null

    private val pickMultipleMedia =
        registerForActivityResult(PickMultipleVisualMedia(20)) { uris ->
            if (uris.isNotEmpty()) {
                Log.d(TAG, "Multiple images selected: ${uris.size}")
                onMultipleImagesSelected?.invoke(uris)
            }
        }
```

**setContent 内の MainScreen 呼び出しに追加:**

```kotlin
                MainScreen(
                    onPickImage = { launchImagePicker() },
                    onPickMultipleImages = { launchMultipleImagePicker() },   // ← 追加
                    onImageSelected = { callback ->
                        onImageSelected = callback
                    }
                )
```

**ただし MainScreen の onImageSelected コールバック付近で、複数画像用のコールバックも設定する必要がある。以下の LaunchedEffect を MainScreen 内にすでにあるものの隣に追加するか、MainActivity 側で設定する。最もシンプルな方法として、MainActivity に以下を追加:**

```kotlin
    private fun launchMultipleImagePicker() {
        onMultipleImagesSelected = { uris ->
            // MainViewModel を取得してバッチ開始
            // Activity から ViewModel にアクセスするには viewModels() を使う
        }
        pickMultipleMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
```

**しかし MainViewModel は MainScreen 内部で viewModel() で生成されているため、Activity から直接アクセスできません。そこで、MainScreen のパラメータ経由でコールバックチェーンにします。**

**修正方針: MainScreen にコールバックを追加する代わりに、MainScreen 内で直接PickMultipleVisualMedia を使うようにします。**

**最終的な MainActivity の完全な修正版:**

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/MainActivity.kt

package com.nexus.vision

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.nexus.vision.ui.MainScreen
import com.nexus.vision.ui.theme.NexusVisionTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var onImageSelected: ((Uri) -> Unit)? = null
    private var onMultipleImagesSelected: ((List<Uri>) -> Unit)? = null

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                Log.d(TAG, "Image selected: $uri")
                onImageSelected?.invoke(uri)
            }
        }

    private val pickMultipleMedia =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
            if (uris.isNotEmpty()) {
                Log.d(TAG, "Multiple images selected: ${uris.size}")
                onMultipleImagesSelected?.invoke(uris)
            }
        }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            for ((permission, granted) in grants) {
                Log.d(TAG, "Permission $permission: $granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRequiredPermissions()

        setContent {
            NexusVisionTheme {
                MainScreen(
                    onPickImage = { launchImagePicker() },
                    onPickMultipleImages = { launchMultipleImagePicker() },
                    onImageSelected = { callback ->
                        onImageSelected = callback
                    },
                    onMultipleImagesSelected = { callback ->
                        onMultipleImagesSelected = callback
                    }
                )
            }
        }
    }

    private fun launchImagePicker() {
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchMultipleImagePicker() {
        pickMultipleMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions.launch(permissions.toTypedArray())
        }
    }
}
```

---

### ファイル 7: `app/src/main/java/com/nexus/vision/ui/MainScreen.kt`

**完全な修正版:**

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ui/MainScreen.kt

package com.nexus.vision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.vision.ui.components.ChatBubble
import com.nexus.vision.ui.components.ChatInput
import com.nexus.vision.ui.components.CropSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onPickImage: () -> Unit = {},
    onPickMultipleImages: () -> Unit = {},
    onImageSelected: ((android.net.Uri) -> Unit) -> Unit = {},
    onMultipleImagesSelected: ((List<android.net.Uri>) -> Unit) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        onImageSelected { uri ->
            viewModel.setSelectedImage(uri)
        }
        onMultipleImagesSelected { uris ->
            viewModel.startBatchEnhance(uris)
        }
    }

    // バッチピッカーのリクエスト監視
    LaunchedEffect(uiState.requestBatchPicker) {
        if (uiState.requestBatchPicker) {
            viewModel.consumeBatchPickerRequest()
            onPickMultipleImages()
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NEXUS Vision",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        ThermalBadge(levelName = uiState.thermalLevelName)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                HorizontalDivider()

                // バッチ進捗表示
                if (uiState.isBatchRunning && uiState.batchProgressText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = uiState.batchProgressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                // 範囲選択モード中はクロップUIを表示
                if (uiState.cropMode && uiState.cropThumbnail != null) {
                    val (confirmLabel, headerLabel) = when (uiState.cropPurpose) {
                        CropPurpose.ENHANCE -> "この範囲を高画質化" to "高画質化したい範囲をドラッグで選択"
                        CropPurpose.ZOOM -> "この範囲を超解像" to "拡大したい範囲をドラッグで選択"
                    }

                    CropSelector(
                        thumbnail = uiState.cropThumbnail!!,
                        imageWidth = uiState.cropImageWidth,
                        imageHeight = uiState.cropImageHeight,
                        headerLabel = headerLabel,
                        confirmLabel = confirmLabel,
                        onConfirm = { left, top, right, bottom ->
                            viewModel.onCropConfirmed(left, top, right, bottom)
                        },
                        onCancel = { viewModel.cancelCropMode() }
                    )
                } else {
                    ChatInput(
                        text = uiState.inputText,
                        onTextChange = { viewModel.updateInputText(it) },
                        selectedImageUri = uiState.selectedImageUri,
                        onPickImage = onPickImage,
                        onClearImage = { viewModel.clearSelectedImage() },
                        onSend = { viewModel.sendMessage() },
                        isEnabled = !uiState.isProcessing
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!uiState.isEngineReady && !uiState.isProcessing) {
                EngineLoadBanner(
                    statusMessage = uiState.statusMessage,
                    onLoadClick = { viewModel.loadEngine() }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    ChatBubble(message = message)
                }
            }
        }
    }
}

@Composable
private fun EngineLoadBanner(
    statusMessage: String,
    onLoadClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Button(
                onClick = onLoadClick,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("エンジンをロード")
            }
        }
    }
}

@Composable
fun ThermalBadge(levelName: String) {
    val color = when (levelName) {
        "NONE", "LIGHT" -> MaterialTheme.colorScheme.outline
        "MODERATE" -> MaterialTheme.colorScheme.tertiary
        "SEVERE" -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.error
    }

    if (levelName != "NONE") {
        Text(
            text = levelName,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
```

---

### ファイル 8: `app/src/main/java/com/nexus/vision/ui/MainViewModel.kt`

**完全な修正版（既存コードに追加分を統合したもの）:**

既存の MainViewModel.kt は非常に長い（28KB）ため、以下の **差分適用ルール** に従ってください:

**1. import に以下を追加:**
```kotlin
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nexus.vision.worker.BatchEnhanceQueue
import com.nexus.vision.worker.BatchEnhanceWorker
```

**2. MainUiState に 3 フィールド追加:**
```kotlin
data class MainUiState(
    // ... 既存フィールドすべて保持 ...
    val isBatchRunning: Boolean = false,
    val batchProgressText: String = "",
    val requestBatchPicker: Boolean = false
)
```

**3. MainViewModel の init ブロック末尾（`initSuperResolution()` の後）に追加:**
```kotlin
        // バッチ進捗監視
        viewModelScope.launch {
            BatchEnhanceQueue.progress.collect { progress ->
                if (!progress.isRunning && progress.total == 0) {
                    _uiState.value = _uiState.value.copy(
                        isBatchRunning = false,
                        batchProgressText = ""
                    )
                    return@collect
                }

                val text = buildString {
                    if (progress.isPaused) {
                        append("⏸ 一時停止中 (${progress.pauseReason})")
                    } else if (progress.isRunning) {
                        append("🔄 ${progress.completed + 1}/${progress.total} 処理中...")
                        if (progress.estimatedRemainingMs > 0) {
                            val min = progress.estimatedRemainingMs / 60_000
                            val sec = (progress.estimatedRemainingMs % 60_000) / 1_000
                            append(" 残り約 ${min}分${sec}秒")
                        }
                    } else {
                        append("✅ バッチ完了: ${progress.successCount}/${progress.total} 成功")
                        if (progress.failed > 0) append("、${progress.failed} 失敗")
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isBatchRunning = progress.isRunning || progress.isPaused,
                    batchProgressText = text
                )

                val msgId = batchProgressMessageId
                if (msgId != null) {
                    replaceMessage(
                        msgId,
                        ChatMessage(id = msgId, role = ChatMessage.Role.ASSISTANT, text = text)
                    )
                }
            }
        }
```

**4. MainViewModel のプロパティ宣言エリア（`private val ocrEngine` 付近）に追加:**
```kotlin
    private var batchProgressMessageId: String? = null
```

**5. sendMessage() の `val text = _uiState.value.inputText.trim()` の後、`if (text.isBlank() && imageUri == null) return` の前に追加:**
```kotlin
        // バッチ高画質化コマンド判定
        val isBatchRequest = text.contains("バッチ", ignoreCase = true) &&
                (text.contains("高画質", ignoreCase = true) || text.contains("enhance", ignoreCase = true))

        if (isBatchRequest && imageUri == null) {
            addMessage(ChatMessage(role = ChatMessage.Role.USER, text = text))
            _uiState.value = _uiState.value.copy(
                inputText = "",
                requestBatchPicker = true
            )
            addMessage(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    text = "複数画像を選択してください。選択後にバッチ高画質化を開始します。"
                )
            )
            return
        }
```

**6. MainViewModel に以下のメソッドを追加:**
```kotlin
    /**
     * バッチ高画質化を開始する
     */
    fun startBatchEnhance(uris: List<Uri>) {
        if (uris.isEmpty()) return

        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = "バッチ高画質化: ${uris.size} 枚"
            )
        )

        val processingId = addProcessingMessage("🔄 バッチ高画質化を開始します (${uris.size} 枚)...")
        batchProgressMessageId = processingId

        val displayNames = uris.map { uri ->
            try {
                val cursor = app.contentResolver.query(
                    uri,
                    arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME),
                    null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else "image.jpg"
                } ?: "image.jpg"
            } catch (e: Exception) {
                "image.jpg"
            }
        }

        BatchEnhanceQueue.enqueue(uris, displayNames)

        val workRequest = OneTimeWorkRequestBuilder<BatchEnhanceWorker>().build()
        WorkManager.getInstance(app.applicationContext)
            .enqueueUniqueWork(
                BatchEnhanceWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Log.i(TAG, "Batch enhance started: ${uris.size} images")
    }

    fun consumeBatchPickerRequest() {
        _uiState.value = _uiState.value.copy(requestBatchPicker = false)
    }
```

---

## ビルド確認手順

1. 上記の新規 3 ファイルを作成
2. 既存 3 ファイル（MainActivity, MainScreen, MainViewModel）を修正
3. Android Studio で Build → Rebuild Project
4. エラーがなければ実機でテスト:
   - チャットに「バッチ高画質化」と入力 → 複数画像ピッカーが開く → 画像を選択 → バックグラウンド処理が開始
   - 進捗がチャットに表示される
   - 通知が出る
   - アプリをバックグラウンドに移動しても処理が継続する

## 注意事項

- `BatchEnhanceWorker` は内部で `RouteCProcessor` を独自に初期化・解放します。MainViewModel の `routeC` とは別インスタンスです。これは Worker がプロセスをまたぐ可能性があるためです。
- `BatchEnhanceQueue` は `object`（シングルトン）で、Worker と ViewModel の両方からアクセスします。
- 通知アイコンは仮で `android.R.drawable.ic_menu_gallery` を使用しています。後で専用アイコンに差し替えてください。
- `PickMultipleVisualMedia(20)` で最大 20 枚まで選択可能です。