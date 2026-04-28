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
