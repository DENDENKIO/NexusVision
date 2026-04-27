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
        private const val PARAM_FILE = "models/realesr-animevideov3-x4.param"
        private const val MODEL_FILE = "models/realesr-animevideov3-x4.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 32

        // AI超解像の入力最大サイズ
        private const val SR_MAX_INPUT = 512

        // タイル処理パラメータ
        private const val PROCESS_TILE = 512
        private const val PROCESS_OVERLAP = 32
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            val result = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "Initialized (Vulkan+FP16, tile=$TILE_SIZE)")
            else Log.e(TAG, "Init failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    /**
     * メイン超解像メソッド
     * ≤512px: 直接4× SR
     * >512px: タイル方式 3段融合パイプライン (出力=入力と同サイズ)
     */
    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        val maxSide = maxOf(bitmap.width, bitmap.height)
        return if (maxSide <= SR_MAX_INPUT) {
            Log.i(TAG, "Small image: direct 4x SR")
            processSuperResolution(bitmap)
        } else {
            Log.i(TAG, "Large image: Guided+DWT+IBP fusion pipeline")
            processTiledFusionPipeline(bitmap)
        }
    }

    /**
     * タイル方式3段融合パイプライン
     * 各タイル(512×512)ごとに:
     *   1. origTile = 元解像度タイル切り出し
     *   2. srInput = origTile を ≤512に縮小
     *   3. aiResult = nativeProcess(srInput) → 4×拡大
     *   4. aiUpscaled = aiResult を origTile サイズにリサイズ
     *   5. result = nativeFusionPipeline(origTile, aiUpscaled, srInput)
     *   6. 出力キャンバスに書き込み
     */
    private fun processTiledFusionPipeline(bitmap: Bitmap): Bitmap? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val startTime = System.currentTimeMillis()
            val original = ensureArgb(bitmap) ?: return null

            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val step = PROCESS_TILE - PROCESS_OVERLAP
            val tilesX = (w + step - 1) / step
            val tilesY = (h + step - 1) / step
            val totalTiles = tilesX * tilesY

            Log.i(TAG, "Fusion pipeline: ${w}x${h}, tiles=$totalTiles")

            var processed = 0
            var successCount = 0

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val srcLeft = (tx * step).coerceAtMost(w - 1)
                    val srcTop = (ty * step).coerceAtMost(h - 1)
                    val srcRight = (srcLeft + PROCESS_TILE).coerceAtMost(w)
                    val srcBottom = (srcTop + PROCESS_TILE).coerceAtMost(h)
                    val tileW = srcRight - srcLeft
                    val tileH = srcBottom - srcTop
                    if (tileW < 16 || tileH < 16) continue

                    val origTile = Bitmap.createBitmap(original, srcLeft, srcTop, tileW, tileH)
                    val processedTile = processOneTileFusion(origTile)

                    if (processedTile != null) {
                        canvas.drawBitmap(processedTile, null,
                            Rect(srcLeft, srcTop, srcRight, srcBottom), null)
                        processedTile.recycle()
                        successCount++
                    } else {
                        canvas.drawBitmap(origTile, null,
                            Rect(srcLeft, srcTop, srcRight, srcBottom), null)
                    }
                    origTile.recycle()

                    processed++
                    if (processed % 4 == 0 || processed == totalTiles) {
                        Log.i(TAG, "Progress: $processed/$totalTiles")
                    }
                }
            }

            if (original !== bitmap) original.recycle()
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Fusion done: ${w}x${h}, $successCount/$totalTiles tiles, ${elapsed}ms")
            output
        } catch (e: Exception) {
            Log.e(TAG, "Fusion pipeline error: ${e.message}")
            null
        }
    }

    /**
     * 1タイルの3段融合処理
     */
    private fun processOneTileFusion(origTile: Bitmap): Bitmap? {
        return try {
            val tileW = origTile.width
            val tileH = origTile.height

            // Step 1: 縮小してAI入力を作る
            val srInput = limitSize(origTile, SR_MAX_INPUT)
            val srArgb = ensureArgb(srInput) ?: return null

            // Step 2: AI 4× 超解像
            val aiResult = RealEsrganBridge.nativeProcess(srArgb)
            if (aiResult == null) {
                if (srArgb !== srInput) srArgb.recycle()
                if (srInput !== origTile) srInput.recycle()
                return null
            }

            // Step 3: AI結果を元タイルサイズにリサイズ
            val aiUpscaled = if (aiResult.width != tileW || aiResult.height != tileH) {
                val scaled = Bitmap.createScaledBitmap(aiResult, tileW, tileH, true)
                aiResult.recycle()
                ensureArgb(scaled) ?: return null
            } else {
                ensureArgb(aiResult) ?: return null
            }

            // Step 4: nativeFusionPipeline(origTile, aiUpscaled, srArgb)
            //   srArgb = AI入力 (低解像度版) → IBP のリファレンス
            val origArgb = ensureArgb(origTile) ?: run {
                aiUpscaled.recycle()
                if (srArgb !== srInput) srArgb.recycle()
                if (srInput !== origTile) srInput.recycle()
                return null
            }

            val fused = RealEsrganBridge.nativeFusionPipeline(origArgb, aiUpscaled, srArgb)

            // クリーンアップ
            if (origArgb !== origTile) origArgb.recycle()
            aiUpscaled.recycle()
            if (srArgb !== srInput) srArgb.recycle()
            if (srInput !== origTile) srInput.recycle()

            fused
        } catch (e: Exception) {
            Log.e(TAG, "Tile fusion error: ${e.message}")
            null
        }
    }

    /**
     * 単純4×超解像（小画像用）
     */
    private fun processSuperResolution(bitmap: Bitmap): Bitmap? {
        return try {
            val argb = ensureArgb(bitmap) ?: return null
            Log.i(TAG, "SR: ${argb.width}x${argb.height} -> ${argb.width * SCALE}x${argb.height * SCALE}")
            val result = RealEsrganBridge.nativeProcess(argb)
            if (argb !== bitmap) argb.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "SR error: ${e.message}")
            null
        }
    }

    fun release() {
        try { RealEsrganBridge.nativeRelease() } catch (e: Exception) { Log.e(TAG, "Release: ${e.message}") }
        initialized = false
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap? {
        return if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap
    }

    private fun limitSize(bitmap: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / max
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(16)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(16)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
