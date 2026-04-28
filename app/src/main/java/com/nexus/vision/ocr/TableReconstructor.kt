// ファイルパス: app/src/main/java/com/nexus/vision/ocr/TableReconstructor.kt
package com.nexus.vision.ocr

import android.graphics.Rect
import android.util.Log
import kotlin.math.abs

/**
 * OCR 結果からテーブル構造を復元する
 *
 * ML Kit の TextBlock / TextLine から座標を取得し、
 * Y 座標のクラスタリングで行、X 座標のクラスタリングで列を推定して
 * 二次元テーブルに変換する。
 *
 * 出力: CSV テキスト or Markdown テーブル
 *
 * Phase 9: OCR + 表復元
 */
object TableReconstructor {

    private const val TAG = "TableRecon"

    /**
     * OCR 結果から表構造を復元する
     *
     * @param ocrResult  ML Kit OCR 結果
     * @param yTolerance Y 座標の行グループ化しきい値 (px)
     * @param xTolerance X 座標の列グループ化しきい値 (px)
     * @return テーブル復元結果
     */
    fun reconstruct(
        ocrResult: OcrResult,
        yTolerance: Int = 0,
        xTolerance: Int = 0
    ): TableResult {
        val startTime = System.currentTimeMillis()

        // 1. 全エレメントを座標付きで収集
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

        // 2. Y 座標しきい値の自動計算
        val autoYTolerance = if (yTolerance > 0) yTolerance else {
            estimateYTolerance(cells)
        }
        val autoXTolerance = if (xTolerance > 0) xTolerance else {
            estimateXTolerance(cells)
        }

        Log.d(TAG, "Cells: ${cells.size}, yTol=$autoYTolerance, xTol=$autoXTolerance")

        // 3. Y 座標でクラスタリング → 行グループ
        val rowGroups = clusterByY(cells, autoYTolerance)

        // 4. 各行内を X 座標でソート
        for (group in rowGroups) {
            group.sortBy { it.box.left }
        }

        // 5. X 座標でクラスタリング → 列位置を推定
        val columnPositions = estimateColumnPositions(rowGroups, autoXTolerance)

        // 6. 二次元テーブルに配置
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

    /**
     * Y 座標のしきい値を自動推定する
     * セル高さの中央値の半分を使用
     */
    private fun estimateYTolerance(cells: List<CellInfo>): Int {
        val heights = cells.map { it.box.height() }.sorted()
        val median = if (heights.isNotEmpty()) heights[heights.size / 2] else 20
        return maxOf(median / 2, 5)
    }

    /**
     * X 座標のしきい値を自動推定する
     * セル幅の中央値の半分を使用
     */
    private fun estimateXTolerance(cells: List<CellInfo>): Int {
        val widths = cells.map { it.box.width() }.sorted()
        val median = if (widths.isNotEmpty()) widths[widths.size / 2] else 40
        return maxOf(median / 2, 10)
    }

    /**
     * Y 座標（中心）でクラスタリングして行グループを作る
     */
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

    /**
     * 全行のセルの X 座標（左端）から列位置を推定する
     */
    private fun estimateColumnPositions(
        rowGroups: List<List<CellInfo>>,
        tolerance: Int
    ): List<Int> {
        // 全セルの左端座標を収集
        val allLeftX = rowGroups.flatMap { group ->
            group.map { it.box.left }
        }.sorted()

        // X 座標をクラスタリング
        val positions = mutableListOf<Int>()
        for (x in allLeftX) {
            val nearest = positions.find { abs(it - x) <= tolerance }
            if (nearest == null) {
                positions.add(x)
            }
        }

        return positions.sorted()
    }

    /**
     * 二次元テーブルに配置する
     */
    private fun buildTable(
        rowGroups: List<List<CellInfo>>,
        columnPositions: List<Int>,
        tolerance: Int
    ): List<List<String>> {
        return rowGroups.map { group ->
            val row = MutableList(columnPositions.size) { "" }

            for (cell in group) {
                // 最も近い列位置を探す
                var bestCol = 0
                var bestDist = Int.MAX_VALUE
                for ((colIdx, colX) in columnPositions.withIndex()) {
                    val dist = abs(cell.box.left - colX)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestCol = colIdx
                    }
                }

                // 同じセルに既にテキストがあれば結合
                if (row[bestCol].isNotEmpty()) {
                    row[bestCol] = row[bestCol] + " " + cell.text
                } else {
                    row[bestCol] = cell.text
                }
            }

            row.toList()
        }
    }

    // ── データクラス ──

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

        /**
         * CSV テキストに変換
         */
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
         * Markdown テーブルに変換
         */
        fun toMarkdown(): String {
            if (rows.isEmpty()) return ""

            val sb = StringBuilder()

            // ヘッダー行
            sb.append("| ")
            rows.first().forEach { cell -> sb.append("$cell | ") }
            sb.appendLine()

            // 区切り行
            sb.append("| ")
            repeat(colCount) { sb.append("--- | ") }
            sb.appendLine()

            // データ行
            for (i in 1 until rows.size) {
                sb.append("| ")
                rows[i].forEach { cell -> sb.append("$cell | ") }
                sb.appendLine()
            }

            return sb.toString()
        }

        companion object {
            fun empty() = TableResult(errorMessage = "テーブル構造を検出できませんでした")
        }
    }
}
