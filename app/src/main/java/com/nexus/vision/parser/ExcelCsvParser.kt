// ファイルパス: app/src/main/java/com/nexus/vision/parser/ExcelCsvParser.kt
package com.nexus.vision.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * CSV / Excel (.xlsx) パーサー
 *
 * CSV: Kotlin 標準ライブラリのみで RFC 4180 準拠パース
 * XLSX: ZIP 展開 → xl/sharedStrings.xml + xl/worksheets/sheet1.xml を XML パース
 *       Apache POI 不使用（サイズ・メソッド数削減のため）
 *
 * 出力: Markdown テーブル or JSON 配列
 *
 * Phase 8: ファイル解析
 */
object ExcelCsvParser {

    private const val TAG = "ExcelCsvParser"
    private const val MAX_ROWS = 500       // 読み取り上限行数
    private const val MAX_COLS = 50        // 読み取り上限列数
    private const val MAX_CELL_LENGTH = 200 // セル内テキスト上限

    /**
     * Uri からファイル種別を判定して解析する
     */
    fun parseFromUri(context: Context, uri: Uri, mimeType: String? = null): ParseResult {
        val type = mimeType ?: context.contentResolver.getType(uri) ?: ""

        return when {
            type.contains("csv") || type.contains("comma-separated") ||
                    uri.toString().endsWith(".csv", ignoreCase = true) -> {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return ParseResult.error("ファイルを開けません")
                stream.use { parseCsv(it) }
            }
            type.contains("spreadsheetml") || type.contains("xlsx") ||
                    uri.toString().endsWith(".xlsx", ignoreCase = true) -> {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return ParseResult.error("ファイルを開けません")
                stream.use { parseXlsx(it) }
            }
            type.contains("excel") || type.contains("xls") -> {
                ParseResult.error("旧形式 .xls は非対応です。.xlsx または .csv に変換してください。")
            }
            else -> {
                ParseResult.error("非対応ファイル形式: $type")
            }
        }
    }

    /**
     * CSV をパースする
     */
    fun parseCsv(inputStream: InputStream): ParseResult {
        val startTime = System.currentTimeMillis()

        try {
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val rows = mutableListOf<List<String>>()

            var line: String?
            var rowCount = 0

            while (reader.readLine().also { line = it } != null && rowCount < MAX_ROWS) {
                val cells = parseCsvLine(line!!)
                    .take(MAX_COLS)
                    .map { it.take(MAX_CELL_LENGTH) }
                rows.add(cells)
                rowCount++
            }
            reader.close()

            if (rows.isEmpty()) {
                return ParseResult.error("CSV が空です")
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "CSV parsed: ${rows.size} rows, ${elapsed}ms")

            return ParseResult(
                rows = rows,
                format = "CSV",
                rowCount = rows.size,
                colCount = rows.maxOf { it.size },
                processingTimeMs = elapsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "CSV parse error: ${e.message}")
            return ParseResult.error("CSV 解析エラー: ${e.message}")
        }
    }

    /**
     * CSV 1行をパースする（RFC 4180 準拠: ダブルクォート対応）
     */
    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> inQuotes = true
                ch == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++ // skip escaped quote
                    } else {
                        inQuotes = false
                    }
                }
                ch == ',' && !inQuotes -> {
                    cells.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        cells.add(current.toString().trim())
        return cells
    }

    /**
     * .xlsx を ZIP + XML としてパースする
     */
    fun parseXlsx(inputStream: InputStream): ParseResult {
        val startTime = System.currentTimeMillis()

        try {
            val zipStream = ZipInputStream(inputStream)
            var sharedStrings = listOf<String>()
            var sheetData = listOf<List<String>>()

            var entry = zipStream.nextEntry
            while (entry != null) {
                when {
                    entry.name == "xl/sharedStrings.xml" -> {
                        sharedStrings = parseSharedStrings(zipStream)
                    }
                    entry.name == "xl/worksheets/sheet1.xml" -> {
                        sheetData = parseSheet(zipStream, sharedStrings)
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()

            // sharedStrings が sheet より後に来る場合の再パース
            // （通常は sharedStrings が先だが念のため）
            if (sheetData.isEmpty() && sharedStrings.isNotEmpty()) {
                Log.w(TAG, "Sheet data empty, shared strings found — order issue?")
            }

            if (sheetData.isEmpty()) {
                return ParseResult.error("XLSX のシートデータが空です")
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "XLSX parsed: ${sheetData.size} rows, ${elapsed}ms")

            return ParseResult(
                rows = sheetData,
                format = "XLSX",
                rowCount = sheetData.size,
                colCount = sheetData.maxOfOrNull { it.size } ?: 0,
                processingTimeMs = elapsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "XLSX parse error: ${e.message}")
            return ParseResult.error("XLSX 解析エラー: ${e.message}")
        }
    }

    /**
     * xl/sharedStrings.xml から共有文字列テーブルを読む
     */
    private fun parseSharedStrings(stream: InputStream): List<String> {
        val strings = mutableListOf<String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")

        var inT = false
        val current = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        inT = true
                        current.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inT) current.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "t") {
                        inT = false
                    } else if (parser.name == "si") {
                        strings.add(current.toString())
                        current.clear()
                    }
                }
            }
            eventType = parser.next()
        }
        return strings
    }

    /**
     * xl/worksheets/sheet1.xml からセルデータを読む
     */
    private fun parseSheet(stream: InputStream, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")

        var currentRow = mutableListOf<String>()
        var cellType: String? = null
        var inV = false
        val cellValue = StringBuilder()
        var rowCount = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT && rowCount < MAX_ROWS) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> currentRow = mutableListOf()
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t")
                            cellValue.clear()
                        }
                        "v" -> inV = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inV) cellValue.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v" -> inV = false
                        "c" -> {
                            val value = cellValue.toString()
                            val resolved = if (cellType == "s") {
                                // 共有文字列参照
                                val idx = value.toIntOrNull()
                                if (idx != null && idx < sharedStrings.size) {
                                    sharedStrings[idx]
                                } else value
                            } else {
                                value
                            }
                            currentRow.add(resolved.take(MAX_CELL_LENGTH))
                        }
                        "row" -> {
                            if (currentRow.isNotEmpty()) {
                                rows.add(currentRow.toList())
                                rowCount++
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return rows
    }

    /**
     * パース結果
     */
    data class ParseResult(
        val rows: List<List<String>> = emptyList(),
        val format: String = "",
        val rowCount: Int = 0,
        val colCount: Int = 0,
        val processingTimeMs: Long = 0,
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = errorMessage == null && rows.isNotEmpty()

        /**
         * Markdown テーブルに変換
         */
        fun toMarkdown(): String {
            if (rows.isEmpty()) return errorMessage ?: "データなし"

            val sb = StringBuilder()
            val maxCols = rows.maxOf { it.size }

            // ヘッダー行
            val header = rows.first()
            sb.append("| ")
            for (i in 0 until maxCols) {
                sb.append(header.getOrElse(i) { "" })
                sb.append(" | ")
            }
            sb.appendLine()

            // 区切り行
            sb.append("| ")
            for (i in 0 until maxCols) {
                sb.append("---")
                sb.append(" | ")
            }
            sb.appendLine()

            // データ行
            for (rowIdx in 1 until rows.size) {
                val row = rows[rowIdx]
                sb.append("| ")
                for (i in 0 until maxCols) {
                    sb.append(row.getOrElse(i) { "" })
                    sb.append(" | ")
                }
                sb.appendLine()
            }

            return sb.toString()
        }

        /**
         * 要約テキスト（Gemma-4 送信用）
         */
        fun toSummaryText(): String {
            if (!isSuccess) return errorMessage ?: "データなし"
            return buildString {
                appendLine("【${format} データ】${rowCount} 行 × ${colCount} 列")
                appendLine()
                appendLine(toMarkdown().take(3000)) // Gemma-4 入力制限を考慮
                if (rowCount > 20) {
                    appendLine("... (${rowCount - 20} 行省略)")
                }
            }
        }

        companion object {
            fun error(message: String) = ParseResult(errorMessage = message)
        }
    }
}
