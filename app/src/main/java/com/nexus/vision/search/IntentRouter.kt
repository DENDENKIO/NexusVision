package com.nexus.vision.search

/**
 * チャット入力の意図を判定
 *
 * 検索キーワード例:
 *  「/db コーラ」「#検索 コーラ」「?コーラ」→ DB検索
 *  「コーラ 納品」「コーラ 在庫」「何件ある？」→ DB検索
 *  それ以外 → Gemma会話
 */
object IntentRouter {

    enum class Intent {
        DB_SEARCH,      // DBを検索
        GEMMA_CHAT,     // Gemmaとの会話
        AMBIGUOUS       // どちらか不明（DB検索を優先）
    }

    data class ParsedIntent(
        val intent:       Intent,
        val searchQuery:  String,       // 検索用クエリ（DBワードを除去済み）
        val sourceFilter: Set<String>   // 特定ソース指定（空=全ソース）
    )

    // DB検索を示すプレフィックス
    private val DB_PREFIXES = listOf("/db", "/検索", "#db", "#検索", "?", "？")

    // DB関連キーワード（含む場合はDB検索優先）
    private val DB_KEYWORDS = mapOf(
        "delivery" to listOf("納品", "入荷", "搬入", "配送"),
        "product"  to listOf("商品", "製品", "品目", "アイテム")
    )

    fun parse(input: String): ParsedIntent {
        val trimmed = input.trim()

        // プレフィックス検出 → 強制DB検索
        val prefix = DB_PREFIXES.firstOrNull {
            trimmed.startsWith(it, ignoreCase = true)
        }
        if (prefix != null) {
            val query = trimmed.removePrefix(prefix).trim()
            val sourceFilter = detectSourceFilter(query)
            return ParsedIntent(Intent.DB_SEARCH, cleanQuery(query), sourceFilter)
        }

        // DBキーワード含む → DB検索
        val detectedSources = mutableSetOf<String>()
        DB_KEYWORDS.forEach { (sourceId, keywords) ->
            if (keywords.any { trimmed.contains(it) }) {
                detectedSources.add(sourceId)
            }
        }
        if (detectedSources.isNotEmpty()) {
            return ParsedIntent(Intent.DB_SEARCH, trimmed, detectedSources)
        }

        // JAN形式（数字8桁以上）→ DB検索
        if (trimmed.all { it.isDigit() } && trimmed.length >= 8) {
            return ParsedIntent(Intent.DB_SEARCH, trimmed, emptySet())
        }

        // それ以外 → Gemma会話
        return ParsedIntent(Intent.GEMMA_CHAT, trimmed, emptySet())
    }

    private fun detectSourceFilter(query: String): Set<String> {
        val result = mutableSetOf<String>()
        DB_KEYWORDS.forEach { (id, keywords) ->
            if (keywords.any { query.contains(it) }) result.add(id)
        }
        return result
    }

    private fun cleanQuery(query: String): String {
        var q = query
        DB_KEYWORDS.values.flatten().forEach { kw -> q = q.replace(kw, "").trim() }
        return q.trim()
    }
}
