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
import com.nexus.vision.NexusApplication
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.ocr.MlKitOcrEngine
import com.nexus.vision.ui.theme.NexusVisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ACTION_SEND / ACTION_SEND_MULTIPLE で共有された
 * 画像・テキスト・ファイルを受信する Activity。
 *
 * 他アプリの共有メニューに「NEXUS Vision」として表示される。
 *
 * 受信データに応じて:
 *   - テキスト → Gemma-4 で要約/解析
 *   - 画像(1枚) → OCR テキスト抽出
 *   - 画像(複数) → バッチ OCR
 *   - ファイル → 将来対応(Phase 8)
 *
 * Phase 10: OS 統合
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
                                modifier = Modifier.fillMaxWidth(),
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
     * Intent からデータを解析する
     */
    private fun parseIntent(intent: Intent): ReceivedData {
        val action = intent.action
        val type = intent.type ?: ""

        Log.i(TAG, "Received: action=$action, type=$type")

        return when {
            action == Intent.ACTION_SEND && type.startsWith("text/") -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                ReceivedData.TextData(text)
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
                // その他のファイル
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) ReceivedData.FileData(uri, type)
                else ReceivedData.Empty
            }
            else -> ReceivedData.Empty
        }
    }

    /**
     * 受信データに応じた処理を実行する
     */
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

    /**
     * テキスト解析: エンジンが Ready ならGemma-4で要約、そうでなければテキストをそのまま表示
     */
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

    /**
     * 画像 OCR
     */
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

    /**
     * 複数画像の一括 OCR
     */
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
     * ファイル受信（将来対応: Phase 8）
     */
    private fun processFile(uri: Uri, mimeType: String): String {
        return "ファイルを受信しました (type=$mimeType)\n\n" +
                "対応ファイル形式のパーサーは Phase 8 で実装予定です。\n" +
                "現在対応: 画像（OCR）、テキスト（要約）"
    }

    /**
     * 受信データの種類
     */
    sealed class ReceivedData {
        data class TextData(val text: String) : ReceivedData()
        data class SingleImage(val uri: Uri) : ReceivedData()
        data class MultipleImages(val uris: List<Uri>) : ReceivedData()
        data class FileData(val uri: Uri, val mimeType: String) : ReceivedData()
        data object Empty : ReceivedData()
    }
}
