package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log

/**
 * SAFMN++ 超解像エンジン
 * Phase 14-1: Real-ESRGAN と同じ Tiled Pipeline 構造
 *
 * SAFMN++ は ~240K パラメータで Real-ESRGAN (~16.7M) の 1/70。
 * NCNN Vulkan FP16 で高速推論。CNN のみ（torch.roll 不要）。
 */
class SafmnSuperResolution {
    companion object {
        private const val TAG = "SafmnSR"
        private const val PARAM_FILE = "models/safmn_x4.ncnn.param"
        private const val MODEL_FILE = "models/safmn_x4.ncnn.bin"
        private const val SCALE = 4
        // SAFMN は軽量なので Real-ESRGAN より大きめタイルが可能
        private const val TILE_SIZE = 64

        private const val SR_MAX_INPUT = 128
        private const val PROCESS_TILE = 128
        private const val PROCESS_OVERLAP = 16
        private const val MAX_OUTPUT_SIDE = 4096
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && SafmnBridge.nativeSafmnIsLoaded()) return true
        return try {
            val result = SafmnBridge.nativeSafmnInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "SAFMN++ initialized (Vulkan+FP16, tile=$TILE_SIZE)")
            else Log.e(TAG, "SAFMN++ init failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "SAFMN++ init error: ${e.message}")
            false
        }
    }

    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !SafmnBridge.nativeSafmnIsLoaded()) {
            Log.e(TAG, "SAFMN++ not loaded")
            return null
        }
        val maxSide = maxOf(bitmap.width, bitmap.height)
        return if (maxSide <= SR_MAX_INPUT) {
            Log.i(TAG, "Small (${bitmap.width}x${bitmap.height}): direct 4x")
            processDirect(bitmap)
        } else {
            Log.i(TAG, "Large (${bitmap.width}x${bitmap.height}): tiled")
            processTiled(bitmap)
        }
    }

    private fun processDirect(bitmap: Bitmap): Bitmap? {
        return try {
            val argb = ensureArgb(bitmap) ?: return null
            val result = SafmnBridge.nativeSafmnProcess(argb)
            if (argb !== bitmap) argb.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Direct SR error: ${e.message}")
            null
        }
    }

    private fun processTiled(bitmap: Bitmap): Bitmap? {
        return try {
            val inW = bitmap.width
            val inH = bitmap.height
            val startTime = System.currentTimeMillis()

            val scaleFactor = minOf(
                SCALE.toFloat(),
                MAX_OUTPUT_SIDE.toFloat() / maxOf(inW, inH)
            )
            val outW = (inW * scaleFactor).toInt()
            val outH = (inH * scaleFactor).toInt()

            val original = ensureArgb(bitmap) ?: return null
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val step = PROCESS_TILE - PROCESS_OVERLAP
            val tilesX = (inW + step - 1) / step
            val tilesY = (inH + step - 1) / step
            var successCount = 0

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val srcLeft = (tx * step).coerceAtMost(inW - 1)
                    val srcTop = (ty * step).coerceAtMost(inH - 1)
                    val srcRight = (srcLeft + PROCESS_TILE).coerceAtMost(inW)
                    val srcBottom = (srcTop + PROCESS_TILE).coerceAtMost(inH)
                    val tileW = srcRight - srcLeft
                    val tileH = srcBottom - srcTop
                    if (tileW < 8 || tileH < 8) continue

                    val tile = Bitmap.createBitmap(original, srcLeft, srcTop, tileW, tileH)
                    val limited = limitSize(tile, SR_MAX_INPUT)
                    val argb = ensureArgb(limited) ?: continue

                    val srResult = SafmnBridge.nativeSafmnProcess(argb)
                    if (argb !== limited) argb.recycle()
                    if (limited !== tile) limited.recycle()

                    val outLeft = (srcLeft * scaleFactor).toInt()
                    val outTop = (srcTop * scaleFactor).toInt()
                    val outRight = if (tx == tilesX - 1) outW
                                   else (srcRight * scaleFactor).toInt()
                    val outBottom = if (ty == tilesY - 1) outH
                                    else (srcBottom * scaleFactor).toInt()
                    val targetW = outRight - outLeft
                    val targetH = outBottom - outTop

                    if (srResult != null) {
                        val scaled = if (srResult.width == targetW && srResult.height == targetH)
                            srResult
                        else Bitmap.createScaledBitmap(srResult, targetW, targetH, true)
                        canvas.drawBitmap(scaled, outLeft.toFloat(), outTop.toFloat(), null)
                        if (scaled !== srResult) scaled.recycle()
                        srResult.recycle()
                        successCount++
                    } else {
                        val fb = Bitmap.createScaledBitmap(tile, targetW, targetH, true)
                        canvas.drawBitmap(fb, outLeft.toFloat(), outTop.toFloat(), null)
                        fb.recycle()
                    }
                    tile.recycle()
                }
            }

            if (original !== bitmap) original.recycle()
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Tiled done: ${outW}x${outH}, $successCount tiles, ${elapsed}ms")
            output
        } catch (e: Exception) {
            Log.e(TAG, "Tiled error: ${e.message}")
            null
        }
    }

    fun release() {
        try { SafmnBridge.nativeSafmnRelease() } catch (_: Exception) {}
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
