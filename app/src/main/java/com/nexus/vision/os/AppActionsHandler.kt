package com.nexus.vision.os

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.ui.theme.NexusVisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppActionsHandler : ComponentActivity() {

    companion object {
        private const val TAG = "AppActions"
        const val EXTRA_QUERY = "query"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val query = extractQuery(intent)
        Log.i(TAG, "App Action received: query='$query'")

        setContent {
            NexusVisionTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (query.isBlank()) {
                        // クエリが空 → 入力画面を表示
                        var inputText by remember { mutableStateOf("") }
                        var resultText by remember { mutableStateOf<String?>(null) }
                        var isProcessing by remember { mutableStateOf(false) }
                        val activity = this@AppActionsHandler

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = "NEXUS Vision",
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = inputText,
                                        onValueChange = { inputText = it },
                                        label = { Text("質問を入力") },
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp),
                                        singleLine = true,
                                        enabled = !isProcessing
                                    )

                                    Button(
                                        onClick = {
                                            if (inputText.isNotBlank()) {
                                                isProcessing = true
                                                val q = inputText.trim()
                                                MainScope().launch {
                                                    resultText = handleQuery(q)
                                                    isProcessing = false
                                                }
                                            }
                                        },
                                        enabled = inputText.isNotBlank() && !isProcessing
                                    ) {
                                        Text("送信")
                                    }
                                }

                                if (isProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .align(Alignment.CenterHorizontally)
                                            .padding(top = 24.dp)
                                    )
                                }

                                if (resultText != null) {
                                    Text(
                                        text = resultText!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 16.dp)
                                    )
                                }

                                // メインアプリへ移動ボタン
                                Button(
                                    onClick = {
                                        val mainIntent = Intent(activity, MainActivity::class.java)
                                        startActivity(mainIntent)
                                        finish()
                                    },
                                    modifier = Modifier.padding(top = 16.dp)
                                ) {
                                    Text("メインアプリを開く")
                                }
                            }
                        }
                    } else {
                        // クエリあり → 従来通り処理
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
                                    Text(
                                        text = "Q: $query",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
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
    }

    private fun extractQuery(intent: Intent): String {
        return intent.getStringExtra(EXTRA_QUERY)
            ?: intent.getStringExtra("android.intent.extra.TEXT")
            ?: intent.data?.getQueryParameter("q")
            ?: ""
    }

    private suspend fun handleQuery(query: String): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext "クエリが空です。もう一度お試しください。"
        }

        val engine = NexusEngineManager.getInstance()

        if (engine.state.value is EngineState.Ready) {
            val result = engine.inferText(query)
            result.getOrElse { "エンジンエラー: ${it.message}" }
        } else {
            "エンジンが未ロードです。メインアプリでエンジンをロードしてください。"
        }
    }
}
