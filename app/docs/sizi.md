チャットベースのUIなので、範囲選択は**チャット内にインタラクティブなクロップUIをメッセージとして埋め込む**方式で実装します。

修正するファイルは4つです。

---

### Step 1: `MainUiState` にクロップ選択状態を追加 & `MainViewModel.kt` 全体を修正

```kotlin
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
        addMessage(
            ChatMessage(
                role = ChatMessage.Role.USER,
                text = "選択範囲をズーム: (${pxLeft},${pxTop})-(${pxRight},${pxBottom})"
            )
        )

        val processingId = addProcessingMessage("選択範囲を超解像中...")

        viewModelScope.launch {
            try {
                val response = processRegionZoom(uri, left, top, right, bottom, imgW, imgH)
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
            enterCropMode(imageUri!!)
            return
        }

        val processingLabel = when {
            isOcrRequest -> "テキスト読み取り中..."
            isEnhanceRequest -> "超解像処理中..."
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

    /** ズーム要求時に範囲選択モードに入る */
    private fun enterCropMode(uri: Uri) {
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
            addMessage(
                ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    text = "画像上で拡大したい範囲をドラッグで選択してください (${w}×${h})"
                )
            )

            _uiState.value = _uiState.value.copy(
                cropMode = true,
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
        imgW: Int, imgH: Int
    ): String = withContext(Dispatchers.IO) {
        val context = app.applicationContext

        val regionBitmap = RegionDecoder.decodeRegion(
            context, uri, left, top, right, bottom, maxOutputSide = 2048
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
            val savedUri = saveBitmapToGallery(result.bitmap, "Zoom")
            val responseText = buildString {
                appendLine("✅ デジタルズーム完了 (${result.method})")
                appendLine("📐 元画像: ${imgW}×${imgH}")
                appendLine("📐 選択領域: ${safeCopy.width}×${safeCopy.height}")
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

            val decoded = RegionDecoder.decodeSafe(context, uri, 2048)
                ?: return@withContext "⚠️ 画像のデコードに失敗しました"

            val safeCopy = if (decoded.config != Bitmap.Config.ARGB_8888) {
                val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
                decoded.recycle()
                copy ?: return@withContext "⚠️ 画像のコピーに失敗しました"
            } else {
                decoded
            }

            val result = routeC?.process(safeCopy)

            if (result != null && result.success) {
                val savedUri = saveBitmapToGallery(result.bitmap, "Enhanced")
                val responseText = buildString {
                    appendLine("✅ 超解像完了 (${result.method})")
                    appendLine("📐 元画像: ${origW}×${origH}")
                    appendLine("📐 処理: ${safeCopy.width}×${safeCopy.height} → ${result.bitmap.width}×${result.bitmap.height}")
                    appendLine("⏱ ${result.timeMs}ms")
                    if (savedUri != null) {
                        appendLine("📁 Pictures/NexusVision/ に保存")
                    }
                    appendLine()
                    appendLine("💡 部分拡大は「ズーム」と送信してください")
                }
                safeCopy.recycle()
                result.bitmap.recycle()
                responseText
            } else {
                val savedUri = saveBitmapToGallery(safeCopy, "Original")
                safeCopy.recycle()
                buildString {
                    appendLine("⚠️ 超解像処理に失敗しました")
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
```

### Step 2: `CropSelector.kt` を修正（`drawImage` の修正）

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ui/components/CropSelector.kt
package com.nexus.vision.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 画像の範囲選択コンポーネント
 * サムネイルを表示し、ユーザーがドラッグで矩形を選択
 * 「この範囲を超解像」ボタンで確定
 */
@Composable
fun CropSelector(
    thumbnail: Bitmap,
    imageWidth: Int,
    imageHeight: Int,
    onConfirm: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var confirmed by remember { mutableStateOf(false) }

    val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
    val aspectRatio = thumbnail.width.toFloat() / thumbnail.height.toFloat()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "拡大したい範囲をドラッグで選択 (${imageWidth}×${imageHeight})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // キャンバス（サムネイル＋選択矩形）
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(8.dp))
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragEnd = offset
                            confirmed = false
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragEnd = change.position
                        },
                        onDragEnd = { confirmed = true }
                    )
                }
        ) {
            // サムネイル描画
            drawImage(
                image = imageBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(imageBitmap.width, imageBitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )

            // 選択矩形
            val s = dragStart
            val e = dragEnd
            if (s != null && e != null) {
                val rLeft = minOf(s.x, e.x).coerceIn(0f, size.width)
                val rTop = minOf(s.y, e.y).coerceIn(0f, size.height)
                val rRight = maxOf(s.x, e.x).coerceIn(0f, size.width)
                val rBottom = maxOf(s.y, e.y).coerceIn(0f, size.height)
                val rW = rRight - rLeft
                val rH = rBottom - rTop

                if (rW > 4f && rH > 4f) {
                    // 暗いオーバーレイ（選択外）
                    val dimColor = Color.Black.copy(alpha = 0.5f)
                    // 上
                    drawRect(dimColor, Offset.Zero, Size(size.width, rTop))
                    // 下
                    drawRect(dimColor, Offset(0f, rBottom), Size(size.width, size.height - rBottom))
                    // 左
                    drawRect(dimColor, Offset(0f, rTop), Size(rLeft, rH))
                    // 右
                    drawRect(dimColor, Offset(rRight, rTop), Size(size.width - rRight, rH))

                    // 選択枠
                    drawRect(
                        Color.Cyan,
                        Offset(rLeft, rTop),
                        Size(rW, rH),
                        style = Stroke(width = 2.5f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ボタン
        Row {
            OutlinedButton(onClick = onCancel) {
                Text("キャンセル")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    val s = dragStart
                    val e = dragEnd
                    if (s != null && e != null && canvasSize.width > 0 && canvasSize.height > 0) {
                        val left = (minOf(s.x, e.x) / canvasSize.width).coerceIn(0f, 1f)
                        val top = (minOf(s.y, e.y) / canvasSize.height).coerceIn(0f, 1f)
                        val right = (maxOf(s.x, e.x) / canvasSize.width).coerceIn(0f, 1f)
                        val bottom = (maxOf(s.y, e.y) / canvasSize.height).coerceIn(0f, 1f)
                        if (right - left > 0.02f && bottom - top > 0.02f) {
                            onConfirm(left, top, right, bottom)
                        }
                    }
                },
                enabled = confirmed && dragStart != null && dragEnd != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("この範囲を超解像")
            }
        }
    }
}
```

### Step 3: `MainScreen.kt` にクロップUIを統合

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ui/MainScreen.kt

package com.nexus.vision.ui

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
    onImageSelected: ((android.net.Uri) -> Unit) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        onImageSelected { uri ->
            viewModel.setSelectedImage(uri)
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

                // 範囲選択モード中はクロップUIを表示
                if (uiState.cropMode && uiState.cropThumbnail != null) {
                    CropSelector(
                        thumbnail = uiState.cropThumbnail!!,
                        imageWidth = uiState.cropImageWidth,
                        imageHeight = uiState.cropImageHeight,
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

**操作フロー：**

1. 画像を選択し「ズーム」と送信
2. チャットに「範囲をドラッグで選択してください」とメッセージが出る
3. 画面下部のチャット入力がクロップUIに切り替わる（サムネイル＋ドラッグ選択）
4. 範囲をドラッグで選択 →「この範囲を超解像」ボタンを押す
5. 選択範囲だけを `BitmapRegionDecoder` でデコード（OOMしない）→ 4×超解像 → 保存
6. 結果がチャットに表示される

**変更ファイルまとめ：**

| ファイル | 操作 |
|---|---|
| `image/RegionDecoder.kt` | 前回作成済み・変更なし |
| `ncnn/NcnnSuperResolution.kt` | 前回作成済み・変更なし |
| `pipeline/RouteCProcessor.kt` | 前回作成済み・変更なし |
| `ui/components/CropSelector.kt` | **新規作成** |
| `ui/MainScreen.kt` | **置換** |
| `ui/MainViewModel.kt` | **置換** |