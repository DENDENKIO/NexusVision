

---

# NexusSheets 改善指示書 — 自動遷移・ファイル添付・高度検索

## プロジェクト情報

- **リポジトリ**: https://github.com/DENDENKIO/NexusVision/tree/master
- **パッケージ**: `com.nexus.vision`
- **言語**: Kotlin, Android API 31–35, arm64-v8a
- **ビルド**: AGP 8.7.3, Kotlin 2.2.21, Compose BOM 2025.04.00
- **ObjectBox**: 5.3.0（導入済み）
- **現状**: NexusSheets（Phase 8.5）は実装済み。IndexedFile / IndexedRow エンティティ、NexusSheetsIndex、SheetsQueryEngine が動作中。

## 修正する 3 つの問題

1. **ShareReceiver で登録後に MainActivity のチャットへ自動遷移しない** — 現在は ShareReceiver 自身の画面に結果を表示して終わり
2. **チャット画面からファイル（CSV / XLSX / PDF）を添付して登録できない** — 現在は画像ピッカー（`PickVisualMedia.ImageOnly`）しかない
3. **高度な検索クエリに対応していない** — 列名指定フィルタ、複数条件 AND、列値による行抽出が未実装

## 変更するファイル（7 ファイル）

---

### ファイル 1: `app/src/main/java/com/nexus/vision/os/ShareReceiver.kt`（完全置換）

**変更内容**: ファイル登録後に `finish()` で ShareReceiver を閉じ、MainActivity へ Intent で遷移する。登録メッセージは Intent の Extra としてチャットに渡す。

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
            result.getOrElse { "元テキスト:\n$text" }
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
                    results.appendLine("(エラー: ${e.message})")
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
```

---

### ファイル 2: `app/src/main/java/com/nexus/vision/MainActivity.kt`（完全置換）

**変更内容**:
- `onNewIntent` で ShareReceiver からの `EXTRA_SHARE_RESULT` を受け取り、ViewModel にメッセージ追加
- ファイルピッカー（`OpenDocument`）を追加し、CSV / XLSX / PDF を選択可能に
- 既存の画像ピッカーはそのまま維持

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/MainActivity.kt
package com.nexus.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.nexus.vision.os.ShareReceiver
import com.nexus.vision.ui.MainScreen
import com.nexus.vision.ui.MainViewModel
import com.nexus.vision.ui.theme.NexusVisionTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var onImageSelected: ((Uri) -> Unit)? = null
    private var onMultipleImagesSelected: ((List<Uri>) -> Unit)? = null
    private lateinit var mainViewModel: MainViewModel

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                Log.d(TAG, "Image selected: $uri")
                onImageSelected?.invoke(uri)
            }
        }

    private val pickMultipleMedia =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
            if (uris.isNotEmpty()) {
                Log.d(TAG, "Multiple images selected: ${uris.size}")
                onMultipleImagesSelected?.invoke(uris)
            }
        }

    /**
     * ファイルピッカー: CSV / XLSX / PDF を選択
     */
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                Log.d(TAG, "File selected: $uri")
                // 永続的な読み取り権限を取得
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "Could not take persistable permission: ${e.message}")
                }
                mainViewModel.onFileSelected(uri)
            }
        }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            for ((permission, granted) in grants) {
                Log.d(TAG, "Permission $permission: $granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRequiredPermissions()

        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // ShareReceiver からの結果を処理
        handleShareResult(intent)

        setContent {
            NexusVisionTheme {
                MainScreen(
                    viewModel = mainViewModel,
                    onPickImage = { launchImagePicker() },
                    onPickFile = { launchFilePicker() },
                    onPickMultipleImages = { launchMultipleImagePicker() },
                    onImageSelected = { callback ->
                        onImageSelected = callback
                    },
                    onMultipleImagesSelected = { callback ->
                        onMultipleImagesSelected = callback
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareResult(intent)
    }

    /**
     * ShareReceiver から渡された結果テキストをチャットに追加する
     */
    private fun handleShareResult(intent: Intent?) {
        val shareResult = intent?.getStringExtra(ShareReceiver.EXTRA_SHARE_RESULT)
        if (!shareResult.isNullOrBlank()) {
            mainViewModel.addShareResult(shareResult)
            // Extra を消費して再処理を防止
            intent.removeExtra(ShareReceiver.EXTRA_SHARE_RESULT)
        }
    }

    private fun launchImagePicker() {
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchMultipleImagePicker() {
        pickMultipleMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchFilePicker() {
        pickFile.launch(
            arrayOf(
                "text/csv",
                "text/comma-separated-values",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/pdf",
                "application/octet-stream"
            )
        )
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions.launch(permissions.toTypedArray())
        }
    }
}
```

---

### ファイル 3: `app/src/main/java/com/nexus/vision/ui/MainScreen.kt`（完全置換）

**変更内容**: `onPickFile` コールバックを追加し、`ChatInput` に渡す。

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ui/MainScreen.kt
package com.nexus.vision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.vision.ui.components.ChatBubble
import com.nexus.vision.ui.components.ChatInput
import com.nexus.vision.ui.components.CropSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onPickImage: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onPickMultipleImages: () -> Unit = {},
    onImageSelected: ((android.net.Uri) -> Unit) -> Unit = {},
    onMultipleImagesSelected: ((List<android.net.Uri>) -> Unit) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        onImageSelected { uri ->
            viewModel.setSelectedImage(uri)
        }
        onMultipleImagesSelected { uris ->
            viewModel.startBatchEnhance(uris)
        }
    }

    LaunchedEffect(uiState.requestBatchPicker) {
        if (uiState.requestBatchPicker) {
            viewModel.consumeBatchPickerRequest()
            onPickMultipleImages()
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NEXUS Vision",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        ThermalBadge(levelName = uiState.thermalLevelName)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                HorizontalDivider()

                if (uiState.isBatchRunning && uiState.batchProgressText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = uiState.batchProgressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (uiState.cropMode && uiState.cropThumbnail != null) {
                    val (confirmLabel, headerLabel) = when (uiState.cropPurpose) {
                        CropPurpose.ENHANCE -> "この範囲を高画質化" to "高画質化したい範囲をドラッグで選択"
                        CropPurpose.ZOOM -> "この範囲を超解像" to "拡大したい範囲をドラッグで選択"
                    }

                    CropSelector(
                        thumbnail = uiState.cropThumbnail!!,
                        imageWidth = uiState.cropImageWidth,
                        imageHeight = uiState.cropImageHeight,
                        headerLabel = headerLabel,
                        confirmLabel = confirmLabel,
                        onConfirm = { left, top, right, bottom ->
                            viewModel.onCropConfirmed(left, top, right, bottom)
                        },
                        onCancel = { viewModel.cancelCropMode() }
                    )
                } else {
                    ChatInput(
                        text = uiState.inputText,
                        onTextChange = { viewModel.updateInputText(it) },
                        selectedImageUri = uiState.selectedImageUri,
                        onPickImage = onPickImage,
                        onPickFile = onPickFile,
                        onClearImage = { viewModel.clearSelectedImage() },
                        onSend = { viewModel.sendMessage() },
                        isEnabled = !uiState.isProcessing
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!uiState.isEngineReady && !uiState.isProcessing) {
                EngineLoadBanner(
                    statusMessage = uiState.statusMessage,
                    onLoadClick = { viewModel.loadEngine() }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    ChatBubble(message = message)
                }
            }
        }
    }
}

@Composable
private fun EngineLoadBanner(
    statusMessage: String,
    onLoadClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Button(
                onClick = onLoadClick,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("エンジンをロード")
            }
        }
    }
}

@Composable
fun ThermalBadge(levelName: String) {
    val color = when (levelName) {
        "NONE", "LIGHT" -> MaterialTheme.colorScheme.outline
        "MODERATE" -> MaterialTheme.colorScheme.tertiary
        "SEVERE" -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.error
    }

    if (levelName != "NONE") {
        Text(
            text = levelName,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
```

---

### ファイル 4: `app/src/main/java/com/nexus/vision/ui/components/ChatInput.kt`（完全置換）

**変更内容**: ファイル添付ボタン（クリップアイコン）を画像ボタンの横に追加。

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ui/components/ChatInput.kt
package com.nexus.vision.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * チャット入力エリア
 *
 * Phase 4: 基本実装
 * Phase 8.5: ファイル添付ボタン追加
 */
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    selectedImageUri: Uri?,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onClearImage: () -> Unit,
    onSend: () -> Unit,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        // 選択済み画像プレビュー
        if (selectedImageUri != null) {
            Box(
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                AsyncImage(
                    model = selectedImageUri,
                    contentDescription = "選択された画像",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                IconButton(
                    onClick = onClearImage,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .background(
                            MaterialTheme.colorScheme.error,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "画像を削除",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom
        ) {
            // 画像選択ボタン
            IconButton(onClick = onPickImage) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "画像を選択",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // ファイル添付ボタン（CSV / XLSX / PDF）
            IconButton(onClick = onPickFile) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "ファイルを添付 (CSV/Excel/PDF)",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }

            // テキスト入力フィールド
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("メッセージを入力...") },
                maxLines = 4,
                enabled = isEnabled,
                shape = RoundedCornerShape(24.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // 送信ボタン
            IconButton(
                onClick = onSend,
                enabled = isEnabled && (text.isNotBlank() || selectedImageUri != null)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "送信",
                    tint = if (isEnabled && (text.isNotBlank() || selectedImageUri != null))
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        }
    }
}
```

---

### ファイル 5: `app/src/main/java/com/nexus/vision/sheets/NexusSheetsIndex.kt`（完全置換）

**変更内容**: 列名指定フィルタ・複数条件 AND・列値による行抽出のための `filterByColumn` メソッドと `multiColumnFilter` メソッドを追加。

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/sheets/NexusSheetsIndex.kt
package com.nexus.vision.sheets

import android.util.Log
import io.objectbox.Box
import io.objectbox.BoxStore
import org.json.JSONArray

/**
 * NexusSheets インデックス管理
 *
 * Phase 8.5: NexusSheets
 * Phase 8.6: 高度検索（列フィルタ、複数条件AND、列値行抽出）
 */
class NexusSheetsIndex(boxStore: BoxStore) {

    companion object {
        private const val TAG = "NexusSheetsIndex"
        private const val MAX_SEARCH_RESULTS = 50
    }

    private val fileBox: Box<IndexedFile> = boxStore.boxFor(IndexedFile::class.java)
    private val rowBox: Box<IndexedRow> = boxStore.boxFor(IndexedRow::class.java)

    // ── ファイル登録 ──

    fun addParsedTable(
        rows: List<List<String>>,
        fileName: String,
        fileType: String
    ): Long {
        if (rows.isEmpty()) return -1

        removeByFileName(fileName)

        val headers = rows.first()
        val headerJson = JSONArray(headers).toString()
        val colCount = rows.maxOf { it.size }

        val file = IndexedFile(
            fileName = fileName,
            fileType = fileType,
            importedAt = System.currentTimeMillis(),
            rowCount = rows.size,
            colCount = colCount,
            headerJson = headerJson,
            summary = "ヘッダー: ${headers.joinToString(", ")} / ${rows.size}行×${colCount}列"
        )
        fileBox.put(file)
        val fileId = file.id

        val indexedRows = rows.mapIndexed { index, cells ->
            val cellsJsonStr = JSONArray(cells).toString()
            val searchStr = cells.joinToString(" ")
            IndexedRow(
                fileId = fileId,
                rowIndex = index,
                cellsJson = cellsJsonStr,
                searchText = searchStr
            )
        }
        rowBox.put(indexedRows)

        Log.i(TAG, "Indexed '$fileName': ${rows.size} rows, $colCount cols, fileId=$fileId")
        return fileId
    }

    fun addPdfText(pages: List<String>, fileName: String): Long {
        removeByFileName(fileName)

        val allLines = mutableListOf<List<String>>()
        allLines.add(listOf("ページ", "内容"))

        for ((pageIdx, pageText) in pages.withIndex()) {
            val paragraphs = pageText.split("\n").filter { it.isNotBlank() }
            for (para in paragraphs) {
                allLines.add(listOf("${pageIdx + 1}", para.trim()))
            }
        }

        val file = IndexedFile(
            fileName = fileName,
            fileType = "pdf",
            importedAt = System.currentTimeMillis(),
            rowCount = allLines.size,
            colCount = 2,
            headerJson = JSONArray(listOf("ページ", "内容")).toString(),
            summary = "${pages.size}ページ / ${allLines.size - 1}段落"
        )
        fileBox.put(file)
        val fileId = file.id

        val indexedRows = allLines.mapIndexed { index, cells ->
            IndexedRow(
                fileId = fileId,
                rowIndex = index,
                cellsJson = JSONArray(cells).toString(),
                searchText = cells.joinToString(" ")
            )
        }
        rowBox.put(indexedRows)

        Log.i(TAG, "Indexed PDF '$fileName': ${allLines.size} rows, fileId=$fileId")
        return fileId
    }

    // ── 検索 ──

    fun search(query: String): List<SearchResult> {
        val keywords = query.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (keywords.isEmpty()) return emptyList()

        val firstKeyword = keywords.first()
        val candidates = rowBox.query(
            IndexedRow_.searchText.contains(firstKeyword, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
        ).build().find()

        val filtered = if (keywords.size > 1) {
            candidates.filter { row ->
                keywords.all { kw -> row.searchText.contains(kw, ignoreCase = true) }
            }
        } else {
            candidates
        }

        val fileCache = mutableMapOf<Long, IndexedFile?>()
        return filtered.take(MAX_SEARCH_RESULTS).mapNotNull { row ->
            val file = fileCache.getOrPut(row.fileId) { fileBox.get(row.fileId) }
                ?: return@mapNotNull null
            val cells = parseCellsJson(row.cellsJson)
            val headers = parseCellsJson(file.headerJson)
            SearchResult(
                fileName = file.fileName,
                fileId = row.fileId,
                rowIndex = row.rowIndex,
                cells = cells,
                headers = headers
            )
        }
    }

    // ── 列名指定フィルタ ──

    /**
     * 特定の列に特定の値を含む行を抽出する。
     * 例: colName="地域", value="東京" → 地域列に「東京」を含む全行を返す
     * targetFileId=0 なら全ファイル横断
     */
    fun filterByColumn(
        colName: String,
        value: String,
        targetFileId: Long = 0
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        val files = if (targetFileId > 0) {
            listOfNotNull(fileBox.get(targetFileId))
        } else {
            fileBox.all
        }

        for (file in files) {
            val headers = parseCellsJson(file.headerJson)
            val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
            if (colIndex < 0) continue

            // まず searchText で value を含む行を絞り込み
            val candidates = rowBox.query(
                IndexedRow_.fileId.equal(file.id)
                    .and(IndexedRow_.searchText.contains(value, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE))
            ).build().find()

            for (row in candidates) {
                if (row.rowIndex == 0) continue // ヘッダースキップ
                val cells = parseCellsJson(row.cellsJson)
                val cellValue = cells.getOrElse(colIndex) { "" }
                // 指定列に value が含まれるか厳密チェック
                if (cellValue.contains(value, ignoreCase = true)) {
                    results.add(
                        SearchResult(
                            fileName = file.fileName,
                            fileId = row.fileId,
                            rowIndex = row.rowIndex,
                            cells = cells,
                            headers = headers
                        )
                    )
                }
            }
        }

        return results.take(MAX_SEARCH_RESULTS)
    }

    /**
     * 複数条件 AND フィルタ:
     * 例: conditions = [("地域","東京"), ("年度","2024")]
     * → 地域列に「東京」AND 年度列に「2024」を含む行
     */
    fun multiColumnFilter(
        conditions: List<Pair<String, String>>,
        targetFileId: Long = 0
    ): List<SearchResult> {
        if (conditions.isEmpty()) return emptyList()

        val files = if (targetFileId > 0) {
            listOfNotNull(fileBox.get(targetFileId))
        } else {
            fileBox.all
        }

        val results = mutableListOf<SearchResult>()

        for (file in files) {
            val headers = parseCellsJson(file.headerJson)

            // 各条件の列インデックスを事前に解決
            val resolvedConditions = conditions.mapNotNull { (colName, value) ->
                val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
                if (colIndex >= 0) Triple(colIndex, colName, value) else null
            }

            if (resolvedConditions.size != conditions.size) continue // 全列が見つからなければスキップ

            // 最初の条件値で DB 絞り込み
            val firstValue = resolvedConditions.first().third
            val candidates = rowBox.query(
                IndexedRow_.fileId.equal(file.id)
                    .and(IndexedRow_.searchText.contains(firstValue, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE))
            ).build().find()

            for (row in candidates) {
                if (row.rowIndex == 0) continue
                val cells = parseCellsJson(row.cellsJson)

                // 全条件を満たすかチェック
                val allMatch = resolvedConditions.all { (colIndex, _, value) ->
                    val cellValue = cells.getOrElse(colIndex) { "" }
                    cellValue.contains(value, ignoreCase = true)
                }

                if (allMatch) {
                    results.add(
                        SearchResult(
                            fileName = file.fileName,
                            fileId = row.fileId,
                            rowIndex = row.rowIndex,
                            cells = cells,
                            headers = headers
                        )
                    )
                }
            }
        }

        return results.take(MAX_SEARCH_RESULTS)
    }

    /**
     * 特定列の値でフィルタし、別の列の値のみ抽出する。
     * 例: filterCol="地域", filterValue="東京", extractCol="売上"
     * → 地域が東京の行から売上列の値のみ返す
     */
    fun filterAndExtractColumn(
        filterCol: String,
        filterValue: String,
        extractCol: String,
        targetFileId: Long = 0
    ): List<ExtractResult> {
        val results = mutableListOf<ExtractResult>()

        val files = if (targetFileId > 0) {
            listOfNotNull(fileBox.get(targetFileId))
        } else {
            fileBox.all
        }

        for (file in files) {
            val headers = parseCellsJson(file.headerJson)
            val filterColIndex = headers.indexOfFirst { it.contains(filterCol, ignoreCase = true) }
            val extractColIndex = headers.indexOfFirst { it.contains(extractCol, ignoreCase = true) }
            if (filterColIndex < 0 || extractColIndex < 0) continue

            val candidates = rowBox.query(
                IndexedRow_.fileId.equal(file.id)
                    .and(IndexedRow_.searchText.contains(filterValue, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE))
            ).build().find()

            for (row in candidates) {
                if (row.rowIndex == 0) continue
                val cells = parseCellsJson(row.cellsJson)
                val filterCellValue = cells.getOrElse(filterColIndex) { "" }
                if (filterCellValue.contains(filterValue, ignoreCase = true)) {
                    val extractedValue = cells.getOrElse(extractColIndex) { "" }
                    results.add(
                        ExtractResult(
                            fileName = file.fileName,
                            rowIndex = row.rowIndex,
                            filterCol = headers[filterColIndex],
                            filterValue = filterCellValue,
                            extractCol = headers[extractColIndex],
                            extractedValue = extractedValue,
                            fullRow = cells,
                            headers = headers
                        )
                    )
                }
            }
        }

        return results.take(MAX_SEARCH_RESULTS)
    }

    // ── 行列クロス検索 ──

    fun crossLookup(
        rowKeyword: String,
        colName: String,
        targetFileId: Long = 0
    ): List<CrossResult> {
        val results = mutableListOf<CrossResult>()

        val files = if (targetFileId > 0) {
            listOfNotNull(fileBox.get(targetFileId))
        } else {
            fileBox.all
        }

        for (file in files) {
            val headers = parseCellsJson(file.headerJson)
            val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
            if (colIndex < 0) continue

            val matchingRows = rowBox.query(
                IndexedRow_.fileId.equal(file.id)
                    .and(IndexedRow_.searchText.contains(rowKeyword, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE))
            ).build().find()

            for (row in matchingRows) {
                if (row.rowIndex == 0) continue
                val cells = parseCellsJson(row.cellsJson)
                val value = cells.getOrElse(colIndex) { "" }
                if (value.isNotBlank()) {
                    results.add(
                        CrossResult(
                            fileName = file.fileName,
                            rowKeyword = rowKeyword,
                            colName = headers[colIndex],
                            value = value,
                            rowIndex = row.rowIndex,
                            fullRow = cells,
                            headers = headers
                        )
                    )
                }
            }
        }

        return results.take(MAX_SEARCH_RESULTS)
    }

    // ── 列取得・集計 ──

    fun getColumn(fileId: Long, colName: String): ColumnResult? {
        val file = fileBox.get(fileId) ?: return null
        val headers = parseCellsJson(file.headerJson)
        val colIndex = headers.indexOfFirst { it.contains(colName, ignoreCase = true) }
        if (colIndex < 0) return null

        val rows = rowBox.query(IndexedRow_.fileId.equal(fileId))
            .order(IndexedRow_.rowIndex)
            .build().find()

        val values = rows
            .filter { it.rowIndex > 0 }
            .map { row ->
                val cells = parseCellsJson(row.cellsJson)
                cells.getOrElse(colIndex) { "" }
            }

        return ColumnResult(fileName = file.fileName, colName = headers[colIndex], values = values)
    }

    fun getRows(fileId: Long, fromRow: Int = 0, toRow: Int = Int.MAX_VALUE): List<List<String>> {
        val rows = rowBox.query(IndexedRow_.fileId.equal(fileId))
            .order(IndexedRow_.rowIndex)
            .build().find()
        return rows.filter { it.rowIndex in fromRow..toRow }.map { parseCellsJson(it.cellsJson) }
    }

    fun aggregateColumn(fileId: Long, colName: String): AggregateResult? {
        val colResult = getColumn(fileId, colName) ?: return null
        val numbers = colResult.values.mapNotNull { it.replace(",", "").toDoubleOrNull() }
        if (numbers.isEmpty()) return AggregateResult(
            colName = colResult.colName, fileName = colResult.fileName,
            count = 0, sum = 0.0, avg = 0.0, min = 0.0, max = 0.0,
            message = "「${colResult.colName}」列に数値データがありません"
        )
        return AggregateResult(
            colName = colResult.colName, fileName = colResult.fileName,
            count = numbers.size, sum = numbers.sum(), avg = numbers.average(),
            min = numbers.min(), max = numbers.max()
        )
    }

    fun aggregateColumnAllFiles(colName: String): List<AggregateResult> {
        return fileBox.all.mapNotNull { aggregateColumn(it.id, colName) }.filter { it.count > 0 }
    }

    // ── ファイル管理 ──

    fun listFiles(): List<IndexedFile> = fileBox.all
    fun getFile(fileId: Long): IndexedFile? = fileBox.get(fileId)

    fun findFileByName(name: String): IndexedFile? {
        return fileBox.query(
            IndexedFile_.fileName.contains(name, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
        ).build().findFirst()
    }

    fun removeFile(fileId: Long) {
        val rows = rowBox.query(IndexedRow_.fileId.equal(fileId)).build().find()
        rowBox.remove(rows)
        fileBox.remove(fileId)
        Log.i(TAG, "Removed file id=$fileId (${rows.size} rows)")
    }

    fun removeByFileName(fileName: String) {
        val existing = fileBox.query(
            IndexedFile_.fileName.equal(fileName, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
        ).build().find()
        for (f in existing) { removeFile(f.id) }
    }

    fun removeAll() { rowBox.removeAll(); fileBox.removeAll(); Log.i(TAG, "All removed") }
    fun fileCount(): Long = fileBox.count()
    fun totalRowCount(): Long = rowBox.count()

    /**
     * 全ファイルのヘッダー一覧を返す（検索クエリ解析用）
     */
    fun getAllHeaders(): Map<String, List<String>> {
        return fileBox.all.associate { file ->
            file.fileName to parseCellsJson(file.headerJson)
        }
    }

    // ── ヘルパー ──

    fun parseCellsJson(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    // ── データクラス ──

    data class SearchResult(
        val fileName: String, val fileId: Long, val rowIndex: Int,
        val cells: List<String>, val headers: List<String>
    ) {
        fun toReadableText(): String {
            return cells.mapIndexed { i, cell ->
                val header = headers.getOrElse(i) { "列${i + 1}" }
                "$header: $cell"
            }.joinToString(", ")
        }
    }

    data class CrossResult(
        val fileName: String, val rowKeyword: String, val colName: String,
        val value: String, val rowIndex: Int,
        val fullRow: List<String>, val headers: List<String>
    )

    data class ColumnResult(val fileName: String, val colName: String, val values: List<String>)

    data class ExtractResult(
        val fileName: String, val rowIndex: Int,
        val filterCol: String, val filterValue: String,
        val extractCol: String, val extractedValue: String,
        val fullRow: List<String>, val headers: List<String>
    )

    data class AggregateResult(
        val colName: String, val fileName: String,
        val count: Int, val sum: Double, val avg: Double, val min: Double, val max: Double,
        val message: String? = null
    ) {
        fun toText(): String {
            if (message != null) return message
            return "$fileName の「$colName」: 件数=$count, 合計=${formatNum(sum)}, 平均=${formatNum(avg)}, 最小=${formatNum(min)}, 最大=${formatNum(max)}"
        }
        private fun formatNum(v: Double): String =
            if (v == v.toLong().toDouble()) "%,.0f".format(v) else "%,.2f".format(v)
    }
}
```

---

### ファイル 6: `app/src/main/java/com/nexus/vision/sheets/SheetsQueryEngine.kt`（完全置換）

**変更内容**: 高度な検索クエリを解析するパーサーを追加。以下のパターンに対応:
- `A列「東京」のすべて` → 列フィルタ
- `A列「東京」のすべて + B列「2024」のすべて` → 複数条件 AND
- `「東京」の文字が入っているA列の行を抽出` → 列値行抽出
- `A列が「東京」のB列` → フィルタ＋別列抽出

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/sheets/SheetsQueryEngine.kt
package com.nexus.vision.sheets

import android.util.Log
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager

/**
 * NexusSheets クエリエンジン
 *
 * Phase 8.5: NexusSheets
 * Phase 8.6: 高度検索クエリ対応
 *   - 列名指定フィルタ: A列「東京」のすべて
 *   - 複数条件 AND: A列「東京」+ B列「2024」
 *   - 列値による行抽出: 「東京」が入っているA列の行
 *   - フィルタ+別列抽出: A列が「東京」のB列
 */
class SheetsQueryEngine(private val index: NexusSheetsIndex) {

    companion object {
        private const val TAG = "SheetsQueryEngine"
        private const val MAX_CONTEXT_LENGTH = 3000

        private val SUM_KEYWORDS = listOf("合計", "総計", "sum", "total")
        private val AVG_KEYWORDS = listOf("平均", "average", "avg", "mean")
        private val MAX_KEYWORDS = listOf("最大", "最高", "max", "一番大きい", "一番高い", "トップ")
        private val MIN_KEYWORDS = listOf("最小", "最低", "min", "一番小さい", "一番低い", "ワースト")
        private val COUNT_KEYWORDS = listOf("件数", "何件", "いくつ", "count", "カウント")
        private val COMPARE_KEYWORDS = listOf("比較", "違い", "差", "compare", "vs")
        private val LIST_KEYWORDS = listOf("一覧", "リスト", "全部", "すべて", "list")
        private val DELETE_KEYWORDS = listOf("削除", "消して", "除去", "remove", "delete")
    }

    suspend fun query(userQuery: String): String {
        val trimmed = userQuery.trim()
        Log.i(TAG, "Query: $trimmed")

        // 1. ファイル一覧
        if (LIST_KEYWORDS.any { trimmed.contains(it, ignoreCase = true) } &&
            (trimmed.contains("ファイル") || trimmed.contains("登録") || trimmed.contains("シート"))) {
            return handleListFiles()
        }

        // 2. 削除
        if (DELETE_KEYWORDS.any { trimmed.contains(it, ignoreCase = true) } &&
            (trimmed.contains("ファイル") || trimmed.contains("シート"))) {
            return handleDelete(trimmed)
        }

        // 3. 登録ファイルが 0 件
        if (index.fileCount() == 0L) {
            return "登録されたファイルがありません。PDF / CSV / Excel を共有またはクリップアイコンから添付してください。"
        }

        // 4. 高度検索クエリの判定（列指定フィルタ・複数条件）
        val advancedResult = tryAdvancedQuery(trimmed)
        if (advancedResult != null) return advancedResult

        // 5. 行列クロス検索
        val crossResult = tryCrossLookup(trimmed)
        if (crossResult != null) return crossResult

        // 6. 集計
        val aggregateResult = tryAggregate(trimmed)
        if (aggregateResult != null) return aggregateResult

        // 7. 汎用検索
        return handleSearch(trimmed)
    }

    // ── 高度検索クエリ ──

    /**
     * 高度な検索パターンを解析する。
     *
     * 対応パターン:
     *   ① 「A列「X」のすべて」「A列がXの行」 → filterByColumn
     *   ② 「A列「X」+ B列「Y」」「A列「X」かつB列「Y」」 → multiColumnFilter
     *   ③ 「「X」が入っているA列の行」「A列にXを含む行」 → filterByColumn
     *   ④ 「A列が「X」のB列」「A列がXの場合のB列」 → filterAndExtractColumn
     */
    private fun tryAdvancedQuery(query: String): String? {
        // まず全ファイルのヘッダーを取得
        val allHeadersMap = index.getAllHeaders()
        val allHeaders = allHeadersMap.values.flatten().distinct()
        if (allHeaders.isEmpty()) return null

        // パターン②: 複数条件 AND「A列「X」+ B列「Y」」
        val multiPattern = Regex(
            """(.+?)(?:列|カラム)?[「「](.+?)[」」].*?(?:\+|＋|かつ|AND|and|且つ).*?(.+?)(?:列|カラム)?[「「](.+?)[」」]"""
        )
        multiPattern.find(query)?.let { match ->
            val col1 = resolveColumnName(match.groupValues[1].trim(), allHeaders)
            val val1 = match.groupValues[2].trim()
            val col2 = resolveColumnName(match.groupValues[3].trim(), allHeaders)
            val val2 = match.groupValues[4].trim()

            if (col1 != null && col2 != null) {
                val conditions = listOf(col1 to val1, col2 to val2)
                val results = index.multiColumnFilter(conditions)
                if (results.isNotEmpty()) {
                    return formatFilterResults(results, "${col1}「${val1}」かつ ${col2}「${val2}」")
                }
                return "条件に一致する行が見つかりませんでした。(${col1}=${val1} AND ${col2}=${val2})"
            }
        }

        // パターン④: 「A列が「X」のB列」「A列がXのB列を抽出」
        val extractPattern = Regex(
            """(.+?)(?:列|カラム)?(?:が|＝|=)[「「]?(.+?)[」」]?(?:の|における)(.+?)(?:列|カラム)"""
        )
        extractPattern.find(query)?.let { match ->
            val filterCol = resolveColumnName(match.groupValues[1].trim(), allHeaders)
            val filterVal = match.groupValues[2].trim()
            val extractCol = resolveColumnName(match.groupValues[3].trim(), allHeaders)

            if (filterCol != null && extractCol != null) {
                val results = index.filterAndExtractColumn(filterCol, filterVal, extractCol)
                if (results.isNotEmpty()) {
                    return formatExtractResults(results, filterCol, filterVal, extractCol)
                }
                return "条件に一致するデータが見つかりませんでした。(${filterCol}=${filterVal} の ${extractCol})"
            }
        }

        // パターン①③: 「A列「X」のすべて」「「X」が入っているA列の行」「A列がXの行」
        val singleFilterPatterns = listOf(
            // 「A列「X」のすべて」「A列「X」」
            Regex("""(.+?)(?:列|カラム)?[「「](.+?)[」」](?:のすべて|の行|を抽出|$)"""),
            // 「「X」が入っているA列の行」「「X」を含むA列」
            Regex("""[「「](.+?)[」」](?:が入っている|を含む|がある|の文字が入っている)(.+?)(?:列|カラム)?(?:の行|$)"""),
            // 「A列がXの行」「A列にXを含む行」
            Regex("""(.+?)(?:列|カラム)?(?:が|に)(.+?)(?:を含む|の)(?:行|すべて|データ)""")
        )

        for (pattern in singleFilterPatterns) {
            val match = pattern.find(query) ?: continue

            val part1 = match.groupValues[1].trim()
            val part2 = match.groupValues[2].trim()

            // パターンにより列名と値の位置が異なる
            val (colCandidate, valueCandidate) = if (pattern == singleFilterPatterns[1]) {
                // 「X」が入っているA列の行 → part1=値, part2=列名
                part2 to part1
            } else {
                // A列「X」のすべて → part1=列名, part2=値
                part1 to part2
            }

            val resolvedCol = resolveColumnName(colCandidate, allHeaders)
            if (resolvedCol != null) {
                val results = index.filterByColumn(resolvedCol, valueCandidate)
                if (results.isNotEmpty()) {
                    return formatFilterResults(results, "${resolvedCol}列に「${valueCandidate}」を含む行")
                }
                return "「${resolvedCol}」列に「${valueCandidate}」を含む行が見つかりませんでした。"
            }
        }

        return null
    }

    /**
     * ユーザー入力をヘッダー名に解決する。
     * 「A」「地域」「売上列」等を実際のヘッダー名にマッチ。
     */
    private fun resolveColumnName(input: String, allHeaders: List<String>): String? {
        val cleaned = input.replace("列", "").replace("カラム", "").replace("column", "").trim()
        if (cleaned.isBlank()) return null

        // 完全一致
        allHeaders.find { it.equals(cleaned, ignoreCase = true) }?.let { return it }

        // 部分一致
        allHeaders.find { it.contains(cleaned, ignoreCase = true) }?.let { return it }

        // 逆方向: ヘッダー名が input に含まれる
        allHeaders.find { cleaned.contains(it, ignoreCase = true) }?.let { return it }

        return null
    }

    private fun formatFilterResults(results: List<NexusSheetsIndex.SearchResult>, label: String): String {
        return buildString {
            appendLine("【フィルタ結果】$label — ${results.size} 件")
            appendLine()
            val byFile = results.groupBy { it.fileName }
            for ((fileName, fileResults) in byFile) {
                appendLine("▼ $fileName (${fileResults.size}件)")
                for (r in fileResults.take(30)) {
                    appendLine("  行${r.rowIndex}: ${r.toReadableText()}")
                }
                if (fileResults.size > 30) appendLine("  ... 他 ${fileResults.size - 30} 件")
                appendLine()
            }
        }
    }

    private fun formatExtractResults(
        results: List<NexusSheetsIndex.ExtractResult>,
        filterCol: String, filterVal: String, extractCol: String
    ): String {
        return buildString {
            appendLine("【抽出結果】${filterCol}が「${filterVal}」の ${extractCol} — ${results.size} 件")
            appendLine()
            val byFile = results.groupBy { it.fileName }
            for ((fileName, fileResults) in byFile) {
                appendLine("▼ $fileName (${fileResults.size}件)")
                for (r in fileResults.take(30)) {
                    appendLine("  行${r.rowIndex}: ${r.extractCol} = ${r.extractedValue} (${r.filterCol}: ${r.filterValue})")
                }
                if (fileResults.size > 30) appendLine("  ... 他 ${fileResults.size - 30} 件")
                appendLine()
            }
        }
    }

    // ── 以下は既存の機能（変更なし） ──

    private fun handleListFiles(): String {
        val files = index.listFiles()
        if (files.isEmpty()) return "登録されたファイルはありません。"
        return buildString {
            appendLine("【登録ファイル一覧】${files.size} 件")
            appendLine()
            for (file in files) {
                appendLine("${file.fileName} (${file.fileType}) — ${file.rowCount}行×${file.colCount}列")
                appendLine("  登録日時: ${formatTime(file.importedAt)}")
                appendLine("  ${file.summary}")
                appendLine()
            }
        }
    }

    private fun handleDelete(query: String): String {
        if (query.contains("全") && DELETE_KEYWORDS.any { query.contains(it) }) {
            val count = index.fileCount()
            index.removeAll()
            return "登録ファイルをすべて削除しました（${count} 件）。"
        }
        val files = index.listFiles()
        val matched = files.find { file ->
            query.contains(file.fileName, ignoreCase = true) ||
                    query.contains(file.fileName.substringBeforeLast("."), ignoreCase = true)
        }
        return if (matched != null) {
            index.removeFile(matched.id)
            "「${matched.fileName}」を削除しました。"
        } else {
            "削除対象のファイルが見つかりません。「ファイル一覧」で確認してください。"
        }
    }

    private fun tryCrossLookup(query: String): String? {
        val patterns = listOf(
            Regex("(.+?)の(.+?)(?:は|を|が|について|$)"),
            Regex("(.+?)における(.+?)(?:は|を|が|$)")
        )
        for (pattern in patterns) {
            val match = pattern.find(query) ?: continue
            val rowKey = match.groupValues[1].trim()
            val colKey = match.groupValues[2].trim()

            val isAggregate = (SUM_KEYWORDS + AVG_KEYWORDS + MAX_KEYWORDS + MIN_KEYWORDS + COUNT_KEYWORDS)
                .any { colKey.contains(it, ignoreCase = true) || rowKey.contains(it, ignoreCase = true) }
            if (isAggregate) continue

            val files = index.listFiles()
            val hasMatchingHeader = files.any { file ->
                val headers = index.parseCellsJson(file.headerJson)
                headers.any { it.contains(colKey, ignoreCase = true) }
            }
            if (!hasMatchingHeader) continue

            val results = index.crossLookup(rowKey, colKey)
            if (results.isNotEmpty()) {
                return buildString {
                    appendLine("【${rowKey} × ${colKey}】${results.size} 件")
                    appendLine()
                    for (r in results) {
                        appendLine("${r.fileName}: ${r.rowKeyword} の ${r.colName} = ${r.value}")
                        val otherInfo = r.headers.zip(r.fullRow)
                            .filter { it.first != r.colName }.take(5)
                            .joinToString(", ") { "${it.first}: ${it.second}" }
                        if (otherInfo.isNotBlank()) appendLine("  (他: $otherInfo)")
                    }
                }
            }
        }
        return null
    }

    private fun tryAggregate(query: String): String? {
        val isSum = SUM_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isAvg = AVG_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isMax = MAX_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isMin = MIN_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val isCount = COUNT_KEYWORDS.any { query.contains(it, ignoreCase = true) }

        if (!isSum && !isAvg && !isMax && !isMin && !isCount) return null

        val allHeaders = index.listFiles().flatMap { file ->
            try {
                val arr = org.json.JSONArray(file.headerJson)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) { emptyList() }
        }.distinct()

        val matchedCol = allHeaders.find { header -> query.contains(header, ignoreCase = true) }

        if (matchedCol == null) {
            return buildString {
                appendLine("集計対象の列名が不明です。利用可能な列:")
                appendLine()
                for (file in index.listFiles()) {
                    appendLine("${file.fileName}: ${file.summary}")
                }
            }
        }

        val targetFile = index.listFiles().find { file ->
            query.contains(file.fileName, ignoreCase = true) ||
                    query.contains(file.fileName.substringBeforeLast("."), ignoreCase = true)
        }

        val aggregateResults = if (targetFile != null) {
            listOfNotNull(index.aggregateColumn(targetFile.id, matchedCol))
        } else {
            index.aggregateColumnAllFiles(matchedCol)
        }

        if (aggregateResults.isEmpty()) return "「$matchedCol」列の数値データが見つかりませんでした。"

        return buildString {
            for (agg in aggregateResults) {
                when {
                    isSum -> appendLine("${agg.fileName} の「${agg.colName}」合計: ${formatNum(agg.sum)} (${agg.count}件)")
                    isAvg -> appendLine("${agg.fileName} の「${agg.colName}」平均: ${formatNum(agg.avg)} (${agg.count}件)")
                    isMax -> appendLine("${agg.fileName} の「${agg.colName}」最大: ${formatNum(agg.max)} (${agg.count}件)")
                    isMin -> appendLine("${agg.fileName} の「${agg.colName}」最小: ${formatNum(agg.min)} (${agg.count}件)")
                    isCount -> appendLine("${agg.fileName} の「${agg.colName}」件数: ${agg.count}件")
                }
            }
            if (COMPARE_KEYWORDS.any { query.contains(it, ignoreCase = true) } && aggregateResults.size > 1) {
                appendLine()
                appendLine("▼ 全ファイル比較:")
                for (agg in aggregateResults) { appendLine("  ${agg.toText()}") }
            }
        }
    }

    private suspend fun handleSearch(query: String): String {
        val stopWords = setOf(
            "を", "は", "が", "の", "に", "で", "と", "も", "から", "まで",
            "って", "という", "について", "する", "した", "している", "ある",
            "検索", "探し", "探して", "教えて", "見せて", "取得", "抽出",
            "ファイル", "シート", "データ", "情報"
        )

        val keywords = query.split("\\s+".toRegex())
            .flatMap { it.split("(?<=\\p{IsHan})(?=\\p{IsHiragana})|(?<=\\p{IsHiragana})(?=\\p{IsHan})".toRegex()) }
            .filter { it.length >= 2 && it !in stopWords }

        val searchQuery = if (keywords.isNotEmpty()) keywords.joinToString(" ") else query
        val results = index.search(searchQuery)

        if (results.isEmpty()) {
            for (kw in keywords) {
                val partial = index.search(kw)
                if (partial.isNotEmpty()) return formatSearchResults(partial, "「$kw」")
            }
            return "「$query」に一致するデータが見つかりませんでした。登録ファイル: ${index.fileCount()}件"
        }

        val formattedResults = formatSearchResults(results, "「$searchQuery」")

        val engine = NexusEngineManager.getInstance()
        if (engine.state.value is EngineState.Ready) {
            return generateAiAnswer(query, formattedResults, engine)
        }
        return formattedResults
    }

    private fun formatSearchResults(results: List<NexusSheetsIndex.SearchResult>, label: String): String {
        return buildString {
            appendLine("【検索結果】$label — ${results.size} 件")
            appendLine()
            val byFile = results.groupBy { it.fileName }
            for ((fileName, fileResults) in byFile) {
                appendLine("▼ $fileName (${fileResults.size}件)")
                for (r in fileResults.take(20)) {
                    appendLine("  行${r.rowIndex}: ${r.toReadableText()}")
                }
                if (fileResults.size > 20) appendLine("  ... 他 ${fileResults.size - 20} 件")
                appendLine()
            }
        }
    }

    private suspend fun generateAiAnswer(
        userQuery: String, searchResults: String, engine: NexusEngineManager
    ): String {
        val context = searchResults.take(MAX_CONTEXT_LENGTH)
        val prompt = buildString {
            appendLine("あなたはデータ分析アシスタントです。以下はユーザーが登録したファイルから検索されたデータです。")
            appendLine()
            appendLine("--- 検索データ ---")
            appendLine(context)
            appendLine("--- ここまで ---")
            appendLine()
            appendLine("ユーザーの質問: $userQuery")
            appendLine()
            appendLine("上記のデータに基づいて、質問に対して簡潔かつ正確に回答してください。データに含まれない情報は推測しないでください。")
        }
        return try {
            val aiResult = engine.inferText(prompt)
            val aiAnswer = aiResult.getOrNull()
            if (aiAnswer != null) {
                "$aiAnswer\n\n---\n(参照データ: ${searchResults.lines().count { it.startsWith("  行") }} 件)"
            } else searchResults
        } catch (e: Exception) {
            Log.e(TAG, "AI failed: ${e.message}")
            searchResults
        }
    }

    private fun formatTime(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(epochMs))
    }

    private fun formatNum(v: Double): String =
        if (v == v.toLong().toDouble()) "%,.0f".format(v) else "%,.2f".format(v)
}
```

---

### ファイル 7: `app/src/main/java/com/nexus/vision/ui/MainViewModel.kt`（完全置換）

**変更内容**:
- `addShareResult(text)` メソッドを追加（ShareReceiver から結果を受け取る用）
- `onFileSelected(uri)` メソッドを追加（チャット画面のファイル添付ボタンから呼ばれる）
- ファイル解析結果にインデックス登録を統合
- `parseCellsJson` が `NexusSheetsIndex` 内で `internal` → 修正のため `parseCellsJson` を public に変更済み（ファイル5 で対応済み）

**既存コードの変更箇所:**

1. `import` に `NexusSheetsIndex`, `SheetsQueryEngine` を追加（既存）
2. `sheetsIndex`, `sheetsQueryEngine` プロパティ（既存）
3. `addShareResult(text: String)` メソッドを新規追加:

```kotlin
/**
 * ShareReceiver からの結果テキストをチャットに追加する。
 * MainActivity.handleShareResult() から呼ばれる。
 */
fun addShareResult(text: String) {
    addMessage(ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text))
}
```

4. `onFileSelected(uri: Uri)` メソッドを新規追加:

```kotlin
/**
 * チャット画面のファイル添付ボタンから呼ばれる。
 * ファイルをパースしてインデックスに登録する。
 */
fun onFileSelected(uri: Uri) {
    val processingId = addProcessingMessage("ファイルを登録中...")

    viewModelScope.launch {
        try {
            val response = processFileAndIndex(uri)
            replaceMessage(
                processingId,
                ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = response)
            )
        } catch (e: Exception) {
            Log.e(TAG, "File registration failed", e)
            replaceMessage(
                processingId,
                ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = "ファイル登録エラー: ${e.message}")
            )
        }
    }
}

private suspend fun processFileAndIndex(uri: Uri): String = withContext(Dispatchers.IO) {
    val context = app.applicationContext
    val mimeType = context.contentResolver.getType(uri) ?: ""

    // ファイル名取得
    var filename = "unknown"
    try {
        val cursor = context.contentResolver.query(
            uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null
        )
        cursor?.use { if (it.moveToFirst()) filename = it.getString(0) ?: "unknown" }
    } catch (e: Exception) { /* fallback */ }

    val lowerFilename = filename.lowercase()

    when {
        mimeType.contains("csv") || mimeType.contains("comma-separated") ||
                lowerFilename.endsWith(".csv") -> {
            val result = ExcelCsvParser.parseFromUri(context, uri, mimeType)
            if (result.isSuccess) {
                sheetsIndex.addParsedTable(result.rows, filename, "csv")
                "「$filename」を登録しました。(${result.rowCount}行×${result.colCount}列)\nチャットから検索・質問できます。"
            } else result.errorMessage ?: "CSV 解析失敗"
        }

        mimeType.contains("spreadsheetml") || mimeType.contains("xlsx") ||
                mimeType.contains("excel") ||
                lowerFilename.endsWith(".xlsx") || lowerFilename.endsWith(".xls") -> {
            val result = ExcelCsvParser.parseFromUri(context, uri, mimeType)
            if (result.isSuccess) {
                sheetsIndex.addParsedTable(result.rows, filename, "xlsx")
                "「$filename」を登録しました。(${result.rowCount}行×${result.colCount}列)\nチャットから検索・質問できます。"
            } else result.errorMessage ?: "Excel 解析失敗"
        }

        mimeType.contains("pdf") || lowerFilename.endsWith(".pdf") -> {
            val result = PdfExtractor.extractFromUri(context, uri)
            if (result.isSuccess) {
                sheetsIndex.addPdfText(result.pages.map { it.text }, filename)
                "「$filename」を登録しました。(${result.processedPages}ページ)\nチャットから検索・質問できます。"
            } else result.errorMessage ?: "PDF 解析失敗"
        }

        else -> "非対応ファイル形式: $mimeType ($filename)\n対応: CSV, Excel(.xlsx), PDF"
    }
}
```

**完全な MainViewModel.kt は既存のコードに上記 2 メソッド (`addShareResult`, `onFileSelected`, `processFileAndIndex`) を追加するだけです。既存の全メソッド・プロパティ・import はすべてそのまま維持してください。**

なお、既存の `processFileRequest` メソッド（画像 URI 経由でファイル解析する既存パス）も引き続き維持しますが、こちらもインデックス登録を行うよう更新してください（前回の指示通り）。

---

## 変更しないファイル

- `IndexedFile.kt` — 既存のまま
- `IndexedRow.kt` — 既存のまま
- `NexusApplication.kt` — 既存のまま（`sheetsIndex` / `sheetsQueryEngine` は実装済み）
- `AndroidManifest.xml` — 既存のまま
- `ExcelCsvParser.kt` — 既存のまま
- `PdfExtractor.kt` — 既存のまま
- `SourceCodeParser.kt` — 既存のまま
- `TableReconstructor.kt` — 既存のまま
- `MlKitOcrEngine.kt` — 既存のまま
- `RouteCProcessor.kt` — 既存のまま
- `build.gradle.kts` — 変更不要

## ビルド・テスト手順

1. 7 ファイルを配置/置換
2. **Build > Rebuild Project**
3. テスト項目:
   - ファイルマネージャから CSV 共有 → 登録メッセージ後に **チャット画面へ自動遷移**
   - ファイルマネージャから PDF 共有 → 同上
   - **チャット画面のクリップアイコン** → ファイルピッカーが開き CSV / XLSX / PDF を選択可能 → 登録メッセージ表示
   - チャットで `地域列「東京」のすべて` → 地域列が東京の全行を表示
   - チャットで `地域列「東京」+ 年度列「2024」` → AND 条件でフィルタ
   - チャットで `「東京」が入っている地域列の行` → 同上
   - チャットで `地域列が「東京」の売上列` → 東京の行から売上列の値のみ抽出
   - 既存機能（検索、クロス検索、集計、削除、ファイル一覧）が引き続き動作
   - 画像ピッカー（写真アイコン）は従来通り画像選択

## 検索クエリ例（チャットで入力）

| 入力 | 動作 |
|---|---|
| `地域列「東京」のすべて` | 地域列に「東京」を含む全行 |
| `地域「東京」+ 年度「2024」` | 地域=東京 AND 年度=2024 の行 |
| `「東京」が入っている地域の行` | 同上 |
| `地域が「東京」の売上` | 地域=東京の行の売上列の値 |
| `東京の売上` | クロス検索（既存） |
| `売上の合計` | 集計（既存） |
| `東京を検索` | 汎用検索（既存） |
| `ファイル一覧` | 登録ファイルリスト |