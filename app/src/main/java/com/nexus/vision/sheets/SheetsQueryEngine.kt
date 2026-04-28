// ファイルパス: app/src/main/java/com/nexus/vision/sheets/SheetsQueryEngine.kt
package com.nexus.vision.sheets

import android.util.Log
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager

/**
 * NexusSheets クエリエンジン
 *
 * ユーザーの自然言語クエリを解析し、
 * NexusSheetsIndex で検索 → 結果を Gemma-4 に渡して回答。
 * エンジン未ロード時は検索結果のみテキストで返す。
 *
 * Phase 8.5: NexusSheets
 */
class SheetsQueryEngine(private val index: NexusSheetsIndex) {

    companion object {
        private const val TAG = "SheetsQueryEngine"
        private const val MAX_CONTEXT_LENGTH = 3000 // AI に送るコンテキストの最大文字数

        // 集計キーワード
        private val SUM_KEYWORDS = listOf("合計", "総計", "sum", "total")
        private val AVG_KEYWORDS = listOf("平均", "average", "avg", "mean")
        private val MAX_KEYWORDS = listOf("最大", "最高", "max", "一番大きい", "一番高い", "トップ")
        private val MIN_KEYWORDS = listOf("最小", "最低", "min", "一番小さい", "一番低い", "ワースト")
        private val COUNT_KEYWORDS = listOf("件数", "何件", "いくつ", "count", "カウント")
        private val COMPARE_KEYWORDS = listOf("比較", "違い", "差", "compare", "vs")
        private val LIST_KEYWORDS = listOf("一覧", "リスト", "全部", "すべて", "list")
        private val DELETE_KEYWORDS = listOf("削除", "消して", "除去", "remove", "delete")
    }

    /**
     * ユーザーのクエリを処理して回答テキストを返す。
     */
    suspend fun query(userQuery: String): String {
        val trimmed = userQuery.trim()
        Log.i(TAG, "Query: $trimmed")

        // 1. ファイル一覧コマンド
        if (LIST_KEYWORDS.any { trimmed.contains(it, ignoreCase = true) } &&
            (trimmed.contains("ファイル") || trimmed.contains("登録") || trimmed.contains("シート"))) {
            return handleListFiles()
        }

        // 2. 削除コマンド
        if (DELETE_KEYWORDS.any { trimmed.contains(it, ignoreCase = true) } &&
            (trimmed.contains("ファイル") || trimmed.contains("シート"))) {
            return handleDelete(trimmed)
        }

        // 3. 登録ファイルが 0 件なら早期リターン
        if (index.fileCount() == 0L) {
            return "登録されたファイルがありません。PDF / CSV / Excel を共有してファイルを登録してください。"
        }

        // 4. 行列クロス検索の判定
        val crossResult = tryCrossLookup(trimmed)
        if (crossResult != null) return crossResult

        // 5. 集計の判定
        val aggregateResult = tryAggregate(trimmed)
        if (aggregateResult != null) return aggregateResult

        // 6. 汎用キーワード検索
        return handleSearch(trimmed)
    }

    // ── ファイル一覧 ──

    private fun handleListFiles(): String {
        val files = index.listFiles()
        if (files.isEmpty()) return "登録されたファイルはありません。"

        return buildString {
            appendLine("【登録ファイル一覧】${files.size} 件")
            appendLine()
            for (file in files) {
                appendLine("${file.fileName} (${file.fileType}) — ${file.rowCount}行×${file.colCount}列")
                appendLine("  登録日時: ${formatTime(file.importedAt)}")
                appendLine("  ${file.summary}")
                appendLine()
            }
        }
    }

    // ── 削除 ──

    private fun handleDelete(query: String): String {
        // "全削除" / "全部削除"
        if (query.contains("全") && DELETE_KEYWORDS.any { query.contains(it) }) {
            val count = index.fileCount()
            index.removeAll()
            return "登録ファイルをすべて削除しました（${count} 件）。"
        }

        // ファイル名を推定して削除
        val files = index.listFiles()
        val matched = files.find { file ->
            query.contains(file.fileName, ignoreCase = true) ||
                    query.contains(file.fileName.substringBeforeLast("."), ignoreCase = true)
        }

        return if (matched != null) {
            index.removeFile(matched.id)
            "「${matched.fileName}」を削除しました。"
        } else {
            "削除対象のファイルが見つかりません。「ファイル一覧」で登録ファイルを確認してください。"
        }
    }

    // ── 行列クロス検索 ──

    /**
     * 「東京の売上」「Aの利益」のようなパターンを検出し、
     * 行キーワードと列名のクロス検索を実行する。
     */
    private fun tryCrossLookup(query: String): String? {
        // パターン: 「XのY」「XにおけるY」
        val patterns = listOf(
            Regex("(.+?)の(.+?)(?:は|を|が|について|$)"),
            Regex("(.+?)における(.+?)(?:は|を|が|$)")
        )

        for (pattern in patterns) {
            val match = pattern.find(query) ?: continue
            val rowKey = match.groupValues[1].trim()
            val colKey = match.groupValues[2].trim()

            // rowKey, colKey のどちらかが集計キーワード等であれば除外
            val isAggregate = (SUM_KEYWORDS + AVG_KEYWORDS + MAX_KEYWORDS + MIN_KEYWORDS + COUNT_KEYWORDS)
                .any { colKey.contains(it, ignoreCase = true) || rowKey.contains(it, ignoreCase = true) }
            if (isAggregate) continue

            // ヘッダーに colKey が含まれるか確認
            val files = index.listFiles()
            val hasMatchingHeader = files.any { file ->
                val headers = try {
                    org.json.JSONArray(file.headerJson).let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    }
                } catch (e: Exception) { emptyList() }
                headers.any { it.contains(colKey, ignoreCase = true) }
            }

            if (!hasMatchingHeader) continue

            val results = index.crossLookup(rowKey, colKey)
            if (results.isNotEmpty()) {
                return buildString {
                    appendLine("【${rowKey} × ${colKey}】${results.size} 件見つかりました")
                    appendLine()
                    for (r in results) {
                        appendLine("${r.fileName}: ${r.rowKeyword} の ${r.colName} = ${r.value}")
                        // 同じ行の他の情報も付記
                        val otherInfo = r.headers.zip(r.fullRow)
                            .filter { it.first != r.colName }
                            .take(5)
                            .joinToString(", ") { "${it.first}: ${it.second}" }
                        if (otherInfo.isNotBlank()) {
                            appendLine("  (他: $otherInfo)")
                        }
                    }
                }
            }
        }
        return null
    }

    // ── 集計 ──

    private fun tryAggregate(query: String): String? {
        // 集計キーワードの検出
        val isSum = SUM_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isAvg = AVG_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isMax = MAX_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isMin = MIN_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isCount = COUNT_KEYWORDS.any { query.contains(it, ignoreCase = true) }

        if (!isSum && !isAvg && !isMax && !isMin && !isCount) return null

        // 列名を推定: ヘッダーに含まれる語を query から探す
        val allHeaders = index.listFiles().flatMap { file ->
            try {
                val arr = org.json.JSONArray(file.headerJson)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) { emptyList() }
        }.distinct()

        val matchedCol = allHeaders.find { header ->
            query.contains(header, ignoreCase = true)
        }

        if (matchedCol == null) {
            // 列名不明 → 全ファイルの全列を検索候補としてヘルプ
            return buildString {
                appendLine("集計対象の列名が不明です。以下の列が利用可能です:")
                appendLine()
                for (file in index.listFiles()) {
                    appendLine("${file.fileName}: ${file.summary}")
                }
            }
        }

        // 特定ファイル指定があるかチェック
        val targetFile = index.listFiles().find { file ->
            query.contains(file.fileName, ignoreCase = true) ||
                    query.contains(file.fileName.substringBeforeLast("."), ignoreCase = true)
        }

        val aggregateResults = if (targetFile != null) {
            listOfNotNull(index.aggregateColumn(targetFile.id, matchedCol))
        } else {
            index.aggregateColumnAllFiles(matchedCol)
        }

        if (aggregateResults.isEmpty()) {
            return "「$matchedCol」列の数値データが見つかりませんでした。"
        }

        return buildString {
            for (agg in aggregateResults) {
                when {
                    isSum -> appendLine("${agg.fileName} の「${agg.colName}」合計: ${formatNum(agg.sum)} (${agg.count}件)")
                    isAvg -> appendLine("${agg.fileName} の「${agg.colName}」平均: ${formatNum(agg.avg)} (${agg.count}件)")
                    isMax -> appendLine("${agg.fileName} の「${agg.colName}」最大: ${formatNum(agg.max)} (${agg.count}件)")
                    isMin -> appendLine("${agg.fileName} の「${agg.colName}」最小: ${formatNum(agg.min)} (${agg.count}件)")
                    isCount -> appendLine("${agg.fileName} の「${agg.colName}」件数: ${agg.count}件")
                }
            }

            // 比較の場合は全ファイル分出す
            if (COMPARE_KEYWORDS.any { query.contains(it, ignoreCase = true) } && aggregateResults.size > 1) {
                appendLine()
                appendLine("▼ 全ファイル比較:")
                for (agg in aggregateResults) {
                    appendLine("  ${agg.toText()}")
                }
            }
        }
    }

    // ── 汎用検索 ──

    private suspend fun handleSearch(query: String): String {
        // 検索キーワード抽出（ストップワード除去）
        val stopWords = setOf(
            "を", "は", "が", "の", "に", "で", "と", "も", "から", "まで",
            "って", "という", "について", "する", "した", "している", "ある",
            "検索", "探し", "探して", "教えて", "見せて", "取得", "抽出",
            "ファイル", "シート", "データ", "情報"
        )

        val keywords = query.split("\\s+".toRegex())
            .flatMap { it.split("(?<=\\p{IsHan})(?=\\p{IsHiragana})|(?<=\\p{IsHiragana})(?=\\p{IsHan})".toRegex()) }
            .filter { it.length >= 2 && it !in stopWords }

        val searchQuery = if (keywords.isNotEmpty()) keywords.joinToString(" ") else query
        val results = index.search(searchQuery)

        if (results.isEmpty()) {
            // キーワード個別でも検索
            for (kw in keywords) {
                val partial = index.search(kw)
                if (partial.isNotEmpty()) {
                    return formatSearchResults(partial, "「$kw」")
                }
            }
            return "「$query」に一致するデータが見つかりませんでした。登録ファイル: ${index.fileCount()}件"
        }

        val formattedResults = formatSearchResults(results, "「$searchQuery」")

        // エンジンが Ready なら AI に回答を生成させる
        val engine = NexusEngineManager.getInstance()
        if (engine.state.value is EngineState.Ready) {
            return generateAiAnswer(query, formattedResults, engine)
        }

        return formattedResults
    }

    private fun formatSearchResults(results: List<NexusSheetsIndex.SearchResult>, label: String): String {
        return buildString {
            appendLine("【検索結果】$label — ${results.size} 件")
            appendLine()

            // ファイルごとにグループ化
            val byFile = results.groupBy { it.fileName }
            for ((fileName, fileResults) in byFile) {
                appendLine("▼ $fileName (${fileResults.size}件)")
                for (r in fileResults.take(20)) {
                    appendLine("  行${r.rowIndex}: ${r.toReadableText()}")
                }
                if (fileResults.size > 20) {
                    appendLine("  ... 他 ${fileResults.size - 20} 件")
                }
                appendLine()
            }
        }
    }

    private suspend fun generateAiAnswer(
        userQuery: String,
        searchResults: String,
        engine: NexusEngineManager
    ): String {
        val context = searchResults.take(MAX_CONTEXT_LENGTH)

        val prompt = buildString {
            appendLine("あなたはデータ分析アシスタントです。以下はユーザーが登録したファイルから検索されたデータです。")
            appendLine()
            appendLine("--- 検索データ ---")
            appendLine(context)
            appendLine("--- ここまで ---")
            appendLine()
            appendLine("ユーザーの質問: $userQuery")
            appendLine()
            appendLine("上記のデータに基づいて、質問に対して簡潔かつ正確に回答してください。データに含まれない情報は推測しないでください。")
        }

        return try {
            val aiResult = engine.inferText(prompt)
            val aiAnswer = aiResult.getOrNull()

            if (aiAnswer != null) {
                buildString {
                    appendLine(aiAnswer)
                    appendLine()
                    appendLine("---")
                    appendLine("(参照データ: ${searchResults.lines().count { it.startsWith("  行") }} 件)")
                }
            } else {
                // AI 失敗時は検索結果のみ
                searchResults
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI inference failed: ${e.message}")
            searchResults
        }
    }

    // ── ヘルパー ──

    private fun formatTime(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(epochMs))
    }

    private fun formatNum(v: Double): String {
        return if (v == v.toLong().toDouble()) {
            "%,.0f".format(v)
        } else {
            "%,.2f".format(v)
        }
    }
}
