// ファイルパス: app/src/main/java/com/nexus/vision/cache/L1PHashCache.kt

package com.nexus.vision.cache

import android.util.Log
import com.nexus.vision.deor.PHashCalculator
import io.objectbox.Box
import io.objectbox.BoxStore

/**
 * L1 キャッシュ: pHash ベースの画像推論キャッシュ
 *
 * - 最大500エントリ (LRU 方式で古いものから削除)
 * - ハミング距離 ≤ 8 でキャッシュヒット
 * - ヒット時は推論をスキップ → 高速応答
 *
 * Phase 3: 基本実装
 */
class L1PHashCache(boxStore: BoxStore) {

    companion object {
        private const val TAG = "L1PHashCache"
        private const val MAX_ENTRIES = 500
        private const val HAMMING_THRESHOLD = 8
    }

    private val box: Box<PHashCacheEntry> = boxStore.boxFor(PHashCacheEntry::class.java)

    /**
     * キャッシュを検索する。
     *
     * @param pHash    検索するハッシュ値
     * @param category カテゴリフィルタ（空文字なら全カテゴリ）
     * @return ヒットしたエントリ、またはnull
     */
    fun lookup(pHash: Long, category: String = ""): PHashCacheEntry? {
        // カテゴリでフィルタしてから全エントリを走査
        val candidates = if (category.isNotEmpty()) {
            box.query(PHashCacheEntry_.category.equal(category))
                .build().find()
        } else {
            box.all
        }

        var bestMatch: PHashCacheEntry? = null
        var bestDistance = Int.MAX_VALUE

        for (entry in candidates) {
            val distance = PHashCalculator.hammingDistance(pHash, entry.pHash)
            if (distance <= HAMMING_THRESHOLD && distance < bestDistance) {
                bestDistance = distance
                bestMatch = entry
            }
        }

        if (bestMatch != null) {
            // アクセス情報を更新
            bestMatch.lastAccessedAt = System.currentTimeMillis()
            bestMatch.accessCount++
            box.put(bestMatch)
            Log.d(TAG, "Cache HIT: distance=$bestDistance, category=${bestMatch.category}")
        } else {
            Log.d(TAG, "Cache MISS: pHash=${pHash.toULong().toString(16)}, category=$category")
        }

        return bestMatch
    }

    /**
     * キャッシュにエントリを追加する。
     * 最大エントリ数を超えた場合、最も古いエントリを削除。
     */
    fun put(
        pHash: Long,
        resultText: String,
        category: String = "",
        entropy: Double = 0.0,
        fecsScore: Double = 0.0
    ) {
        // エントリ数チェック → 超過時は LRU で削除
        evictIfNeeded()

        val entry = PHashCacheEntry(
            pHash = pHash,
            resultText = resultText,
            category = category,
            entropy = entropy,
            fecsScore = fecsScore,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = 1
        )

        box.put(entry)
        Log.d(TAG, "Cache PUT: pHash=${pHash.toULong().toString(16)}, category=$category")
    }

    /**
     * 特定カテゴリのキャッシュをクリアする。
     */
    fun clearCategory(category: String) {
        val entries = box.query(PHashCacheEntry_.category.equal(category))
            .build().find()
        box.remove(entries)
        Log.i(TAG, "Cleared ${entries.size} entries for category=$category")
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

        // lastAccessedAt が古いものから削除
        val oldEntries = box.query()
            .order(PHashCacheEntry_.lastAccessedAt)
            .build()
            .find(0, excess.toLong())

        box.remove(oldEntries)
        Log.d(TAG, "Evicted $excess old entries (was $currentCount, max $MAX_ENTRIES)")
    }
}
