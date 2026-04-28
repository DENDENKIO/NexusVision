// ファイルパス: app/src/main/java/com/nexus/vision/sheets/NexusSheetsIndex.kt
package com.nexus.vision.sheets

import android.util.Log
import io.objectbox.Box
import io.objectbox.BoxStore
import org.json.JSONArray

/**
 * NexusSheets インデックス管理
 *
 * Phase 8.5: NexusSheets
 * Phase 8.6: 高度検索（列フィルタ、複数条件AND、列値行抽出）
 */
class NexusSheetsIndex(boxStore: BoxStore) {

    companion object {
        private const val TAG = "NexusSheetsIndex"
        private const val MAX_SEARCH_RESULTS = 50
    }

    private val fileBox: Box<IndexedFile> = boxStore.boxFor(IndexedFile::class.java)
    private val rowBox: Box<IndexedRow> = boxStore.boxFor(IndexedRow::class.java)

    // ── ファイル登録 ──

    fun addParsedTable(
        rows: List<List<String>>,
        fileName: String,
        fileType: String
    ): Long {
        if (rows.isEmpty()) return -1

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

    fun addPdfText(pages: List<String>, fileName: String): Long {
        removeByFileName(fileName)

        val allLines = mutableListOf<List<String>>()
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

    fun search(query: String): List<SearchResult> {
        val keywords = query.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (keywords.isEmpty()) return emptyList()

        val firstKeyword = keywords.first()
        val candidates = rowBox.query(
            IndexedRow_.searchText.contains(firstKeyword, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
        ).build().find()

        val filtered = if (keywords.size > 1) {
            candidates.filter { row ->
                keywords.all { kw -> row.searchText.contains(kw, ignoreCase = true) }
            }
        } else {
            candidates
        }

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

    // ── 列名指定フィルタ ──

    /**
     * 特定の列に特定の値を含む行を抽出する。
     * 例: colName="地域", value="東京" → 地域列に「東京」を含む全行を返す
     * targetFileId=0 なら全ファイル横断
     */
    fun filterByColumn(
        colName: String,
        value: String,
        targetFileId: Long = 0
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        val files = if (targetFileId > 0) {
            listOfNotNull(fileBox.get(targetFileId))
        } else {
            fileBox.all
        }

        for (file in files) {
            val headers = parseCellsJson(file.headerJson)
            val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
            if (colIndex < 0) continue

            // まず searchText で value を含む行を絞り込み
            val candidates = rowBox.query(
                IndexedRow_.fileId.equal(file.id)
                    .and(IndexedRow_.searchText.contains(value, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE))
            ).build().find()

            for (row in candidates) {
                if (row.rowIndex == 0) continue // ヘッダースキップ
                val cells = parseCellsJson(row.cellsJson)
                val cellValue = cells.getOrElse(colIndex) { "" }
                // 指定列に value が含まれるか厳密チェック
                if (cellValue.contains(value, ignoreCase = true)) {
                    results.add(
                        SearchResult(
                            fileName = file.fileName,
                            fileId = row.fileId,
                            rowIndex = row.rowIndex,
                            cells = cells,
                            headers = headers
                        )
                    )
                }
            }
        }

        return results.take(MAX_SEARCH_RESULTS)
    }

    /**
     * 複数条件 AND フィルタ:
     * 例: conditions = [("地域","東京"), ("年度","2024")]
     * → 地域列に「東京」AND 年度列に「2024」を含む行
     */
    fun multiColumnFilter(
        conditions: List<Pair<String, String>>,
        targetFileId: Long = 0
    ): List<SearchResult> {
        if (conditions.isEmpty()) return emptyList()

        val files = if (targetFileId > 0) {
            listOfNotNull(fileBox.get(targetFileId))
        } else {
            fileBox.all
        }

        val results = mutableListOf<SearchResult>()

        for (file in files) {
            val headers = parseCellsJson(file.headerJson)

            // 各条件の列インデックスを事前に解決
            val resolvedConditions = conditions.mapNotNull { (colName, value) ->
                val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
                if (colIndex >= 0) Triple(colIndex, colName, value) else null
            }

            if (resolvedConditions.size != conditions.size) continue // 全列が見つからなければスキップ

            // 最初の条件値で DB 絞り込み
            val firstValue = resolvedConditions.first().third
            val candidates = rowBox.query(
                IndexedRow_.fileId.equal(file.id)
                    .and(IndexedRow_.searchText.contains(firstValue, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE))
            ).build().find()

            for (row in candidates) {
                if (row.rowIndex == 0) continue
                val cells = parseCellsJson(row.cellsJson)

                // 全条件を満たすかチェック
                val allMatch = resolvedConditions.all { (colIndex, _, value) ->
                    val cellValue = cells.getOrElse(colIndex) { "" }
                    cellValue.contains(value, ignoreCase = true)
                }

                if (allMatch) {
                    results.add(
                        SearchResult(
                            fileName = file.fileName,
                            fileId = row.fileId,
                            rowIndex = row.rowIndex,
                            cells = cells,
                            headers = headers
                        )
                    )
                }
            }
        }

        return results.take(MAX_SEARCH_RESULTS)
    }

    /**
     * 特定列の値でフィルタし、別の列の値のみ抽出する。
     * 例: filterCol="地域", filterValue="東京", extractCol="売上"
     * → 地域が東京の行から売上列の値のみ返す
     */
    fun filterAndExtractColumn(
        filterCol: String,
        filterValue: String,
        extractCol: String,
        targetFileId: Long = 0
    ): List<ExtractResult> {
        val results = mutableListOf<ExtractResult>()

        val files = if (targetFileId > 0) {
            listOfNotNull(fileBox.get(targetFileId))
        } else {
            fileBox.all
        }

        for (file in files) {
            val headers = parseCellsJson(file.headerJson)
            val filterColIndex = headers.indexOfFirst { it.contains(filterCol, ignoreCase = true) }
            val extractColIndex = headers.indexOfFirst { it.contains(extractCol, ignoreCase = true) }
            if (filterColIndex < 0 || extractColIndex < 0) continue

            val candidates = rowBox.query(
                IndexedRow_.fileId.equal(file.id)
                    .and(IndexedRow_.searchText.contains(filterValue, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE))
            ).build().find()

            for (row in candidates) {
                if (row.rowIndex == 0) continue
                val cells = parseCellsJson(row.cellsJson)
                val filterCellValue = cells.getOrElse(filterColIndex) { "" }
                if (filterCellValue.contains(filterValue, ignoreCase = true)) {
                    val extractedValue = cells.getOrElse(extractColIndex) { "" }
                    results.add(
                        ExtractResult(
                            fileName = file.fileName,
                            rowIndex = row.rowIndex,
                            filterCol = headers[filterColIndex],
                            filterValue = filterCellValue,
                            extractCol = headers[extractColIndex],
                            extractedValue = extractedValue,
                            fullRow = cells,
                            headers = headers
                        )
                    )
                }
            }
        }

        return results.take(MAX_SEARCH_RESULTS)
    }

    // ── 行列クロス検索 ──

    fun crossLookup(
        rowKeyword: String,
        colName: String,
        targetFileId: Long = 0
    ): List<CrossResult> {
        val results = mutableListOf<CrossResult>()

        val files = if (targetFileId > 0) {
            listOfNotNull(fileBox.get(targetFileId))
        } else {
            fileBox.all
        }

        for (file in files) {
            val headers = parseCellsJson(file.headerJson)
            val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
            if (colIndex < 0) continue

            val matchingRows = rowBox.query(
                IndexedRow_.fileId.equal(file.id)
                    .and(IndexedRow_.searchText.contains(rowKeyword, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE))
            ).build().find()

            for (row in matchingRows) {
                if (row.rowIndex == 0) continue
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

    // ── 列取得・集計 ──

    fun getColumn(fileId: Long, colName: String): ColumnResult? {
        val file = fileBox.get(fileId) ?: return null
        val headers = parseCellsJson(file.headerJson)
        val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
        if (colIndex < 0) return null

        val rows = rowBox.query(IndexedRow_.fileId.equal(fileId))
            .order(IndexedRow_.rowIndex)
            .build().find()

        val values = rows
            .filter { it.rowIndex > 0 }
            .map { row ->
                val cells = parseCellsJson(row.cellsJson)
                cells.getOrElse(colIndex) { "" }
            }

        return ColumnResult(fileName = file.fileName, colName = headers[colIndex], values = values)
    }

    fun getRows(fileId: Long, fromRow: Int = 0, toRow: Int = Int.MAX_VALUE): List<List<String>> {
        val rows = rowBox.query(IndexedRow_.fileId.equal(fileId))
            .order(IndexedRow_.rowIndex)
            .build().find()
        return rows.filter { it.rowIndex in fromRow..toRow }.map { parseCellsJson(it.cellsJson) }
    }

    fun aggregateColumn(fileId: Long, colName: String): AggregateResult? {
        val colResult = getColumn(fileId, colName) ?: return null
        val numbers = colResult.values.mapNotNull { it.replace(",", "").toDoubleOrNull() }
        if (numbers.isEmpty()) return AggregateResult(
            colName = colResult.colName, fileName = colResult.fileName,
            count = 0, sum = 0.0, avg = 0.0, min = 0.0, max = 0.0,
            message = "「${colResult.colName}」列に数値データがありません"
        )
        return AggregateResult(
            colName = colResult.colName, fileName = colResult.fileName,
            count = numbers.size, sum = numbers.sum(), avg = numbers.average(),
            min = numbers.min(), max = numbers.max()
        )
    }

    fun aggregateColumnAllFiles(colName: String): List<AggregateResult> {
        return fileBox.all.mapNotNull { aggregateColumn(it.id, colName) }.filter { it.count > 0 }
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
        val rows = rowBox.query(IndexedRow_.fileId.equal(fileId)).build().find()
        rowBox.remove(rows)
        fileBox.remove(fileId)
        Log.i(TAG, "Removed file id=$fileId (${rows.size} rows)")
    }

    fun removeByFileName(fileName: String) {
        val existing = fileBox.query(
            IndexedFile_.fileName.equal(fileName, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
        ).build().find()
        for (f in existing) { removeFile(f.id) }
    }

    fun removeAll() { rowBox.removeAll(); fileBox.removeAll(); Log.i(TAG, "All removed") }
    fun fileCount(): Long = fileBox.count()
    fun totalRowCount(): Long = rowBox.count()

    /**
     * 全ファイルのヘッダー一覧を返す（検索クエリ解析用）
     */
    fun getAllHeaders(): Map<String, List<String>> {
        return fileBox.all.associate { file ->
            file.fileName to parseCellsJson(file.headerJson)
        }
    }

    // ── ヘルパー ──

    fun parseCellsJson(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    // ── データクラス ──

    data class SearchResult(
        val fileName: String, val fileId: Long, val rowIndex: Int,
        val cells: List<String>, val headers: List<String>
    ) {
        fun toReadableText(): String {
            return cells.mapIndexed { i, cell ->
                val header = headers.getOrElse(i) { "列${i + 1}" }
                "$header: $cell"
            }.joinToString(", ")
        }
    }

    data class CrossResult(
        val fileName: String, val rowKeyword: String, val colName: String,
        val value: String, val rowIndex: Int,
        val fullRow: List<String>, val headers: List<String>
    )

    data class ColumnResult(val fileName: String, val colName: String, val values: List<String>)

    data class ExtractResult(
        val fileName: String, val rowIndex: Int,
        val filterCol: String, val filterValue: String,
        val extractCol: String, val extractedValue: String,
        val fullRow: List<String>, val headers: List<String>
    )

    data class AggregateResult(
        val colName: String, val fileName: String,
        val count: Int, val sum: Double, val avg: Double, val min: Double, val max: Double,
        val message: String? = null
    ) {
        fun toText(): String {
            if (message != null) return message
            return "$fileName の「$colName」: 件数=$count, 合計=${formatNum(sum)}, 平均=${formatNum(avg)}, 最小=${formatNum(min)}, 最大=${formatNum(max)}"
        }
        private fun formatNum(v: Double): String =
            if (v == v.toLong().toDouble()) "%,.0f".format(v) else "%,.2f".format(v)
    }
}
