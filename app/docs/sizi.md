

8688×11584の画像全体をメモリに読み込む時点でクラッシュしますね。`BitmapFactory.decodeStream`で約380MBのメモリが必要になるので当然です。

**方針変更：画像全体をメモリに読み込まず、選択した領域だけをデコードして超解像する**

Android の `BitmapRegionDecoder` を使えば、画像の一部分だけをメモリに読み込めます。ユーザーが範囲を選択 → その領域だけデコード → 超解像、という流れです。

これを実現するために必要なものは：

1. **画像の領域選択UI（クロップUI）** — 画像のプレビュー（低解像度サムネイル）を表示し、ユーザーが拡大したい範囲をドラッグで選択
2. **`BitmapRegionDecoder`で選択領域だけデコード** — メモリ消費を最小化
3. **デコードした領域に超解像を適用**

まず全ファイルの変更を整理します。

---

### Step 1: クロップ選択UI

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ui/components/CropSelector.kt
package com.nexus.vision.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import android.graphics.Bitmap

/**
 * 画像の範囲選択コンポーネント
 * サムネイルを表示し、ユーザーがドラッグで矩形を選択
 * onCropSelected: 選択範囲を元画像上の座標比率(0.0〜1.0)で返す
 */
@Composable
fun CropSelector(
    thumbnail: Bitmap,
    onCropSelected: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }

    Box(
        modifier = modifier
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStart = offset
                        dragEnd = offset
                    },
                    onDrag = { change, _ ->
                        dragEnd = change.position
                    },
                    onDragEnd = {
                        val start = dragStart
                        val end = dragEnd
                        if (start != null && end != null && viewSize.width > 0 && viewSize.height > 0) {
                            val left = (minOf(start.x, end.x) / viewSize.width).coerceIn(0f, 1f)
                            val top = (minOf(start.y, end.y) / viewSize.height).coerceIn(0f, 1f)
                            val right = (maxOf(start.x, end.x) / viewSize.width).coerceIn(0f, 1f)
                            val bottom = (maxOf(start.y, end.y) / viewSize.height).coerceIn(0f, 1f)
                            if (right - left > 0.02f && bottom - top > 0.02f) {
                                onCropSelected(left, top, right, bottom)
                            }
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // サムネイル描画
            drawImage(
                image = imageBitmap,
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )

            // 選択矩形描画
            val s = dragStart
            val e = dragEnd
            if (s != null && e != null) {
                val rect = Rect(
                    left = minOf(s.x, e.x),
                    top = minOf(s.y, e.y),
                    right = maxOf(s.x, e.x),
                    bottom = maxOf(s.y, e.y)
                )
                // 半透明オーバーレイ（選択外）
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset.Zero,
                    size = Size(size.width, rect.top)
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(0f, rect.bottom),
                    size = Size(size.width, size.height - rect.bottom)
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(0f, rect.top),
                    size = Size(rect.left, rect.height)
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(rect.right, rect.top),
                    size = Size(size.width - rect.right, rect.height)
                )
                // 選択枠
                drawRect(
                    color = Color.White,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = 3f)
                )
                drawRect(
                    color = Color.Cyan,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = 1.5f)
                )
            }
        }
    }
}
```

### Step 2: 領域デコーダー

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/image/RegionDecoder.kt
package com.nexus.vision.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log

/**
 * 画像の一部領域だけをメモリに読み込む
 * 大画像でもOOMにならない
 */
object RegionDecoder {
    private const val TAG = "RegionDecoder"

    /**
     * 画像の実際のサイズを取得（メモリにデコードしない）
     */
    fun getImageSize(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                Pair(options.outWidth, options.outHeight)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "getImageSize error: ${e.message}")
            null
        }
    }

    /**
     * 低解像度サムネイルを取得（プレビュー用、長辺maxSide以下）
     */
    fun decodeThumbnail(context: Context, uri: Uri, maxSide: Int = 1024): Bitmap? {
        return try {
            val size = getImageSize(context, uri) ?: return null
            val (w, h) = size
            val sampleSize = calculateSampleSize(w, h, maxSide)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeThumbnail error: ${e.message}")
            null
        }
    }

    /**
     * 指定領域だけをデコード
     * ratioRect: 0.0〜1.0 の比率で指定（left, top, right, bottom）
     * maxOutputSide: 出力の長辺の最大値（超解像に渡すため制限）
     */
    fun decodeRegion(
        context: Context,
        uri: Uri,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        maxOutputSide: Int = 2048
    ): Bitmap? {
        return try {
            val size = getImageSize(context, uri) ?: return null
            val (imgW, imgH) = size

            // 比率からピクセル座標に変換
            val pixelLeft = (left * imgW).toInt().coerceIn(0, imgW)
            val pixelTop = (top * imgH).toInt().coerceIn(0, imgH)
            val pixelRight = (right * imgW).toInt().coerceIn(0, imgW)
            val pixelBottom = (bottom * imgH).toInt().coerceIn(0, imgH)

            val regionW = pixelRight - pixelLeft
            val regionH = pixelBottom - pixelTop
            if (regionW < 32 || regionH < 32) {
                Log.w(TAG, "Region too small: ${regionW}x${regionH}")
                return null
            }

            Log.i(TAG, "Decoding region: ($pixelLeft,$pixelTop)-($pixelRight,$pixelBottom) = ${regionW}x${regionH} from ${imgW}x${imgH}")

            // 領域がmaxOutputSideを超える場合はサンプリングで縮小
            val maxRegionSide = maxOf(regionW, regionH)
            val sampleSize = if (maxRegionSide > maxOutputSide) {
                calculateSampleSize(regionW, regionH, maxOutputSide)
            } else {
                1
            }

            val rect = Rect(pixelLeft, pixelTop, pixelRight, pixelBottom)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val stream = context.contentResolver.openInputStream(uri)
                ?: return null

            val decoder = BitmapRegionDecoder.newInstance(stream, false)
            val bitmap = decoder?.decodeRegion(rect, options)
            decoder?.recycle()
            stream.close()

            if (bitmap != null) {
                Log.i(TAG, "Region decoded: ${bitmap.width}x${bitmap.height} (sample=$sampleSize)")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "decodeRegion error: ${e.message}")
            null
        }
    }

    /**
     * 画像全体を安全にデコード（長辺maxSide以下にサンプリング）
     */
    fun decodeSafe(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? {
        return try {
            val size = getImageSize(context, uri) ?: return null
            val (w, h) = size
            val sampleSize = calculateSampleSize(w, h, maxSide)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeSafe error: ${e.message}")
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        val maxDim = maxOf(width, height)
        var sampleSize = 1
        while (maxDim / sampleSize > maxSide) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
```

### Step 3: NcnnSuperResolution.kt（シンプル化）

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"
        private const val PARAM_FILE = "models/realesrgan-x4plus.param"
        private const val MODEL_FILE = "models/realesrgan-x4plus.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 64
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            val result = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "x4plus model initialized")
            else Log.e(TAG, "x4plus model init failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    /**
     * 4×超解像を実行
     * 入力は長辺2048px以下を想定（呼び出し側で制限済み）
     */
    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        return try {
            val argbInput = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            Log.i(TAG, "SR: ${argbInput.width}x${argbInput.height} -> ${argbInput.width * SCALE}x${argbInput.height * SCALE}")
            val startTime = System.currentTimeMillis()
            val result = RealEsrganBridge.nativeProcess(argbInput)
            val elapsed = System.currentTimeMillis() - startTime
            if (result != null) {
                Log.i(TAG, "SR done: ${result.width}x${result.height} in ${elapsed}ms")
            } else {
                Log.e(TAG, "nativeProcess returned null after ${elapsed}ms")
            }
            if (argbInput !== bitmap) argbInput.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "SR error: ${e.message}")
            null
        }
    }

    fun release() {
        try { RealEsrganBridge.nativeRelease() } catch (e: Exception) { Log.e(TAG, "Release error: ${e.message}") }
        initialized = false
    }
}
```

### Step 4: RouteCProcessor.kt（簡素化）

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/pipeline/RouteCProcessor.kt
package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class RouteCProcessor(private val context: Context) {
    companion object {
        private const val TAG = "RouteCProcessor"
    }

    private var sr: com.nexus.vision.ncnn.NcnnSuperResolution? = null

    fun initialize(): Boolean {
        sr = com.nexus.vision.ncnn.NcnnSuperResolution()
        return sr?.initialize(context) ?: false
    }

    /**
     * Bitmapを超解像する（呼び出し側で適切なサイズに制限済み）
     */
    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()
        val result = sr?.upscale(bitmap)
        val elapsed = System.currentTimeMillis() - startTime

        return if (result != null) {
            Log.i(TAG, "SR success: ${bitmap.width}x${bitmap.height} -> ${result.width}x${result.height} in ${elapsed}ms")
            ProcessResult(result, "NCNN Real-ESRGAN x4plus", elapsed, true)
        } else {
            Log.w(TAG, "SR failed, returning original")
            ProcessResult(bitmap, "passthrough (SR failed)", elapsed, false)
        }
    }

    fun release() {
        sr?.release()
        sr = null
    }

    data class ProcessResult(
        val bitmap: Bitmap,
        val method: String,
        val elapsedMs: Long,
        val success: Boolean
    ) {
        val timeMs: Long get() = elapsedMs
    }
}
```

### Step 5: MainViewModel.kt の processEnhanceRequest と processZoomRequest を修正

`MainViewModel.kt` の `processEnhanceRequest` と `processZoomRequest` を以下に置き換えてください：

```kotlin
    /**
     * 高画質化処理
     * 大画像 → 安全にサンプリングデコード（長辺2048以下）して超解像
     * 小画像 → そのまま超解像
     */
    private suspend fun processEnhanceRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            // 元画像サイズを取得（メモリにロードしない）
            val imgSize = com.nexus.vision.image.RegionDecoder.getImageSize(context, uri)
                ?: return@withContext "⚠️ 画像サイズを取得できません"
            val (origW, origH) = imgSize

            // 安全にデコード（長辺2048px以下にサンプリング）
            val decoded = com.nexus.vision.image.RegionDecoder.decodeSafe(context, uri, 2048)
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

    /**
     * デジタルズーム処理
     * 画像のプレビューを表示 → ユーザーが範囲選択 → 選択領域だけデコード → 超解像
     * （暫定版：中央50%をROIとして処理。UI連携は次フェーズ）
     */
    private suspend fun processZoomRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val imgSize = com.nexus.vision.image.RegionDecoder.getImageSize(context, uri)
                ?: return@withContext "⚠️ 画像サイズを取得できません"
            val (origW, origH) = imgSize

            // 中央50%をROIとしてデコード（長辺2048以下）
            val regionBitmap = com.nexus.vision.image.RegionDecoder.decodeRegion(
                context, uri,
                left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f,
                maxOutputSide = 2048
            ) ?: return@withContext "⚠️ 領域のデコードに失敗しました"

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
                    appendLine("📐 元画像: ${origW}×${origH}")
                    appendLine("📐 ROI: 中央50% → ${safeCopy.width}×${safeCopy.height}")
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
                "⚠️ デジタルズームに失敗しました"
            }
        }
```

---

**変更ファイルまとめ：**

| ファイル | 変更 |
|---|---|
| `ui/components/CropSelector.kt` | **新規作成** — 範囲選択UI（次フェーズで接続） |
| `image/RegionDecoder.kt` | **新規作成** — 安全な部分デコード |
| `ncnn/NcnnSuperResolution.kt` | **置換** — シンプル化、x4plusモデル |
| `pipeline/RouteCProcessor.kt` | **置換** — シンプル化 |
| `ui/MainViewModel.kt` | **部分修正** — `processEnhanceRequest`と`processZoomRequest`のみ |

**動作フロー：**

「高画質」→ 元画像を長辺2048以下にサンプリングデコード → 4×超解像 → 最大8192pxの出力を保存

「ズーム」→ 中央50%だけを `BitmapRegionDecoder` でデコード → 4×超解像 → 保存

どちらもOOMにならず、元画像は一度もフルサイズでメモリに読み込みません。