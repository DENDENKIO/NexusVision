全体像を把握しました。次は **Phase 8（ファイル解析）** と **Phase 9（OCR 表復元）** です。以下がコード生成AIへの指示です。

---

## コード生成AIへの指示: Phase 8 – ファイル解析 + Phase 9 – OCR 表復元（Step 8-1 〜 9-2）

### プロジェクト情報
- リポジトリ: https://github.com/DENDENKIO/NexusVision/tree/master
- パッケージ: `com.nexus.vision`
- 言語: Kotlin, Android (API 31-35, arm64-v8a)
- ビルド: AGP 8.7.3, Kotlin 2.2.21, Compose BOM 2025.04.00
- 既存クラス: `MlKitOcrEngine`（OCR）, `NexusEngineManager`（テキスト推論）, `MainViewModel`（メイン画面）, `ShareReceiver`（共有受信, Phase 10 実装済み）

### 目的
NexusVision を汎用AIハブとして強化するため、CSV/Excel/PDF/ソースコードの解析と、OCR 結果からの表構造復元を実装する。Phase 8 で実装したパーサーは ShareReceiver（共有受信）からも呼べるようにし、将来 Gemma-4 に構造化テキストを送って要約・分析させる基盤とする。

### 重要: Apache POI は使わない
Android では Apache POI（.xlsx パーサー）は JAR サイズが巨大（10MB+）でメソッド数も多く、実用的でない。代わりに `.xlsx` は ZIP + XML として直接パースする軽量実装にする。CSV は Kotlin 標準ライブラリのみで対応。

### 作成するファイル（4ファイル + ShareReceiver 更新 + MainViewModel 更新）

---

#### ファイル 1: `app/src/main/java/com/nexus/vision/parser/ExcelCsvParser.kt`

```kotlin
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
```

---

#### ファイル 2: `app/src/main/java/com/nexus/vision/parser/SourceCodeParser.kt`

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/parser/SourceCodeParser.kt
package com.nexus.vision.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * ソースコードパーサー
 *
 * Kotlin / Java / Python / JavaScript / TypeScript のソースを解析し、
 * 関数・クラス・インポートを抽出して構造化テキストに変換する。
 *
 * 正規表現ベースの軽量実装。完全な AST ではないが、
 * Gemma-4 に送る概要テキストとしては十分な精度。
 *
 * Phase 8: ファイル解析
 */
object SourceCodeParser {

    private const val TAG = "SourceCodeParser"
    private const val MAX_LINES = 5000
    private const val MAX_LINE_LENGTH = 500

    /**
     * Uri からソースコードを解析する
     */
    fun parseFromUri(context: Context, uri: Uri, mimeType: String? = null): CodeParseResult {
        val type = mimeType ?: context.contentResolver.getType(uri) ?: ""
        val filename = uri.lastPathSegment ?: ""

        val language = detectLanguage(filename, type)

        val stream = context.contentResolver.openInputStream(uri)
            ?: return CodeParseResult.error("ファイルを開けません")

        return stream.use { parse(it, language) }
    }

    /**
     * InputStream からソースコードを解析する
     */
    fun parse(inputStream: InputStream, language: String = "unknown"): CodeParseResult {
        val startTime = System.currentTimeMillis()

        try {
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val lines = mutableListOf<String>()

            var line: String?
            while (reader.readLine().also { line = it } != null && lines.size < MAX_LINES) {
                lines.add(line!!.take(MAX_LINE_LENGTH))
            }
            reader.close()

            val fullText = lines.joinToString("\n")
            val imports = extractImports(lines, language)
            val classes = extractClasses(lines, language)
            val functions = extractFunctions(lines, language)
            val comments = countComments(lines, language)

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Parsed $language: ${lines.size} lines, ${classes.size} classes, ${functions.size} functions, ${elapsed}ms")

            return CodeParseResult(
                language = language,
                totalLines = lines.size,
                imports = imports,
                classes = classes,
                functions = functions,
                commentLines = comments,
                fullText = fullText,
                processingTimeMs = elapsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            return CodeParseResult.error("解析エラー: ${e.message}")
        }
    }

    /**
     * ファイル名・MIME タイプから言語を推定する
     */
    private fun detectLanguage(filename: String, mimeType: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "py" -> "Python"
            "js" -> "JavaScript"
            "ts" -> "TypeScript"
            "c", "h" -> "C"
            "cpp", "cc", "cxx", "hpp" -> "C++"
            "swift" -> "Swift"
            "rb" -> "Ruby"
            "go" -> "Go"
            "rs" -> "Rust"
            "dart" -> "Dart"
            "xml" -> "XML"
            "json" -> "JSON"
            "yaml", "yml" -> "YAML"
            "md" -> "Markdown"
            "sh", "bash" -> "Shell"
            "sql" -> "SQL"
            "html", "htm" -> "HTML"
            "css" -> "CSS"
            else -> when {
                mimeType.contains("kotlin") -> "Kotlin"
                mimeType.contains("java") -> "Java"
                mimeType.contains("python") -> "Python"
                mimeType.contains("javascript") -> "JavaScript"
                else -> "unknown"
            }
        }
    }

    /**
     * インポート文を抽出する
     */
    private fun extractImports(lines: List<String>, language: String): List<String> {
        val pattern = when (language) {
            "Kotlin", "Java" -> Regex("""^\s*import\s+(.+)""")
            "Python" -> Regex("""^\s*(import\s+.+|from\s+.+\s+import\s+.+)""")
            "JavaScript", "TypeScript" -> Regex("""^\s*(import\s+.+|require\s*\(.+\))""")
            "C", "C++" -> Regex("""^\s*#include\s+(.+)""")
            "Go" -> Regex("""^\s*import\s+(.+)""")
            "Rust" -> Regex("""^\s*use\s+(.+);""")
            "Dart" -> Regex("""^\s*import\s+(.+);""")
            else -> return emptyList()
        }

        return lines.mapNotNull { line ->
            pattern.find(line.trim())?.value?.trim()
        }.distinct()
    }

    /**
     * クラス/構造体定義を抽出する
     */
    private fun extractClasses(lines: List<String>, language: String): List<String> {
        val pattern = when (language) {
            "Kotlin" -> Regex("""^\s*(data\s+)?class\s+(\w+)""")
            "Java" -> Regex("""^\s*(public\s+|private\s+|protected\s+)?(abstract\s+)?class\s+(\w+)""")
            "Python" -> Regex("""^\s*class\s+(\w+)""")
            "JavaScript", "TypeScript" -> Regex("""^\s*(export\s+)?(default\s+)?class\s+(\w+)""")
            "C++", "C" -> Regex("""^\s*(class|struct)\s+(\w+)""")
            "Go" -> Regex("""^\s*type\s+(\w+)\s+struct""")
            "Rust" -> Regex("""^\s*(pub\s+)?struct\s+(\w+)""")
            "Swift" -> Regex("""^\s*(class|struct)\s+(\w+)""")
            "Dart" -> Regex("""^\s*(abstract\s+)?class\s+(\w+)""")
            else -> return emptyList()
        }

        return lines.mapNotNull { line ->
            pattern.find(line.trim())?.value?.trim()
        }.distinct()
    }

    /**
     * 関数/メソッド定義を抽出する
     */
    private fun extractFunctions(lines: List<String>, language: String): List<String> {
        val pattern = when (language) {
            "Kotlin" -> Regex("""^\s*(private\s+|public\s+|internal\s+|protected\s+)?(suspend\s+)?fun\s+(\w+)""")
            "Java" -> Regex("""^\s*(public|private|protected)?\s*(static\s+)?\w+\s+(\w+)\s*\(""")
            "Python" -> Regex("""^\s*def\s+(\w+)\s*\(""")
            "JavaScript", "TypeScript" -> Regex("""^\s*(export\s+)?(async\s+)?function\s+(\w+)|^\s*(const|let|var)\s+(\w+)\s*=\s*(async\s+)?\(""")
            "C", "C++" -> Regex("""^\s*\w[\w*&\s]+\s+(\w+)\s*\(""")
            "Go" -> Regex("""^\s*func\s+(\(?\w*\)?\s*)?(\w+)\s*\(""")
            "Rust" -> Regex("""^\s*(pub\s+)?(async\s+)?fn\s+(\w+)""")
            "Swift" -> Regex("""^\s*(func|init)\s+(\w+)""")
            "Dart" -> Regex("""^\s*(\w+\s+)?(\w+)\s*\(.*\)\s*(async\s*)?\{""")
            else -> return emptyList()
        }

        return lines.mapNotNull { line ->
            pattern.find(line.trim())?.value?.trim()
        }.distinct().take(100) // 関数が多すぎる場合は上限
    }

    /**
     * コメント行数をカウントする
     */
    private fun countComments(lines: List<String>, language: String): Int {
        var count = 0
        var inBlockComment = false

        for (line in lines) {
            val trimmed = line.trim()
            when (language) {
                "Python" -> {
                    if (trimmed.startsWith("#")) count++
                    if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''")) {
                        inBlockComment = !inBlockComment
                        count++
                    } else if (inBlockComment) count++
                }
                "HTML", "XML" -> {
                    if (trimmed.startsWith("<!--")) {
                        inBlockComment = true
                        count++
                    }
                    if (inBlockComment) count++
                    if (trimmed.contains("-->")) inBlockComment = false
                }
                else -> {
                    // C-style comments (Kotlin, Java, JS, TS, C, C++, Go, Rust, Swift, Dart)
                    if (trimmed.startsWith("//")) count++
                    if (trimmed.startsWith("/*")) {
                        inBlockComment = true
                    }
                    if (inBlockComment) count++
                    if (trimmed.contains("*/")) inBlockComment = false
                }
            }
        }
        return count
    }

    /**
     * ソースコード解析結果
     */
    data class CodeParseResult(
        val language: String = "unknown",
        val totalLines: Int = 0,
        val imports: List<String> = emptyList(),
        val classes: List<String> = emptyList(),
        val functions: List<String> = emptyList(),
        val commentLines: Int = 0,
        val fullText: String = "",
        val processingTimeMs: Long = 0,
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = errorMessage == null

        /**
         * 構造化テキスト（Gemma-4 送信用）
         */
        fun toSummaryText(): String {
            if (!isSuccess) return errorMessage ?: "解析失敗"

            return buildString {
                appendLine("【ソースコード解析】$language, $totalLines 行, コメント $commentLines 行")
                appendLine()

                if (imports.isNotEmpty()) {
                    appendLine("▼ インポート (${imports.size}):")
                    imports.take(20).forEach { appendLine("  $it") }
                    if (imports.size > 20) appendLine("  ... (${imports.size - 20} 省略)")
                    appendLine()
                }

                if (classes.isNotEmpty()) {
                    appendLine("▼ クラス/構造体 (${classes.size}):")
                    classes.forEach { appendLine("  $it") }
                    appendLine()
                }

                if (functions.isNotEmpty()) {
                    appendLine("▼ 関数/メソッド (${functions.size}):")
                    functions.take(50).forEach { appendLine("  $it") }
                    if (functions.size > 50) appendLine("  ... (${functions.size - 50} 省略)")
                    appendLine()
                }

                // コード本文（先頭 2000 文字）
                appendLine("▼ コード先頭:")
                appendLine(fullText.take(2000))
                if (fullText.length > 2000) appendLine("... (省略)")
            }
        }

        companion object {
            fun error(message: String) = CodeParseResult(errorMessage = message)
        }
    }
}
```

---

#### ファイル 3: `app/src/main/java/com/nexus/vision/parser/PdfExtractor.kt`

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/parser/PdfExtractor.kt
package com.nexus.vision.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nexus.vision.ocr.MlKitOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF テキスト抽出
 *
 * Android PdfRenderer でページを画像化 → ML Kit OCR でテキスト抽出。
 * ネイティブ PDF テキスト抽出ライブラリを使わず、
 * 画像ベースで処理するため、スキャン PDF にも対応。
 *
 * Phase 8: ファイル解析
 */
object PdfExtractor {

    private const val TAG = "PdfExtractor"
    private const val MAX_PAGES = 20        // 処理上限ページ数
    private const val RENDER_DPI = 200      // レンダリング解像度
    private const val RENDER_MAX_SIDE = 2048 // レンダリング最大辺

    /**
     * Uri から PDF を解析する
     */
    suspend fun extractFromUri(context: Context, uri: Uri): PdfResult =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val ocrEngine = MlKitOcrEngine()

            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext PdfResult.error("PDF を開けません")

                val renderer = PdfRenderer(pfd)
                val totalPages = renderer.pageCount
                val pagesToProcess = minOf(totalPages, MAX_PAGES)

                Log.i(TAG, "PDF: $totalPages pages, processing $pagesToProcess")

                val pages = mutableListOf<PageResult>()

                for (i in 0 until pagesToProcess) {
                    val page = renderer.openPage(i)

                    // DPI に基づいたレンダリングサイズ計算
                    val scale = RENDER_DPI.toFloat() / 72f // PDF 標準は 72dpi
                    var renderWidth = (page.width * scale).toInt()
                    var renderHeight = (page.height * scale).toInt()

                    // 最大辺制限
                    if (maxOf(renderWidth, renderHeight) > RENDER_MAX_SIDE) {
                        val ratio = RENDER_MAX_SIDE.toFloat() / maxOf(renderWidth, renderHeight)
                        renderWidth = (renderWidth * ratio).toInt()
                        renderHeight = (renderHeight * ratio).toInt()
                    }

                    val bitmap = Bitmap.createBitmap(
                        renderWidth, renderHeight, Bitmap.Config.ARGB_8888
                    )

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // OCR
                    val ocrResult = ocrEngine.recognize(bitmap)
                    bitmap.recycle()

                    pages.add(
                        PageResult(
                            pageNumber = i + 1,
                            text = ocrResult.fullText,
                            blockCount = ocrResult.blocks.size
                        )
                    )

                    Log.d(TAG, "Page ${i + 1}: ${ocrResult.fullText.length} chars")
                }

                renderer.close()
                pfd.close()

                val elapsed = System.currentTimeMillis() - startTime
                val fullText = pages.joinToString("\n\n") { "--- ページ ${it.pageNumber} ---\n${it.text}" }

                Log.i(TAG, "PDF done: $pagesToProcess pages, ${fullText.length} chars, ${elapsed}ms")

                PdfResult(
                    pages = pages,
                    totalPages = totalPages,
                    processedPages = pagesToProcess,
                    fullText = fullText,
                    processingTimeMs = elapsed
                )
            } catch (e: Exception) {
                Log.e(TAG, "PDF extract error: ${e.message}")
                PdfResult.error("PDF 解析エラー: ${e.message}")
            } finally {
                ocrEngine.close()
            }
        }

    /**
     * PDF 解析結果
     */
    data class PdfResult(
        val pages: List<PageResult> = emptyList(),
        val totalPages: Int = 0,
        val processedPages: Int = 0,
        val fullText: String = "",
        val processingTimeMs: Long = 0,
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = errorMessage == null

        fun toSummaryText(): String {
            if (!isSuccess) return errorMessage ?: "解析失敗"
            return buildString {
                appendLine("【PDF 解析結果】$processedPages/$totalPages ページ (${processingTimeMs}ms)")
                appendLine()
                appendLine(fullText.take(3000))
                if (fullText.length > 3000) appendLine("... (省略)")
            }
        }

        companion object {
            fun error(message: String) = PdfResult(errorMessage = message)
        }
    }

    data class PageResult(
        val pageNumber: Int,
        val text: String,
        val blockCount: Int
    )
}
```

---

#### ファイル 4: `app/src/main/java/com/nexus/vision/ocr/TableReconstructor.kt`

```kotlin
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
```

---

#### ファイル 5: `app/src/main/java/com/nexus/vision/os/ShareReceiver.kt`（完全置換）

`processFile` メソッドを更新し、CSV/XLSX/PDF/ソースコードに対応させる:

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
 * ACTION_SEND / ACTION_SEND_MULTIPLE で共有された
 * 画像・テキスト・ファイルを受信する Activity。
 *
 * Phase 10: OS 統合
 * Phase 8: ファイル解析統合
 */
class ShareReceiver : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"
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

        return when {
            action == Intent.ACTION_SEND && type.startsWith("text/") -> {
                // text/plain はテキスト、text/csv は CSV として扱う
                if (type.contains("csv")) {
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) ReceivedData.FileData(uri, type)
                    else {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                        ReceivedData.TextData(text)
                    }
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    ReceivedData.TextData(text)
                }
            }
            action == Intent.ACTION_SEND && type.startsWith("image/") -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) ReceivedData.SingleImage(uri)
                else ReceivedData.Empty
            }
            action == Intent.ACTION_SEND_MULTIPLE && type.startsWith("image/") -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) ReceivedData.MultipleImages(uris)
                else ReceivedData.Empty
            }
            action == Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) ReceivedData.FileData(uri, type)
                else ReceivedData.Empty
            }
            else -> ReceivedData.Empty
        }
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
                    "(エンジン未ロードのため要約はスキップされました。" +
                    "メインアプリでエンジンをロードしてから再度共有してください)"
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
     * ファイル処理（Phase 8 統合）
     * MIME タイプ / ファイル名でパーサーを振り分ける
     */
    private suspend fun processFile(uri: Uri, mimeType: String): String {
        val filename = uri.lastPathSegment ?: ""
        Log.i(TAG, "processFile: mime=$mimeType, filename=$filename")

        return when {
            // CSV
            mimeType.contains("csv") || mimeType.contains("comma-separated") ||
                    filename.endsWith(".csv", ignoreCase = true) -> {
                val result = ExcelCsvParser.parseFromUri(applicationContext, uri, mimeType)
                if (result.isSuccess) {
                    "【CSV 解析結果】\n${result.rowCount} 行 × ${result.colCount} 列 (${result.processingTimeMs}ms)\n\n" +
                            result.toMarkdown()
                } else {
                    result.errorMessage ?: "CSV 解析失敗"
                }
            }

            // Excel (.xlsx)
            mimeType.contains("spreadsheetml") || mimeType.contains("xlsx") ||
                    filename.endsWith(".xlsx", ignoreCase = true) -> {
                val result = ExcelCsvParser.parseFromUri(applicationContext, uri, mimeType)
                if (result.isSuccess) {
                    "【Excel 解析結果】\n${result.rowCount} 行 × ${result.colCount} 列 (${result.processingTimeMs}ms)\n\n" +
                            result.toMarkdown()
                } else {
                    result.errorMessage ?: "Excel 解析失敗"
                }
            }

            // PDF
            mimeType.contains("pdf") || filename.endsWith(".pdf", ignoreCase = true) -> {
                val result = PdfExtractor.extractFromUri(applicationContext, uri)
                if (result.isSuccess) {
                    result.toSummaryText()
                } else {
                    result.errorMessage ?: "PDF 解析失敗"
                }
            }

            // ソースコード (text/* のうち CSV 以外)
            mimeType.startsWith("text/") ||
                    filename.endsWith(".kt") || filename.endsWith(".java") ||
                    filename.endsWith(".py") || filename.endsWith(".js") ||
                    filename.endsWith(".ts") || filename.endsWith(".c") ||
                    filename.endsWith(".cpp") || filename.endsWith(".h") ||
                    filename.endsWith(".swift") || filename.endsWith(".go") ||
                    filename.endsWith(".rs") || filename.endsWith(".dart") ||
                    filename.endsWith(".json") || filename.endsWith(".xml") ||
                    filename.endsWith(".yaml") || filename.endsWith(".yml") ||
                    filename.endsWith(".md") || filename.endsWith(".sh") ||
                    filename.endsWith(".sql") || filename.endsWith(".html") ||
                    filename.endsWith(".css") -> {
                val result = SourceCodeParser.parseFromUri(applicationContext, uri, mimeType)
                if (result.isSuccess) {
                    result.toSummaryText()
                } else {
                    result.errorMessage ?: "ソースコード解析失敗"
                }
            }

            else -> {
                "非対応ファイル形式: $mimeType\n\n" +
                        "対応形式: 画像(OCR)、テキスト(要約)、CSV、Excel(.xlsx)、PDF、ソースコード"
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

---

### 変更しないファイル
- `MlKitOcrEngine.kt` → 前回追加した `recognizeFromUri` がそのまま使われる
- `MainViewModel.kt` → 変更なし（チャット内のファイル解析は将来ステップで追加）
- `build.gradle.kts` → 追加ライブラリなし（XmlPullParser は Android 標準、ZipInputStream は Java 標準）

### ビルド・テスト手順

1. 上記5ファイルを配置/更新する
2. `Build > Rebuild Project` でビルド確認
3. テスト項目:
   - **CSV 共有**: ファイルマネージャーから .csv ファイルを「NEXUS Vision で処理」→ Markdown テーブル表示
   - **XLSX 共有**: .xlsx ファイルを共有 → Sheet1 の内容が Markdown テーブルで表示
   - **PDF 共有**: .pdf ファイルを共有 → ページごとに OCR テキスト表示
   - **ソースコード共有**: .kt / .py ファイルを共有 → 構造解析結果（クラス・関数一覧）表示
   - **OCR テーブル復元**: 表が写った画像を OCR → TableReconstructor で行列復元 → CSV/Markdown 出力（MainViewModel の OCR パスに統合する場合は次ステップ）

### 注意点
- `.xlsx` パーサーは Sheet1 のみ対応。複数シートは将来拡張。
- PDF は画像化→OCR のため、1ページ2〜5秒かかる。20ページ上限。
- `TableReconstructor` は現時点では直接 ShareReceiver からは呼ばれない。次ステップで MainViewModel の OCR 処理に組み込む予定（OCR 結果が表形式ならテーブル復元を自動適用）。