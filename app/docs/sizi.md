

---

## コード生成AIへの指示: ShareReceiver.kt の parseIntent 修正

### 問題
`.kt` ファイルや `.xlsx` ファイルを他アプリから共有しても、パーサー（ExcelCsvParser / SourceCodeParser）が呼ばれない。原因は `parseIntent()` 内で `text/*` MIME タイプを受信した場合に `EXTRA_STREAM`（ファイル URI）ではなく `EXTRA_TEXT`（文字列）を優先して `TextData` に分類してしまうため。また `application/octet-stream` で送られるファイルも `FileData` に分類されるが、ファイル名の拡張子チェックが `parseIntent` にないため見逃す。

### 修正方針
`parseIntent` で `EXTRA_STREAM` に URI があればファイル名の拡張子を見て `FileData` に優先的に分類する。

### 対象ファイル: `app/src/main/java/com/nexus/vision/os/ShareReceiver.kt`（完全置換）

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

        /** ファイルとして解析すべき拡張子一覧 */
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

        /** ファイルとして解析すべき MIME タイプの部分文字列 */
        private val FILE_MIME_PARTS = setOf(
            "pdf", "csv", "comma-separated",
            "spreadsheetml", "xlsx", "excel",
            "octet-stream"
        )
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

    /**
     * Intent からデータを解析する。
     *
     * 判定の優先順位:
     *   1. EXTRA_STREAM に URI があり、拡張子または MIME がファイル系 → FileData
     *   2. image/* → SingleImage / MultipleImages
     *   3. EXTRA_TEXT に文字列がある → TextData
     *   4. EXTRA_STREAM に URI があるがファイル判定できない → FileData (fallback)
     *   5. 何もない → Empty
     */
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

            // EXTRA_STREAM がある場合 → ファイルか画像かを判定
            if (streamUri != null) {
                // 実際の MIME タイプをContentResolverからも取得
                val resolvedMime = contentResolver.getType(streamUri) ?: type
                val filename = getFilename(streamUri)

                Log.i(TAG, "Stream URI: $streamUri, resolvedMime=$resolvedMime, filename=$filename")

                // 画像判定（かつファイル拡張子がソースコード等でない場合）
                if (resolvedMime.startsWith("image/") && !hasFileExtension(filename)) {
                    return ReceivedData.SingleImage(streamUri)
                }

                // ファイル判定（拡張子 or MIME タイプ）
                if (hasFileExtension(filename) || hasFileMimeType(resolvedMime)) {
                    return ReceivedData.FileData(streamUri, resolvedMime)
                }

                // 画像系 MIME なら画像として扱う
                if (resolvedMime.startsWith("image/")) {
                    return ReceivedData.SingleImage(streamUri)
                }

                // それ以外の EXTRA_STREAM は汎用ファイルとして扱う
                return ReceivedData.FileData(streamUri, resolvedMime)
            }

            // EXTRA_STREAM がなく EXTRA_TEXT がある場合 → テキスト
            val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!extraText.isNullOrBlank()) {
                return ReceivedData.TextData(extraText)
            }
        }

        return ReceivedData.Empty
    }

    /**
     * URI からファイル名を取得する
     */
    private fun getFilename(uri: Uri): String {
        // ContentResolver の DISPLAY_NAME を優先
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
        // fallback: URI の lastPathSegment
        return uri.lastPathSegment ?: ""
    }

    /**
     * ファイル名がファイル解析対象の拡張子を持つか
     */
    private fun hasFileExtension(filename: String): Boolean {
        val lower = filename.lowercase()
        return FILE_EXTENSIONS.any { lower.endsWith(it) }
    }

    /**
     * MIME タイプがファイル解析対象か
     */
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
        val filename = getFilename(uri)
        val resolvedMime = mimeType.ifBlank { contentResolver.getType(uri) ?: "" }
        val lowerFilename = filename.lowercase()

        Log.i(TAG, "processFile: mime=$resolvedMime, filename=$filename")

        return when {
            // CSV
            resolvedMime.contains("csv") || resolvedMime.contains("comma-separated") ||
                    lowerFilename.endsWith(".csv") -> {
                val result = ExcelCsvParser.parseFromUri(applicationContext, uri, resolvedMime)
                if (result.isSuccess) {
                    "【CSV 解析結果】\n${result.rowCount} 行 × ${result.colCount} 列 (${result.processingTimeMs}ms)\n\n" +
                            result.toMarkdown()
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
                    "【Excel 解析結果】\n${result.rowCount} 行 × ${result.colCount} 列 (${result.processingTimeMs}ms)\n\n" +
                            result.toMarkdown()
                } else {
                    result.errorMessage ?: "Excel 解析失敗"
                }
            }

            // PDF
            resolvedMime.contains("pdf") || lowerFilename.endsWith(".pdf") -> {
                val result = PdfExtractor.extractFromUri(applicationContext, uri)
                if (result.isSuccess) {
                    result.toSummaryText()
                } else {
                    result.errorMessage ?: "PDF 解析失敗"
                }
            }

            // ソースコード / テキスト系ファイル
            resolvedMime.startsWith("text/") ||
                    resolvedMime.contains("octet-stream") ||
                    lowerFilename.endsWith(".kt") || lowerFilename.endsWith(".kts") ||
                    lowerFilename.endsWith(".java") || lowerFilename.endsWith(".py") ||
                    lowerFilename.endsWith(".js") || lowerFilename.endsWith(".ts") ||
                    lowerFilename.endsWith(".c") || lowerFilename.endsWith(".cpp") ||
                    lowerFilename.endsWith(".h") || lowerFilename.endsWith(".hpp") ||
                    lowerFilename.endsWith(".swift") || lowerFilename.endsWith(".go") ||
                    lowerFilename.endsWith(".rs") || lowerFilename.endsWith(".dart") ||
                    lowerFilename.endsWith(".json") || lowerFilename.endsWith(".xml") ||
                    lowerFilename.endsWith(".yaml") || lowerFilename.endsWith(".yml") ||
                    lowerFilename.endsWith(".md") || lowerFilename.endsWith(".sh") ||
                    lowerFilename.endsWith(".sql") || lowerFilename.endsWith(".html") ||
                    lowerFilename.endsWith(".css") || lowerFilename.endsWith(".gradle") ||
                    lowerFilename.endsWith(".toml") || lowerFilename.endsWith(".properties") -> {
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

### 変更点まとめ

`parseIntent()` を完全に書き直しました。旧版では `text/*` を全て `EXTRA_TEXT` 文字列として処理していましたが、新版では `EXTRA_STREAM` に URI がある場合はまず `ContentResolver` でファイル名を取得し、拡張子とMIMEタイプでファイル系かどうかを判定します。`.kt` ファイルが `text/plain` で来ても、拡張子 `.kt` が `FILE_EXTENSIONS` に含まれるため `FileData` として正しく分類されます。`.xlsx` が `application/octet-stream` で来ても、拡張子 `.xlsx` で判定されます。

`getFilename()` メソッドを追加し、`ContentResolver` の `DISPLAY_NAME` カラムから正確なファイル名を取得します。`uri.lastPathSegment` だけではエンコードされたパスや content URI で正しいファイル名が取れないことがあるため。

`processFile()` 内でも `getFilename()` を使い、`uri.lastPathSegment` の代わりに正確なファイル名で拡張子判定するように変更。`application/octet-stream` をソースコード/テキスト系のフォールバックとして処理するよう追加。

### 変更対象は1ファイルのみ
他のファイル（ExcelCsvParser.kt, SourceCodeParser.kt, PdfExtractor.kt, TableReconstructor.kt, MainViewModel.kt）は変更なし。

### テスト手順
1. `ShareReceiver.kt` を上記で置換
2. `Build > Rebuild Project`
3. ファイルマネージャーから `.kt` ファイルを共有 → 「NEXUS Vision で処理」→ 構造解析結果が表示される
4. ファイルマネージャーから `.xlsx` ファイルを共有 → Markdown テーブルが表示される
5. ファイルマネージャーから `.csv` ファイルを共有 → Markdown テーブルが表示される