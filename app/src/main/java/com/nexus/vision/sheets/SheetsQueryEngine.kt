// ファイルパス: app/src/main/java/com/nexus/vision/sheets/SheetsQueryEngine.kt
package com.nexus.vision.sheets

import android.util.Log
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager

/**
 * NexusSheets クエリエンジン
 *
 * Phase 8.5: NexusSheets
 * Phase 8.6: 高度検索クエリ対応
 *   - 列名指定フィルタ: A列「東京」のすべて
 *   - 複数条件 AND: A列「東京」+ B列「2024」
 *   - 列値による行抽出: 「東京」が入っているA列の行
 *   - フィルタ+別列抽出: A列が「東京」のB列
 */
class SheetsQueryEngine(private val index: NexusSheetsIndex) {

    companion object {
        private const val TAG = "SheetsQueryEngine"
        private const val MAX_CONTEXT_LENGTH = 3000

        private val SUM_KEYWORDS = listOf("合計", "総計", "sum", "total")
        private val AVG_KEYWORDS = listOf("平均", "average", "avg", "mean")
        private val MAX_KEYWORDS = listOf("最大", "最高", "max", "一番大きい", "一番高い", "トップ")
        private val MIN_KEYWORDS = listOf("最小", "最低", "min", "一番小さい", "一番低い", "ワースト")
        private val COUNT_KEYWORDS = listOf("件数", "何件", "いくつ", "count", "カウント")
        private val COMPARE_KEYWORDS = listOf("比較", "違い", "差", "compare", "vs")
        private val LIST_KEYWORDS = listOf("一覧", "リスト", "全部", "すべて", "list")
        private val DELETE_KEYWORDS = listOf("削除", "消して", "除去", "remove", "delete")
    }

    suspend fun query(userQuery: String): String {
        val trimmed = userQuery.trim()
        Log.i(TAG, "Query: $trimmed")

        // 1. ファイル一覧
        if (LIST_KEYWORDS.any { trimmed.contains(it, ignoreCase = true) } &&
            (trimmed.contains("ファイル") || trimmed.contains("登録") || trimmed.contains("シート"))) {
            return handleListFiles()
        }

        // 2. 削除
        if (DELETE_KEYWORDS.any { trimmed.contains(it, ignoreCase = true) } &&
            (trimmed.contains("ファイル") || trimmed.contains("シート"))) {
            return handleDelete(trimmed)
        }

        // 3. 登録ファイルが 0 件
        if (index.fileCount() == 0L) {
            return "登録されたファイルがありません。PDF / CSV / Excel を共有またはクリップアイコンから添付してください。"
        }

        // 4. 高度検索クエリの判定（列指定フィルタ・複数条件）
        val advancedResult = tryAdvancedQuery(trimmed)
        if (advancedResult != null) return advancedResult

        // 5. 行列クロス検索
        val crossResult = tryCrossLookup(trimmed)
        if (crossResult != null) return crossResult

        // 6. 集計
        val aggregateResult = tryAggregate(trimmed)
        if (aggregateResult != null) return aggregateResult

        // 7. 汎用検索
        return handleSearch(trimmed)
    }

    // ── 高度検索クエリ ──

    /**
     * 高度な検索パターンを解析する。
     *
     * 対応パターン:
     *   ① 「A列「X」のすべて」「A列がXの行」 → filterByColumn
     *   ② 「A列「X」+ B列「Y」」「A列「X」かつB列「Y」」 → multiColumnFilter
     *   ③ 「「X」が入っているA列の行」「A列にXを含む行」 → filterByColumn
     *   ④ 「A列が「X」のB列」「A列がXの場合のB列」 → filterAndExtractColumn
     */
    private fun tryAdvancedQuery(query: String): String? {
        // まず全ファイルのヘッダーを取得
        val allHeadersMap = index.getAllHeaders()
        val allHeaders = allHeadersMap.values.flatten().distinct()
        if (allHeaders.isEmpty()) return null

        // パターン②: 複数条件 AND「A列「X」+ B列「Y」」
        val multiPattern = Regex(
            """(.+?)(?:列|カラム)?[「「](.+?)[」」].*?(?:\+|＋|かつ|AND|and|且つ).*?(.+?)(?:列|カラム)?[「「](.+?)[」」]"""
        )
        multiPattern.find(query)?.let { match ->
            val col1 = resolveColumnName(match.groupValues[1].trim(), allHeaders)
            val val1 = match.groupValues[2].trim()
            val col2 = resolveColumnName(match.groupValues[3].trim(), allHeaders)
            val val2 = match.groupValues[4].trim()

            if (col1 != null && col2 != null) {
                val conditions = listOf(col1 to val1, col2 to val2)
                val results = index.multiColumnFilter(conditions)
                if (results.isNotEmpty()) {
                    return formatFilterResults(results, "${col1}「${val1}」かつ ${col2}「${val2}」")
                }
                return "条件に一致する行が見つかりませんでした。(${col1}=${val1} AND ${col2}=${val2})"
            }
        }

        // パターン④: 「A列が「X」のB列」「A列がXのB列を抽出」
        val extractPattern = Regex(
            """(.+?)(?:列|カラム)?(?:が|＝|=)[「「]?(.+?)[」」]?(?:の|における)(.+?)(?:列|カラム)"""
        )
        extractPattern.find(query)?.let { match ->
            val filterCol = resolveColumnName(match.groupValues[1].trim(), allHeaders)
            val filterVal = match.groupValues[2].trim()
            val extractCol = resolveColumnName(match.groupValues[3].trim(), allHeaders)

            if (filterCol != null && extractCol != null) {
                val results = index.filterAndExtractColumn(filterCol, filterVal, extractCol)
                if (results.isNotEmpty()) {
                    return formatExtractResults(results, filterCol, filterVal, extractCol)
                }
                return "条件に一致するデータが見つかりませんでした。(${filterCol}=${filterVal} の ${extractCol})"
            }
        }

        // パターン①③: 「A列「X」のすべて」「「X」が入っているA列の行」「A列がXの行」
        val singleFilterPatterns = listOf(
            // 「A列「X」のすべて」「A列「X」」
            Regex("""(.+?)(?:列|カラム)?[「「](.+?)[」」](?:のすべて|の行|を抽出|$)"""),
            // 「「X」が入っているA列の行」「「X」を含むA列」
            Regex("""[「「](.+?)[」」](?:が入っている|を含む|がある|の文字が入っている)(.+?)(?:列|カラム)?(?:の行|$)"""),
            // 「A列がXの行」「A列にXを含む行」
            Regex("""(.+?)(?:列|カラム)?(?:が|に)(.+?)(?:を含む|の)(?:行|すべて|データ)""")
        )

        for (pattern in singleFilterPatterns) {
            val match = pattern.find(query) ?: continue

            val part1 = match.groupValues[1].trim()
            val part2 = match.groupValues[2].trim()

            // パターンにより列名と値の位置が異なる
            val (colCandidate, valueCandidate) = if (pattern == singleFilterPatterns[1]) {
                // 「X」が入っているA列の行 → part1=値, part2=列名
                part2 to part1
            } else {
                // A列「X」のすべて → part1=列名, part2=値
                part1 to part2
            }

            val resolvedCol = resolveColumnName(colCandidate, allHeaders)
            if (resolvedCol != null) {
                val results = index.filterByColumn(resolvedCol, valueCandidate)
                if (results.isNotEmpty()) {
                    return formatFilterResults(results, "${resolvedCol}列に「${valueCandidate}」を含む行")
                }
                return "「${resolvedCol}」列に「${valueCandidate}」を含む行が見つかりませんでした。"
            }
        }

        return null
    }

    /**
     * ユーザー入力をヘッダー名に解決する。
     * 「A」「地域」「売上列」等を実際のヘッダー名にマッチ。
     */
    private fun resolveColumnName(input: String, allHeaders: List<String>): String? {
        val cleaned = input.replace("列", "").replace("カラム", "").replace("column", "").trim()
        if (cleaned.isBlank()) return null

        // 完全一致
        allHeaders.find { it.equals(cleaned, ignoreCase = true) }?.let { return it }

        // 部分一致
        allHeaders.find { it.contains(cleaned, ignoreCase = true) }?.let { return it }

        // 逆方向: ヘッダー名が input に含まれる
        allHeaders.find { cleaned.contains(it, ignoreCase = true) }?.let { return it }

        return null
    }

    private fun formatFilterResults(results: List<NexusSheetsIndex.SearchResult>, label: String): String {
        return buildString {
            appendLine("【フィルタ結果】$label — ${results.size} 件")
            appendLine()
            val byFile = results.groupBy { it.fileName }
            for ((fileName, fileResults) in byFile) {
                appendLine("▼ $fileName (${fileResults.size}件)")
                for (r in fileResults.take(30)) {
                    appendLine("  行${r.rowIndex}: ${r.toReadableText()}")
                }
                if (fileResults.size > 30) appendLine("  ... 他 ${fileResults.size - 30} 件")
                appendLine()
            }
        }
    }

    private fun formatExtractResults(
        results: List<NexusSheetsIndex.ExtractResult>,
        filterCol: String, filterVal: String, extractCol: String
    ): String {
        return buildString {
            appendLine("【抽出結果】${filterCol}が「${filterVal}」の ${extractCol} — ${results.size} 件")
            appendLine()
            val byFile = results.groupBy { it.fileName }
            for ((fileName, fileResults) in byFile) {
                appendLine("▼ $fileName (${fileResults.size}件)")
                for (r in fileResults.take(30)) {
                    appendLine("  行${r.rowIndex}: ${r.extractCol} = ${r.extractedValue} (${r.filterCol}: ${r.filterValue})")
                }
                if (fileResults.size > 30) appendLine("  ... 他 ${fileResults.size - 30} 件")
                appendLine()
            }
        }
    }

    // ── 以下は既存の機能（変更なし） ──

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

    private fun handleDelete(query: String): String {
        if (query.contains("全") && DELETE_KEYWORDS.any { query.contains(it) }) {
            val count = index.fileCount()
            index.removeAll()
            return "登録ファイルをすべて削除しました（${count} 件）。"
        }
        val files = index.listFiles()
        val matched = files.find { file ->
            query.contains(file.fileName, ignoreCase = true) ||
                    query.contains(file.fileName.substringBeforeLast("."), ignoreCase = true)
        }
        return if (matched != null) {
            index.removeFile(matched.id)
            "「${matched.fileName}」を削除しました。"
        } else {
            "削除対象のファイルが見つかりません。「ファイル一覧」で確認してください。"
        }
    }

    private fun tryCrossLookup(query: String): String? {
        val patterns = listOf(
            Regex("(.+?)の(.+?)(?:は|を|が|について|$)"),
            Regex("(.+?)における(.+?)(?:は|を|が|$)")
        )
        for (pattern in patterns) {
            val match = pattern.find(query) ?: continue
            val rowKey = match.groupValues[1].trim()
            val colKey = match.groupValues[2].trim()

            val isAggregate = (SUM_KEYWORDS + AVG_KEYWORDS + MAX_KEYWORDS + MIN_KEYWORDS + COUNT_KEYWORDS)
                .any { colKey.contains(it, ignoreCase = true) || rowKey.contains(it, ignoreCase = true) }
            if (isAggregate) continue

            val files = index.listFiles()
            val hasMatchingHeader = files.any { file ->
                val headers = index.parseCellsJson(file.headerJson)
                headers.any { it.contains(colKey, ignoreCase = true) }
            }
            if (!hasMatchingHeader) continue

            val results = index.crossLookup(rowKey, colKey)
            if (results.isNotEmpty()) {
                return buildString {
                    appendLine("【${rowKey} × ${colKey}】${results.size} 件")
                    appendLine()
                    for (r in results) {
                        appendLine("${r.fileName}: ${r.rowKeyword} の ${r.colName} = ${r.value}")
                        val otherInfo = r.headers.zip(r.fullRow)
                            .filter { it.first != r.colName }.take(5)
                            .joinToString(", ") { "${it.first}: ${it.second}" }
                        if (otherInfo.isNotBlank()) appendLine("  (他: $otherInfo)")
                    }
                }
            }
        }
        return null
    }

    private fun tryAggregate(query: String): String? {
        val isSum = SUM_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isAvg = AVG_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isMax = MAX_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isMin = MIN_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isCount = COUNT_KEYWORDS.any { query.contains(it, ignoreCase = true) }

        if (!isSum && !isAvg && !isMax && !isMin && !isCount) return null

        val allHeaders = index.listFiles().flatMap { file ->
            try {
                val arr = org.json.JSONArray(file.headerJson)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) { emptyList() }
        }.distinct()

        val matchedCol = allHeaders.find { header -> query.contains(header, ignoreCase = true) }

        if (matchedCol == null) {
            return buildString {
                appendLine("集計対象の列名が不明です。利用可能な列:")
                appendLine()
                for (file in index.listFiles()) {
                    appendLine("${file.fileName}: ${file.summary}")
                }
            }
        }

        val targetFile = index.listFiles().find { file ->
            query.contains(file.fileName, ignoreCase = true) ||
                    query.contains(file.fileName.substringBeforeLast("."), ignoreCase = true)
        }

        val aggregateResults = if (targetFile != null) {
            listOfNotNull(index.aggregateColumn(targetFile.id, matchedCol))
        } else {
            index.aggregateColumnAllFiles(matchedCol)
        }

        if (aggregateResults.isEmpty()) return "「$matchedCol」列の数値データが見つかりませんでした。"

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
            if (COMPARE_KEYWORDS.any { query.contains(it, ignoreCase = true) } && aggregateResults.size > 1) {
                appendLine()
                appendLine("▼ 全ファイル比較:")
                for (agg in aggregateResults) { appendLine("  ${agg.toText()}") }
            }
        }
    }

    private suspend fun handleSearch(query: String): String {
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
            for (kw in keywords) {
                val partial = index.search(kw)
                if (partial.isNotEmpty()) return formatSearchResults(partial, "「$kw」")
            }
            return "「$query」に一致するデータが見つかりませんでした。登録ファイル: ${index.fileCount()}件"
        }

        val formattedResults = formatSearchResults(results, "「$searchQuery」")

        val engine = NexusEngineManager.getInstance()
        if (engine.state.value is EngineState.Ready) {
            return generateAiAnswer(query, formattedResults)
        }
        return formattedResults
    }

    private fun formatSearchResults(results: List<NexusSheetsIndex.SearchResult>, label: String): String {
        return buildString {
            appendLine("【検索結果】$label — ${results.size} 件")
            appendLine()
            val byFile = results.groupBy { it.fileName }
            for ((fileName, fileResults) in byFile) {
                appendLine("▼ $fileName (${fileResults.size}件)")
                for (r in fileResults.take(20)) {
                    appendLine("  行${r.rowIndex}: ${r.toReadableText()}")
                }
                if (fileResults.size > 20) appendLine("  ... 他 ${fileResults.size - 20} 件")
                appendLine()
            }
        }
    }

    private suspend fun generateAiAnswer(
        userQuery: String, searchResults: String
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
            val result = NexusEngineManager.getInstance().inferText(prompt)
            val aiAnswer = result.getOrNull()
            if (aiAnswer != null) {
                "$aiAnswer\n\n---\n(参照データ: ${searchResults.lines().count { it.startsWith("  行") }} 件)"
            } else searchResults
        } catch (e: Exception) {
            Log.e(TAG, "AI failed: ${e.message}")
            searchResults
        }
    }

    private fun formatTime(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(epochMs))
    }

    private fun formatNum(v: Double): String =
        if (v == v.toLong().toDouble()) "%,.0f".format(v) else "%,.2f".format(v)
}
