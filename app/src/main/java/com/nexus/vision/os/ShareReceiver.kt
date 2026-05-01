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
import androidx.compose.foundation.layout.padding
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
import com.nexus.vision.MainActivity
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
 * CSV / Excel / PDF はインデックスに登録し、処理後に MainActivity へ遷移する。
 * 画像は OCR、テキストは AI 要約し、結果を MainActivity のチャットに表示。
 *
 * Phase 10: OS 統合
 * Phase 8.5: NexusSheets インデックス統合
 */
class ShareReceiver : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"
        const val EXTRA_SHARE_RESULT = "com.nexus.vision.SHARE_RESULT"

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val receivedData = parseIntent(intent)

        setContent {
            NexusVisionTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var isProcessing by remember { mutableStateOf(true) }

                    LaunchedEffect(Unit) {
                        val result = processReceivedData(receivedData)
                        isProcessing = false
                        // MainActivity へ遷移し、結果テキストを渡す
                        navigateToMain(result)
                    }

                    if (isProcessing) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text(
                                    text = "NEXUS Vision で処理中...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 処理完了後に MainActivity へ遷移する。
     * 結果テキストを EXTRA_SHARE_RESULT として渡す。
     */
    private fun navigateToMain(resultText: String) {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHARE_RESULT, resultText)
        }
        startActivity(mainIntent)
        finish()
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
            result.getOrElse { "エンジンエラー: ${it.message}" }
        } else {
            "【共有テキスト受信】\n\n$text"
        }
    }

    private suspend fun processImage(uri: Uri): String {
        val ocrEngine = MlKitOcrEngine()
        return try {
            val result = ocrEngine.recognizeFromUri(applicationContext, uri)
            if (result.isNotBlank()) "【テキスト読み取り結果】\n\n$result"
            else "テキストを検出できませんでした"
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
                    results.appendLine(if (text.isNotBlank()) text else "(テキスト未検出)")
                } catch (e: Exception) {
                    results.appendLine("エラー: 推論に失敗しました")
                }
                results.appendLine()
            }
        } finally {
            ocrEngine.close()
        }
        return results.toString().ifBlank { "テキストを検出できませんでした" }
    }

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
                    sheetsIndex.addParsedTable(result.rows, filename, "csv")
                    "「$filename」を登録しました。(${result.rowCount}行×${result.colCount}列)\nチャットから検索・質問できます。"
                } else {
                    result.errorMessage ?: "CSV 解析失敗"
                }
            }

            // Excel
            resolvedMime.contains("spreadsheetml") || resolvedMime.contains("xlsx") ||
                    resolvedMime.contains("excel") ||
                    lowerFilename.endsWith(".xlsx") || lowerFilename.endsWith(".xls") -> {
                val result = ExcelCsvParser.parseFromUri(applicationContext, uri, resolvedMime)
                if (result.isSuccess) {
                    sheetsIndex.addParsedTable(result.rows, filename, "xlsx")
                    "「$filename」を登録しました。(${result.rowCount}行×${result.colCount}列)\nチャットから検索・質問できます。"
                } else {
                    result.errorMessage ?: "Excel 解析失敗"
                }
            }

            // PDF
            resolvedMime.contains("pdf") || lowerFilename.endsWith(".pdf") -> {
                val result = PdfExtractor.extractFromUri(applicationContext, uri)
                if (result.isSuccess) {
                    val pageTexts = result.pages.map { it.text }
                    sheetsIndex.addPdfText(pageTexts, filename)
                    "「$filename」を登録しました。(${result.processedPages}ページ)\nチャットから検索・質問できます。"
                } else {
                    result.errorMessage ?: "PDF 解析失敗"
                }
            }

            // ソースコード
            FILE_EXTENSIONS.any { lowerFilename.endsWith(it) } ||
                    resolvedMime.startsWith("text/") || resolvedMime.contains("octet-stream") -> {
                val result = SourceCodeParser.parseFromUri(applicationContext, uri, resolvedMime)
                if (result.isSuccess) result.toSummaryText()
                else result.errorMessage ?: "ファイル解析失敗"
            }

            else -> "非対応ファイル形式: $resolvedMime ($filename)"
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
