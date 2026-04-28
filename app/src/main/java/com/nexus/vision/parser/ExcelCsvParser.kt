// ファイルパス: app/src/main/java/com/nexus/vision/parser/ExcelCsvParser.kt
package com.nexus.vision.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * CSV / Excel (.xlsx) パーサー
 *
 * CSV: 自動文字コード検出 (UTF-8 / Shift_JIS / EUC-JP / ISO-8859-1)
 * XLSX: ZIP 展開 → xl/sharedStrings.xml + xl/worksheets/sheet1.xml を XML パース
 *
 * Phase 8: ファイル解析
 */
object ExcelCsvParser {

    private const val TAG = "ExcelCsvParser"
    private const val MAX_ROWS = 500
    private const val MAX_COLS = 50
    private const val MAX_CELL_LENGTH = 200

    fun parseFromUri(context: Context, uri: Uri, mimeType: String? = null): ParseResult {
        val type = mimeType ?: context.contentResolver.getType(uri) ?: ""
        val filename = uri.lastPathSegment ?: ""

        return when {
            type.contains("csv") || type.contains("comma-separated") ||
                    filename.endsWith(".csv", ignoreCase = true) -> {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return ParseResult.error("ファイルを開けません")
                stream.use { parseCsv(it) }
            }
            type.contains("spreadsheetml") || type.contains("xlsx") ||
                    filename.endsWith(".xlsx", ignoreCase = true) -> {
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
     * CSV をパースする（自動文字コード検出）
     */
    fun parseCsv(inputStream: InputStream): ParseResult {
        val startTime = System.currentTimeMillis()

        try {
            // まず全バイトを読み込んでから文字コードを検出
            val rawBytes = inputStream.readBytes()
            val charset = detectCharset(rawBytes)
            Log.i(TAG, "CSV charset detected: ${charset.name()}")

            val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(rawBytes), charset))
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
     * バイト列から文字コードを推定する。
     * BOM → UTF-8 / UTF-16 を優先。
     * BOM なしなら、Shift_JIS の頻出バイトパターンで判定。
     * 判定できなければ UTF-8 をデフォルトとする。
     */
    private fun detectCharset(bytes: ByteArray): Charset {
        // BOM チェック
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        }

        // UTF-8 として有効かチェック
        if (isValidUtf8(bytes)) {
            // UTF-8 として有効でも、ASCII のみの場合は Shift_JIS の可能性もある
            // ただし ASCII のみなら文字化けしないので UTF-8 で OK
            val hasHighBytes = bytes.any { it.toInt() and 0xFF > 0x7F }
            if (!hasHighBytes) return Charsets.UTF_8 // ASCII only

            // マルチバイトが含まれ、UTF-8 として有効なら UTF-8
            return Charsets.UTF_8
        }

        // UTF-8 として無効 → Shift_JIS を試す
        return try {
            val sjis = Charset.forName("Shift_JIS")
            // Shift_JIS としてデコード→再エンコードで一致するか
            val decoded = String(bytes, sjis)
            val reEncoded = decoded.toByteArray(sjis)
            if (reEncoded.contentEquals(bytes)) sjis else Charsets.ISO_8859_1
        } catch (e: Exception) {
            Charsets.ISO_8859_1
        }
    }

    /**
     * UTF-8 として有効なバイト列かチェック
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val seqLen = when {
                b <= 0x7F -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> return false
            }
            if (i + seqLen > bytes.size) return false
            for (j in 1 until seqLen) {
                val cont = bytes[i + j].toInt() and 0xFF
                if (cont !in 0x80..0xBF) return false
            }
            i += seqLen
        }
        return true
    }

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
                        i++
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

    fun parseXlsx(inputStream: InputStream): ParseResult {
        val startTime = System.currentTimeMillis()

        try {
            // XLSX は2パスが必要なことがある（sharedStrings が sheet の後に来る場合）
            // そのため一度全体を読み込む
            val rawBytes = inputStream.readBytes()

            // 1パス目: sharedStrings を取得
            var sharedStrings = listOf<String>()
            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/sharedStrings.xml") {
                        sharedStrings = parseSharedStrings(zip)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            // 2パス目: sheet データを取得（sharedStrings を使って解決）
            var sheetData = listOf<List<String>>()
            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/worksheets/sheet1.xml") {
                        sheetData = parseSheet(zip, sharedStrings)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
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
         * プレーンテキストのテーブルに変換（等幅フォント前提）
         * Markdown の | --- | ではなく、桁揃えしたテキストテーブル
         */
        fun toTextTable(): String {
            if (rows.isEmpty()) return errorMessage ?: "データなし"

            val maxCols = rows.maxOf { it.size }

            // 各列の最大幅を計算
            val colWidths = IntArray(maxCols)
            for (row in rows) {
                for ((i, cell) in row.withIndex()) {
                    colWidths[i] = maxOf(colWidths[i], displayWidth(cell))
                }
            }
            // 最小幅 3
            for (i in colWidths.indices) {
                colWidths[i] = maxOf(colWidths[i], 3)
            }

            val sb = StringBuilder()

            // 区切り線
            fun appendSeparator() {
                sb.append("+")
                for (w in colWidths) {
                    sb.append("-".repeat(w + 2))
                    sb.append("+")
                }
                sb.appendLine()
            }

            // ヘッダー行
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

        /**
         * 全角文字を考慮した表示幅
         */
        private fun displayWidth(text: String): Int {
            var width = 0
            for (ch in text) {
                width += if (ch.code > 0x7F) 2 else 1
            }
            return width
        }

        /**
         * 全角文字を考慮したパディング
         */
        private fun padCell(text: String, targetWidth: Int): String {
            val currentWidth = displayWidth(text)
            val padding = targetWidth - currentWidth
            return if (padding > 0) {
                text + " ".repeat(padding)
            } else {
                text
            }
        }

        /**
         * Markdown テーブルに変換（将来の Markdown レンダリング用に残す）
         */
        fun toMarkdown(): String {
            if (rows.isEmpty()) return errorMessage ?: "データなし"

            val sb = StringBuilder()
            val maxCols = rows.maxOf { it.size }

            val header = rows.first()
            sb.append("| ")
            for (i in 0 until maxCols) {
                sb.append(header.getOrElse(i) { "" })
                sb.append(" | ")
            }
            sb.appendLine()

            sb.append("| ")
            for (i in 0 until maxCols) {
                sb.append("---")
                sb.append(" | ")
            }
            sb.appendLine()

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
                appendLine(toTextTable())
            }
        }

        companion object {
            fun error(message: String) = ParseResult(errorMessage = message)
        }
    }
}
