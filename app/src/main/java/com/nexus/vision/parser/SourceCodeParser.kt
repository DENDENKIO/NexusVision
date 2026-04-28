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
