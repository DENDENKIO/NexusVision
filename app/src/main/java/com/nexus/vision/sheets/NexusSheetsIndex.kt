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
