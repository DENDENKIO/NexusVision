package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        
        // --- Pipeline Tuning ---
        private const val SR_MAX_INPUT = 128        // Max side for AI input (128 -> AI Output 512)
        private const val PROCESS_TILE = 128         // Tile size for processing
        private const val PROCESS_OVERLAP = 16       // Overlap to prevent seams
        private const val MAX_OUTPUT_SIDE = 4096     // Max side for output bitmap (memory limit)
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
    suspend fun upscale(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return@withContext null
        }

        val w = bitmap.width
        val h = bitmap.height
        val maxSide = maxOf(w, h)

        return@withContext if (maxSide <= SR_MAX_INPUT) {
            // Very small image: direct 4x SR
            Log.i(TAG, "Small image: direct 4x SR (${w}x${h})")
            processSuperResolution(bitmap)
        } else {
            // Larger image: use tiled fusion pipeline for detail preservation
            Log.i(TAG, "Large image: Tiled Fusion Pipeline (${w}x${h})")
            processTiledFusionPipeline(bitmap)
        }
    }

    /**
     * Tiled processing pipeline with detail fusion.
     * Upscales images up to 4x, capped at MAX_OUTPUT_SIDE.
     */
    private fun processTiledFusionPipeline(bitmap: Bitmap): Bitmap? {
        return try {
            val inW = bitmap.width
            val inH = bitmap.height
            val startTime = System.currentTimeMillis()

            // Calculate output scale factor (target 4x, but limited by MAX_OUTPUT_SIDE)
            val scaleFactor = minOf(SCALE.toFloat(), MAX_OUTPUT_SIDE.toFloat() / maxOf(inW, inH))
            val outW = (inW * scaleFactor).toInt()
            val outH = (inH * scaleFactor).toInt()

            Log.i(TAG, "Pipeline Start: ${inW}x${inH} -> ${outW}x${outH} (Scale: ${String.format("%.2f", scaleFactor)})")

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

                    // 1. Extract tile from original
                    val origTile = Bitmap.createBitmap(original, srcLeft, srcTop, tileW, tileH)
                    
                    // 2. Process tile through fusion pipeline
                    val fusedTile = processOneTileFusion(origTile)
                    
                    // 3. Determine output destination in the canvas
                    val outLeft = (srcLeft * scaleFactor).toInt()
                    val outTop = (srcTop * scaleFactor).toInt()
                    val outRight = if (tx == tilesX - 1) outW else (srcRight * scaleFactor).toInt()
                    val outBottom = if (ty == tilesY - 1) outH else (srcBottom * scaleFactor).toInt()
                    
                    val targetW = outRight - outLeft
                    val targetH = outBottom - outTop

                    if (fusedTile != null) {
                        // Draw fused result (might need resizing if scaleFactor < 4)
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
                        // Fallback: draw original tile upscaled
                        val fallback = Bitmap.createScaledBitmap(origTile, targetW, targetH, true)
                        canvas.drawBitmap(fallback, outLeft.toFloat(), outTop.toFloat(), null)
                        fallback.recycle()
                        Log.w(TAG, "Tile @($tx,$ty) failed fusion, using fallback")
                    }

                    origTile.recycle()
                    processed++
                    
                    if (processed % 10 == 0 || processed == totalTiles) {
                        Log.i(TAG, "Progress: $processed / $totalTiles")
                    }
                }
            }

            if (original !== bitmap) original.recycle()
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Pipeline Done: ${outW}x${outH}, $successCount/$totalTiles tiles successful, ${elapsed}ms")
            
            output
        } catch (e: Exception) {
            Log.e(TAG, "Tiled fusion error: ${e.message}", e)
            null
        }
    }

    /**
     * Processes a single tile through the AI-Fusion core.
     * [origTile] (128x128) -> AI (512x512) + Original Upscaled (512x512) -> IBP/Fusion (512x512)
     */
    private fun processOneTileFusion(origTile: Bitmap): Bitmap? {
        var srInput: Bitmap? = null
        var srArgb: Bitmap? = null
        var aiResult: Bitmap? = null
        var origUpscaled: Bitmap? = null

        return try {
            // 1. Prepare AI input (max 128x128)
            srInput = limitSize(origTile, SR_MAX_INPUT)
            srArgb = ensureArgb(srInput)

            // 2. Perform 4x AI Super-Resolution (Output is always 4x larger, e.g., 512x512)
            aiResult = RealEsrganBridge.nativeProcess(srArgb!!) ?: return null

            // 3. Prepare high-frequency source (upscale original tile to match AI output size)
            val aiW = aiResult.width
            val aiH = aiResult.height
            
            // Scaled original serves as the "guide" and "high-frequency" carrier
            origUpscaled = Bitmap.createScaledBitmap(origTile, aiW, aiH, true).let { 
                ensureArgb(it) 
            }

            // 4. Perform Detail Fusion Pipeline (Guided Filter + DWT + IBP)
            // originalBitmap: source of high-frequency details (512x512)
            // aiEnhancedBitmap: source of low-frequency structure (512x512)
            // aiLowResBitmap: original resolution reference for IBP (128x128)
            val fused = RealEsrganBridge.nativeFusionPipeline(origUpscaled!!, aiResult, srArgb)
            
            fused
        } catch (e: Exception) {
            Log.e(TAG, "OneTile error: ${e.message}")
            null
        } finally {
            // Cleanup
            if (srArgb !== srInput) srArgb?.recycle()
            if (srInput !== origTile) srInput?.recycle()
            aiResult?.recycle()
            origUpscaled?.recycle()
        }
    }

    /**
     * Direct 4x Super Resolution for small images.
     */
    private fun processSuperResolution(bitmap: Bitmap): Bitmap? {
        return try {
            val argb = ensureArgb(bitmap) ?: return null
            Log.d(TAG, "SR Start: ${argb.width}x${argb.height}")
            val result = RealEsrganBridge.nativeProcess(argb)
            if (argb !== bitmap) argb.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Direct SR error: ${e.message}")
            null
        }
    }

    /**
     * Releases model resources.
     */
    fun release() {
        try {
            RealEsrganBridge.nativeRelease()
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}")
        }
        initialized = false
    }

    /**
     * Ensures bitmap is in ARGB_8888 format for JNI consumption.
     */
    private fun ensureArgb(bitmap: Bitmap): Bitmap? {
        return try {
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else bitmap
        } catch (e: Exception) {
            Log.e(TAG, "ensureArgb failed: ${e.message}")
            null
        }
    }

    /**
     * Resizes bitmap if its side exceeds [maxSide].
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
