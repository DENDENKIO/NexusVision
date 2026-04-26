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
import com.nexus.vision.deor.PHashCalculator
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.engine.ThermalLevel
import com.nexus.vision.image.DocumentSharpener
import com.nexus.vision.image.DirectCrop100MP
import com.nexus.vision.ocr.MlKitOcrEngine
import com.nexus.vision.ui.components.ChatMessage
import com.nexus.vision.pipeline.RouteCProcessor
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
    val selectedImagePath: String? = null
)

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val app = NexusApplication.getInstance()
    private val engineManager = NexusEngineManager.getInstance()
    private val l1Cache: L1PHashCache = app.l1Cache
    private val l2Cache: L2InferenceCache = app.l2Cache

    // Phase 5 追加: OCR エンジン
    private val ocrEngine = MlKitOcrEngine()

    // Phase 7 完全修正版: Route C 処理（NCNN 超解像）
    private var routeC: RouteCProcessor? = null
    private var routeCInitialized = false

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

        addSystemMessage("NEXUS Vision へようこそ。エンジンをロードしてからメッセージを送信してください。")

        // Phase 7: 超解像エンジンの初期化
        initSuperResolution()
    }

    // Phase 7 完全修正版: 超解像初期化
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

    // Phase 5 追加: ViewModel 破棄時に OCR エンジンを解放
    // Phase 7 追加: NCNN SR リソース解放
    override fun onCleared() {
        super.onCleared()
        ocrEngine.close()
        routeC?.release()
    }

    // ── エンジン操作 ──

    fun loadEngine() {
        viewModelScope.launch {
            addSystemMessage("エンジンをロード中...")
            engineManager.loadEngine()
            if (engineManager.state.value is EngineState.Ready) {
                addSystemMessage("エンジンの準備が完了しました。メッセージを送信できます。")
            }
        }
    }

    // ── 入力操作 ──

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
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

    // ── メッセージ送信 ──

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val imageUri = _uiState.value.selectedImageUri

        if (text.isBlank() && imageUri == null) return

        val displayText = text.ifBlank { "この画像を分析してください" }

        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = displayText,
                imagePath = imageUri?.toString()
            )
        )

        _uiState.value = _uiState.value.copy(
            inputText = "",
            selectedImageUri = null,
            selectedImagePath = null
        )

        // OCR 指示を検出
        val isOcrRequest = imageUri != null && (
                text.contains("読み取", ignoreCase = true) ||
                text.contains("テキスト", ignoreCase = true) ||
                text.contains("OCR", ignoreCase = true) ||
                text.contains("文字", ignoreCase = true) ||
                text.isBlank()
        )

        // Phase 6 追加: 超解像指示を検出
        val isEnhanceRequest = imageUri != null && (
                text.contains("鮮明", ignoreCase = true) ||
                text.contains("高画質", ignoreCase = true) ||
                text.contains("超解像", ignoreCase = true) ||
                text.contains("enhance", ignoreCase = true) ||
                text.contains("きれい", ignoreCase = true)
        )

        val needsEngine = !isOcrRequest && !isEnhanceRequest

        if (needsEngine && !_uiState.value.isEngineReady) {
            addMessage(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    text = "エンジンがロードされていません。「エンジンをロード」ボタンを押してからテキスト送信してください。\n\n" +
                            "画像の文字読み取り（OCR）や画像鮮鋭化はエンジンなしで利用できます。"
                )
            )
            return
        }

        val processingLabel = when {
            isOcrRequest -> "テキスト読み取り中..."
            isEnhanceRequest -> "EASS 超解像処理中..."
            imageUri != null -> "画像を分析中..."
            else -> "考え中..."
        }

        val processingId = addProcessingMessage(processingLabel)

        viewModelScope.launch {
            try {
                val response = when {
                    isOcrRequest -> processOcrRequest(imageUri!!)
                    // Phase 6 追加
                    isEnhanceRequest -> processEnhanceRequest(imageUri!!)
                    imageUri != null -> processImageMessage(imageUri, displayText)
                    else -> processTextMessage(displayText)
                }

                replaceMessage(
                    processingId,
                    ChatMessage(
                        id = processingId,
                        role = ChatMessage.Role.ASSISTANT,
                        text = response
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Message processing failed", e)
                replaceMessage(
                    processingId,
                    ChatMessage(
                        id = processingId,
                        role = ChatMessage.Role.ASSISTANT,
                        text = "エラーが発生しました: ${e.message}"
                    )
                )
            }
        }
    }

    // ── テキスト処理 ──

    private suspend fun processTextMessage(text: String): String {
        val cached = l2Cache.lookup(text)
        if (cached != null) return cached.responseText

        val result = engineManager.inferText(text)
        return result.getOrElse { throw it }.also { response ->
            l2Cache.put(text, response)
        }
    }

    // ── 画像処理（DEOR 連携） ──

    private suspend fun processImageMessage(uri: Uri, text: String): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("画像を開けません")
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) throw IllegalStateException("画像のデコードに失敗しました")

            val pHash = PHashCalculator.calculate(originalBitmap)

            val cachedEntry = l1Cache.lookup(pHash, category = "classify")
            if (cachedEntry != null) {
                originalBitmap.recycle()
                return@withContext cachedEntry.resultText
            }

            val resizeResult = AdaptiveResizer.resize(originalBitmap)

            val tempFile = saveBitmapToTemp(context, resizeResult.bitmap)

            if (resizeResult.bitmap !== originalBitmap) {
                resizeResult.bitmap.recycle()
            }
            originalBitmap.recycle()

            val result = engineManager.inferImage(tempFile.absolutePath, text)
            val response = result.getOrElse {
                tempFile.delete()
                throw it
            }

            l1Cache.put(
                pHash = pHash,
                resultText = response,
                category = "classify",
                entropy = resizeResult.entropy
            )

            tempFile.delete()
            response
        }

    // ── Phase 5 追加: OCR 処理 ──

    /**
     * 画像から文字を読み取る。
     * DocumentSharpener で CaseA / CaseB を自動判定。
     */
    private suspend fun processOcrRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            // 画像サイズを取得
            val sizeStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("画像を開けません")
            val (width, height) = DirectCrop100MP.getImageDimensions(sizeStream)
            sizeStream.close()

            val caseType = DocumentSharpener.detectCase(width, height)
            Log.d(TAG, "OCR: image=${width}x${height} → Case $caseType")

            val result = if (caseType == "A") {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("画像を開けません")
                val docResult = DocumentSharpener.processCaseA(stream, ocrEngine)
                stream.close()
                docResult
            } else {
                val coarseStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("画像を開けません")
                val cropStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("画像を開けません")
                val docResult = DocumentSharpener.processCaseB(coarseStream, cropStream, ocrEngine)
                coarseStream.close()
                cropStream.close()
                docResult
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

    // ── Phase 6 追加: EASS 超解像処理 ──

    /**
     * RouteCProcessor で画像を超解像補正する。
     */
    private suspend fun processEnhanceRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("画像を開けません")
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) throw IllegalStateException("画像のデコードに失敗しました")

            // DEOR で適応リサイズ
            val resizeResult = AdaptiveResizer.resize(originalBitmap)
            if (resizeResult.bitmap !== originalBitmap) originalBitmap.recycle()

            // ① 安全なコピーを作成（元のが recycle されても大丈夫なように）
            val safeCopy = resizeResult.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (resizeResult.bitmap !== safeCopy) resizeResult.bitmap.recycle()

            if (safeCopy == null) {
                return@withContext "⚠️ 画像のコピーに失敗しました"
            }

            // 超解像実行
            val result = routeC?.process(safeCopy)

            if (result != null && result.success) {
                // ② 超解像結果を保存（result.bitmap は新規作成された Bitmap）
                val savedUri = saveBitmapToGallery(result.bitmap, "Enhanced")
                // コピーを解放
                safeCopy.recycle()

                val responseText = buildString {
                    appendLine("✅ 超解像完了 (${result.method})")
                    appendLine("📐 ${safeCopy.width}×${safeCopy.height} → ${result.bitmap.width}×${result.bitmap.height}")
                    appendLine("⏱ ${result.timeMs}ms")
                    if (savedUri != null) {
                        appendLine("📁 Pictures/NexusVision/ に保存")
                    } else {
                        appendLine("⚠️ 保存に失敗しました")
                    }
                }
                result.bitmap.recycle()
                responseText
            } else {
                // ③ 失敗時はコピーをそのまま保存
                val savedUri = saveBitmapToGallery(safeCopy, "Original")
                safeCopy.recycle()

                buildString {
                    appendLine("⚠️ 超解像は利用できませんでした")
                    appendLine("元画像をそのまま保存しました")
                    if (savedUri != null) {
                        appendLine("📁 Pictures/NexusVision/ に保存済")
                    }
                }
            }
        }

    // ── ヘルパー ──

    /**
     * 処理済み画像をギャラリーに保存する。
     * Pictures/NexusVision/ フォルダに JPEG で保存される。
     * 保存後はギャラリーアプリから閲覧可能。
     */
    private fun saveBitmapToGallery(bitmap: Bitmap, prefix: String = "NEXUS"): Uri? {
        val context = app.applicationContext
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
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
                // 書き込み完了 → 公開
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

    private fun saveBitmapToTemp(context: Context, bitmap: Bitmap): File {
        val tempFile = File(context.cacheDir, "nexus_temp_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return tempFile
    }

    private fun addMessage(message: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + message
        )
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
}
