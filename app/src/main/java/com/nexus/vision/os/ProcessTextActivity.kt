// ファイルパス: app/src/main/java/com/nexus/vision/os/ProcessTextActivity.kt
package com.nexus.vision.os

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.nexus.vision.ui.theme.NexusVisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ACTION_PROCESS_TEXT で選択テキストを受信する Activity。
 *
 * ブラウザや他アプリでテキストを選択すると、コンテキストメニューに
 * 「NEXUS 解析」が表示される。タップするとこの Activity が起動し、
 * 選択テキストを Gemma-4 で解析する。
 *
 * readonly=false の場合、処理結果を元のアプリに返すことも可能。
 *
 * Phase 10: OS 統合
 */
class ProcessTextActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ProcessText"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        val isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

        Log.i(TAG, "Received: text='${selectedText.take(50)}...', readonly=$isReadOnly")

        setContent {
            NexusVisionTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var resultText by remember { mutableStateOf<String?>(null) }
                    var isProcessing by remember { mutableStateOf(true) }
                    val activity = this@ProcessTextActivity

                    LaunchedEffect(Unit) {
                        resultText = analyzeText(selectedText)
                        isProcessing = false
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        if (isProcessing) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "NEXUS Vision で解析中...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                                if (selectedText.length > 80) {
                                    Text(
                                        text = "\"${selectedText.take(80)}...\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = "NEXUS Vision 解析結果",
                                    style = MaterialTheme.typography.headlineSmall
                                )

                                // 元テキスト
                                Text(
                                    text = "元テキスト:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                                Text(
                                    text = selectedText,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                                )

                                HorizontalDivider()

                                // 結果
                                Text(
                                    text = "解析結果:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                                Text(
                                    text = resultText ?: "処理できませんでした",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                                )

                                // ボタン行
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // コピーボタン
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                                                    as ClipboardManager
                                            clipboard.setPrimaryClip(
                                                ClipData.newPlainText("NEXUS Result", resultText ?: "")
                                            )
                                            Toast.makeText(activity, "コピーしました", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("結果をコピー")
                                    }

                                    // 結果を返す（readonly=false の場合）
                                    if (!isReadOnly && resultText != null) {
                                        Button(
                                            onClick = {
                                                val resultIntent = Intent().apply {
                                                    putExtra(Intent.EXTRA_PROCESS_TEXT, resultText)
                                                }
                                                setResult(RESULT_OK, resultIntent)
                                                finish()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("結果を挿入")
                                        }
                                    }

                                    // 閉じるボタン
                                    OutlinedButton(
                                        onClick = { finish() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("閉じる")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * テキストを解析する。
     * エンジンがロード済みなら Gemma-4 で解析、未ロードなら基本的な文字数・行数情報を返す。
     */
    private suspend fun analyzeText(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext "テキストが空です"

        val engine = NexusEngineManager.getInstance()

        if (engine.state.value is EngineState.Ready) {
            val prompt = buildString {
                appendLine("以下のテキストを解析してください。内容に応じて適切な処理を行ってください:")
                appendLine("- 質問文なら回答を提供")
                appendLine("- 外国語なら日本語に翻訳")
                appendLine("- 長文なら要約")
                appendLine("- コードならレビュー・説明")
                appendLine("- その他なら内容の分析・解説")
                appendLine()
                appendLine("テキスト:")
                appendLine(text)
            }

            val result = engine.inferText(prompt)
            result.getOrElse {
                "エンジンエラー: ${it.message}\n\n" +
                        basicAnalysis(text)
            }
        } else {
            basicAnalysis(text) + "\n\n" +
                    "(エンジン未ロード。メインアプリでエンジンをロードすると AI 解析が利用できます)"
        }
    }

    /**
     * エンジンなしの基本テキスト分析
     */
    private fun basicAnalysis(text: String): String {
        val charCount = text.length
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val lineCount = text.lines().size

        // 簡易言語判定
        val hasJapanese = text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' || it in '\u4E00'..'\u9FFF' }
        val hasKorean = text.any { it in '\uAC00'..'\uD7AF' }
        val hasChinese = !hasJapanese && text.any { it in '\u4E00'..'\u9FFF' }
        val language = when {
            hasJapanese -> "日本語"
            hasKorean -> "韓国語"
            hasChinese -> "中国語"
            else -> "英語/その他"
        }

        return buildString {
            appendLine("【テキスト基本情報】")
            appendLine("文字数: $charCount")
            appendLine("単語数: $wordCount")
            appendLine("行数: $lineCount")
            appendLine("言語: $language")
        }
    }
}
