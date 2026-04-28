// ファイルパス: app/src/main/java/com/nexus/vision/ocr/TableReconstructor.kt
package com.nexus.vision.ocr

import android.graphics.Rect
import android.util.Log
import kotlin.math.abs

/**
 * OCR 結果からテーブル構造を復元する
 *
 * Phase 9: OCR + 表復元
 */
object TableReconstructor {

    private const val TAG = "TableRecon"

    fun reconstruct(
        ocrResult: OcrResult,
        yTolerance: Int = 0,
        xTolerance: Int = 0
    ): TableResult {
        val startTime = System.currentTimeMillis()

        val cells = mutableListOf<CellInfo>()
        for (block in ocrResult.blocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    cells.add(CellInfo(element.text, box))
                }
            }
        }

        if (cells.isEmpty()) {
            return TableResult.empty()
        }

        val autoYTolerance = if (yTolerance > 0) yTolerance else estimateYTolerance(cells)
        val autoXTolerance = if (xTolerance > 0) xTolerance else estimateXTolerance(cells)

        Log.d(TAG, "Cells: ${cells.size}, yTol=$autoYTolerance, xTol=$autoXTolerance")

        val rowGroups = clusterByY(cells, autoYTolerance)

        for (group in rowGroups) {
            group.sortBy { it.box.left }
        }

        val columnPositions = estimateColumnPositions(rowGroups, autoXTolerance)
        val table = buildTable(rowGroups, columnPositions, autoXTolerance)

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Table: ${table.size} rows × ${columnPositions.size} cols, ${elapsed}ms")

        return TableResult(
            rows = table,
            rowCount = table.size,
            colCount = columnPositions.size,
            processingTimeMs = elapsed
        )
    }

    private fun estimateYTolerance(cells: List<CellInfo>): Int {
        val heights = cells.map { it.box.height() }.sorted()
        val median = if (heights.isNotEmpty()) heights[heights.size / 2] else 20
        return maxOf(median / 2, 5)
    }

    private fun estimateXTolerance(cells: List<CellInfo>): Int {
        val widths = cells.map { it.box.width() }.sorted()
        val median = if (widths.isNotEmpty()) widths[widths.size / 2] else 40
        return maxOf(median / 2, 10)
    }

    private fun clusterByY(cells: List<CellInfo>, tolerance: Int): List<MutableList<CellInfo>> {
        val sorted = cells.sortedBy { it.box.centerY() }
        val groups = mutableListOf<MutableList<CellInfo>>()

        for (cell in sorted) {
            val centerY = cell.box.centerY()
            val matchedGroup = groups.find { group ->
                val groupCenterY = group.map { it.box.centerY() }.average()
                abs(centerY - groupCenterY) <= tolerance
            }

            if (matchedGroup != null) {
                matchedGroup.add(cell)
            } else {
                groups.add(mutableListOf(cell))
            }
        }

        return groups
    }

    private fun estimateColumnPositions(
        rowGroups: List<List<CellInfo>>,
        tolerance: Int
    ): List<Int> {
        val allLeftX = rowGroups.flatMap { group ->
            group.map { it.box.left }
        }.sorted()

        val positions = mutableListOf<Int>()
        for (x in allLeftX) {
            val nearest = positions.find { abs(it - x) <= tolerance }
            if (nearest == null) {
                positions.add(x)
            }
        }

        return positions.sorted()
    }

    private fun buildTable(
        rowGroups: List<List<CellInfo>>,
        columnPositions: List<Int>,
        tolerance: Int
    ): List<List<String>> {
        return rowGroups.map { group ->
            val row = MutableList(columnPositions.size) { "" }

            for (cell in group) {
                var bestCol = 0
                var bestDist = Int.MAX_VALUE
                for ((colIdx, colX) in columnPositions.withIndex()) {
                    val dist = abs(cell.box.left - colX)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestCol = colIdx
                    }
                }

                if (row[bestCol].isNotEmpty()) {
                    row[bestCol] = row[bestCol] + " " + cell.text
                } else {
                    row[bestCol] = cell.text
                }
            }

            row.toList()
        }
    }

    data class CellInfo(
        val text: String,
        val box: Rect
    )

    data class TableResult(
        val rows: List<List<String>> = emptyList(),
        val rowCount: Int = 0,
        val colCount: Int = 0,
        val processingTimeMs: Long = 0,
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = rows.isNotEmpty() && errorMessage == null
        val isTable: Boolean get() = rowCount >= 2 && colCount >= 2

        fun toCsv(): String {
            return rows.joinToString("\n") { row ->
                row.joinToString(",") { cell ->
                    if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
                        "\"${cell.replace("\"", "\"\"")}\""
                    } else {
                        cell
                    }
                }
            }
        }

        /**
         * プレーンテキストのテーブル（等幅フォント前提で桁揃え）
         */
        fun toTextTable(): String {
            if (rows.isEmpty()) return ""

            val maxCols = colCount

            // 各列の最大幅を計算（全角文字は幅2）
            val colWidths = IntArray(maxCols)
            for (row in rows) {
                for ((i, cell) in row.withIndex()) {
                    if (i < maxCols) {
                        colWidths[i] = maxOf(colWidths[i], displayWidth(cell))
                    }
                }
            }
            for (i in colWidths.indices) {
                colWidths[i] = maxOf(colWidths[i], 3)
            }

            val sb = StringBuilder()

            fun appendSeparator() {
                sb.append("+")
                for (w in colWidths) {
                    sb.append("-".repeat(w + 2))
                    sb.append("+")
                }
                sb.appendLine()
            }

            // ヘッダー
            appendSeparator()
            sb.append("|")
            val header = rows.first()
            for (i in 0 until maxCols) {
                val cell = header.getOrElse(i) { "" }
                sb.append(" ")
                sb.append(padCell(cell, colWidths[i]))
                sb.append(" |")
            }
            sb.appendLine()
            appendSeparator()

            // データ行
            for (rowIdx in 1 until rows.size) {
                val row = rows[rowIdx]
                sb.append("|")
                for (i in 0 until maxCols) {
                    val cell = row.getOrElse(i) { "" }
                    sb.append(" ")
                    sb.append(padCell(cell, colWidths[i]))
                    sb.append(" |")
                }
                sb.appendLine()
            }
            appendSeparator()

            return sb.toString()
        }

        fun toMarkdown(): String {
            if (rows.isEmpty()) return ""

            val sb = StringBuilder()

            sb.append("| ")
            rows.first().forEach { cell -> sb.append("$cell | ") }
            sb.appendLine()

            sb.append("| ")
            repeat(colCount) { sb.append("--- | ") }
            sb.appendLine()

            for (i in 1 until rows.size) {
                sb.append("| ")
                rows[i].forEach { cell -> sb.append("$cell | ") }
                sb.appendLine()
            }

            return sb.toString()
        }

        private fun displayWidth(text: String): Int {
            var width = 0
            for (ch in text) {
                width += if (ch.code > 0x7F) 2 else 1
            }
            return width
        }

        private fun padCell(text: String, targetWidth: Int): String {
            val currentWidth = displayWidth(text)
            val padding = targetWidth - currentWidth
            return if (padding > 0) text + " ".repeat(padding) else text
        }

        companion object {
            fun empty() = TableResult(errorMessage = "テーブル構造を検出できませんでした")
        }
    }
}
