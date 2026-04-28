

# NexusSheets 実装指示書 — Phase 8.5: ファイルインデックス＋検索＋AI連携

## プロジェクト情報

- **リポジトリ**: https://github.com/DENDENKIO/NexusVision/tree/master
- **パッケージ**: `com.nexus.vision`
- **言語**: Kotlin, Android API 31–35, arm64-v8a
- **ビルド**: AGP 8.7.3, Kotlin 2.2.21, Compose BOM 2025.04.00
- **ObjectBox**: 5.3.0（導入済み、`io.objectbox` プラグイン適用済み）
- **既存エンティティ**: `com.nexus.vision.cache` パッケージに `PHashCacheEntry`, `InferenceCacheEntry` が存在し、`MyObjectBox` は `com.nexus.vision.cache` パッケージに自動生成されている
- **既存パーサー**: `ExcelCsvParser`, `PdfExtractor`, `SourceCodeParser`, `TableReconstructor` は実装済み
- **AI エンジン**: `NexusEngineManager.getInstance().inferText(prompt)` で Gemma-4 テキスト推論（`Result<String>` を返す）。エンジン未ロード時は `state.value !is EngineState.Ready`

## 目的

複数の PDF / CSV / Excel ファイルをアプリ内 ObjectBox DB にインデックス化し、チャットから自然言語で横断検索・行列クロス抽出・AI 連携質問応答を可能にする。テーブル表示は行わず、すべてテキストベースの回答として返す。

## 作成・変更するファイル（8 ファイル）

### ファイル 1: `app/src/main/java/com/nexus/vision/sheets/IndexedFile.kt`（新規作成）

ObjectBox エンティティ。1 ファイルにつき 1 レコード。

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/sheets/IndexedFile.kt
package com.nexus.vision.sheets

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * インデックス登録されたファイルのメタデータ
 * Phase 8.5: NexusSheets
 */
@Entity
data class IndexedFile(
    @Id var id: Long = 0,
    @Index var fileName: String = "",
    var fileType: String = "",        // "csv", "xlsx", "pdf", "code"
    var importedAt: Long = 0,         // System.currentTimeMillis()
    var rowCount: Int = 0,
    var colCount: Int = 0,
    var headerJson: String = "",      // JSON array of column header names: ["地域","売上","利益"]
    var summary: String = ""          // 自動生成サマリ
)
```

### ファイル 2: `app/src/main/java/com/nexus/vision/sheets/IndexedRow.kt`（新規作成）

ObjectBox エンティティ。1 行につき 1 レコード。`searchText` に全セルを結合したテキストを格納し、`contains` で全文検索する。

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/sheets/IndexedRow.kt
package com.nexus.vision.sheets

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * インデックス登録された行データ
 * Phase 8.5: NexusSheets
 */
@Entity
data class IndexedRow(
    @Id var id: Long = 0,
    @Index var fileId: Long = 0,      // IndexedFile.id
    var rowIndex: Int = 0,            // 0-based 行番号
    var cellsJson: String = "",       // JSON array: ["東京","1500","800"]
    @Index var searchText: String = "" // 全セル結合テキスト（検索用）: "東京 1500 800"
)
```

### ファイル 3: `app/src/main/java/com/nexus/vision/sheets/NexusSheetsIndex.kt`（新規作成）

インデックスの追加・検索・行列抽出・削除を担当するクラス。

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/sheets/NexusSheetsIndex.kt
package com.nexus.vision.sheets

import android.util.Log
import io.objectbox.Box
import io.objectbox.BoxStore
import org.json.JSONArray

/**
 * NexusSheets インデックス管理
 *
 * 機能:
 *   - ファイル登録（CSV/XLSX/PDF のパース結果を行単位で保存）
 *   - 全文検索（複数ファイル横断）
 *   - 行指定取得 / 列指定取得
 *   - 行列クロス検索（行キーワードと列名の交差値を取得）
 *   - ファイル一覧 / 削除
 *
 * Phase 8.5: NexusSheets
 */
class NexusSheetsIndex(boxStore: BoxStore) {

    companion object {
        private const val TAG = "NexusSheetsIndex"
        private const val MAX_SEARCH_RESULTS = 50
    }

    private val fileBox: Box<IndexedFile> = boxStore.boxFor(IndexedFile::class.java)
    private val rowBox: Box<IndexedRow> = boxStore.boxFor(IndexedRow::class.java)

    // ── ファイル登録 ──

    /**
     * CSV/XLSX パース結果をインデックスに登録する。
     * rows: List<List<String>> — 最初の行をヘッダーとみなす。
     * 戻り値: 登録された IndexedFile の id。
     */
    fun addParsedTable(
        rows: List<List<String>>,
        fileName: String,
        fileType: String
    ): Long {
        if (rows.isEmpty()) return -1

        // 同名ファイルが既にある場合は削除して再登録
        removeByFileName(fileName)

        val headers = rows.first()
        val headerJson = JSONArray(headers).toString()

        val colCount = rows.maxOf { it.size }

        val file = IndexedFile(
            fileName = fileName,
            fileType = fileType,
            importedAt = System.currentTimeMillis(),
            rowCount = rows.size,
            colCount = colCount,
            headerJson = headerJson,
            summary = "ヘッダー: ${headers.joinToString(", ")} / ${rows.size}行×${colCount}列"
        )
        fileBox.put(file)
        val fileId = file.id

        // 行データを一括登録
        val indexedRows = rows.mapIndexed { index, cells ->
            val cellsJsonStr = JSONArray(cells).toString()
            val searchStr = cells.joinToString(" ")
            IndexedRow(
                fileId = fileId,
                rowIndex = index,
                cellsJson = cellsJsonStr,
                searchText = searchStr
            )
        }
        rowBox.put(indexedRows)

        Log.i(TAG, "Indexed '$fileName': ${rows.size} rows, $colCount cols, fileId=$fileId")
        return fileId
    }

    /**
     * PDF パース結果をインデックスに登録する。
     * 各ページのテキストを段落（改行区切り）ごとに行として保存。
     */
    fun addPdfText(
        pages: List<String>,    // ページごとのテキスト
        fileName: String
    ): Long {
        removeByFileName(fileName)

        val allLines = mutableListOf<List<String>>()
        // ヘッダー行
        allLines.add(listOf("ページ", "内容"))

        for ((pageIdx, pageText) in pages.withIndex()) {
            val paragraphs = pageText.split("\n").filter { it.isNotBlank() }
            for (para in paragraphs) {
                allLines.add(listOf("${pageIdx + 1}", para.trim()))
            }
        }

        val file = IndexedFile(
            fileName = fileName,
            fileType = "pdf",
            importedAt = System.currentTimeMillis(),
            rowCount = allLines.size,
            colCount = 2,
            headerJson = JSONArray(listOf("ページ", "内容")).toString(),
            summary = "${pages.size}ページ / ${allLines.size - 1}段落"
        )
        fileBox.put(file)
        val fileId = file.id

        val indexedRows = allLines.mapIndexed { index, cells ->
            IndexedRow(
                fileId = fileId,
                rowIndex = index,
                cellsJson = JSONArray(cells).toString(),
                searchText = cells.joinToString(" ")
            )
        }
        rowBox.put(indexedRows)

        Log.i(TAG, "Indexed PDF '$fileName': ${allLines.size} rows, fileId=$fileId")
        return fileId
    }

    // ── 検索 ──

    /**
     * 全ファイル横断でキーワード検索する。
     * 複数キーワードはスペース区切りで AND 検索。
     * 戻り値: SearchResult のリスト（ファイル名・行番号・セルデータ付き）
     */
    fun search(query: String): List<SearchResult> {
        val keywords = query.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (keywords.isEmpty()) return emptyList()

        // 最初のキーワードで ObjectBox クエリ
        val firstKeyword = keywords.first()
        val candidates = rowBox.query(
            IndexedRow_.searchText.contains(firstKeyword, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
        ).build().find()

        // 残りのキーワードで Kotlin 側フィルタ（AND）
        val filtered = if (keywords.size > 1) {
            candidates.filter { row ->
                keywords.all { kw ->
                    row.searchText.contains(kw, ignoreCase = true)
                }
            }
        } else {
            candidates
        }

        // ファイル情報を付与して返す（上限 MAX_SEARCH_RESULTS）
        val fileCache = mutableMapOf<Long, IndexedFile?>()
        return filtered.take(MAX_SEARCH_RESULTS).mapNotNull { row ->
            val file = fileCache.getOrPut(row.fileId) { fileBox.get(row.fileId) }
                ?: return@mapNotNull null
            val cells = parseCellsJson(row.cellsJson)
            val headers = parseCellsJson(file.headerJson)
            SearchResult(
                fileName = file.fileName,
                fileId = row.fileId,
                rowIndex = row.rowIndex,
                cells = cells,
                headers = headers
            )
        }
    }

    /**
     * 行列クロス検索:
     * rowKeyword に一致する行を探し、colName に一致する列の値を返す。
     * 例: rowKeyword="東京", colName="売上" → 東京の行の売上列の値
     *
     * targetFileId が指定された場合はそのファイルのみ検索。
     * 0 の場合は全ファイル横断。
     */
    fun crossLookup(
        rowKeyword: String,
        colName: String,
        targetFileId: Long = 0
    ): List<CrossResult> {
        val results = mutableListOf<CrossResult>()

        // 対象ファイルの決定
        val files = if (targetFileId > 0) {
            listOfNotNull(fileBox.get(targetFileId))
        } else {
            fileBox.all
        }

        for (file in files) {
            val headers = parseCellsJson(file.headerJson)
            // 列名を検索（部分一致）
            val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
            if (colIndex < 0) continue

            // その fileId の行でキーワードに一致するものを検索
            val matchingRows = rowBox.query(
                IndexedRow_.fileId.equal(file.id)
                    .and(IndexedRow_.searchText.contains(rowKeyword, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE))
            ).build().find()

            for (row in matchingRows) {
                if (row.rowIndex == 0) continue // ヘッダー行はスキップ
                val cells = parseCellsJson(row.cellsJson)
                val value = cells.getOrElse(colIndex) { "" }
                if (value.isNotBlank()) {
                    results.add(
                        CrossResult(
                            fileName = file.fileName,
                            rowKeyword = rowKeyword,
                            colName = headers[colIndex],
                            value = value,
                            rowIndex = row.rowIndex,
                            fullRow = cells,
                            headers = headers
                        )
                    )
                }
            }
        }

        return results.take(MAX_SEARCH_RESULTS)
    }

    /**
     * 特定ファイルの特定列の全値を取得する。
     * colName: 列ヘッダー名（部分一致）
     */
    fun getColumn(fileId: Long, colName: String): ColumnResult? {
        val file = fileBox.get(fileId) ?: return null
        val headers = parseCellsJson(file.headerJson)
        val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
        if (colIndex < 0) return null

        val rows = rowBox.query(IndexedRow_.fileId.equal(fileId))
            .order(IndexedRow_.rowIndex)
            .build().find()

        val values = rows
            .filter { it.rowIndex > 0 } // ヘッダー行スキップ
            .map { row ->
                val cells = parseCellsJson(row.cellsJson)
                cells.getOrElse(colIndex) { "" }
            }

        return ColumnResult(
            fileName = file.fileName,
            colName = headers[colIndex],
            values = values
        )
    }

    /**
     * 特定ファイルの特定行範囲を取得する。
     */
    fun getRows(fileId: Long, fromRow: Int = 0, toRow: Int = Int.MAX_VALUE): List<List<String>> {
        val rows = rowBox.query(IndexedRow_.fileId.equal(fileId))
            .order(IndexedRow_.rowIndex)
            .build().find()

        return rows
            .filter { it.rowIndex in fromRow..toRow }
            .map { parseCellsJson(it.cellsJson) }
    }

    // ── 集計 ──

    /**
     * 特定ファイルの列の数値集計。
     * 合計・平均・最大・最小・件数を返す。
     */
    fun aggregateColumn(fileId: Long, colName: String): AggregateResult? {
        val colResult = getColumn(fileId, colName) ?: return null

        val numbers = colResult.values.mapNotNull { it.replace(",", "").toDoubleOrNull() }
        if (numbers.isEmpty()) return AggregateResult(
            colName = colResult.colName,
            fileName = colResult.fileName,
            count = 0, sum = 0.0, avg = 0.0, min = 0.0, max = 0.0,
            message = "「${colResult.colName}」列に数値データがありません"
        )

        return AggregateResult(
            colName = colResult.colName,
            fileName = colResult.fileName,
            count = numbers.size,
            sum = numbers.sum(),
            avg = numbers.average(),
            min = numbers.min(),
            max = numbers.max()
        )
    }

    /**
     * 全ファイルを横断して列名が一致するものの集計を行う。
     */
    fun aggregateColumnAllFiles(colName: String): List<AggregateResult> {
        return fileBox.all.mapNotNull { file ->
            aggregateColumn(file.id, colName)
        }.filter { it.count > 0 }
    }

    // ── ファイル管理 ──

    fun listFiles(): List<IndexedFile> = fileBox.all

    fun getFile(fileId: Long): IndexedFile? = fileBox.get(fileId)

    fun findFileByName(name: String): IndexedFile? {
        return fileBox.query(
            IndexedFile_.fileName.contains(name, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
        ).build().findFirst()
    }

    fun removeFile(fileId: Long) {
        // まず関連行を削除
        val rows = rowBox.query(IndexedRow_.fileId.equal(fileId)).build().find()
        rowBox.remove(rows)
        fileBox.remove(fileId)
        Log.i(TAG, "Removed file id=$fileId (${rows.size} rows)")
    }

    fun removeByFileName(fileName: String) {
        val existing = fileBox.query(
            IndexedFile_.fileName.equal(fileName, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
        ).build().find()
        for (f in existing) {
            removeFile(f.id)
        }
    }

    fun removeAll() {
        rowBox.removeAll()
        fileBox.removeAll()
        Log.i(TAG, "All indexed files removed")
    }

    fun fileCount(): Long = fileBox.count()
    fun totalRowCount(): Long = rowBox.count()

    // ── ヘルパー ──

    private fun parseCellsJson(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── データクラス ──

    data class SearchResult(
        val fileName: String,
        val fileId: Long,
        val rowIndex: Int,
        val cells: List<String>,
        val headers: List<String>
    ) {
        /** "列名: 値" 形式のテキスト */
        fun toReadableText(): String {
            return cells.mapIndexed { i, cell ->
                val header = headers.getOrElse(i) { "列${i + 1}" }
                "$header: $cell"
            }.joinToString(", ")
        }
    }

    data class CrossResult(
        val fileName: String,
        val rowKeyword: String,
        val colName: String,
        val value: String,
        val rowIndex: Int,
        val fullRow: List<String>,
        val headers: List<String>
    )

    data class ColumnResult(
        val fileName: String,
        val colName: String,
        val values: List<String>
    )

    data class AggregateResult(
        val colName: String,
        val fileName: String,
        val count: Int,
        val sum: Double,
        val avg: Double,
        val min: Double,
        val max: Double,
        val message: String? = null
    ) {
        fun toText(): String {
            if (message != null) return message
            return "$fileName の「$colName」: 件数=${count}, 合計=${formatNum(sum)}, 平均=${formatNum(avg)}, 最小=${formatNum(min)}, 最大=${formatNum(max)}"
        }

        private fun formatNum(v: Double): String {
            return if (v == v.toLong().toDouble()) {
                "%,.0f".format(v)
            } else {
                "%,.2f".format(v)
            }
        }
    }
}
```

### ファイル 4: `app/src/main/java/com/nexus/vision/sheets/SheetsQueryEngine.kt`（新規作成）

ユーザーの自然言語クエリを解析し、NexusSheetsIndex の検索結果を Gemma-4 に渡して回答を生成する。

```kotlin
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
```

### ファイル 5: `app/src/main/java/com/nexus/vision/NexusApplication.kt`（完全置換）

`NexusSheetsIndex` と `SheetsQueryEngine` の初期化を追加。

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/NexusApplication.kt
package com.nexus.vision

import android.app.Application
import android.util.Log
import com.nexus.vision.cache.L1PHashCache
import com.nexus.vision.cache.L2InferenceCache
import com.nexus.vision.cache.MyObjectBox
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.engine.ThermalMonitor
import com.nexus.vision.sheets.NexusSheetsIndex
import com.nexus.vision.sheets.SheetsQueryEngine
import io.objectbox.BoxStore

/**
 * NEXUS Vision アプリケーションクラス
 *
 * 責務:
 *   - ThermalMonitor 初期化        (Phase 2)
 *   - NexusEngineManager 初期化    (Phase 2)
 *   - ObjectBox 初期化             (Phase 3)
 *   - L1/L2 キャッシュ初期化        (Phase 3)
 *   - NexusSheetsIndex 初期化      (Phase 8.5)
 *   - SheetsQueryEngine 初期化     (Phase 8.5)
 *   - グローバル例外ハンドラ
 */
class NexusApplication : Application() {

    companion object {
        private const val TAG = "NexusApp"

        @Volatile
        private lateinit var instance: NexusApplication

        fun getInstance(): NexusApplication = instance
    }

    lateinit var thermalMonitor: ThermalMonitor
        private set

    lateinit var boxStore: BoxStore
        private set
    lateinit var l1Cache: L1PHashCache
        private set
    lateinit var l2Cache: L2InferenceCache
        private set

    // Phase 8.5 追加
    lateinit var sheetsIndex: NexusSheetsIndex
        private set
    lateinit var sheetsQueryEngine: SheetsQueryEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.i(TAG, "NEXUS Vision initialized — versionName=${BuildConfig.VERSION_NAME}")

        // Phase 2
        thermalMonitor = ThermalMonitor(this)
        thermalMonitor.startMonitoring()
        NexusEngineManager.getInstance().initialize(this, thermalMonitor)

        // Phase 3: ObjectBox
        boxStore = MyObjectBox.builder()
            .androidContext(this)
            .name("nexus-vision-db")
            .build()
        Log.i(TAG, "ObjectBox initialized")

        // Phase 3: キャッシュ
        l1Cache = L1PHashCache(boxStore)
        l2Cache = L2InferenceCache(boxStore)
        Log.i(TAG, "Caches initialized — L1: ${l1Cache.count()} entries, L2: ${l2Cache.count()} entries")

        // Phase 8.5: NexusSheets
        sheetsIndex = NexusSheetsIndex(boxStore)
        sheetsQueryEngine = SheetsQueryEngine(sheetsIndex)
        Log.i(TAG, "NexusSheets initialized — ${sheetsIndex.fileCount()} files, ${sheetsIndex.totalRowCount()} rows")
    }

    override fun onTerminate() {
        super.onTerminate()
        NexusEngineManager.getInstance().release()
        boxStore.close()
    }
}
```

### ファイル 6: `app/src/main/java/com/nexus/vision/os/ShareReceiver.kt`（完全置換）

ファイル共有時にインデックス登録を行い、テーブル表示ではなく登録完了メッセージを表示する。

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/os/ShareReceiver.kt
package com.nexus.vision.os

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexus.vision.NexusApplication
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.ocr.MlKitOcrEngine
import com.nexus.vision.parser.ExcelCsvParser
import com.nexus.vision.parser.PdfExtractor
import com.nexus.vision.parser.SourceCodeParser
import com.nexus.vision.ui.theme.NexusVisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ACTION_SEND / ACTION_SEND_MULTIPLE で共有されたデータを受信する Activity。
 *
 * CSV / Excel / PDF はインデックスに登録し、チャットから検索可能にする。
 * 画像は OCR 処理。テキストは AI 要約。
 *
 * Phase 10: OS 統合
 * Phase 8.5: NexusSheets インデックス統合
 */
class ShareReceiver : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"

        private val FILE_EXTENSIONS = setOf(
            ".csv", ".xlsx", ".xls", ".pdf",
            ".kt", ".kts", ".java", ".py", ".js", ".ts", ".jsx", ".tsx",
            ".c", ".cpp", ".cc", ".cxx", ".h", ".hpp",
            ".swift", ".go", ".rs", ".dart", ".rb",
            ".json", ".xml", ".yaml", ".yml",
            ".md", ".sh", ".bash", ".sql",
            ".html", ".htm", ".css", ".scss",
            ".gradle", ".toml", ".properties", ".cfg", ".ini", ".conf"
        )

        private val FILE_MIME_PARTS = setOf(
            "pdf", "csv", "comma-separated",
            "spreadsheetml", "xlsx", "excel",
            "octet-stream"
        )

        /** インデックス登録対象の拡張子 */
        private val INDEXABLE_EXTENSIONS = setOf(".csv", ".xlsx", ".pdf")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val receivedData = parseIntent(intent)

        setContent {
            NexusVisionTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var resultText by remember { mutableStateOf<String?>(null) }
                    var isProcessing by remember { mutableStateOf(true) }

                    LaunchedEffect(Unit) {
                        resultText = processReceivedData(receivedData)
                        isProcessing = false
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isProcessing) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text(
                                    text = "NEXUS Vision で処理中...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "処理結果",
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )
                                Text(
                                    text = resultText ?: "データを処理できませんでした",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseIntent(intent: Intent): ReceivedData {
        val action = intent.action
        val type = intent.type ?: ""

        Log.i(TAG, "Received: action=$action, type=$type")

        if (action == Intent.ACTION_SEND_MULTIPLE && type.startsWith("image/")) {
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (!uris.isNullOrEmpty()) return ReceivedData.MultipleImages(uris)
        }

        if (action == Intent.ACTION_SEND) {
            val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

            if (streamUri != null) {
                val resolvedMime = contentResolver.getType(streamUri) ?: type
                val filename = getFilename(streamUri)

                Log.i(TAG, "Stream URI: $streamUri, resolvedMime=$resolvedMime, filename=$filename")

                if (resolvedMime.startsWith("image/") && !hasFileExtension(filename)) {
                    return ReceivedData.SingleImage(streamUri)
                }

                if (hasFileExtension(filename) || hasFileMimeType(resolvedMime)) {
                    return ReceivedData.FileData(streamUri, resolvedMime)
                }

                if (resolvedMime.startsWith("image/")) {
                    return ReceivedData.SingleImage(streamUri)
                }

                return ReceivedData.FileData(streamUri, resolvedMime)
            }

            val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!extraText.isNullOrBlank()) {
                return ReceivedData.TextData(extraText)
            }
        }

        return ReceivedData.Empty
    }

    private fun getFilename(uri: Uri): String {
        try {
            val cursor = contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val name = it.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not query display name: ${e.message}")
        }
        return uri.lastPathSegment ?: ""
    }

    private fun hasFileExtension(filename: String): Boolean {
        val lower = filename.lowercase()
        return FILE_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun hasFileMimeType(mimeType: String): Boolean {
        val lower = mimeType.lowercase()
        return FILE_MIME_PARTS.any { lower.contains(it) }
    }

    private suspend fun processReceivedData(data: ReceivedData): String =
        withContext(Dispatchers.IO) {
            when (data) {
                is ReceivedData.TextData -> processText(data.text)
                is ReceivedData.SingleImage -> processImage(data.uri)
                is ReceivedData.MultipleImages -> processMultipleImages(data.uris)
                is ReceivedData.FileData -> processFile(data.uri, data.mimeType)
                is ReceivedData.Empty -> "共有データを受信できませんでした"
            }
        }

    private suspend fun processText(text: String): String {
        if (text.isBlank()) return "テキストが空です"

        val engine = NexusEngineManager.getInstance()
        return if (engine.state.value is EngineState.Ready) {
            val result = engine.inferText("以下のテキストを簡潔に要約してください:\n\n$text")
            result.getOrElse {
                "エンジンエラー: ${it.message}\n\n元テキスト:\n$text"
            }
        } else {
            "【共有テキスト受信】\n\n$text\n\n" +
                    "(エンジン未ロードのため要約はスキップされました)"
        }
    }

    private suspend fun processImage(uri: Uri): String {
        val ocrEngine = MlKitOcrEngine()
        return try {
            val result = ocrEngine.recognizeFromUri(applicationContext, uri)
            if (result.isNotBlank()) {
                "【テキスト読み取り結果】\n\n$result"
            } else {
                "テキストを検出できませんでした"
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            "OCR エラー: ${e.message}"
        } finally {
            ocrEngine.close()
        }
    }

    private suspend fun processMultipleImages(uris: List<Uri>): String {
        val ocrEngine = MlKitOcrEngine()
        val results = StringBuilder()

        try {
            for ((index, uri) in uris.withIndex()) {
                results.appendLine("--- 画像 ${index + 1}/${uris.size} ---")
                try {
                    val text = ocrEngine.recognizeFromUri(applicationContext, uri)
                    if (text.isNotBlank()) {
                        results.appendLine(text)
                    } else {
                        results.appendLine("(テキスト未検出)")
                    }
                } catch (e: Exception) {
                    results.appendLine("(エラー: ${e.message})")
                }
                results.appendLine()
            }
        } finally {
            ocrEngine.close()
        }

        return results.toString().ifBlank { "テキストを検出できませんでした" }
    }

    /**
     * ファイル処理: CSV / Excel はインデックス登録、PDF も登録、ソースコードは構造解析のみ
     */
    private suspend fun processFile(uri: Uri, mimeType: String): String {
        val filename = getFilename(uri)
        val resolvedMime = mimeType.ifBlank { contentResolver.getType(uri) ?: "" }
        val lowerFilename = filename.lowercase()

        Log.i(TAG, "processFile: mime=$resolvedMime, filename=$filename")

        val sheetsIndex = NexusApplication.getInstance().sheetsIndex

        return when {
            // CSV
            resolvedMime.contains("csv") || resolvedMime.contains("comma-separated") ||
                    lowerFilename.endsWith(".csv") -> {
                val result = ExcelCsvParser.parseFromUri(applicationContext, uri, resolvedMime)
                if (result.isSuccess) {
                    val fileId = sheetsIndex.addParsedTable(result.rows, filename, "csv")
                    buildString {
                        appendLine("「$filename」を登録しました。")
                        appendLine("${result.rowCount} 行 × ${result.colCount} 列 (${result.processingTimeMs}ms)")
                        appendLine()
                        appendLine("チャットから検索・質問できます。")
                        appendLine("例: 「東京の売上」「売上の合計」「○○を検索」")
                    }
                } else {
                    result.errorMessage ?: "CSV 解析失敗"
                }
            }

            // Excel (.xlsx)
            resolvedMime.contains("spreadsheetml") || resolvedMime.contains("xlsx") ||
                    resolvedMime.contains("excel") ||
                    lowerFilename.endsWith(".xlsx") || lowerFilename.endsWith(".xls") -> {
                val result = ExcelCsvParser.parseFromUri(applicationContext, uri, resolvedMime)
                if (result.isSuccess) {
                    val fileId = sheetsIndex.addParsedTable(result.rows, filename, "xlsx")
                    buildString {
                        appendLine("「$filename」を登録しました。")
                        appendLine("${result.rowCount} 行 × ${result.colCount} 列 (${result.processingTimeMs}ms)")
                        appendLine()
                        appendLine("チャットから検索・質問できます。")
                        appendLine("例: 「東京の売上」「売上の合計」「○○を検索」")
                    }
                } else {
                    result.errorMessage ?: "Excel 解析失敗"
                }
            }

            // PDF
            resolvedMime.contains("pdf") || lowerFilename.endsWith(".pdf") -> {
                val result = PdfExtractor.extractFromUri(applicationContext, uri)
                if (result.isSuccess) {
                    val pageTexts = result.pages.map { it.text }
                    val fileId = sheetsIndex.addPdfText(pageTexts, filename)
                    buildString {
                        appendLine("「$filename」を登録しました。")
                        appendLine("${result.processedPages}/${result.totalPages} ページ (${result.processingTimeMs}ms)")
                        appendLine()
                        appendLine("チャットから検索・質問できます。")
                        appendLine("例: 「○○を検索」「○○について教えて」")
                    }
                } else {
                    result.errorMessage ?: "PDF 解析失敗"
                }
            }

            // ソースコード / テキスト系（インデックス対象外、構造解析のみ）
            resolvedMime.startsWith("text/") ||
                    resolvedMime.contains("octet-stream") ||
                    FILE_EXTENSIONS.any { lowerFilename.endsWith(it) } -> {
                val result = SourceCodeParser.parseFromUri(applicationContext, uri, resolvedMime)
                if (result.isSuccess) {
                    result.toSummaryText()
                } else {
                    result.errorMessage ?: "ファイル解析失敗"
                }
            }

            else -> {
                "非対応ファイル形式: $resolvedMime ($filename)\n\n" +
                        "対応形式: CSV, Excel(.xlsx), PDF, ソースコード(.kt .java .py .js 等), 画像(OCR)"
            }
        }
    }

    sealed class ReceivedData {
        data class TextData(val text: String) : ReceivedData()
        data class SingleImage(val uri: Uri) : ReceivedData()
        data class MultipleImages(val uris: List<Uri>) : ReceivedData()
        data class FileData(val uri: Uri, val mimeType: String) : ReceivedData()
        data object Empty : ReceivedData()
    }
}
```

### ファイル 7: `app/src/main/java/com/nexus/vision/ui/MainViewModel.kt`（完全置換）

`sendMessage()` にシート検索判定を追加。ファイル解析結果をインデックス登録。チャットから NexusSheets の全機能にアクセス可能にする。

**変更箇所のみ明示（既存コードはすべて維持）:**

1. `import` に以下を追加:
```kotlin
import com.nexus.vision.sheets.NexusSheetsIndex
import com.nexus.vision.sheets.SheetsQueryEngine
```

2. クラス冒頭のプロパティに追加:
```kotlin
private val sheetsIndex: NexusSheetsIndex = app.sheetsIndex
private val sheetsQueryEngine: SheetsQueryEngine = app.sheetsQueryEngine
```

3. ウェルカムメッセージを変更:
```kotlin
addSystemMessage("NEXUS Vision へようこそ。画像・PDF・CSV・テキストを送信できます。ファイルを共有すると登録され、チャットから横断検索できます。")
```

4. `sendMessage()` 内のコマンド判定に以下を追加（`isFileRequest` の後、`needsEngine` の前）:
```kotlin
// NexusSheets クエリ判定
val isSheetsQuery = !isOcrRequest && !isTableRequest && !isEnhanceRequest &&
        !isZoomRequest && !isFileRequest && imageUri == null &&
        (sheetsIndex.fileCount() > 0 || text.contains("ファイル一覧") || text.contains("登録"))

val SHEETS_KEYWORDS = listOf(
    "検索", "探して", "探す", "ファイル一覧", "登録",
    "合計", "平均", "最大", "最小", "件数",
    "の売上", "の利益", "の金額", "の数",
    "比較", "削除", "シート"
)
val isSheetsKeywordMatch = SHEETS_KEYWORDS.any { text.contains(it, ignoreCase = true) }

// シートクエリの場合はエンジン不要で処理可能
val actualSheetsQuery = isSheetsQuery || (imageUri == null && isSheetsKeywordMatch && !needsEngine)
```

5. `needsEngine` の定義を修正:
```kotlin
val needsEngine = !isOcrRequest && !isTableRequest && !isEnhanceRequest &&
        !isZoomRequest && !isFileRequest && !actualSheetsQuery
```

6. シートクエリの処理ブランチを `processingLabel` と `response` の `when` に追加:
```kotlin
// processingLabel の when に追加
actualSheetsQuery -> "データを検索中..."

// response の when に追加
actualSheetsQuery -> sheetsQueryEngine.query(displayText)
```

7. `processFileRequest` を更新してインデックス登録も行う:
```kotlin
// CSV / Excel の場合、result.isSuccess のブロック内で:
val fileId = sheetsIndex.addParsedTable(result.rows, filename, "csv")  // or "xlsx"
// 返却テキストを変更:
buildString {
    appendLine("「$filename」を登録しました。(${result.rowCount}行×${result.colCount}列)")
    appendLine("チャットから検索・質問できます。")
}

// PDF の場合、result.isSuccess のブロック内で:
val pageTexts = result.pages.map { it.text }
val fileId = sheetsIndex.addPdfText(pageTexts, filename)
buildString {
    appendLine("「$filename」を登録しました。(${result.processedPages}ページ)")
    appendLine("チャットから検索・質問できます。")
}
```

8. エンジン未ロード時のメッセージを更新:
```kotlin
"エンジンがロードされていません。\n\n" +
    "エンジンなしで利用可能: 画像OCR、表復元、高画質化、ファイル登録・検索・集計\n" +
    "AI 連携回答にはエンジンのロードが必要です。"
```

**以下に完全な MainViewModel.kt を記載します。既存の全メソッドを維持しつつ、上記の変更をすべて適用してください:**

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ui/MainViewModel.kt

package com.nexus.vision.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.vision.NexusApplication
import com.nexus.vision.cache.L1PHashCache
import com.nexus.vision.cache.L2InferenceCache
import com.nexus.vision.deor.AdaptiveResizer
import com.nexus.vision.deor.PHashCalculator
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.engine.ThermalLevel
import com.nexus.vision.image.DocumentSharpener
import com.nexus.vision.image.DirectCrop100MP
import com.nexus.vision.image.RegionDecoder
import com.nexus.vision.ocr.MlKitOcrEngine
import com.nexus.vision.ocr.TableReconstructor
import com.nexus.vision.ui.components.ChatMessage
import com.nexus.vision.pipeline.RouteCProcessor
import com.nexus.vision.ncnn.RealEsrganBridge
import com.nexus.vision.parser.ExcelCsvParser
import com.nexus.vision.parser.PdfExtractor
import com.nexus.vision.parser.SourceCodeParser
import com.nexus.vision.sheets.NexusSheetsIndex
import com.nexus.vision.sheets.SheetsQueryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nexus.vision.worker.BatchEnhanceQueue
import com.nexus.vision.worker.BatchEnhanceWorker

enum class CropPurpose {
    ENHANCE,
    ZOOM
}

data class MainUiState(
    val isEngineReady: Boolean = false,
    val isProcessing: Boolean = false,
    val isDegraded: Boolean = false,
    val statusMessage: String = "AI エンジン準備中...",
    val errorMessage: String? = null,
    val thermalLevelName: String = "NONE",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val selectedImageUri: Uri? = null,
    val selectedImagePath: String? = null,
    val cropMode: Boolean = false,
    val cropPurpose: CropPurpose = CropPurpose.ENHANCE,
    val cropImageUri: Uri? = null,
    val cropThumbnail: Bitmap? = null,
    val cropImageWidth: Int = 0,
    val cropImageHeight: Int = 0,
    val isBatchRunning: Boolean = false,
    val batchProgressText: String = "",
    val requestBatchPicker: Boolean = false
)

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"

        /** NexusSheets が反応するキーワード */
        private val SHEETS_KEYWORDS = listOf(
            "検索", "探して", "探す", "ファイル一覧", "登録",
            "合計", "平均", "最大", "最小", "最高", "最低", "件数",
            "比較", "削除", "シート",
            "の売上", "の利益", "の金額", "の数", "の値",
            "について", "はいくつ", "はいくら"
        )
    }

    private val app = NexusApplication.getInstance()
    private val engineManager = NexusEngineManager.getInstance()
    private val l1Cache: L1PHashCache = app.l1Cache
    private val l2Cache: L2InferenceCache = app.l2Cache

    private val ocrEngine = MlKitOcrEngine()

    private var routeC: RouteCProcessor? = null
    private var routeCInitialized = false

    // Phase 8.5: NexusSheets
    private val sheetsIndex: NexusSheetsIndex = app.sheetsIndex
    private val sheetsQueryEngine: SheetsQueryEngine = app.sheetsQueryEngine

    private var batchProgressMessageId: String? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val engineState: StateFlow<EngineState> = engineManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineState.Idle)

    val thermalLevel: StateFlow<ThermalLevel> =
        app.thermalMonitor.thermalLevel
            .stateIn(viewModelScope, SharingStarted.Eagerly, ThermalLevel.NONE)

    init {
        viewModelScope.launch {
            engineManager.state.collect { state ->
                _uiState.value = _uiState.value.copy(
                    isEngineReady = state is EngineState.Ready,
                    isProcessing = state is EngineState.Processing,
                    isDegraded = state is EngineState.Degraded,
                    statusMessage = when (state) {
                        is EngineState.Idle -> "エンジン未ロード"
                        is EngineState.Initializing -> "モデルロード中..."
                        is EngineState.Ready -> "準備完了"
                        is EngineState.Processing -> state.taskDescription
                        is EngineState.Degraded -> "発熱のためテキストオンリーモード"
                        is EngineState.Error -> "エラー: ${state.message}"
                        is EngineState.Released -> "エンジン解放済み"
                    },
                    errorMessage = if (state is EngineState.Error) state.message else null
                )
            }
        }

        viewModelScope.launch {
            thermalLevel.collect { level ->
                _uiState.value = _uiState.value.copy(thermalLevelName = level.name)
            }
        }

        // ウェルカムメッセージ（登録ファイル数を表示）
        val fileCount = sheetsIndex.fileCount()
        val welcomeExtra = if (fileCount > 0) "登録済みファイル: ${fileCount}件。" else ""
        addSystemMessage(
            "NEXUS Vision へようこそ。画像・PDF・CSV・テキストを送信できます。" +
                    "ファイルを共有すると登録され、チャットから横断検索できます。$welcomeExtra"
        )
        initSuperResolution()

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
                        append("一時停止中 (${progress.pauseReason})")
                    } else if (progress.isRunning) {
                        append("${progress.completed + 1}/${progress.total} 処理中...")
                        if (progress.estimatedRemainingMs > 0) {
                            val min = progress.estimatedRemainingMs / 60_000
                            val sec = (progress.estimatedRemainingMs % 60_000) / 1_000
                            append(" 残り約 ${min}分${sec}秒")
                        }
                    } else {
                        append("バッチ完了: ${progress.successCount}/${progress.total} 成功")
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
    }

    private fun initSuperResolution() {
        viewModelScope.launch(Dispatchers.IO) {
            routeC = RouteCProcessor(app.applicationContext)
            routeCInitialized = routeC?.initialize() == true
            if (routeCInitialized) {
                Log.i(TAG, "Super-resolution ready")
            } else {
                Log.w(TAG, "Super-resolution init failed (will passthrough)")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ocrEngine.close()
        routeC?.release()
    }

    // ── エンジン操作 ──

    fun loadEngine() {
        viewModelScope.launch {
            addSystemMessage("エンジンをロード中...")
            engineManager.loadEngine()
            if (engineManager.state.value is EngineState.Ready) {
                addSystemMessage("エンジンの準備が完了しました。メッセージを送信できます。")
            }
        }
    }

    // ── 入力操作 ──

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun setSelectedImage(uri: Uri) {
        _uiState.value = _uiState.value.copy(
            selectedImageUri = uri,
            selectedImagePath = null
        )
    }

    fun clearSelectedImage() {
        _uiState.value = _uiState.value.copy(
            selectedImageUri = null,
            selectedImagePath = null
        )
    }

    // ── 範囲選択モード ──

    fun cancelCropMode() {
        _uiState.value = _uiState.value.copy(
            cropMode = false,
            cropPurpose = CropPurpose.ENHANCE,
            cropImageUri = null,
            cropThumbnail = null,
            cropImageWidth = 0,
            cropImageHeight = 0
        )
    }

    fun onCropConfirmed(left: Float, top: Float, right: Float, bottom: Float) {
        val uri = _uiState.value.cropImageUri ?: return
        val imgW = _uiState.value.cropImageWidth
        val imgH = _uiState.value.cropImageHeight
        val purpose = _uiState.value.cropPurpose

        _uiState.value = _uiState.value.copy(
            cropMode = false,
            cropThumbnail = null
        )

        val pxLeft = (left * imgW).toInt()
        val pxTop = (top * imgH).toInt()
        val pxRight = (right * imgW).toInt()
        val pxBottom = (bottom * imgH).toInt()

        val actionLabel = when (purpose) {
            CropPurpose.ENHANCE -> "選択範囲を高画質化"
            CropPurpose.ZOOM -> "選択範囲をズーム"
        }

        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = "${actionLabel}: (${pxLeft},${pxTop})-(${pxRight},${pxBottom})"
            )
        )

        val processingLabel = when (purpose) {
            CropPurpose.ENHANCE -> "選択範囲を高画質化中..."
            CropPurpose.ZOOM -> "選択範囲を超解像中..."
        }
        val processingId = addProcessingMessage(processingLabel)

        viewModelScope.launch {
            try {
                val response = processRegionZoom(uri, left, top, right, bottom, imgW, imgH, purpose)
                replaceMessage(
                    processingId,
                    ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = response)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Region zoom failed", e)
                replaceMessage(
                    processingId,
                    ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = "エラーが発生しました: ${e.message}")
                )
            }
        }
    }

    // ── メッセージ送信 ──

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val imageUri = _uiState.value.selectedImageUri

        if (text.isBlank() && imageUri == null) return

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

        val displayText = text.ifBlank { "この画像を分析してください" }

        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = displayText,
                imagePath = imageUri?.toString()
            )
        )

        _uiState.value = _uiState.value.copy(
            inputText = "",
            selectedImageUri = null,
            selectedImagePath = null
        )

        // ── コマンド判定 ──

        val isOcrRequest = imageUri != null && (
                text.contains("読み取", ignoreCase = true) ||
                text.contains("テキスト", ignoreCase = true) ||
                text.contains("OCR", ignoreCase = true) ||
                text.contains("文字", ignoreCase = true) ||
                text.isBlank()
        )

        val isTableRequest = imageUri != null && (
                text.contains("表", ignoreCase = true) ||
                text.contains("テーブル", ignoreCase = true) ||
                text.contains("table", ignoreCase = true) ||
                text.contains("CSV", ignoreCase = true) ||
                text.contains("Markdown", ignoreCase = true)
        )

        val isEnhanceRequest = imageUri != null && (
                text.contains("鮮明", ignoreCase = true) ||
                text.contains("高画質", ignoreCase = true) ||
                text.contains("超解像", ignoreCase = true) ||
                text.contains("enhance", ignoreCase = true) ||
                text.contains("きれい", ignoreCase = true)
        )

        val isZoomRequest = imageUri != null && (
                text.contains("ズーム", ignoreCase = true) ||
                text.contains("拡大", ignoreCase = true) ||
                text.contains("zoom", ignoreCase = true)
        )

        val isFileRequest = imageUri != null && !isOcrRequest && !isTableRequest &&
                !isEnhanceRequest && !isZoomRequest && isFileUri(imageUri)

        // NexusSheets クエリ判定
        val isSheetsQuery = imageUri == null && (
                sheetsIndex.fileCount() > 0 &&
                        SHEETS_KEYWORDS.any { text.contains(it, ignoreCase = true) }
                ) || (imageUri == null && (
                text.contains("ファイル一覧") ||
                        text.contains("登録ファイル") ||
                        text.contains("全削除")
                ))

        val needsEngine = !isOcrRequest && !isTableRequest && !isEnhanceRequest &&
                !isZoomRequest && !isFileRequest && !isSheetsQuery

        if (needsEngine && !_uiState.value.isEngineReady) {
            addMessage(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    text = "エンジンがロードされていません。\n\n" +
                            "エンジンなしで利用可能: 画像OCR、表復元、高画質化、ファイル登録・検索・集計\n" +
                            "AI 連携回答にはエンジンのロードが必要です。"
                )
            )
            return
        }

        if (isZoomRequest) {
            enterCropMode(imageUri!!, CropPurpose.ZOOM)
            return
        }

        if (isEnhanceRequest) {
            enterCropMode(imageUri!!, CropPurpose.ENHANCE)
            return
        }

        val processingLabel = when {
            isSheetsQuery -> "データを検索中..."
            isTableRequest -> "表構造を復元中..."
            isOcrRequest -> "テキスト読み取り中..."
            isFileRequest -> "ファイルを解析中..."
            imageUri != null -> "画像を分析中..."
            else -> "考え中..."
        }

        val processingId = addProcessingMessage(processingLabel)

        viewModelScope.launch {
            try {
                val response = when {
                    isSheetsQuery -> sheetsQueryEngine.query(displayText)
                    isTableRequest -> processTableRequest(imageUri!!)
                    isOcrRequest -> processOcrRequest(imageUri!!)
                    isFileRequest -> processFileRequest(imageUri!!)
                    imageUri != null -> processImageMessage(imageUri, displayText)
                    else -> processTextMessage(displayText)
                }

                replaceMessage(
                    processingId,
                    ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = response)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Message processing failed", e)
                replaceMessage(
                    processingId,
                    ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = "エラーが発生しました: ${e.message}")
                )
            }
        }
    }

    private fun isFileUri(uri: Uri): Boolean {
        val mimeType = app.contentResolver.getType(uri) ?: ""
        val path = uri.toString().lowercase()
        return mimeType.contains("pdf") || mimeType.contains("csv") ||
                mimeType.contains("spreadsheetml") || mimeType.contains("xlsx") ||
                mimeType.startsWith("text/") ||
                path.endsWith(".pdf") || path.endsWith(".csv") || path.endsWith(".xlsx") ||
                path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".py") ||
                path.endsWith(".js") || path.endsWith(".ts") || path.endsWith(".json") ||
                path.endsWith(".xml") || path.endsWith(".yaml") || path.endsWith(".yml") ||
                path.endsWith(".md") || path.endsWith(".html") || path.endsWith(".css") ||
                path.endsWith(".sql") || path.endsWith(".sh") || path.endsWith(".c") ||
                path.endsWith(".cpp") || path.endsWith(".h") || path.endsWith(".swift") ||
                path.endsWith(".go") || path.endsWith(".rs") || path.endsWith(".dart")
    }

    private fun enterCropMode(uri: Uri, purpose: CropPurpose) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = app.applicationContext
            val imgSize = RegionDecoder.getImageSize(context, uri)
            val thumbnail = RegionDecoder.decodeThumbnail(context, uri, 800)

            if (imgSize == null || thumbnail == null) {
                addMessage(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        text = "画像を読み込めませんでした"
                    )
                )
                return@launch
            }

            val (w, h) = imgSize
            val actionText = when (purpose) {
                CropPurpose.ENHANCE -> "高画質化したい範囲"
                CropPurpose.ZOOM -> "拡大したい範囲"
            }
            addMessage(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    text = "画像上で${actionText}をドラッグで選択してください (${w}×${h})"
                )
            )

            _uiState.value = _uiState.value.copy(
                cropMode = true,
                cropPurpose = purpose,
                cropImageUri = uri,
                cropThumbnail = thumbnail,
                cropImageWidth = w,
                cropImageHeight = h
            )
        }
    }

    // ── 選択領域ズーム処理 ──

    private suspend fun processRegionZoom(
        uri: Uri,
        left: Float, top: Float, right: Float, bottom: Float,
        imgW: Int, imgH: Int,
        purpose: CropPurpose
    ): String = withContext(Dispatchers.IO) {
        val context = app.applicationContext

        val regionBitmap = RegionDecoder.decodeRegion(
            context, uri, left, top, right, bottom, maxOutputSide = 4096
        ) ?: return@withContext "選択領域のデコードに失敗しました"

        val safeCopy = if (regionBitmap.config != Bitmap.Config.ARGB_8888) {
            val copy = regionBitmap.copy(Bitmap.Config.ARGB_8888, false)
            regionBitmap.recycle()
            copy ?: return@withContext "コピーに失敗しました"
        } else {
            regionBitmap
        }

        val result = routeC?.process(safeCopy)

        if (result != null && result.success) {
            val savePrefix = when (purpose) {
                CropPurpose.ENHANCE -> "Enhanced"
                CropPurpose.ZOOM -> "Zoom"
            }
            val resultLabel = when (purpose) {
                CropPurpose.ENHANCE -> "高画質化完了"
                CropPurpose.ZOOM -> "デジタルズーム完了"
            }

            val savedUri = saveBitmapToGallery(result.bitmap, savePrefix)
            val responseText = buildString {
                appendLine("$resultLabel (${result.method})")
                appendLine("元画像: ${imgW}×${imgH}")
                appendLine("選択範囲: ${safeCopy.width}×${safeCopy.height}")
                appendLine("出力: ${result.bitmap.width}×${result.bitmap.height}")
                appendLine("処理時間: ${result.timeMs}ms")
                if (savedUri != null) {
                    appendLine("Pictures/NexusVision/ に保存しました")
                }
            }
            safeCopy.recycle()
            result.bitmap.recycle()
            responseText
        } else {
            safeCopy.recycle()
            "選択範囲の超解像に失敗しました"
        }
    }

    // ── テキスト処理 ──

    private suspend fun processTextMessage(text: String): String {
        val cached = l2Cache.lookup(text)
        if (cached != null) return cached.responseText

        val result = engineManager.inferText(text)
        return result.getOrElse { throw it }.also { response ->
            l2Cache.put(text, response)
        }
    }

    // ── 画像処理 ──

    private suspend fun processImageMessage(uri: Uri, text: String): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("画像を開けません")
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) throw IllegalStateException("画像のデコードに失敗しました")

            val pHash = PHashCalculator.calculate(originalBitmap)

            val cachedEntry = l1Cache.lookup(pHash, category = "classify")
            if (cachedEntry != null) {
                originalBitmap.recycle()
                return@withContext cachedEntry.resultText
            }

            val resizeResult = AdaptiveResizer.resize(originalBitmap)

            val tempFile = saveBitmapToTemp(context, resizeResult.bitmap)

            if (resizeResult.bitmap !== originalBitmap) {
                resizeResult.bitmap.recycle()
            }
            originalBitmap.recycle()

            val result = engineManager.inferImage(tempFile.absolutePath, text)
            val response = result.getOrElse {
                tempFile.delete()
                throw it
            }

            l1Cache.put(
                pHash = pHash,
                resultText = response,
                category = "classify",
                entropy = resizeResult.entropy
            )

            tempFile.delete()
            response
        }

    // ── OCR 処理 ──

    private suspend fun processOcrRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val sizeStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("画像を開けません")
            val (width, height) = DirectCrop100MP.getImageDimensions(sizeStream)
            sizeStream.close()

            val caseType = DocumentSharpener.detectCase(width, height)
            Log.d(TAG, "OCR: image=${width}x${height} → Case $caseType")

            val result = if (caseType == "A") {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("画像を開けません")
                val docResult = DocumentSharpener.processCaseA(stream, ocrEngine)
                stream.close()
                docResult
            } else {
                val coarseStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("画像を開けません")
                val cropStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("画像を開けません")
                val docResult = DocumentSharpener.processCaseB(coarseStream, cropStream, ocrEngine)
                coarseStream.close()
                cropStream.close()
                docResult
            }

            if (result.fullText.isBlank()) {
                "テキストを検出できませんでした。"
            } else {
                buildString {
                    appendLine("【テキスト読み取り結果】(${result.pipeline}, ${result.processingTimeMs}ms)")
                    appendLine()
                    append(result.fullText)
                }
            }
        }

    // ── 表復元処理 ──

    private suspend fun processTableRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("画像を開けません")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap == null) throw IllegalStateException("画像のデコードに失敗しました")

            val ocrResult = ocrEngine.recognize(bitmap)
            bitmap.recycle()

            if (ocrResult.fullText.isBlank()) {
                return@withContext "テキストを検出できませんでした。表が含まれる画像を選択してください。"
            }

            val tableResult = TableReconstructor.reconstruct(ocrResult)

            if (!tableResult.isTable) {
                return@withContext buildString {
                    appendLine("【テキスト読み取り結果】(表構造は検出されませんでした)")
                    appendLine()
                    append(ocrResult.fullText)
                }
            }

            buildString {
                appendLine("【表復元結果】${tableResult.rowCount} 行 × ${tableResult.colCount} 列 (${tableResult.processingTimeMs}ms)")
                appendLine()
                appendLine("CSV:")
                appendLine(tableResult.toCsv())
            }
        }

    // ── ファイル解析処理 ──

    private suspend fun processFileRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val filename = uri.lastPathSegment ?: "unknown"

            Log.i(TAG, "File request: mime=$mimeType, filename=$filename")

            when {
                // PDF
                mimeType.contains("pdf") || filename.endsWith(".pdf", ignoreCase = true) -> {
                    val result = PdfExtractor.extractFromUri(context, uri)
                    if (result.isSuccess) {
                        // インデックス登録
                        val pageTexts = result.pages.map { it.text }
                        sheetsIndex.addPdfText(pageTexts, filename)
                        buildString {
                            appendLine("「$filename」を登録しました。")
                            appendLine("${result.processedPages}/${result.totalPages} ページ (${result.processingTimeMs}ms)")
                            appendLine()
                            appendLine("チャットから検索・質問できます。")
                            appendLine("例: 「○○を検索」「○○について教えて」")
                        }
                    } else {
                        result.errorMessage ?: "PDF 解析失敗"
                    }
                }

                // CSV / Excel
                mimeType.contains("csv") || mimeType.contains("spreadsheetml") ||
                        mimeType.contains("xlsx") ||
                        filename.endsWith(".csv", ignoreCase = true) ||
                        filename.endsWith(".xlsx", ignoreCase = true) -> {
                    val result = ExcelCsvParser.parseFromUri(context, uri, mimeType)
                    if (result.isSuccess) {
                        // インデックス登録
                        val fileType = if (filename.endsWith(".xlsx", ignoreCase = true)) "xlsx" else "csv"
                        sheetsIndex.addParsedTable(result.rows, filename, fileType)
                        buildString {
                            appendLine("「$filename」を登録しました。")
                            appendLine("${result.rowCount} 行 × ${result.colCount} 列 (${result.processingTimeMs}ms)")
                            appendLine()
                            appendLine("チャットから検索・質問できます。")
                            appendLine("例: 「東京の売上」「売上の合計」「○○を検索」")
                        }
                    } else {
                        result.errorMessage ?: "ファイル解析失敗"
                    }
                }

                // ソースコード
                else -> {
                    val result = SourceCodeParser.parseFromUri(context, uri, mimeType)
                    if (result.isSuccess) {
                        result.toSummaryText()
                    } else {
                        result.errorMessage ?: "ファイル解析失敗"
                    }
                }
            }
        }

    // ── 超解像処理 ──

    private suspend fun processEnhanceRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val imgSize = RegionDecoder.getImageSize(context, uri)
                ?: return@withContext "画像サイズを取得できません"
            val (origW, origH) = imgSize

            val maxOutputPixels = 4096L * 4096
            val origPixels = origW.toLong() * origH

            val maxDecodeSide = if (origPixels <= maxOutputPixels) {
                maxOf(origW, origH)
            } else {
                4096
            }

            Log.i(TAG, "Enhance: original=${origW}x${origH}, maxDecode=$maxDecodeSide")

            val decoded = RegionDecoder.decodeSafe(context, uri, maxDecodeSide)
                ?: return@withContext "画像のデコードに失敗しました"

            val safeCopy = if (decoded.config != Bitmap.Config.ARGB_8888) {
                val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
                decoded.recycle()
                copy ?: return@withContext "コピーに失敗しました"
            } else {
                decoded
            }

            Log.i(TAG, "Decoded: ${safeCopy.width}x${safeCopy.height}")

            val result = routeC?.process(safeCopy)

            if (result != null && result.success) {
                val savedUri = saveBitmapToGallery(result.bitmap, "Enhanced")
                val responseText = buildString {
                    appendLine("高画質化完了 (${result.method})")
                    appendLine("元画像: ${origW}×${origH}")
                    appendLine("処理入力: ${safeCopy.width}×${safeCopy.height}")
                    appendLine("出力: ${result.bitmap.width}×${result.bitmap.height}")
                    appendLine("処理時間: ${result.timeMs}ms")
                    if (savedUri != null) {
                        appendLine("Pictures/NexusVision/ に保存しました")
                    }
                    if (origPixels > maxOutputPixels) {
                        appendLine()
                        appendLine("この画像は非常に大きいため、4096pxに制限して処理しました")
                        appendLine("部分拡大は「ズーム」で範囲選択すると元解像度で鮮明に処理できます")
                    }
                }
                safeCopy.recycle()
                result.bitmap.recycle()
                responseText
            } else {
                val savedUri = saveBitmapToGallery(safeCopy, "Original")
                safeCopy.recycle()
                buildString {
                    appendLine("高画質化に失敗しました")
                    appendLine("元画像をそのまま保存しました")
                    if (savedUri != null) appendLine("Pictures/NexusVision/ に保存済")
                }
            }
        }

    // ── ヘルパー ──

    private fun saveBitmapToGalleryStreaming(bitmap: Bitmap, prefix: String = "NEXUS", quality: Int = 95): Uri? {
        val context = app.applicationContext
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "${prefix}_${timestamp}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NexusVision")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

        try {
            resolver.openFileDescriptor(uri, "w")?.use { pfd ->
                val fd = pfd.detachFd()
                val ctx = RealEsrganBridge.nativeJpegBeginWrite(fd, bitmap.width, bitmap.height, quality)
                if (ctx != 0L) {
                    val batchRows = 64
                    for (row in 0 until bitmap.height step batchRows) {
                        val numRows = if (row + batchRows > bitmap.height) bitmap.height - row else batchRows
                        RealEsrganBridge.nativeJpegWriteRows(ctx, bitmap, row, numRows)
                    }
                    RealEsrganBridge.nativeJpegEndWrite(ctx)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            Log.i(TAG, "Streaming saved: $filename (uri=$uri)")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Streaming save failed: ${e.message}")
            resolver.delete(uri, null, null)
            return null
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, prefix: String = "NEXUS"): Uri? {
        if (bitmap.width * bitmap.height > 4096 * 4096) {
            Log.i(TAG, "Using streaming save for large image (${bitmap.width}x${bitmap.height})")
            return saveBitmapToGalleryStreaming(bitmap, prefix)
        }

        val context = app.applicationContext
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "${prefix}_${timestamp}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NexusVision")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                Log.i(TAG, "Saved to gallery: $filename (uri=$uri)")
                return uri
            } catch (e: IOException) {
                Log.e(TAG, "Failed to save to gallery: ${e.message}")
                resolver.delete(uri, null, null)
            }
        }
        return null
    }

    private fun saveBitmapToTemp(context: Context, bitmap: Bitmap): File {
        val tempFile = File(context.cacheDir, "nexus_temp_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return tempFile
    }

    private fun addMessage(message: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + message
        )
    }

    fun startBatchEnhance(uris: List<Uri>) {
        if (uris.isEmpty()) return

        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = "バッチ高画質化: ${uris.size} 枚"
            )
        )

        val processingId = addProcessingMessage("バッチ高画質化を開始します (${uris.size} 枚)...")
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

    private fun addSystemMessage(text: String) {
        addMessage(ChatMessage(role = ChatMessage.Role.SYSTEM, text = text))
    }

    private fun addProcessingMessage(label: String): String {
        val message = ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            text = "",
            isProcessing = true,
            processingLabel = label
        )
        addMessage(message)
        return message.id
    }

    private fun replaceMessage(id: String, newMessage: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map {
                if (it.id == id) newMessage else it
            }
        )
    }
}
```

### ファイル 8: `app/build.gradle.kts`（変更不要）

既存の ObjectBox 5.3.0 と Kotlin 依存で十分です。`org.json.JSONArray` は Android SDK に標準搭載のため追加依存は不要です。`kotlinx-serialization` も不要に変更しました（`org.json.JSONArray` を使用）。

## ObjectBox 重要注意事項

`IndexedFile` と `IndexedRow` は `com.nexus.vision.sheets` パッケージに配置されますが、既存の `MyObjectBox` は `com.nexus.vision.cache` パッケージに生成されています。ObjectBox プラグインはプロジェクト全体のすべての `@Entity` アノテーション付きクラスを自動検出し、`MyObjectBox` を再生成します。そのため:

1. `IndexedFile.kt` と `IndexedRow.kt` を配置後、**Build > Rebuild Project** を実行すると `MyObjectBox` が再生成され、新エンティティが含まれます。
2. `NexusApplication.kt` の `MyObjectBox.builder()` の import パスは変更不要（既存の `com.nexus.vision.cache.MyObjectBox` のまま）。ObjectBox プラグインが自動で全エンティティを含むクラスを生成します。
3. アプリを初回起動すると ObjectBox がスキーママイグレーションを自動実行し、新しい Box（テーブル）が作成されます。既存のキャッシュデータは影響を受けません。

## ビルド・テスト手順

1. 以下のファイルを作成・配置:
   - `app/src/main/java/com/nexus/vision/sheets/IndexedFile.kt`（新規）
   - `app/src/main/java/com/nexus/vision/sheets/IndexedRow.kt`（新規）
   - `app/src/main/java/com/nexus/vision/sheets/NexusSheetsIndex.kt`（新規）
   - `app/src/main/java/com/nexus/vision/sheets/SheetsQueryEngine.kt`（新規）

2. 以下のファイルを完全置換:
   - `app/src/main/java/com/nexus/vision/NexusApplication.kt`
   - `app/src/main/java/com/nexus/vision/os/ShareReceiver.kt`
   - `app/src/main/java/com/nexus/vision/ui/MainViewModel.kt`

3. `build.gradle.kts` は変更不要。

4. Android Studio で **Build > Rebuild Project**（ObjectBox コード生成のため Rebuild が必要）。

5. テスト項目:
   - ファイルマネージャから CSV を共有 → 「○○.csv を登録しました（N行×M列）」と表示
   - ファイルマネージャから .xlsx を共有 → 同上
   - ファイルマネージャから PDF を共有 → 「○○.pdf を登録しました（Nページ）」と表示
   - チャットで「ファイル一覧」→ 登録ファイルリスト表示
   - チャットで「東京の売上」→ 行列クロス検索結果
   - チャットで「売上の合計」→ 列の合計値
   - チャットで「○○を検索」→ キーワード検索結果
   - チャットで「2023 と 2024 を比較」→ 複数ファイル横断比較
   - チャットで「○○.csv 削除」→ ファイル削除
   - チャットで「全削除」→ 全ファイル削除
   - エンジンロード後、検索結果に対して AI が回答を生成
   - .kt ファイル共有 → 構造解析（インデックス対象外、従来通り）
   - 画像共有 → OCR（従来通り）

## 変更しないファイル（維持）

- `ExcelCsvParser.kt` — 既存のまま（`toTextTable()` は残すが使わない）
- `PdfExtractor.kt` — 既存のまま
- `SourceCodeParser.kt` — 既存のまま
- `TableReconstructor.kt` — 既存のまま
- `MlKitOcrEngine.kt` — 既存のまま
- `RouteCProcessor.kt` — 既存のまま（EASS 無効化版）
- `AndroidManifest.xml` — 既存のまま
- `MainScreen.kt` — 変更不要（MainViewModel が処理を振り分けるため UI 変更なし）

---

