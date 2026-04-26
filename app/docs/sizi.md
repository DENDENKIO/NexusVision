



`NcnnSuperResolution.kt` を以下に置き換えてください：

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"

        private const val PARAM_FILE = "models/realesrgan-x4plus.param"
        private const val MODEL_FILE = "models/realesrgan-x4plus.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 64

        // この閾値以下なら4×拡大、超えたら元サイズ維持で画質強調
        private const val UPSCALE_THRESHOLD = 2048

        // 画質強調モードのタイルサイズ（元画像上の切り出しサイズ）
        private const val ENHANCE_TILE = 256
        // タイル重なり（継ぎ目を目立たなくする）
        private const val ENHANCE_OVERLAP = 16
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
     * メインの高画質化エントリポイント
     * 小画像 → 4×拡大超解像
     * 大画像 → 元サイズ維持の画質強調
     */
    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }

        val maxSide = maxOf(bitmap.width, bitmap.height)

        return if (maxSide <= UPSCALE_THRESHOLD) {
            Log.i(TAG, "Mode: 4x upscale (${bitmap.width}x${bitmap.height})")
            processWithNcnn(bitmap)
        } else {
            Log.i(TAG, "Mode: enhance-in-place (${bitmap.width}x${bitmap.height})")
            enhanceInPlace(bitmap)
        }
    }

    /**
     * 画質強調モード（案C）
     * 元画像をタイル分割 → 各タイルを4×超解像 → 元のタイルサイズに縮小 → 合成
     * 出力サイズ＝入力サイズ（拡大しない）
     * 超解像→縮小を経ることでノイズ除去＋ディテール強調の効果を得る
     */
    private fun enhanceInPlace(bitmap: Bitmap): Bitmap? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val startTime = System.currentTimeMillis()

            // 入力を確実にARGB_8888にする
            val source = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }

            // 出力用Bitmap（元と同じサイズ）
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val step = ENHANCE_TILE - ENHANCE_OVERLAP
            val tilesX = (w + step - 1) / step
            val tilesY = (h + step - 1) / step
            val totalTiles = tilesX * tilesY
            var processed = 0

            Log.i(TAG, "Enhance: ${w}x${h}, tiles=${tilesX}x${tilesY} (${totalTiles} total), tile=${ENHANCE_TILE}px, overlap=${ENHANCE_OVERLAP}px")

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val srcLeft = (tx * step).coerceAtMost(w - 1)
                    val srcTop = (ty * step).coerceAtMost(h - 1)
                    val srcRight = (srcLeft + ENHANCE_TILE).coerceAtMost(w)
                    val srcBottom = (srcTop + ENHANCE_TILE).coerceAtMost(h)
                    val tileW = srcRight - srcLeft
                    val tileH = srcBottom - srcTop

                    if (tileW < 16 || tileH < 16) continue

                    // タイル切り出し
                    val tile = Bitmap.createBitmap(source, srcLeft, srcTop, tileW, tileH)

                    // 4×超解像
                    val srTile = RealEsrganBridge.nativeProcess(tile)
                    tile.recycle()

                    if (srTile != null) {
                        // 超解像結果を元のタイルサイズに縮小
                        val shrunk = Bitmap.createScaledBitmap(srTile, tileW, tileH, true)
                        srTile.recycle()

                        // 出力に書き込み
                        val dstRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
                        canvas.drawBitmap(shrunk, null, dstRect, null)
                        shrunk.recycle()
                    } else {
                        // 失敗時は元タイルをそのまま書き込み
                        val fallback = Bitmap.createBitmap(source, srcLeft, srcTop, tileW, tileH)
                        val dstRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
                        canvas.drawBitmap(fallback, null, dstRect, null)
                        fallback.recycle()
                        Log.w(TAG, "Tile ($tx,$ty) SR failed, using original")
                    }

                    processed++
                    if (processed % 5 == 0 || processed == totalTiles) {
                        val pct = (processed * 100.0 / totalTiles)
                        Log.i(TAG, "Enhance progress: $processed/$totalTiles (${String.format("%.1f", pct)}%)")
                    }
                }
            }

            if (source !== bitmap) source.recycle()

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Enhance done: ${w}x${h} in ${elapsed}ms ($totalTiles tiles)")
            output
        } catch (e: Exception) {
            Log.e(TAG, "Enhance error: ${e.message}")
            null
        }
    }

    /**
     * 通常の4×超解像（小画像用）
     */
    private fun processWithNcnn(bitmap: Bitmap): Bitmap? {
        return try {
            val argbInput = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            Log.i(TAG, "4x SR: ${argbInput.width}x${argbInput.height} -> ${argbInput.width * SCALE}x${argbInput.height * SCALE}")
            val startTime = System.currentTimeMillis()
            val result = RealEsrganBridge.nativeProcess(argbInput)
            val elapsed = System.currentTimeMillis() - startTime
            if (result != null) {
                Log.i(TAG, "4x SR done: ${result.width}x${result.height} in ${elapsed}ms")
            } else {
                Log.e(TAG, "nativeProcess returned null after ${elapsed}ms")
            }
            if (argbInput !== bitmap) argbInput.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Process error: ${e.message}")
            null
        }
    }

    /**
     * デジタルズーム（部分拡大＋超解像）
     */
    suspend fun digitalZoom(
        bitmap: Bitmap,
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        zoomFactor: Float = 2.0f
    ): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        return try {
            val roiWidth = (bitmap.width / zoomFactor).toInt().coerceIn(64, bitmap.width)
            val roiHeight = (bitmap.height / zoomFactor).toInt().coerceIn(64, bitmap.height)
            var left = ((centerX * bitmap.width) - roiWidth / 2).toInt()
            var top = ((centerY * bitmap.height) - roiHeight / 2).toInt()
            left = left.coerceIn(0, bitmap.width - roiWidth)
            top = top.coerceIn(0, bitmap.height - roiHeight)

            Log.i(TAG, "Digital zoom: ROI=${left},${top} ${roiWidth}x${roiHeight}")
            val roi = Bitmap.createBitmap(bitmap, left, top, roiWidth, roiHeight)

            // ROIに対してupscale（サイズに応じて4×拡大 or 画質強調が自動選択）
            val result = upscale(roi)
            if (roi !== bitmap) roi.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Digital zoom error: ${e.message}")
            null
        }
    }

    fun release() {
        try { RealEsrganBridge.nativeRelease() } catch (e: Exception) { Log.e(TAG, "Release error: ${e.message}") }
        initialized = false
    }
}
```

次に `MainViewModel.kt` の `processEnhanceRequest` を修正して、高解像度画像でもスキップせず処理するようにします：

```kotlin
    private suspend fun processEnhanceRequest(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val context = app.applicationContext

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("画像を開けません")
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) throw IllegalStateException("画像のデコードに失敗しました")

            val origW = originalBitmap.width
            val origH = originalBitmap.height

            // 安全なコピーを作成
            val safeCopy = originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
            originalBitmap.recycle()

            if (safeCopy == null) {
                return@withContext "⚠️ 画像のコピーに失敗しました"
            }

            // 超解像実行（サイズに応じて4×拡大 or 画質強調が自動選択）
            val result = routeC?.process(safeCopy)

            if (result != null && result.success) {
                val savedUri = saveBitmapToGallery(result.bitmap, "Enhanced")
                val mode = if (result.bitmap.width == safeCopy.width && result.bitmap.height == safeCopy.height) {
                    "画質強調" // 元サイズ維持
                } else {
                    "4×超解像" // 拡大
                }
                val responseText = buildString {
                    appendLine("✅ ${mode}完了 (${result.method})")
                    appendLine("📐 ${origW}×${origH} → ${result.bitmap.width}×${result.bitmap.height}")
                    appendLine("⏱ ${result.timeMs}ms")
                    if (savedUri != null) {
                        appendLine("📁 Pictures/NexusVision/ に保存")
                    } else {
                        appendLine("⚠️ 保存に失敗しました")
                    }
                }
                safeCopy.recycle()
                result.bitmap.recycle()
                responseText
            } else {
                val savedUri = saveBitmapToGallery(safeCopy, "Original")
                safeCopy.recycle()
                buildString {
                    appendLine("⚠️ 画質改善処理に失敗しました")
                    appendLine("元画像をそのまま保存しました (${origW}×${origH})")
                    if (savedUri != null) {
                        appendLine("📁 Pictures/NexusVision/ に保存済")
                    }
                }
            }
        }
```

**変更まとめ（案A + 案C 統合）：**

| モード | 条件 | 処理 | 出力 |
|---|---|---|---|
| 4×超解像 | 長辺 ≤2048px | そのままncnnに通す | 元の4倍サイズ |
| 画質強調 | 長辺 >2048px | タイル分割→各タイル4×SR→元サイズに縮小→合成 | 元と同じサイズ（画質向上） |

8688×11584 の画像は約1100タイルに分割されるため、**処理に数十分かかる可能性があります**。まずは小さめの画像（3000×4000程度）で動作確認してください。変更ファイルは `NcnnSuperResolution.kt` と `MainViewModel.kt` の `processEnhanceRequest` メソッドの2箇所です。