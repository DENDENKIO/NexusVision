// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"

        // 案A: 実写写真向け高品質モデルに変更
        private const val PARAM_FILE = "models/realesrgan-x4plus.param"
        private const val MODEL_FILE = "models/realesrgan-x4plus.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 64  // x4plusはモデルが大きいためタイルを小さく

        // 戦略の閾値
        private const val SR_4X_MAX_INPUT = 2048
        private const val SR_2X_MAX_INPUT = 4096
    }

    enum class Strategy {
        FULL_4X,
        DOWNSCALE_THEN_4X,
        ALREADY_HIGH_RES
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            val result = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "x4plus model initialized successfully")
            else Log.e(TAG, "x4plus model initialization failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    private fun determineStrategy(width: Int, height: Int): Strategy {
        val maxSide = maxOf(width, height)
        return when {
            maxSide <= SR_4X_MAX_INPUT -> Strategy.FULL_4X
            maxSide <= SR_2X_MAX_INPUT -> Strategy.DOWNSCALE_THEN_4X
            else -> Strategy.ALREADY_HIGH_RES
        }
    }

    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }

        val strategy = determineStrategy(bitmap.width, bitmap.height)
        Log.i(TAG, "Input: ${bitmap.width}x${bitmap.height}, strategy: $strategy")

        return when (strategy) {
            Strategy.ALREADY_HIGH_RES -> {
                Log.i(TAG, "Image already high-res (${bitmap.width}x${bitmap.height}), skipping SR")
                null
            }
            Strategy.FULL_4X -> {
                processWithNcnn(bitmap)
            }
            Strategy.DOWNSCALE_THEN_4X -> {
                val maxSide = maxOf(bitmap.width, bitmap.height)
                val ratio = SR_4X_MAX_INPUT.toFloat() / maxSide
                val newW = (bitmap.width * ratio).toInt()
                val newH = (bitmap.height * ratio).toInt()
                Log.i(TAG, "Downscaling: ${bitmap.width}x${bitmap.height} -> ${newW}x${newH} before 4x SR")
                val downscaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                val result = processWithNcnn(downscaled)
                if (downscaled !== bitmap) downscaled.recycle()
                result
            }
        }
    }

    private fun processWithNcnn(bitmap: Bitmap): Bitmap? {
        return try {
            val argbInput = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            Log.i(TAG, "Processing: ${argbInput.width}x${argbInput.height} -> expected ${argbInput.width * SCALE}x${argbInput.height * SCALE}")
            val startTime = System.currentTimeMillis()
            val result = RealEsrganBridge.nativeProcess(argbInput)
            val elapsed = System.currentTimeMillis() - startTime
            if (result != null) {
                Log.i(TAG, "Done: ${result.width}x${result.height} in ${elapsed}ms")
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
            Log.i(TAG, "Digital zoom: ROI=${left},${top} ${roiWidth}x${roiHeight} from ${bitmap.width}x${bitmap.height}")
            val roi = Bitmap.createBitmap(bitmap, left, top, roiWidth, roiHeight)
            val strategy = determineStrategy(roi.width, roi.height)
            val result = when (strategy) {
                Strategy.ALREADY_HIGH_RES, Strategy.DOWNSCALE_THEN_4X -> {
                    val maxSide = maxOf(roi.width, roi.height)
                    val ratio = SR_4X_MAX_INPUT.toFloat() / maxSide
                    val downscaled = Bitmap.createScaledBitmap(
                        roi, (roi.width * ratio).toInt(), (roi.height * ratio).toInt(), true
                    )
                    val sr = processWithNcnn(downscaled)
                    downscaled.recycle()
                    sr
                }
                Strategy.FULL_4X -> processWithNcnn(roi)
            }
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
