// ファイルパス: app/src/main/java/com/nexus/vision/os/AppActionsHandler.kt
package com.nexus.vision.os

import android.content.Intent
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
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.ui.theme.NexusVisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemini 音声コマンド（App Actions）から起動される Activity。
 *
 * Google Assistant / Gemini から「NEXUS Vision で○○を調べて」のような
 * 音声コマンドで起動し、クエリに対して Gemma-4 で回答する。
 *
 * shortcuts.xml で BII (Built-in Intent) を定義し、この Activity にルーティング。
 *
 * Phase 10: OS 統合
 */
class AppActionsHandler : ComponentActivity() {

    companion object {
        private const val TAG = "AppActions"

        // Intent extra keys（shortcuts.xml と一致させる）
        const val EXTRA_QUERY = "query"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val query = extractQuery(intent)
        Log.i(TAG, "App Action received: query='$query'")

        setContent {
            NexusVisionTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var resultText by remember { mutableStateOf<String?>(null) }
                    var isProcessing by remember { mutableStateOf(true) }

                    LaunchedEffect(Unit) {
                        resultText = handleQuery(query)
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
                            Column {
                                Text(
                                    text = "NEXUS Vision",
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                if (query.isNotBlank()) {
                                    Text(
                                        text = "Q: $query",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                }
                                Text(
                                    text = resultText ?: "処理できませんでした",
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
     * Intent からクエリを抽出する。
     * App Actions は shortcuts.xml の parameter mapping に従って Extra を渡す。
     */
    private fun extractQuery(intent: Intent): String {
        // shortcuts.xml で定義した extra key
        return intent.getStringExtra(EXTRA_QUERY)
            ?: intent.getStringExtra("android.intent.extra.TEXT")
            ?: intent.data?.getQueryParameter("q")
            ?: ""
    }

    /**
     * クエリを処理する
     */
    private suspend fun handleQuery(query: String): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext "クエリが空です。もう一度お試しください。"
        }

        val engine = NexusEngineManager.getInstance()

        if (engine.state.value is EngineState.Ready) {
            val result = engine.inferText(query)
            result.getOrElse {
                "エンジンエラー: ${it.message}"
            }
        } else {
            "エンジンが未ロードです。NEXUS Vision アプリを開いてエンジンをロードしてください。"
        }
    }
}
