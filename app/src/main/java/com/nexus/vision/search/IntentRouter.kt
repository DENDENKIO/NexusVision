package com.nexus.vision.search

import com.nexus.vision.retail.search.FuzzyNormalizer

/**
 * チャット入力をDB検索 / Gemma / 曖昧の3モードに振り分ける
 */
object IntentRouter {

    enum class Intent { DB_SEARCH, GEMMA_CHAT, AMBIGUOUS }

    data class ParsedInput(
        val intent:       Intent,
        val searchQuery:  String,         // 検索クエリ（正規化済み）
        val sourceFilter: Set<String>     // 指定ソースID（空=全DB）
    )

    // 明示的なDB検索プレフィックス
    private val DB_PREFIXES = listOf("/db ", "/検索 ", "/search ", "検索：", "検索:")

    // DB検索と強く関連するキーワード
    private val DB_STRONG_KEYWORDS = listOf(
        "jan", "janコード", "商品", "納品", "在庫", "メーカー", "規格", "数量",
        "いくつ", "何個", "どのくらい", "入荷", "発注", "部門", "仕入"
    )

    // Gemmaに渡すべきキーワード（こちらが優先）
    private val GEMMA_KEYWORDS = listOf(
        "教えて", "説明", "なぜ", "どうして", "どうやって", "方法",
        "おすすめ", "意味", "とは", "ください"
    )

    fun parse(input: String): ParsedInput {
        val trimmed = input.trim()
        val lower   = FuzzyNormalizer.normalize(trimmed)

        // 1. 明示プレフィックス → DB確定
        DB_PREFIXES.forEach { prefix ->
            if (lower.startsWith(prefix.trim().lowercase())) {
                val query = trimmed.substring(prefix.length).trim()
                val (sourceIds, cleanQuery) = extractSourceFilter(query)
                return ParsedInput(Intent.DB_SEARCH, cleanQuery, sourceIds)
            }
        }

        // 2. Gemmaキーワードが含まれる → Gemma確定
        if (GEMMA_KEYWORDS.any { lower.contains(it) }) {
            return ParsedInput(Intent.GEMMA_CHAT, trimmed, emptySet())
        }

        // 3. DBキーワードが含まれる → 曖昧（DB検索優先、0件ならGemma）
        if (DB_STRONG_KEYWORDS.any { lower.contains(it) }) {
            return ParsedInput(Intent.AMBIGUOUS, trimmed, emptySet())
        }

        // 4. その他 → Gemma
        return ParsedInput(Intent.GEMMA_CHAT, trimmed, emptySet())
    }

    /**
     * クエリ文字列からソース指定を抽出
     * 例: "納品DB コーラ" → ({"delivery"}, "コーラ")
     */
    private fun extractSourceFilter(query: String): Pair<Set<String>, String> {
        val sourceMap = mapOf(
            "納品"  to "delivery",
            "商品"  to "product",
            // 将来追加: "在庫" to "stock"
        )
        var cleanQuery = query
        val foundIds = mutableSetOf<String>()

        sourceMap.forEach { (keyword, id) ->
            if (cleanQuery.contains(keyword + "DB") || cleanQuery.contains(keyword + "db")) {
                foundIds.add(id)
                cleanQuery = cleanQuery
                    .replace(keyword + "DB", "")
                    .replace(keyword + "db", "")
                    .trim()
            }
        }
        return Pair(foundIds, cleanQuery)
    }
}
