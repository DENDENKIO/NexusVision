// ファイルパス: app/src/main/java/com/nexus/vision/cache/L2InferenceCache.kt

package com.nexus.vision.cache

import android.util.Log
import io.objectbox.Box
import io.objectbox.BoxStore

/**
 * L2 キャッシュ: テキストクエリ → 推論応答キャッシュ
 *
 * - 最大200エントリ (LRU)
 * - queryHash (String.hashCode) で高速検索、queryText で完全一致確認
 *
 * Phase 3: 基本実装
 * Phase 11: 長期記憶 (HNSW) に拡張
 */
class L2InferenceCache(boxStore: BoxStore) {

    companion object {
        private const val TAG = "L2InferenceCache"
        private const val MAX_ENTRIES = 200
    }

    private val box: Box<InferenceCacheEntry> = boxStore.boxFor(InferenceCacheEntry::class.java)

    /**
     * クエリテキストでキャッシュを検索する。
     *
     * @param queryText 検索するクエリ
     * @return ヒットしたエントリ、またはnull
     */
    fun lookup(queryText: String): InferenceCacheEntry? {
        val queryHash = queryText.hashCode()

        val candidates = box.query(InferenceCacheEntry_.queryHash.equal(queryHash))
            .build().find()

        // hashCode 衝突を考慮して文字列完全一致を確認
        val match = candidates.firstOrNull { it.queryText == queryText }

        if (match != null) {
            match.lastAccessedAt = System.currentTimeMillis()
            match.accessCount++
            box.put(match)
            Log.d(TAG, "Cache HIT: query='${queryText.take(50)}...'")
        } else {
            Log.d(TAG, "Cache MISS: query='${queryText.take(50)}...'")
        }

        return match
    }

    /**
     * キャッシュにエントリを追加する。
     */
    fun put(queryText: String, responseText: String) {
        evictIfNeeded()

        val entry = InferenceCacheEntry(
            queryText = queryText,
            queryHash = queryText.hashCode(),
            responseText = responseText,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = 1
        )

        box.put(entry)
        Log.d(TAG, "Cache PUT: query='${queryText.take(50)}...'")
    }

    /**
     * 全キャッシュをクリアする。
     */
    fun clearAll() {
        val count = box.count()
        box.removeAll()
        Log.i(TAG, "Cleared all $count entries")
    }

    /**
     * 現在のエントリ数を取得する。
     */
    fun count(): Long = box.count()

    /**
     * LRU 方式で古いエントリを削除する。
     */
    private fun evictIfNeeded() {
        val currentCount = box.count()
        if (currentCount < MAX_ENTRIES) return

        val excess = (currentCount - MAX_ENTRIES + 1).toInt()

        val oldEntries = box.query()
            .order(InferenceCacheEntry_.lastAccessedAt)
            .build()
            .find(0, excess.toLong())

        box.remove(oldEntries)
        Log.d(TAG, "Evicted $excess old entries (was $currentCount, max $MAX_ENTRIES)")
    }
}
