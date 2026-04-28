

## コード生成AIへの指示: Phase 10 – OS 統合（Step 10-1 〜 10-5）

### プロジェクト情報
- リポジトリ: https://github.com/DENDENKIO/NexusVision/tree/master
- パッケージ: `com.nexus.vision`
- 言語: Kotlin, Android (API 31-35, arm64-v8a)
- ビルド: AGP 8.7.3, Kotlin 2.2.21, Compose BOM 2026.04.01
- 既存の主要クラス: `NexusEngineManager`（テキスト/画像推論）, `MlKitOcrEngine`（OCR）, `RouteCProcessor`（超解像）, `MainViewModel`（メイン画面）, `ThermalMonitor`（発熱監視）

### 目的
NexusVision を「黒子のAI」として Android OS に統合する。他アプリの共有メニュー、テキスト選択メニュー、クイック設定タイル、フローティングHUDからNexusVisionの機能を呼び出せるようにする。画像高画質化はメイン機能ではなく、テキスト解析・OCR・ファイル共有受信など**汎用的なAIハブ**としての統合が主目的。

### 作成するファイル（5ファイル + Manifest変更 + リソース追加）

---

#### ファイル 1: `app/src/main/java/com/nexus/vision/os/ShareReceiver.kt`

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
```

---

#### ファイル 2: `app/src/main/java/com/nexus/vision/os/ProcessTextActivity.kt`

```kotlin
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
```

---

#### ファイル 3: `app/src/main/java/com/nexus/vision/os/QuickSettingsTile.kt`

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/os/QuickSettingsTile.kt
package com.nexus.vision.os

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.nexus.vision.NexusApplication

/**
 * クイック設定タイル「NEXUS Vision」
 *
 * 通知シェードのクイック設定にタイルを追加する。
 * タップで HUD オーバーレイの ON/OFF をトグルする。
 *
 * - タイル Active 状態: HUD 表示中
 * - タイル Inactive 状態: HUD 非表示
 *
 * Phase 10: OS 統合
 */
class QuickSettingsTile : TileService() {

    companion object {
        private const val TAG = "QSTile"

        // HUD の表示状態（アプリ全体で共有）
        @Volatile
        var isHudActive: Boolean = false
            private set

        fun setHudActive(active: Boolean) {
            isHudActive = active
        }
    }

    /**
     * タイルが追加された時
     */
    override fun onTileAdded() {
        super.onTileAdded()
        Log.i(TAG, "Tile added")
        updateTileState()
    }

    /**
     * タイルが表示開始された時（クイック設定パネルが開かれた時）
     */
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    /**
     * タイルがタップされた時
     */
    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Tile clicked, current HUD state: $isHudActive")

        if (isHudActive) {
            stopHud()
        } else {
            startHud()
        }

        updateTileState()
    }

    /**
     * HUD を開始する
     */
    private fun startHud() {
        try {
            val intent = Intent(this, HudOverlay::class.java).apply {
                action = HudOverlay.ACTION_SHOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isHudActive = true
            Log.i(TAG, "HUD started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HUD: ${e.message}")
        }
    }

    /**
     * HUD を停止する
     */
    private fun stopHud() {
        try {
            val intent = Intent(this, HudOverlay::class.java).apply {
                action = HudOverlay.ACTION_HIDE
            }
            startService(intent)
            isHudActive = false
            Log.i(TAG, "HUD stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop HUD: ${e.message}")
        }
    }

    /**
     * タイルの表示状態を更新する
     */
    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (isHudActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "NEXUS HUD"
        tile.subtitle = if (isHudActive) "ON" else "OFF"
        tile.updateTile()
    }
}
```

---

#### ファイル 4: `app/src/main/java/com/nexus/vision/os/AppActionsHandler.kt`

```kotlin
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
```

---

#### ファイル 5: `app/src/main/java/com/nexus/vision/os/HudOverlay.kt`

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/os/HudOverlay.kt
package com.nexus.vision.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import androidx.core.app.NotificationCompat
import com.nexus.vision.R
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * フローティング HUD オーバーレイ
 *
 * SYSTEM_ALERT_WINDOW で画面上にフローティングウィンドウを表示。
 * 他アプリを使いながら NEXUS Vision に質問を投げられる。
 *
 * - ドラッグで移動可能
 * - 入力欄に質問を入力して送信
 * - Gemma-4 の回答を表示
 * - 最小化/閉じるボタン
 *
 * Accessibility Service 非依存（Android 17 Advanced Protection Mode 対応）
 *
 * Phase 10: OS 統合
 */
class HudOverlay : Service() {

    companion object {
        private const val TAG = "HudOverlay"
        private const val NOTIFICATION_ID = 20001
        private const val CHANNEL_ID = "nexus_hud_channel"

        const val ACTION_SHOW = "com.nexus.vision.HUD_SHOW"
        const val ACTION_HIDE = "com.nexus.vision.HUD_HIDE"
    }

    private var windowManager: WindowManager? = null
    private var hudView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // HUD 内の View 参照
    private var resultTextView: TextView? = null
    private var inputEditText: EditText? = null
    private var scrollView: ScrollView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startForeground(NOTIFICATION_ID, createNotification())
                showHud()
            }
            ACTION_HIDE -> {
                hideHud()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification())
                showHud()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideHud()
        scope.cancel()
        QuickSettingsTile.setHudActive(false)
        super.onDestroy()
    }

    /**
     * HUD ウィンドウを表示する
     */
    private fun showHud() {
        if (hudView != null) return // すでに表示中

        val wm = windowManager ?: return

        // --- レイアウトを XML ではなくコードで構築 ---
        val context = this

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE0202020.toInt()) // 半透明ダーク
            setPadding(24, 16, 24, 16)
        }

        // タイトルバー（ドラッグ領域 + 閉じるボタン）
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(context).apply {
            text = "NEXUS Vision HUD"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFFF5555.toInt())
            textSize = 18f
            setPadding(16, 0, 0, 0)
            setOnClickListener {
                hideHud()
                QuickSettingsTile.setHudActive(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        titleBar.addView(titleText)
        titleBar.addView(closeButton)

        // 結果表示エリア
        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300
            ).apply { topMargin = 8 }
        }

        resultTextView = TextView(context).apply {
            text = "質問を入力してください"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
        }
        scrollView?.addView(resultTextView)

        // 入力行
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        inputEditText = EditText(context).apply {
            hint = "質問を入力..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x80FFFFFF.toInt())
            textSize = 13f
            setBackgroundColor(0x40FFFFFF.toInt())
            setPadding(12, 8, 12, 8)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sendButton = TextView(context).apply {
            text = "送信"
            setTextColor(0xFF4FC3F7.toInt())
            textSize = 14f
            setPadding(16, 8, 8, 8)
            setOnClickListener { onSendClicked() }
        }

        inputRow.addView(inputEditText)
        inputRow.addView(sendButton)

        container.addView(titleBar)
        container.addView(scrollView)
        container.addView(inputRow)

        // WindowManager パラメータ
        val params = WindowManager.LayoutParams(
            dpToPx(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        // ドラッグ移動
        setupDrag(titleBar, params)

        wm.addView(container, params)
        hudView = container
        QuickSettingsTile.setHudActive(true)

        Log.i(TAG, "HUD shown")
    }

    /**
     * HUD ウィンドウを非表示にする
     */
    private fun hideHud() {
        hudView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove HUD view: ${e.message}")
            }
        }
        hudView = null
        resultTextView = null
        inputEditText = null
        scrollView = null
        Log.i(TAG, "HUD hidden")
    }

    /**
     * 送信ボタンが押された
     */
    private fun onSendClicked() {
        val query = inputEditText?.text?.toString()?.trim() ?: return
        if (query.isBlank()) return

        inputEditText?.setText("")
        resultTextView?.text = "処理中..."

        scope.launch {
            val answer = processQuery(query)
            resultTextView?.text = "Q: $query\n\nA: $answer"
            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * クエリを処理する
     */
    private suspend fun processQuery(query: String): String = withContext(Dispatchers.IO) {
        val engine = NexusEngineManager.getInstance()

        if (engine.state.value is EngineState.Ready) {
            val result = engine.inferText(query)
            result.getOrElse { "エラー: ${it.message}" }
        } else {
            "エンジン未ロード。メインアプリでエンジンをロードしてください。"
        }
    }

    /**
     * タイトルバーのドラッグ移動セットアップ
     */
    private fun setupDrag(dragHandle: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    hudView?.let { windowManager?.updateViewLayout(it, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NEXUS HUD",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "HUD オーバーレイの動作通知"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NEXUS Vision HUD")
            .setContentText("HUD が動作中です")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}
```

---

#### ファイル 6: `app/src/main/res/xml/shortcuts.xml`（新規作成）

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- ファイルパス: app/src/main/res/xml/shortcuts.xml -->
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">

    <!--
      App Actions: Gemini / Google Assistant から起動するための BII 定義。
      「NEXUS Vision で○○を調べて」→ AppActionsHandler が起動。
      Phase 10: OS 統合
    -->

    <capability android:name="actions.intent.GET_THING">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.nexus.vision"
            android:targetClass="com.nexus.vision.os.AppActionsHandler">
            <parameter
                android:name="thing.name"
                android:key="query" />
        </intent>
    </capability>

    <!-- 静的ショートカット: ホーム画面ロングプレスで表示 -->
    <shortcut
        android:shortcutId="quick_ask"
        android:enabled="true"
        android:shortcutShortLabel="@string/shortcut_ask_label"
        android:shortcutLongLabel="@string/shortcut_ask_long_label">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.nexus.vision"
            android:targetClass="com.nexus.vision.os.AppActionsHandler" />
        <capability-binding android:key="actions.intent.GET_THING" />
    </shortcut>

</shortcuts>
```

---

#### ファイル 7: `app/src/main/res/values/strings.xml`（更新）

```xml
<!-- ファイルパス: app/src/main/res/values/strings.xml -->
<resources>
    <string name="app_name">NEXUS Vision</string>
    <string name="shortcut_ask_label">NEXUS に質問</string>
    <string name="shortcut_ask_long_label">NEXUS Vision に質問する</string>
    <string name="share_label">NEXUS Vision で処理</string>
    <string name="process_text_label">NEXUS 解析</string>
    <string name="qs_tile_label">NEXUS HUD</string>
</resources>
```

---

#### ファイル 8: `app/src/main/AndroidManifest.xml`（完全置換）



```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- ファイルパス: app/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

    <application
        android:name=".NexusApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:largeHeap="true"
        android:supportsRtl="true"
        android:theme="@style/Theme.NexusVision"
        tools:targetApi="35">

        <!-- メインランチャー Activity -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize"
            android:theme="@style/Theme.NexusVision">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- App Actions: shortcuts.xml -->
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts" />
        </activity>

        <!-- 共有受信 Activity (ACTION_SEND / ACTION_SEND_MULTIPLE) -->
        <activity
            android:name=".os.ShareReceiver"
            android:exported="true"
            android:label="@string/share_label"
            android:theme="@style/Theme.NexusVision">
            <!-- テキスト共有 -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/*" />
            </intent-filter>
            <!-- 画像共有(単一) -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
            <!-- 画像共有(複数) -->
            <intent-filter>
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
            <!-- ファイル共有 -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/*" />
            </intent-filter>
        </activity>

        <!-- テキスト選択メニュー Activity (ACTION_PROCESS_TEXT) -->
        <activity
            android:name=".os.ProcessTextActivity"
            android:exported="true"
            android:label="@string/process_text_label"
            android:theme="@style/Theme.NexusVision">
            <intent-filter>
                <action android:name="android.intent.action.PROCESS_TEXT" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>

        <!-- App Actions ハンドラ Activity -->
        <activity
            android:name=".os.AppActionsHandler"
            android:exported="true"
            android:theme="@style/Theme.NexusVision">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
            </intent-filter>
        </activity>

        <!-- クイック設定タイル -->
        <service
            android:name=".os.QuickSettingsTile"
            android:exported="true"
            android:label="@string/qs_tile_label"
            android:icon="@drawable/ic_launcher_foreground"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>

        <!-- HUD オーバーレイ (フォアグラウンドサービス) -->
        <service
            android:name=".os.HudOverlay"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="user_initiated_hud_overlay" />
        </service>

        <!-- WorkManager のフォアグラウンドサービス (バッチ処理用) -->
        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            tools:node="merge" />

        <uses-native-library android:name="libvndksupport.so" android:required="false" />
        <uses-native-library android:name="libOpenCL.so" android:required="false" />

        <meta-data
            android:name="com.google.mlkit.vision.DEPENDENCIES"
            android:value="ocr_ja" />

    </application>

</manifest>
```

ポイントは以下の追加・変更箇所です。

`FOREGROUND_SERVICE_SPECIAL_USE` パーミッションを追加（HudOverlay 用）。`ShareReceiver` Activity に4つの intent-filter（テキスト・画像単一・画像複数・ファイル）を宣言。`ProcessTextActivity` に `ACTION_PROCESS_TEXT` の intent-filter を宣言。`AppActionsHandler` Activity と `MainActivity` 内に `shortcuts.xml` の meta-data を配置。`QuickSettingsTile` サービスに `BIND_QUICK_SETTINGS_TILE` 権限と QS_TILE アクション。`HudOverlay` サービスに `foregroundServiceType="specialUse"` と `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` を宣言。既存の `SystemForegroundService`（バッチ処理用）と `uses-native-library`、ML Kit の meta-data はそのまま保持。