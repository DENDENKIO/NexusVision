package com.nexus.vision.search

import com.nexus.vision.retail.search.FuzzyNormalizer
import kotlinx.coroutines.*

/**
 * 検索エンジン本体
 *
 * - 複数ソースを並列検索
 * - スコア順にソート
 * - Gemmaなしで完全動作
 */
object NexusSearchEngine {

    /**
     * 検索実行
     * @param rawQuery  ユーザーの入力そのまま
     * @param sourceIds 検索対象ソースID（空=全ソース）
     * @param limit     ソースあたりの最大件数
     */
    suspend fun search(
        rawQuery:  String,
        sourceIds: Set<String> = emptySet(),
        limit:     Int = 30
    ): SearchEngineResult = coroutineScope {

        val tokens = FuzzyNormalizer.tokenize(rawQuery)
        if (tokens.isEmpty()) return@coroutineScope SearchEngineResult.empty(rawQuery)

        val targetSources = if (sourceIds.isEmpty()) {
            SearchSourceRegistry.getAll()
        } else {
            SearchSourceRegistry.getAll().filter { src ->
                src is IdentifiableSource && src.sourceId in sourceIds
            }
        }

        // 各ソースを並列検索
        val deferreds = targetSources.map { source ->
            async(Dispatchers.IO) {
                try {
                    if (source.isAvailable())
                        source.search(tokens, rawQuery, limit)
                    else emptyList()
                } catch (e: Exception) {
                    emptyList<SearchResult>()
                }
            }
        }

        val allResults = deferreds.awaitAll().flatten()
            .sortedByDescending { it.score }

        SearchEngineResult(
            query      = rawQuery,
            results    = allResults,
            totalCount = allResults.size,
            sources    = targetSources.map { it.displayName }
        )
    }
}

data class SearchEngineResult(
    val query:      String,
    val results:    List<SearchResult>,
    val totalCount: Int,
    val sources:    List<String>
) {
    companion object {
        fun empty(query: String) = SearchEngineResult(query, emptyList(), 0, emptyList())
    }
    val isEmpty get() = results.isEmpty()
    val summary get() = "${sources.joinToString("・")} から ${totalCount}件ヒット"
}

/** 共通スコア計算（先頭一致 > 部分一致） */
fun scoreOf(text: String, tokens: List<String>): Float {
    val n = FuzzyNormalizer.normalize(text)
    return tokens.sumOf { t ->
        when {
            n.startsWith(t) -> 3.0
            n.contains(t)   -> 1.0
            else            -> 0.0
        }
    }.toFloat()
}
