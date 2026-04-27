package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log

/**
 * NcnnSuperResolution: 3-Stage Fusion Pipeline (AI + Guided Filter + DWT + IBP)
 * Optimized for high-resolution images with memory safety.
 */
class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"
        private const val PARAM_FILE = "models/realesr-animevideov3-x4.param"
        private const val MODEL_FILE = "models/realesr-animevideov3-x4.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 32

        // --- Constants for Fixed Fusion Pipeline ---
        private const val SR_MAX_INPUT = 128        // Max side for AI input (AI Output will be 512)
        private const val PROCESS_TILE = 128         // Source tile size
        private const val PROCESS_OVERLAP = 16       // Overlap to prevent seams
        private const val MAX_OUTPUT_SIDE = 4096     // Safety limit for output dimensions
    }

    private var initialized = false

    /**
     * Initialize the NCNN model and JNI environment.
     */
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
     * Entry point for upscaling/enhancing an image.
     */
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
            Log.i(TAG, "Large image (${bitmap.width}x${bitmap.height}): Tiled Fusion Pipeline")
            processTiledFusionPipeline(bitmap)
        }
    }

    /**
     * Tiled fusion pipeline that increases resolution while preserving details.
     */
    private fun processTiledFusionPipeline(bitmap: Bitmap): Bitmap? {
        return try {
            val inW = bitmap.width
            val inH = bitmap.height
            val startTime = System.currentTimeMillis()

            // Step 1: Calculate output dimensions and scale factor
            // Target is 4x, but capped by MAX_OUTPUT_SIDE
            val scaleFactor = minOf(SCALE.toFloat(), MAX_OUTPUT_SIDE.toFloat() / maxOf(inW, inH))
            val outW = (inW * scaleFactor).toInt()
            val outH = (inH * scaleFactor).toInt()

            Log.i(TAG, "Fusion Pipeline Start: ${inW}x${inH} -> ${outW}x${outH} (Scale: $scaleFactor)")

            val original = ensureArgb(bitmap) ?: return null
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val step = PROCESS_TILE - PROCESS_OVERLAP
            val tilesX = (inW + step - 1) / step
            val tilesY = (inH + step - 1) / step
            val totalTiles = tilesX * tilesY

            var processed = 0
            var successCount = 0

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val srcLeft = (tx * step).coerceAtMost(inW - 1)
                    val srcTop = (ty * step).coerceAtMost(inH - 1)
                    val srcRight = (srcLeft + PROCESS_TILE).coerceAtMost(inW)
                    val srcBottom = (srcTop + PROCESS_TILE).coerceAtMost(inH)
                    
                    val tileW = srcRight - srcLeft
                    val tileH = srcBottom - srcTop
                    
                    if (tileW < 8 || tileH < 8) continue // Too small to process

                    // Step 3-1: Extract tile
                    val origTile = Bitmap.createBitmap(original, srcLeft, srcTop, tileW, tileH)

                    // Step 3-2: Process one tile through fusion
                    val fusedTile = processOneTileFusion(origTile)

                    // Step 3-3: Write back to canvas with appropriate scaling
                    val outLeft = (srcLeft * scaleFactor).toInt()
                    val outTop = (srcTop * scaleFactor).toInt()
                    val outRight = if (tx == tilesX - 1) outW else (srcRight * scaleFactor).toInt()
                    val outBottom = if (ty == tilesY - 1) outH else (srcBottom * scaleFactor).toInt()
                    
                    val targetW = outRight - outLeft
                    val targetH = outBottom - outTop

                    if (fusedTile != null) {
                        if (fusedTile.width == targetW && fusedTile.height == targetH) {
                            canvas.drawBitmap(fusedTile, outLeft.toFloat(), outTop.toFloat(), null)
                        } else {
                            val scaled = Bitmap.createScaledBitmap(fusedTile, targetW, targetH, true)
                            canvas.drawBitmap(scaled, outLeft.toFloat(), outTop.toFloat(), null)
                            scaled.recycle()
                        }
                        fusedTile.recycle()
                        successCount++
                    } else {
                        // Fallback: simple upscale of original tile
                        val fallback = Bitmap.createScaledBitmap(origTile, targetW, targetH, true)
                        canvas.drawBitmap(fallback, outLeft.toFloat(), outTop.toFloat(), null)
                        fallback.recycle()
                    }

                    origTile.recycle()
                    processed++
                    
                    if (processed % 10 == 0 || processed == totalTiles) {
                        Log.i(TAG, "Progress: $processed/$totalTiles")
                    }
                }
            }

            if (original !== bitmap) original.recycle()
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Fusion Pipeline Done: ${outW}x${outH}, $successCount/$totalTiles tiles, ${elapsed}ms")
            
            output
        } catch (e: Exception) {
            Log.e(TAG, "Fusion pipeline error: ${e.message}")
            null
        }
    }

    /**
     * Core logic for fusing AI output and original image details.
     */
    private fun processOneTileFusion(origTile: Bitmap): Bitmap? {
        var srInput: Bitmap? = null
        var srArgb: Bitmap? = null
        var aiResult: Bitmap? = null
        var origUpscaled: Bitmap? = null

        return try {
            val tileW = origTile.width
            val tileH = origTile.height

            // 1. Prepare AI input (should already be <= 128x128)
            srInput = limitSize(origTile, SR_MAX_INPUT)
            srArgb = ensureArgb(srInput) ?: return null

            // 2. AI 4x Super-Resolution
            aiResult = RealEsrganBridge.nativeProcess(srArgb) ?: return null

            // 3. Upscale original reference to match AI output size (e.g. 128x128 -> 512x512)
            val aiW = aiResult.width
            val aiH = aiResult.height
            val scaledRef = Bitmap.createScaledBitmap(origTile, aiW, aiH, true)
            origUpscaled = ensureArgb(scaledRef) ?: return null
            if (scaledRef !== origUpscaled) scaledRef.recycle()

            // 4. Detail Fusion (Guided+DWT+IBP)
            // originalBitmap: 512x512 (High Frequency Source)
            // aiEnhancedBitmap: 512x512 (Structure Source)
            // aiLowResBitmap: 128x128 (IBP Reference)
            val fused = RealEsrganBridge.nativeFusionPipeline(origUpscaled, aiResult, srArgb)
            
            fused
        } catch (e: Exception) {
            Log.e(TAG, "OneTile fusion error: ${e.message}")
            null
        } finally {
            // Clean up temporary bitmaps
            if (srArgb !== srInput) srArgb?.recycle()
            if (srInput !== origTile) srInput?.recycle()
            aiResult?.recycle()
            origUpscaled?.recycle()
        }
    }

    /**
     * Simple 4x Super Resolution for small images.
     */
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

    /**
     * Releases model resources manually.
     */
    fun release() {
        try {
            RealEsrganBridge.nativeRelease()
        } catch (e: Exception) {
            Log.e(TAG, "Release: ${e.message}")
        }
        initialized = false
    }

    /**
     * Ensures bitmap is in ARGB_8888 format for C++ processing.
     */
    private fun ensureArgb(bitmap: Bitmap): Bitmap? {
        return if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap
    }

    /**
     * Limits bitmap size to a maximum side while maintaining aspect ratio.
     */
    private fun limitSize(bitmap: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / max
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(8)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(8)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
