// ファイルパス: app/src/main/java/com/nexus/vision/memory/LongTermMemory.kt

package com.nexus.vision.memory

import android.util.Log
import io.objectbox.BoxStore
import io.objectbox.query.Query
import io.objectbox.query.QueryBuilder
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.kotlin.boxFor
import kotlin.math.sqrt

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  ObjectBox エンティティ (コンパイルエラー回避のためクラス定義を先に配置)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Entity
data class MemoryEntry(
    @Id var id: Long = 0,
    var userQuery: String = "",
    var aiResponse: String = "",
    @Index var category: String = "",
    var importance: Double = 0.5,
    var vectorJson: String = "",
    var createdAt: Long = 0,
    @Index var lastAccessedAt: Long = 0,
    var accessCount: Int = 0
)

@Entity
data class CategoryFrequency(
    @Id var id: Long = 0,
    @Index var category: String = "",
    var frequency: Int = 0, // 'count' から 'frequency' に変更して衝突回避
    var lastUsedAt: Long = 0
)

/**
 * Phase 11: 長期記憶
 */
class LongTermMemory(private val boxStore: BoxStore) {

    companion object {
        private const val TAG = "LongTermMemory"
        private const val MAX_ENTRIES = 2000
        private const val VECTOR_DIM = 256
        private const val MAX_SEARCH_RESULTS = 10
        private const val SIMILARITY_THRESHOLD = 0.15
    }

    private val memoryBox = boxStore.boxFor<MemoryEntry>()
    private val categoryBox = boxStore.boxFor<CategoryFrequency>()

    private var idfTable: Map<String, Double> = emptyMap()
    private var idfDirty = true

    init {
        Log.i(TAG, "LongTermMemory initialized: ${memoryBox.count()} entries, ${categoryBox.count()} categories")
    }

    fun remember(
        userQuery: String,
        aiResponse: String,
        category: String = "chat",
        importance: Double = 0.5
    ) {
        val combinedText = "$userQuery $aiResponse"
        val tokens = tokenize(combinedText)
        val vector = buildTfVector(tokens)

        val entry = MemoryEntry(
            userQuery = userQuery.take(500),
            aiResponse = aiResponse.take(2000),
            category = category,
            importance = importance,
            vectorJson = vector.joinToString(",") { "%.6f".format(it) },
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = 0
        )

        memoryBox.put(entry)
        idfDirty = true
        updateCategoryFrequency(category)
        evictIfNeeded()
        Log.i(TAG, "Remembered: category=$category, tokens=${tokens.size}")
    }

    fun search(
        query: String,
        maxResults: Int = MAX_SEARCH_RESULTS,
        category: String? = null
    ): List<SearchResult> {
        rebuildIdfIfNeeded()

        val queryTokens = tokenize(query)
        val queryVector = buildTfIdfVector(queryTokens)

        val queryBuilder = if (category != null) {
            memoryBox.query(MemoryEntry_.category.equal(category))
        } else {
            memoryBox.query()
        }
        
        val q = queryBuilder.build()
        val allEntries = q.find()
        q.close()

        if (allEntries.isEmpty()) return emptyList()

        val results = allEntries.mapNotNull { entry ->
            val entryVector = parseVector(entry.vectorJson)
            if (entryVector.isEmpty()) return@mapNotNull null

            val similarity = cosineSimilarity(queryVector, entryVector)
            if (similarity < SIMILARITY_THRESHOLD) return@mapNotNull null

            val timeFactor = timeDecayFactor(entry.lastAccessedAt)
            val accessBoost = 1.0 + kotlin.math.ln((entry.accessCount + 1).toDouble())
            val score = similarity * accessBoost * entry.importance * timeFactor

            SearchResult(entry, similarity, score)
        }
            .sortedByDescending { it.score }
            .take(maxResults)

        results.forEach { result ->
            result.entry.lastAccessedAt = System.currentTimeMillis()
            result.entry.accessCount++
            memoryBox.put(result.entry)
        }

        return results
    }

    fun getRecentContext(limit: Int = 5): List<MemoryEntry> {
        val q = memoryBox.query()
            .orderDesc(MemoryEntry_.createdAt)
            .build()
        val results = q.find(0, limit.toLong())
        q.close()
        return results
    }

    fun getTopCategories(limit: Int = 5): List<CategoryFrequency> {
        val q = categoryBox.query()
            .orderDesc(CategoryFrequency_.frequency)
            .build()
        val results = q.find(0, limit.toLong())
        q.close()
        return results
    }

    fun getUserProfileSummary(): String {
        val topCats = getTopCategories(5)
        val totalMemories = memoryBox.count()
        if (totalMemories == 0L) return ""

        return buildString {
            appendLine("ユーザーの利用傾向:")
            topCats.forEach { cat ->
                appendLine("- ${cat.category}: ${cat.frequency}回")
            }
            appendLine("合計記録数: $totalMemories")
        }
    }

    fun buildContextForPrompt(query: String, maxChars: Int = 1500): String {
        val results = search(query, maxResults = 3)
        if (results.isEmpty()) return ""

        return buildString {
            appendLine("以下はユーザーとの過去の関連する会話です:")
            for (result in results) {
                val entry = result.entry
                appendLine("---")
                appendLine("Q: ${entry.userQuery.take(200)}")
                appendLine("A: ${entry.aiResponse.take(300)}")
                if (length > maxChars) break
            }
            appendLine("---")
        }.take(maxChars)
    }

    fun entryCount(): Long = memoryBox.count()

    private fun updateCategoryFrequency(category: String) {
        val q = categoryBox.query(CategoryFrequency_.category.equal(category)).build()
        val existing = q.findFirst()
        q.close()

        if (existing != null) {
            existing.frequency++
            existing.lastUsedAt = System.currentTimeMillis()
            categoryBox.put(existing)
        } else {
            categoryBox.put(
                CategoryFrequency(
                    category = category,
                    frequency = 1,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun evictIfNeeded() {
        val count = memoryBox.count()
        if (count <= MAX_ENTRIES) return
        val toRemove = (count - MAX_ENTRIES + MAX_ENTRIES / 10).toInt()
        val q = memoryBox.query()
            .order(MemoryEntry_.lastAccessedAt)
            .build()
        val oldest = q.find(0, toRemove.toLong())
        q.close()
        memoryBox.remove(oldest)
        idfDirty = true
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val normalized = text.lowercase().replace(Regex("[\\s　]+"), " ").trim()
        Regex("[a-z0-9_]{2,}").findAll(normalized).forEach { tokens.add(it.value) }
        val jaText = normalized.replace(Regex("[a-z0-9_\\s\\p{Punct}]+"), "")
        for (i in 0 until jaText.length - 1) {
            tokens.add(jaText.substring(i, i + 2))
        }
        return tokens
    }

    private fun buildTfVector(tokens: List<String>): DoubleArray {
        val vector = DoubleArray(VECTOR_DIM)
        for (token in tokens) {
            val dim = (token.hashCode().and(0x7FFFFFFF)) % VECTOR_DIM
            vector[dim] += 1.0
        }
        val norm = sqrt(vector.sumOf { it * it })
        if (norm > 0) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }

    private fun buildTfIdfVector(tokens: List<String>): DoubleArray {
        val vector = DoubleArray(VECTOR_DIM)
        for (token in tokens) {
            val dim = (token.hashCode().and(0x7FFFFFFF)) % VECTOR_DIM
            val idf = idfTable[token] ?: 1.0
            vector[dim] += idf
        }
        val norm = sqrt(vector.sumOf { it * it })
        if (norm > 0) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }

    private fun rebuildIdfIfNeeded() {
        if (!idfDirty) return
        val allEntries = memoryBox.all
        val n = allEntries.size.toDouble()
        if (n == 0.0) {
            idfTable = emptyMap()
            idfDirty = false
            return
        }
        val docFreq = mutableMapOf<String, Int>()
        allEntries.forEach { entry ->
            val tokens = tokenize("${entry.userQuery} ${entry.aiResponse}").toSet()
            tokens.forEach { token -> docFreq[token] = (docFreq[token] ?: 0) + 1 }
        }
        idfTable = docFreq.mapValues { (_, df) -> kotlin.math.ln(n / (df + 1.0)) + 1.0 }
        idfDirty = false
    }

    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        val minLen = minOf(a.size, b.size)
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until minLen) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) dot / denom else 0.0
    }

    private fun timeDecayFactor(lastAccessedAt: Long): Double {
        val hoursSince = (System.currentTimeMillis() - lastAccessedAt) / 3_600_000.0
        return kotlin.math.exp(-0.693 * hoursSince / 168.0).coerceIn(0.1, 1.0)
    }

    private fun parseVector(json: String): DoubleArray {
        if (json.isBlank()) return DoubleArray(0)
        return try {
            json.split(",").map { it.trim().toDouble() }.toDoubleArray()
        } catch (e: Exception) { DoubleArray(0) }
    }

    data class SearchResult(val entry: MemoryEntry, val similarity: Double, val score: Double)
}
