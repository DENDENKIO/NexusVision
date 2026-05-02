package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"
        private const val PARAM_FILE = "models/realesr-general-x4v3.param"
        private const val MODEL_FILE = "models/realesr-general-x4v3.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 128

        // --- 最適化: タイルサイズ拡大 ---
        private const val SR_MAX_INPUT = 256
        private const val PROCESS_TILE = 256
        private const val PROCESS_OVERLAP = 16
        private const val MAX_OUTPUT_SIDE = 4096
    }

    private var initialized = false

    // Fusion ON/OFF 切り替えフラグ（テスト用）は削除 -> Direct 固定
    // var useFusion = false

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

    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        val maxSide = maxOf(bitmap.width, bitmap.height)
        return if (maxSide <= SR_MAX_INPUT) {
            Log.i(TAG, "Small image (${bitmap.width}x${bitmap.height}): direct 4x SR")
            processSuperResolution(bitmap)
        } else {
            Log.i(TAG, "Large image (${bitmap.width}x${bitmap.height}): Tiled Pipeline")
            processTiledPipeline(bitmap)
        }
    }

    private fun processTiledPipeline(bitmap: Bitmap): Bitmap? {
        return try {
            val inW = bitmap.width
            val inH = bitmap.height
            val startTime = System.currentTimeMillis()

            val scaleFactor = minOf(SCALE.toFloat(), MAX_OUTPUT_SIDE.toFloat() / maxOf(inW, inH))
            val outW = (inW * scaleFactor).toInt()
            val outH = (inH * scaleFactor).toInt()

            Log.i(TAG, "Pipeline Start: ${inW}x${inH} -> ${outW}x${outH} (Scale: $scaleFactor)")

            val original = ensureArgb(bitmap) ?: return null
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val step = PROCESS_TILE - PROCESS_OVERLAP
            val tilesX = (inW + step - 1) / step
            val tilesY = (inH + step - 1) / step
            val totalTiles = tilesX * tilesY

            var processed = 0
            var successCount = 0

            Log.i(TAG, "Tiles: ${tilesX}x${tilesY} = $totalTiles (tile=$PROCESS_TILE, step=$step)")

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val srcLeft = (tx * step).coerceAtMost(inW - 1)
                    val srcTop = (ty * step).coerceAtMost(inH - 1)
                    val srcRight = (srcLeft + PROCESS_TILE).coerceAtMost(inW)
                    val srcBottom = (srcTop + PROCESS_TILE).coerceAtMost(inH)

                    val tileW = srcRight - srcLeft
                    val tileH = srcBottom - srcTop
                    if (tileW < 8 || tileH < 8) continue

                    val origTile = Bitmap.createBitmap(original, srcLeft, srcTop, tileW, tileH)

                    // タイルを処理（Direct 固定）
                    val resultTile = processOneTileDirect(origTile)

                    val outLeft = (srcLeft * scaleFactor).toInt()
                    val outTop = (srcTop * scaleFactor).toInt()
                    val outRight = if (tx == tilesX - 1) outW else (srcRight * scaleFactor).toInt()
                    val outBottom = if (ty == tilesY - 1) outH else (srcBottom * scaleFactor).toInt()
                    val targetW = outRight - outLeft
                    val targetH = outBottom - outTop

                    if (resultTile != null) {
                        val scaled = if (resultTile.width == targetW && resultTile.height == targetH)
                            resultTile
                        else Bitmap.createScaledBitmap(resultTile, targetW, targetH, true)
                        canvas.drawBitmap(scaled, outLeft.toFloat(), outTop.toFloat(), null)
                        if (scaled !== resultTile) scaled.recycle()
                        resultTile.recycle()
                        successCount++
                    } else {
                        val fallback = Bitmap.createScaledBitmap(origTile, targetW, targetH, true)
                        canvas.drawBitmap(fallback, outLeft.toFloat(), outTop.toFloat(), null)
                        fallback.recycle()
                    }

                    origTile.recycle()
                    processed++

                    if (processed % 5 == 0 || processed == totalTiles) {
                        Log.i(TAG, "Progress: $processed/$totalTiles")
                    }
                }
            }

            if (original !== bitmap) original.recycle()

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Pipeline Done: ${outW}x${outH}, $successCount/$totalTiles tiles, ${elapsed}ms")

            output
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error: ${e.message}")
            null
        }
    }

    /**
     * Fusion なし: AI SR の出力をそのまま使う（油絵感・ノイズ軽減テスト）
     */
    private fun processOneTileDirect(origTile: Bitmap): Bitmap? {
        return try {
            val srInput = limitSize(origTile, SR_MAX_INPUT)
            val srArgb = ensureArgb(srInput) ?: return null
            val aiResult = RealEsrganBridge.nativeProcess(srArgb)
            if (srArgb !== srInput) srArgb.recycle()
            if (srInput !== origTile) srInput.recycle()
            aiResult
        } catch (e: Exception) {
            Log.e(TAG, "Direct tile error: ${e.message}")
            null
        }
    }

    /**
     * Fusion あり: AI SR + Guided Filter + DWT + IBP（従来の処理）
     */
    private fun processOneTileFusion(origTile: Bitmap): Bitmap? {
        var srInput: Bitmap? = null
        var srArgb: Bitmap? = null
        var aiResult: Bitmap? = null
        var origUpscaled: Bitmap? = null

        return try {
            srInput = limitSize(origTile, SR_MAX_INPUT)
            srArgb = ensureArgb(srInput) ?: return null
            aiResult = RealEsrganBridge.nativeProcess(srArgb) ?: return null

            val aiW = aiResult.width
            val aiH = aiResult.height
            val scaledRef = Bitmap.createScaledBitmap(origTile, aiW, aiH, true)
            origUpscaled = ensureArgb(scaledRef) ?: return null
            if (scaledRef !== origUpscaled) scaledRef.recycle()

            RealEsrganBridge.nativeFusionPipeline(origUpscaled, aiResult, srArgb)
        } catch (e: Exception) {
            Log.e(TAG, "Fusion tile error: ${e.message}")
            null
        } finally {
            if (srArgb !== srInput) srArgb?.recycle()
            if (srInput !== origTile) srInput?.recycle()
            aiResult?.recycle()
            origUpscaled?.recycle()
        }
    }

    private fun processSuperResolution(bitmap: Bitmap): Bitmap? {
        return try {
            val argb = ensureArgb(bitmap) ?: return null
            Log.i(TAG, "SR Start: ${argb.width}x${argb.height} -> ${argb.width * SCALE}x${argb.height * SCALE}")
            val result = RealEsrganBridge.nativeProcess(argb)
            if (argb !== bitmap) argb.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Direct SR error: ${e.message}")
            null
        }
    }

    fun release() {
        try { RealEsrganBridge.nativeRelease() } catch (e: Exception) { Log.e(TAG, "Release: ${e.message}") }
        initialized = false
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap? {
        return if (bitmap.config != Bitmap.Config.ARGB_8888)
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        else bitmap
    }

    private fun limitSize(bitmap: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / max
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(8)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(8)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
