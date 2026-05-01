// ファイルパス: app/src/main/java/com/nexus/vision/ui/MainViewModel.kt

package com.nexus.vision.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.vision.NexusApplication
import com.nexus.vision.cache.L1PHashCache
import com.nexus.vision.cache.L2InferenceCache
import com.nexus.vision.deor.AdaptiveResizer
import com.nexus.vision.deor.EntropyCalculator
import com.nexus.vision.deor.PHashCalculator
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.engine.ThermalLevel
import com.nexus.vision.image.DocumentSharpener
import com.nexus.vision.image.DirectCrop100MP
import com.nexus.vision.image.RegionDecoder
import com.nexus.vision.ocr.MlKitOcrEngine
import com.nexus.vision.ocr.TableReconstructor
import com.nexus.vision.ui.components.ChatMessage
import com.nexus.vision.notification.InlineReplyHandler
import com.nexus.vision.pipeline.RouteCProcessor
import com.nexus.vision.ncnn.RealEsrganBridge
import com.nexus.vision.parser.ExcelCsvParser
import com.nexus.vision.parser.PdfExtractor
import com.nexus.vision.parser.SourceCodeParser
import com.nexus.vision.sheets.NexusSheetsIndex
import com.nexus.vision.sheets.SheetsQueryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nexus.vision.worker.BatchEnhanceQueue
import com.nexus.vision.worker.BatchEnhanceWorker
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CropPurpose {
    ENHANCE,
    ZOOM
}

data class MainUiState(
    val isEngineReady: Boolean = false,
    val isProcessing: Boolean = false,
    val isDegraded: Boolean = false,
    val statusMessage: String = "AI エンジン準備中...",
    val errorMessage: String? = null,
    val thermalLevelName: String = "NONE",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val selectedImageUri: Uri? = null,
    val selectedImagePath: String? = null,
    val cropMode: Boolean = false,
    val cropPurpose: CropPurpose = CropPurpose.ENHANCE,
    val cropImageUri: Uri? = null,
    val cropThumbnail: Bitmap? = null,
    val cropImageWidth: Int = 0,
    val cropImageHeight: Int = 0,
    val isBatchRunning: Boolean = false,
    val batchProgressText: String = "",
    val requestBatchPicker: Boolean = false,
    val processingLabel: String? = null
)

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"

        private val SHEETS_KEYWORDS = listOf(
            "検索", "探して", "探す", "ファイル一覧", "登録",
            "合計", "平均", "最大", "最小", "最高", "最低", "件数",
            "比較", "削除", "シート",
            "の売上", "の利益", "の金額", "の数", "の値",
            "について", "はいくつ", "はいくら"
        )

        // ★ NEW: 引用タグの正規表現 — @番号-HH:MM 形式
        private val QUOTE_PATTERN = Regex("""@(\d+)-(\d{1,2}:\d{2})""")
    }

    private val app = NexusApplication.getInstance()
    private val engineManager = NexusEngineManager.getInstance()
    private val l1Cache: L1PHashCache = app.l1Cache
    private val l2Cache: L2InferenceCache = app.l2Cache

    private val ocrEngine = MlKitOcrEngine()

    private var routeC: RouteCProcessor? = null
    private var routeCInitialized = false

    private val sheetsIndex: NexusSheetsIndex = app.sheetsIndex
    private val sheetsQueryEngine: SheetsQueryEngine = app.sheetsQueryEngine

    private var batchProgressMessageId: String? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val engineState: StateFlow<EngineState> = engineManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineState.Idle)

    val thermalLevel: StateFlow<ThermalLevel> =
        app.thermalMonitor.thermalLevel
            .stateIn(viewModelScope, SharingStarted.Eagerly, ThermalLevel.NONE)

    init {
        viewModelScope.launch {
            engineManager.state.collect { state ->
                // F: アトミック更新
                _uiState.value = _uiState.value.copy(
                    isEngineReady = state is EngineState.Ready,
                    isProcessing = state is EngineState.Processing,
                    isDegraded = state is EngineState.Degraded,
                    statusMessage = when (state) {
                        is EngineState.Idle -> "エンジン未ロード"
                        is EngineState.Initializing -> "モデルロード中..."
                        is EngineState.Ready -> "準備完了"
                        is EngineState.Processing -> state.taskDescription
                        is EngineState.Degraded -> "発熱のためテキストオンリーモード"
                        is EngineState.Error -> "エラー: ${state.message}"
                        is EngineState.Released -> "エンジン解放済み"
                    },
                    errorMessage = if (state is EngineState.Error) state.message else null
                )
            }
        }

        viewModelScope.launch {
            thermalLevel.collect { level ->
                _uiState.value = _uiState.value.copy(thermalLevelName = level.name)
            }
        }

        val fileCount = sheetsIndex.fileCount()
        val welcomeExtra = if (fileCount > 0) "登録済みファイル: ${fileCount}件。" else ""
        addSystemMessage(
            "NEXUS Vision へようこそ。画像・PDF・CSV・テキストを送信できます。" +
                    "ファイルを共有すると登録され、チャットから横断検索できます。$welcomeExtra"
        )
        initSuperResolution()

        viewModelScope.launch {
            BatchEnhanceQueue.progress.collect { progress ->
                if (!progress.isRunning && progress.total == 0) {
                    _uiState.value = _uiState.value.copy(
                        isBatchRunning = false,
                        batchProgressText = ""
                    )
                    return@collect
                }

                val text = buildString {
                    if (progress.isPaused) {
                        append("一時停止中 (${progress.pauseReason})")
                    } else if (progress.isRunning) {
                        append("${progress.completed + 1}/${progress.total} 処理中...")
                        if (progress.estimatedRemainingMs > 0) {
                            val min = progress.estimatedRemainingMs / 60_000
                            val sec = (progress.estimatedRemainingMs % 60_000) / 1_000
                            append(" 残り約 ${min}分${sec}秒")
                        }
                    } else {
                        append("バッチ完了: ${progress.successCount}/${progress.total} 成功")
                        if (progress.failed > 0) append("、${progress.failed} 失敗")
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isBatchRunning = progress.isRunning || progress.isPaused,
                    batchProgressText = text
                )

                val msgId = batchProgressMessageId
                if (msgId != null) {
                    replaceMessage(
                        msgId,
                        ChatMessage(id = msgId, role = ChatMessage.Role.ASSISTANT, text = text)
                    )
                }
            }
        }
    }

    private fun initSuperResolution() {
        viewModelScope.launch(Dispatchers.IO) {
            routeC = RouteCProcessor(app.applicationContext)
            routeCInitialized = routeC?.initialize() == true
            if (routeCInitialized) {
                Log.i(TAG, "Super-resolution ready")
            } else {
                Log.w(TAG, "Super-resolution init failed (will passthrough)")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ocrEngine.close()
        routeC?.release()
    }

    // ── エンジン操作 ──

    fun loadEngine() {
        viewModelScope.launch {
            addSystemMessage("エンジンをロード中...")
            engineManager.loadEngine() // loadEngine() 内部で Dispatchers.IO に切替済み
            if (engineManager.state.value is EngineState.Ready) {
                addSystemMessage("エンジンの準備が完了しました。メッセージを送信できます。")
            }
        }
    }

    // ── 入力操作 ──

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    // ★ NEW: 引用タグを入力テキストの末尾に追加
    fun appendQuoteTag(tag: String) {
        val current = _uiState.value.inputText
        val newText = if (current.isBlank()) tag else "$current$tag"
        _uiState.value = _uiState.value.copy(inputText = newText)
    }

    fun setSelectedImage(uri: Uri) {
        _uiState.value = _uiState.value.copy(
            selectedImageUri = uri,
            selectedImagePath = null
        )
    }

    fun clearSelectedImage() {
        _uiState.value = _uiState.value.copy(
            selectedImageUri = null,
            selectedImagePath = null
        )
    }

    // ── 範囲選択モード ──

    fun cancelCropMode() {
        _uiState.value = _uiState.value.copy(
            cropMode = false,
            cropPurpose = CropPurpose.ENHANCE,
            cropImageUri = null,
            cropThumbnail = null,
            cropImageWidth = 0,
            cropImageHeight = 0
        )
    }

    fun onCropConfirmed(left: Float, top: Float, right: Float, bottom: Float) {
        val uri = _uiState.value.cropImageUri ?: return
        val imgW = _uiState.value.cropImageWidth
        val imgH = _uiState.value.cropImageHeight
        val purpose = _uiState.value.cropPurpose

        _uiState.value = _uiState.value.copy(
            cropMode = false,
            cropThumbnail = null
        )

        val pxLeft = (left * imgW).toInt()
        val pxTop = (top * imgH).toInt()
        val pxRight = (right * imgW).toInt()
        val pxBottom = (bottom * imgH).toInt()

        val actionLabel = when (purpose) {
            CropPurpose.ENHANCE -> "選択範囲を高画質化"
            CropPurpose.ZOOM -> "選択範囲をズーム"
        }

        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = "${actionLabel}: (${pxLeft},${pxTop})-(${pxRight},${pxBottom})"
            )
        )

        val processingLabel = when (purpose) {
            CropPurpose.ENHANCE -> "選択範囲を高画質化中..."
            CropPurpose.ZOOM -> "選択範囲を超解像中..."
        }
        val processingId = addProcessingMessage(processingLabel)

        viewModelScope.launch {
            try {
                val response = processRegionZoom(uri, left, top, right, bottom, imgW, imgH, purpose)
                replaceMessage(
                    processingId,
                    ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = response)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Region zoom failed", e)
                replaceMessage(
                    processingId,
                    ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = "エラーが発生しました: ${e.message}")
                )
            }
        }
    }

    // ── メッセージ送信 ──

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val imageUri = _uiState.value.selectedImageUri

        if (text.isBlank() && imageUri == null) return

        // --- Phase 14-1: SR モデル切替コマンド ---
        val isSrSwitchCommand = text.let {
            it.contains("SAFMN", ignoreCase = true) ||
            it.contains("ESRGAN", ignoreCase = true) ||
            it == "高速モード" || it == "高画質モード"
        }
        if (isSrSwitchCommand && imageUri == null) {
            addMessage(ChatMessage(role = ChatMessage.Role.USER, text = text))
            _uiState.value = _uiState.value.copy(inputText = "")
            
            val target = if (text.contains("ESRGAN", ignoreCase = true) ||
                             text == "高画質モード")
                RouteCProcessor.SrModel.REAL_ESRGAN
            else
                RouteCProcessor.SrModel.SAFMN_PP

            val success = routeC?.switchModel(target) ?: false
            val modelName = if (target == RouteCProcessor.SrModel.SAFMN_PP)
                "SAFMN++ (軽量高速)" else "Real-ESRGAN (重厚高品質)"
            val msg = if (success) "超解像モデルを $modelName に切り替えました"
                      else "$modelName は初期化されていません"
            addMessage(ChatMessage(role = ChatMessage.Role.ASSISTANT, text = msg))
            return
        }

        val isBatchRequest = text.contains("バッチ", ignoreCase = true) &&
                (text.contains("高画質", ignoreCase = true) || text.contains("enhance", ignoreCase = true))

        if (isBatchRequest && imageUri == null) {
            addMessage(ChatMessage(role = ChatMessage.Role.USER, text = text))
            _uiState.value = _uiState.value.copy(
                inputText = "",
                requestBatchPicker = true
            )
            addMessage(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    text = "複数画像を選択してください。選択後にバッチ高画質化を開始します。"
                )
            )
            return
        }

        // 通知コマンド判定
        val isNotifyCommand = imageUri == null && (
                text == "通知" || text.equals("notify", ignoreCase = true)
                )

        if (isNotifyCommand) {
            addMessage(ChatMessage(role = ChatMessage.Role.USER, text = text))
            _uiState.value = _uiState.value.copy(inputText = "")

            if (_uiState.value.isEngineReady || _uiState.value.isDegraded) {
                InlineReplyHandler.showReplyNotification(
                    app.applicationContext,
                    "NEXUS Vision — 通知から質問できます"
                )
                addMessage(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        text = "通知バーにインライン応答を表示しました。\n" +
                                "通知を下に引いて「返信」をタップすると、通知から直接質問できます。"
                    )
                )
            } else {
                addMessage(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        text = "エンジンが未ロードのため通知応答を利用できません。\n" +
                                "先にエンジンをロードしてから再度お試しください。"
                    )
                )
            }
            return
        }

        // ★ NEW: 引用タグを解析して参照コンテキストを構築
        val currentMessages = _uiState.value.messages
        val quoteMatches = QUOTE_PATTERN.findAll(text).toList()
        val quotedIndices = mutableListOf<Int>()
        val quoteContext = buildString {
            for (match in quoteMatches) {
                val msgNumber = match.groupValues[1].toIntOrNull() ?: continue
                if (msgNumber in 1..currentMessages.size) {
                    val referenced = currentMessages[msgNumber - 1]
                    quotedIndices.add(msgNumber)
                    appendLine("--- 参照チャット #$msgNumber (${formatTimestampCompact(referenced.timestamp)}) ---")
                    // テキストが長すぎる場合は切り詰め
                    val refText = if (referenced.text.length > 1500) {
                        referenced.text.take(1500) + "...(省略)"
                    } else {
                        referenced.text
                    }
                    appendLine(refText)
                    appendLine("--- 参照ここまで ---")
                    appendLine()
                }
            }
        }

        val displayText = text.ifBlank { "この画像を分析してください" }

        // ★ CHANGED: 引用番号を ChatMessage に記録
        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = displayText,
                imagePath = imageUri?.toString(),
                quotedIndices = quotedIndices.toList()
            )
        )

        _uiState.value = _uiState.value.copy(
            inputText = "",
            selectedImageUri = null,
            selectedImagePath = null
        )

        val isOcrRequest = imageUri != null && (
                text.contains("読み取", ignoreCase = true) ||
                text.contains("テキスト", ignoreCase = true) ||
                text.contains("OCR", ignoreCase = true) ||
                text.contains("文字", ignoreCase = true) ||
                text.isBlank()
        )

        val isTableRequest = imageUri != null && (
                text.contains("表", ignoreCase = true) ||
                text.contains("テーブル", ignoreCase = true) ||
                text.contains("table", ignoreCase = true) ||
                text.contains("CSV", ignoreCase = true) ||
                text.contains("Markdown", ignoreCase = true)
        )

        val isEnhanceRequest = imageUri != null && (
                text.contains("鮮明", ignoreCase = true) ||
                text.contains("高画質", ignoreCase = true) ||
                text.contains("超解像", ignoreCase = true) ||
                text.contains("enhance", ignoreCase = true) ||
                text.contains("きれい", ignoreCase = true)
        )

        val isZoomRequest = imageUri != null && (
                text.contains("ズーム", ignoreCase = true) ||
                text.contains("拡大", ignoreCase = true) ||
                text.contains("zoom", ignoreCase = true)
        )

        val isFileRequest = imageUri != null && !isOcrRequest && !isTableRequest &&
                !isEnhanceRequest && !isZoomRequest && isFileUri(imageUri)

        val isSheetsQuery = (imageUri == null && (
                sheetsIndex.fileCount() > 0 &&
                        SHEETS_KEYWORDS.any { text.contains(it, ignoreCase = true) }
                )) || (imageUri == null && (
                text.contains("ファイル一覧") ||
                        text.contains("登録ファイル") ||
                        text.contains("全削除")
                ))

        val needsEngine = !isOcrRequest && !isTableRequest && !isEnhanceRequest &&
                !isZoomRequest && !isFileRequest && !isSheetsQuery

        if (needsEngine && !_uiState.value.isEngineReady) {
            addMessage(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    text = "エンジンがロードされていません。\n\n" +
                            "エンジンなしで利用可能: 画像OCR、表復元、高画質化、ファイル登録・検索・集計\n" +
                            "AI 連携回答にはエンジンのロードが必要です。"
                )
            )
            return
        }

        if (isZoomRequest) {
            enterCropMode(imageUri!!, CropPurpose.ZOOM)
            return
        }

        if (isEnhanceRequest) {
            enterCropMode(imageUri!!, CropPurpose.ENHANCE)
            return
        }

        val processingLabel = when {
            isSheetsQuery -> "データを検索中..."
            isTableRequest -> "表構造を復元中..."
            isOcrRequest -> "テキスト読み取り中..."
            isFileRequest -> "ファイルを解析中..."
            imageUri != null -> "画像を分析中..."
            else -> "考え中..."
        }

        val processingId = addProcessingMessage(processingLabel)

        viewModelScope.launch {
            try {
                val response = when {
                    // J: Sheets クエリを IO スレッドで実行
                    isSheetsQuery -> withContext(Dispatchers.IO) {
                        sheetsQueryEngine.query(displayText)
                    }
                    isTableRequest -> processTableRequest(imageUri!!)
                    isOcrRequest -> processOcrRequest(imageUri!!)
                    isFileRequest -> processFileRequest(imageUri!!)
                    imageUri != null -> processImageMessage(imageUri, displayText)
                    // ★ CHANGED: テキストメッセージに引用コンテキストを渡す
                    else -> processTextMessage(displayText, quoteContext)
                }

                // ★ Phase 11: 長期記憶に記録
                try {
                    val memoryCategory = when {
                        isSheetsQuery -> "search"
                        isTableRequest -> "ocr"
                        isOcrRequest -> "ocr"
                        isFileRequest -> "file"
                        imageUri != null -> "vision"
                        else -> "chat"
                    }
                    app.longTermMemory.remember(
                        userQuery = displayText,
                        aiResponse = response,
                        category = memoryCategory,
                        importance = when (memoryCategory) {
                            "file", "search" -> 0.8
                            "vision", "ocr" -> 0.6
                            else -> 0.4
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Memory save failed: ${e.message}")
                }

                replaceMessage(
                    processingId,
                    ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = response)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Message processing failed", e)
                replaceMessage(
                    processingId,
                    ChatMessage(id = processingId, role = ChatMessage.Role.ASSISTANT, text = "エラーが発生しました: ${e.message}")
                )
            } finally {
                _uiState.value = _uiState.value.copy(processingLabel = null)
            }
        }
    }

    private fun isFileUri(uri: Uri): Boolean {
        val mimeType = app.contentResolver.getType(uri) ?: ""
        val path = uri.toString().lowercase()
        return mimeType.contains("pdf") || mimeType.contains("csv") ||
                mimeType.contains("spreadsheetml") || mimeType.contains("xlsx") ||
                mimeType.startsWith("text/") ||
                path.endsWith(".pdf") || path.endsWith(".csv") || path.endsWith(".xlsx") ||
                path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".py") ||
                path.endsWith(".js") || path.endsWith(".ts") || path.endsWith(".json") ||
                path.endsWith(".xml") || path.endsWith(".yaml") || path.endsWith(".yml") ||
                path.endsWith(".md") || path.endsWith(".html") || path.endsWith(".css") ||
                path.endsWith(".sql") || path.endsWith(".sh") || path.endsWith(".c") ||
                path.endsWith(".cpp") || path.endsWith(".h") || path.endsWith(".swift") ||
                path.endsWith(".go") || path.endsWith(".rs") || path.endsWith(".dart")
    }

    private fun enterCropMode(uri: Uri, purpose: CropPurpose) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = app.applicationContext
            val imgSize = RegionDecoder.getImageSize(context, uri)
            val thumbnail = RegionDecoder.decodeThumbnail(context, uri, 800)

            if (imgSize == null || thumbnail == null) {
                addMessage(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        text = "画像を読み込めませんでした"
                    )
                )
                return@launch
            }

            val (w, h) = imgSize
            val actionText = when (purpose) {
                CropPurpose.ENHANCE -> "高画質化したい範囲"
                CropPurpose.ZOOM -> "拡大したい範囲"
            }
            addMessage(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    text = "画像上で${actionText}をドラッグで選択してください (${w}×${h})"
                )
            )

            _uiState.value = _uiState.value.copy(
                cropMode = true,
                cropPurpose = purpose,
                cropImageUri = uri,
                cropThumbnail = thumbnail,
                cropImageWidth = w,
                cropImageHeight = h
            )
        }
    }

    // ── 選択領域ズーム処理 ──

    private suspend fun processRegionZoom(
        uri: Uri,
        left: Float, top: Float, right: Float, bottom: Float,
        imgW: Int, imgH: Int,
        purpose: CropPurpose
    ): String = withContext(Dispatchers.IO) {
        val context = app.applicationContext

        val regionBitmap = RegionDecoder.decodeRegion(
            context, uri, left, top, right, bottom, maxOutputSide = 4096
        ) ?: return@withContext "選択領域のデコードに失敗しました"

        // K: Bitmap recycle 順序を安全に管理
        var safeCopy: Bitmap? = null
        try {
            safeCopy = if (regionBitmap.config != Bitmap.Config.ARGB_8888) {
                val copy = regionBitmap.copy(Bitmap.Config.ARGB_8888, false)
                regionBitmap.recycle()
                copy ?: return@withContext "コピーに失敗しました"
            } else {
                regionBitmap
            }

            val result = routeC?.process(safeCopy)

            if (result != null && result.success) {
                val savePrefix = when (purpose) {
                    CropPurpose.ENHANCE -> "Enhanced"
                    CropPurpose.ZOOM -> "Zoom"
                }
                val resultLabel = when (purpose) {
                    CropPurpose.ENHANCE -> "高画質化完了"
                    CropPurpose.ZOOM -> "デジタルズーム完了"
                }

                val savedUri = saveBitmapToGallery(result.bitmap, savePrefix)
                val responseText = buildString {
                    appendLine("$resultLabel (${result.method})")
                    appendLine("元画像: ${imgW}×${imgH}")
                    appendLine("選択範囲: ${safeCopy!!.width}×${safeCopy!!.height}")
                    appendLine("出力: ${result.bitmap.width}×${result.bitmap.height}")
                    appendLine("処理時間: ${result.timeMs}ms")
                    if (savedUri != null) {
                        appendLine("Pictures/NexusVision/ に保存しました")
                    }
                }
                result.bitmap.recycle()
                responseText
            } else {
                "選択範囲の超解像に失敗しました"
            }
        } finally {
            // K: finally で確実に recycle（二重 recycle 防止）
            safeCopy?.let {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    // ── テキスト処理 ──

    // ★ CHANGED: 引用コンテキストを受け取り、プロンプトに付加する
    private suspend fun processTextMessage(text: String, quoteContext: String = ""): String =
        withContext(Dispatchers.IO) {  // J: IO ディスパッチャ
            // ★ Phase 11: 長期記憶から関連コンテキストを取得
            val longTermContext = try {
                app.longTermMemory.buildContextForPrompt(text, maxChars = 1000)
            } catch (e: Exception) { "" }

            // 引用がある場合、キャッシュキーにはコンテキストも含める
            val cacheKey = if (quoteContext.isNotBlank() || longTermContext.isNotBlank()) {
                "$longTermContext\n$quoteContext\n$text"
            } else {
                text
            }

            val cached = l2Cache.lookup(cacheKey)
            if (cached != null) return@withContext cached.responseText

            // ★ NEW: 引用コンテキスト + 長期記憶コンテキストがある場合、プロンプトを組み立てる
            val prompt = buildString {
                if (longTermContext.isNotBlank()) {
                    appendLine(longTermContext)
                }
                if (quoteContext.isNotBlank()) {
                    appendLine("以下は参照されたチャット履歴です:")
                    appendLine(quoteContext)
                }
                if (longTermContext.isNotBlank() || quoteContext.isNotBlank()) {
                    appendLine("上記を踏まえて、以下のユーザーの質問に回答してください:")
                }
                append(text)
            }

            val result = engineManager.inferText(prompt)
            result.getOrElse { throw it }.also { response ->
                l2Cache.put(cacheKey, response)
            }
        }

    // ── 画像処理 (OCR→テキスト推論パイプライン) ──

    // ── 画像処理 (最適化版: OCR→テキスト→Gemmaテキスト推論) ──

    private suspend fun processImageMessage(uri: Uri, text: String): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext
            val startTime = System.currentTimeMillis()

            // ──────────────────────────────────────────────
            // 最適化1: フルサイズデコードを廃止
            //   旧: BitmapFactory.decodeStream() → フル展開 (48MB+)
            //   新: RegionDecoder.decodeSafe() → 最大 1024px にサンプリング
            // ──────────────────────────────────────────────
            val decoded = RegionDecoder.decodeSafe(context, uri, maxSide = 1024)
                ?: throw IllegalStateException("画像のデコードに失敗しました")

            val decodeMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "★ Image decode: ${decoded.width}x${decoded.height} in ${decodeMs}ms")

            // ──────────────────────────────────────────────
            // 最適化2: pHash を軽量デコード済みビットマップで計算
            // ──────────────────────────────────────────────
            val pHash = PHashCalculator.calculate(decoded)

            val cachedEntry = l1Cache.lookup(pHash, category = "vision")
            if (cachedEntry != null) {
                decoded.recycle()
                Log.i(TAG, "★ L1 cache HIT — skipping inference entirely")
                return@withContext cachedEntry.resultText
            }

            // ──────────────────────────────────────────────
            // 最適化3: OCR→テキスト→Gemmaテキスト推論パイプライン
            //
            //   理由:
            //   - Gemma E2B-it はテキストモデル。画像を直接理解しない
            //   - ML Kit OCR は 200-500ms で完了する
            //   - テキスト推論はマルチモーダル推論より 3-10x 速い
            //   - OCR テキストがない画像も説明文で要約可能
            // ──────────────────────────────────────────────

            val ocrStartMs = System.currentTimeMillis()
            val ocrResult = try {
                ocrEngine.recognize(decoded)
            } catch (e: Exception) {
                Log.w(TAG, "OCR failed, proceeding without text", e)
                null
            }
            val ocrMs = System.currentTimeMillis() - ocrStartMs
            Log.i(TAG, "★ OCR: ${ocrResult?.fullText?.length ?: 0} chars in ${ocrMs}ms")

            // エントロピーはキャッシュ用に計算（軽量: decoded は既に ≤1024px）
            val entropy = EntropyCalculator.calculate(decoded)

            // Bitmapはもう不要
            decoded.recycle()

            // ── プロンプト構築 ──
            val ocrText = ocrResult?.fullText?.take(2000) ?: ""
            val prompt = buildImageAnalysisPrompt(text, ocrText, entropy)

            // ── 推論 ──
            val inferStartMs = System.currentTimeMillis()
            val result = engineManager.inferText(prompt)
            val inferMs = System.currentTimeMillis() - inferStartMs
            Log.i(TAG, "★ Inference: ${inferMs}ms")

            val response = result.getOrElse { throw it }

            // ── キャッシュ保存 ──
            l1Cache.put(
                pHash = pHash,
                resultText = response,
                category = "vision",
                entropy = entropy
            )

            val totalMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "★ Total image pipeline: ${totalMs}ms (decode=${decodeMs}, ocr=${ocrMs}, infer=${inferMs})")

            response
        }

    /**
     * 画像分析用のプロンプトを構築する。
     * OCR テキストの有無で指示を分岐。
     */
    private fun buildImageAnalysisPrompt(
        userText: String,
        ocrText: String,
        entropy: Double
    ): String = buildString {
        appendLine("あなたは画像分析アシスタントです。")
        appendLine()

        if (ocrText.isNotBlank()) {
            appendLine("以下は画像から抽出されたテキストです:")
            appendLine("---")
            appendLine(ocrText)
            appendLine("---")
            appendLine()
        }

        // エントロピーから画像の特徴を補足
        val imageDesc = when {
            entropy < 2.5 -> "この画像はほぼ単色・シンプルな画像です。"
            entropy < 5.5 -> "この画像は中程度の複雑さの画像です。"
            else -> "この画像は高エントロピー（複雑・詳細な）画像です。"
        }
        appendLine(imageDesc)
        appendLine()

        if (ocrText.isBlank()) {
            appendLine("画像からテキストは検出されませんでした。")
            appendLine("ユーザーの指示に基づいて、画像の内容を推測して回答してください。")
            appendLine()
        }

        appendLine("ユーザーの指示: $userText")
    }


    // ── OCR 処理 (C: InputStream use{} で二重 close 防止) ──

    private suspend fun processOcrRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            // C: use{} で確実に close、二重 close を防止
            val (width, height) = context.contentResolver.openInputStream(uri)?.use { sizeStream ->
                DirectCrop100MP.getImageDimensions(sizeStream)
            } ?: throw IllegalStateException("画像を開けません")

            val caseType = DocumentSharpener.detectCase(width, height)
            Log.d(TAG, "OCR: image=${width}x${height} → Case $caseType")

            val result = if (caseType == "A") {
                // C: use{} で InputStream を自動 close
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    DocumentSharpener.processCaseA(stream, ocrEngine)
                } ?: throw IllegalStateException("画像を開けません")
            } else {
                // C: CaseB は2つのストリームが必要 → それぞれ use{} で管理
                val coarseStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("画像を開けません")
                val cropStream = context.contentResolver.openInputStream(uri)
                    ?: run {
                        coarseStream.close()
                        throw IllegalStateException("画像を開けません")
                    }
                try {
                    DocumentSharpener.processCaseB(coarseStream, cropStream, ocrEngine)
                } finally {
                    // C: 例外時も確実に close（DocumentSharpener 内部で消費済みなら close は no-op）
                    try { coarseStream.close() } catch (_: Exception) {}
                    try { cropStream.close() } catch (_: Exception) {}
                }
            }

            if (result.fullText.isBlank()) {
                "テキストを検出できませんでした。"
            } else {
                buildString {
                    appendLine("【テキスト読み取り結果】(${result.pipeline}, ${result.processingTimeMs}ms)")
                    appendLine()
                    append(result.fullText)
                }
            }
        }

    // ── 表復元処理 (C: use{}) ──

    private suspend fun processTableRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            // C: use{} で InputStream を安全に閉じる
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: throw IllegalStateException("画像を開けません")

            try {
                val ocrResult = ocrEngine.recognize(bitmap)

                if (ocrResult.fullText.isBlank()) {
                    return@withContext "テキストを検出できませんでした。表が含まれる画像を選択してください。"
                }

                val tableResult = TableReconstructor.reconstruct(ocrResult)

                if (!tableResult.isTable) {
                    return@withContext buildString {
                        appendLine("【テキスト読み取り結果】(表構造は検出されませんでした)")
                        appendLine()
                        append(ocrResult.fullText)
                    }
                }

                buildString {
                    appendLine("【表復元結果】${tableResult.rowCount} 行 × ${tableResult.colCount} 列 (${tableResult.processingTimeMs}ms)")
                    appendLine()
                    appendLine("CSV:")
                    appendLine(tableResult.toCsv())
                }
            } finally {
                // K: OCR 使用後に recycle
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

    // ── ファイル解析処理 ──

    private suspend fun processFileRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val filename = uri.lastPathSegment ?: "unknown"

            Log.i(TAG, "File request: mime=$mimeType, filename=$filename")

            when {
                mimeType.contains("pdf") || filename.endsWith(".pdf", ignoreCase = true) -> {
                    val result = PdfExtractor.extractFromUri(context, uri)
                    if (result.isSuccess) {
                        val pageTexts = result.pages.map { it.text }
                        sheetsIndex.addPdfText(pageTexts, filename)
                        buildString {
                            appendLine("「$filename」を登録しました。")
                            appendLine("${result.processedPages}/${result.totalPages} ページ (${result.processingTimeMs}ms)")
                            appendLine()
                            appendLine("チャットから検索・質問できます。")
                            appendLine("例: 「○○を検索」「○○について教えて」")
                        }
                    } else {
                        result.errorMessage ?: "PDF 解析失敗"
                    }
                }

                mimeType.contains("csv") || mimeType.contains("spreadsheetml") ||
                        mimeType.contains("xlsx") ||
                        filename.endsWith(".csv", ignoreCase = true) ||
                        filename.endsWith(".xlsx", ignoreCase = true) -> {
                    val result = ExcelCsvParser.parseFromUri(context, uri, mimeType)
                    if (result.isSuccess) {
                        val fileType = if (filename.endsWith(".xlsx", ignoreCase = true)) "xlsx" else "csv"
                        sheetsIndex.addParsedTable(result.rows, filename, fileType)
                        buildString {
                            appendLine("「$filename」を登録しました。")
                            appendLine("${result.rowCount} 行 × ${result.colCount} 列 (${result.processingTimeMs}ms)")
                            appendLine()
                            appendLine("チャットから検索・質問できます。")
                            appendLine("例: 「東京の売上」「売上の合計」「○○を検索」")
                        }
                    } else {
                        result.errorMessage ?: "ファイル解析失敗"
                    }
                }

                else -> {
                    val result = SourceCodeParser.parseFromUri(context, uri, mimeType)
                    if (result.isSuccess) {
                        result.toSummaryText()
                    } else {
                        result.errorMessage ?: "ファイル解析失敗"
                    }
                }
            }
        }

    // ── 超解像処理 (K: recycle 順序修正) ──

    private suspend fun processEnhanceRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val imgSize = RegionDecoder.getImageSize(context, uri)
                ?: return@withContext "画像サイズを取得できません"
            val (origW, origH) = imgSize

            val maxOutputPixels = 4096L * 4096
            val origPixels = origW.toLong() * origH

            val maxDecodeSide = if (origPixels <= maxOutputPixels) {
                maxOf(origW, origH)
            } else {
                4096
            }

            Log.i(TAG, "Enhance: original=${origW}x${origH}, maxDecode=$maxDecodeSide")

            val decoded = RegionDecoder.decodeSafe(context, uri, maxDecodeSide)
                ?: return@withContext "画像のデコードに失敗しました"

            var safeCopy: Bitmap? = null
            try {
                safeCopy = if (decoded.config != Bitmap.Config.ARGB_8888) {
                    val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
                    decoded.recycle()
                    copy ?: return@withContext "コピーに失敗しました"
                } else {
                    decoded
                }

                Log.i(TAG, "Decoded: ${safeCopy.width}x${safeCopy.height}")

                val result = routeC?.process(safeCopy)

                if (result != null && result.success) {
                    val savedUri = saveBitmapToGallery(result.bitmap, "Enhanced")
                    val responseText = buildString {
                        appendLine("高画質化完了 (${result.method})")
                        appendLine("元画像: ${origW}×${origH}")
                        appendLine("処理入力: ${safeCopy!!.width}×${safeCopy!!.height}")
                        appendLine("出力: ${result.bitmap.width}×${result.bitmap.height}")
                        appendLine("処理時間: ${result.timeMs}ms")
                        if (savedUri != null) {
                            appendLine("Pictures/NexusVision/ に保存しました")
                        }
                        if (origPixels > maxOutputPixels) {
                            appendLine()
                            appendLine("この画像は非常に大きいため、4096pxに制限して処理しました")
                            appendLine("部分拡大は「ズーム」で範囲選択すると元解像度で鮮明に処理できます")
                        }
                    }
                    result.bitmap.recycle()
                    responseText
                } else {
                    val savedUri = saveBitmapToGallery(safeCopy, "Original")
                    buildString {
                        appendLine("高画質化に失敗しました")
                        appendLine("元画像をそのまま保存しました")
                        if (savedUri != null) appendLine("Pictures/NexusVision/ に保存済")
                    }
                }
            } finally {
                // K: finally で確実に recycle
                safeCopy?.let { if (!it.isRecycled) it.recycle() }
            }
        }

    // ── ヘルパー ──

    /**
     * H: FD リーク修正 — detachFd() を使わず OutputStream で書き込む。
     * 大きな画像もストリーミング for 処理するが、FD を detach しない。
     */
    // ★ NEW: タイムスタンプをコンパクトに (引用用)
    private fun formatTimestampCompact(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun saveBitmapToGalleryStreaming(bitmap: Bitmap, prefix: String = "NEXUS", quality: Int = 95): Uri? {
        val context = app.applicationContext
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "${prefix}_${timestamp}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NexusVision")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

        try {
            // H: detachFd() を使わず openOutputStream で書き込み
            // FD リークを防止
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            Log.i(TAG, "Streaming saved: $filename (uri=$uri)")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Streaming save failed: ${e.message}")
            resolver.delete(uri, null, null)
            return null
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, prefix: String = "NEXUS"): Uri? {
        if (bitmap.width * bitmap.height > 4096 * 4096) {
            Log.i(TAG, "Using streaming save for large image (${bitmap.width}x${bitmap.height})")
            return saveBitmapToGalleryStreaming(bitmap, prefix)
        }

        val context = app.applicationContext
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "${prefix}_${timestamp}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NexusVision")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                Log.i(TAG, "Saved to gallery: $filename (uri=$uri)")
                return uri
            } catch (e: IOException) {
                Log.e(TAG, "Failed to save to gallery: ${e.message}")
                resolver.delete(uri, null, null)
            }
        }
        return null
    }


    private fun addMessage(message: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + message
        )
    }

    fun startBatchEnhance(uris: List<Uri>) {
        if (uris.isEmpty()) return

        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = "バッチ高画質化: ${uris.size} 枚"
            )
        )

        val processingId = addProcessingMessage("バッチ高画質化を開始します (${uris.size} 枚)...")
        batchProgressMessageId = processingId

        val displayNames = uris.map { uri ->
            try {
                val cursor = app.contentResolver.query(
                    uri,
                    arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME),
                    null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else "image.jpg"
                } ?: "image.jpg"
            } catch (e: Exception) {
                "image.jpg"
            }
        }

        BatchEnhanceQueue.enqueue(uris, displayNames)

        val workRequest = OneTimeWorkRequestBuilder<BatchEnhanceWorker>().build()
        WorkManager.getInstance(app.applicationContext)
            .enqueueUniqueWork(
                BatchEnhanceWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Log.i(TAG, "Batch enhance started: ${uris.size} images")
    }

    fun consumeBatchPickerRequest() {
        _uiState.value = _uiState.value.copy(requestBatchPicker = false)
    }

    private fun addSystemMessage(text: String) {
        addMessage(ChatMessage(role = ChatMessage.Role.SYSTEM, text = text))
    }

    private fun addProcessingMessage(label: String): String {
        val message = ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            text = "",
            isProcessing = true,
            processingLabel = label
        )
        addMessage(message)
        return message.id
    }

    private fun replaceMessage(id: String, newMessage: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map {
                if (it.id == id) newMessage else it
            }
        )
    }

    fun addShareResult(text: String) {
        addMessage(ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text))
    }

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
}
