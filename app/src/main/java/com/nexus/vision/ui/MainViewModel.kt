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
import com.nexus.vision.image.RegionDecoder
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

/** 範囲選択の目的 */
enum class CropPurpose {
    ENHANCE,  // 高画質化
    ZOOM      // ズーム（拡大）
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
    // 範囲選択モード
    val cropMode: Boolean = false,
    val cropPurpose: CropPurpose = CropPurpose.ENHANCE,
    val cropImageUri: Uri? = null,
    val cropThumbnail: Bitmap? = null,
    val cropImageWidth: Int = 0,
    val cropImageHeight: Int = 0
)

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val app = NexusApplication.getInstance()
    private val engineManager = NexusEngineManager.getInstance()
    private val l1Cache: L1PHashCache = app.l1Cache
    private val l2Cache: L2InferenceCache = app.l2Cache

    private val ocrEngine = MlKitOcrEngine()

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
        initSuperResolution()
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

    // ── 範囲選択モード ──

    /** 範囲選択をキャンセル */
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

    /** ユーザーが範囲を確定した */
    fun onCropConfirmed(left: Float, top: Float, right: Float, bottom: Float) {
        val uri = _uiState.value.cropImageUri ?: return
        val imgW = _uiState.value.cropImageWidth
        val imgH = _uiState.value.cropImageHeight
        val purpose = _uiState.value.cropPurpose

        // 範囲選択モードを終了
        _uiState.value = _uiState.value.copy(
            cropMode = false,
            cropThumbnail = null
        )

        // ユーザーメッセージ
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

        val isOcrRequest = imageUri != null && (
                text.contains("読み取", ignoreCase = true) ||
                text.contains("テキスト", ignoreCase = true) ||
                text.contains("OCR", ignoreCase = true) ||
                text.contains("文字", ignoreCase = true) ||
                text.isBlank()
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

        val needsEngine = !isOcrRequest && !isEnhanceRequest && !isZoomRequest

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

        // ズーム要求 → 範囲選択モードに入る
        if (isZoomRequest) {
            enterCropMode(imageUri!!, CropPurpose.ZOOM)
            return
        }

        // 高画質化要求 → 範囲選択モードに入る
        if (isEnhanceRequest) {
            enterCropMode(imageUri!!, CropPurpose.ENHANCE)
            return
        }

        val processingLabel = when {
            isOcrRequest -> "テキスト読み取り中..."
            imageUri != null -> "画像を分析中..."
            else -> "考え中..."
        }

        val processingId = addProcessingMessage(processingLabel)

        viewModelScope.launch {
            try {
                val response = when {
                    isOcrRequest -> processOcrRequest(imageUri!!)
                    isEnhanceRequest -> processEnhanceRequest(imageUri!!)
                    imageUri != null -> processImageMessage(imageUri, displayText)
                    else -> processTextMessage(displayText)
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
            }
        }
    }

    /** ズーム/高画質化要求時に範囲選択モードに入る */
    private fun enterCropMode(uri: Uri, purpose: CropPurpose) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = app.applicationContext
            val imgSize = RegionDecoder.getImageSize(context, uri)
            val thumbnail = RegionDecoder.decodeThumbnail(context, uri, 800)

            if (imgSize == null || thumbnail == null) {
                addMessage(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        text = "⚠️ 画像を読み込めませんでした"
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

        // ズーム領域も4096まで許容して高精細に
        val regionBitmap = RegionDecoder.decodeRegion(
            context, uri, left, top, right, bottom, maxOutputSide = 4096
        ) ?: return@withContext "⚠️ 選択領域のデコードに失敗しました"

        val safeCopy = if (regionBitmap.config != Bitmap.Config.ARGB_8888) {
            val copy = regionBitmap.copy(Bitmap.Config.ARGB_8888, false)
            regionBitmap.recycle()
            copy ?: return@withContext "⚠️ コピーに失敗しました"
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
                CropPurpose.ENHANCE -> "✅ 高画質化完了"
                CropPurpose.ZOOM -> "✅ デジタルズーム完了"
            }

            val savedUri = saveBitmapToGallery(result.bitmap, savePrefix)
            val responseText = buildString {
                appendLine("${resultLabel} (${result.method})")
                appendLine("📐 元画像: ${imgW}×${imgH}")
                appendLine("📐 選択範囲: ${safeCopy.width}×${safeCopy.height}")
                appendLine("📐 出力: ${result.bitmap.width}×${result.bitmap.height}")
                appendLine("⏱ ${result.timeMs}ms")
                if (savedUri != null) {
                    appendLine("📁 Pictures/NexusVision/ に保存")
                }
            }
            safeCopy.recycle()
            result.bitmap.recycle()
            responseText
        } else {
            safeCopy.recycle()
            "⚠️ 選択範囲の超解像に失敗しました"
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

    // ── OCR 処理 ──

    private suspend fun processOcrRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

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

    // ── 超解像処理 ──

    private suspend fun processEnhanceRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val imgSize = RegionDecoder.getImageSize(context, uri)
                ?: return@withContext "⚠️ 画像サイズを取得できません"
            val (origW, origH) = imgSize

            val maxOutputPixels = 4096L * 4096  // 出力の最大ピクセル数（約64MB）
            val origPixels = origW.toLong() * origH

            // 出力可能な最大サイズを計算
            val maxDecodeSide = if (origPixels <= maxOutputPixels) {
                // 元サイズが安全範囲内 → 元サイズでデコード
                maxOf(origW, origH)
            } else {
                // 巨大画像 → Bitmap.createBitmap で確保可能な最大に制限
                4096
            }

            Log.i(TAG, "Enhance: original=${origW}x${origH}, maxDecode=$maxDecodeSide")

            val decoded = RegionDecoder.decodeSafe(context, uri, maxDecodeSide)
                ?: return@withContext "⚠️ 画像のデコードに失敗しました"

            val safeCopy = if (decoded.config != Bitmap.Config.ARGB_8888) {
                val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
                decoded.recycle()
                copy ?: return@withContext "⚠️ コピーに失敗しました"
            } else {
                decoded
            }

            Log.i(TAG, "Decoded: ${safeCopy.width}x${safeCopy.height}")

            val result = routeC?.process(safeCopy)

            if (result != null && result.success) {
                val savedUri = saveBitmapToGallery(result.bitmap, "Enhanced")
                val responseText = buildString {
                    appendLine("✅ 高画質化完了 (${result.method})")
                    appendLine("📐 元画像: ${origW}×${origH}")
                    appendLine("📐 処理入力: ${safeCopy.width}×${safeCopy.height}")
                    appendLine("📐 出力: ${result.bitmap.width}×${result.bitmap.height}")
                    appendLine("⏱ ${result.timeMs}ms")
                    if (savedUri != null) {
                        appendLine("📁 Pictures/NexusVision/ に保存")
                    }
                    if (origPixels > maxOutputPixels) {
                        appendLine()
                        appendLine("💡 この画像は非常に大きいため、4096pxに制限して処理しました")
                        appendLine("💡 部分拡大は「ズーム」で範囲選択すると元解像度で鮮明に処理できます")
                    }
                }
                safeCopy.recycle()
                result.bitmap.recycle()
                responseText
            } else {
                val savedUri = saveBitmapToGallery(safeCopy, "Original")
                safeCopy.recycle()
                buildString {
                    appendLine("⚠️ 高画質化に失敗しました")
                    appendLine("元画像をそのまま保存しました")
                    if (savedUri != null) appendLine("📁 Pictures/NexusVision/ に保存済")
                }
            }
        }

    // ── ヘルパー ──

    private fun saveBitmapToGallery(bitmap: Bitmap, prefix: String = "NEXUS"): Uri? {
        val context = app.applicationContext
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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
