// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"
        private const val PARAM_FILE = "models/realesr-animevideov3-x4.param"
        private const val MODEL_FILE = "models/realesr-animevideov3-x4.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 128  // 小さめタイルでメモリ節約

        // 出力の長辺がこの値を超えないよう入力を制限
        private const val MAX_OUTPUT_SIDE = 4096
        private const val MAX_INPUT_SIDE = MAX_OUTPUT_SIDE / SCALE  // 1024
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            val result = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "Model initialized successfully")
            else Log.e(TAG, "Model initialization failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    /**
     * 画像全体を超解像する（高画質化モード）
     * 出力は最大 MAX_OUTPUT_SIDE px（長辺4096px）
     */
    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        return try {
            val input = limitSize(bitmap)
            Log.i(TAG, "Upscale input: ${input.width}x${input.height} -> expected output: ${input.width * SCALE}x${input.height * SCALE}")
            val startTime = System.currentTimeMillis()

            val argbInput = if (input.config != Bitmap.Config.ARGB_8888) {
                input.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                input
            }

            val result = RealEsrganBridge.nativeProcess(argbInput)
            val elapsed = System.currentTimeMillis() - startTime

            if (result != null) {
                Log.i(TAG, "Upscale done: ${result.width}x${result.height} in ${elapsed}ms")
            } else {
                Log.e(TAG, "nativeProcess returned null after ${elapsed}ms")
            }

            // 中間ビットマップの解放
            if (argbInput !== input && argbInput !== bitmap) argbInput.recycle()
            if (input !== bitmap) input.recycle()

            result
        } catch (e: Exception) {
            Log.e(TAG, "Upscale error: ${e.message}")
            null
        }
    }

    /**
     * デジタルズーム：指定領域(ROI)だけを切り出して超解像する
     * centerX, centerY: 元画像上の中心座標（0.0〜1.0の比率）
     * zoomFactor: 何倍ズームか（例: 2.0, 3.0, 4.0）
     * 出力は切り出し領域を4×超解像した結果
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
            // ROIのサイズを計算（ズーム倍率の逆数が切り出し範囲）
            val roiWidth = (bitmap.width / zoomFactor).toInt().coerceIn(64, bitmap.width)
            val roiHeight = (bitmap.height / zoomFactor).toInt().coerceIn(64, bitmap.height)

            // 中心座標からROIの左上を計算
            var left = ((centerX * bitmap.width) - roiWidth / 2).toInt()
            var top = ((centerY * bitmap.height) - roiHeight / 2).toInt()
            left = left.coerceIn(0, bitmap.width - roiWidth)
            top = top.coerceIn(0, bitmap.height - roiHeight)

            Log.i(TAG, "Digital zoom: ROI=${left},${top} ${roiWidth}x${roiHeight} from ${bitmap.width}x${bitmap.height}, zoom=${zoomFactor}x")

            // ROIを切り出し
            val roi = Bitmap.createBitmap(bitmap, left, top, roiWidth, roiHeight)

            // ROIをさらにサイズ制限してから超解像
            val limitedRoi = limitSize(roi)
            if (limitedRoi !== roi) roi.recycle()

            Log.i(TAG, "Digital zoom input: ${limitedRoi.width}x${limitedRoi.height} -> expected: ${limitedRoi.width * SCALE}x${limitedRoi.height * SCALE}")
            val startTime = System.currentTimeMillis()

            val argbInput = if (limitedRoi.config != Bitmap.Config.ARGB_8888) {
                limitedRoi.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                limitedRoi
            }

            val result = RealEsrganBridge.nativeProcess(argbInput)
            val elapsed = System.currentTimeMillis() - startTime

            if (result != null) {
                Log.i(TAG, "Digital zoom done: ${result.width}x${result.height} in ${elapsed}ms")
            } else {
                Log.e(TAG, "Digital zoom nativeProcess returned null after ${elapsed}ms")
            }

            if (argbInput !== limitedRoi && argbInput !== bitmap) argbInput.recycle()
            if (limitedRoi !== bitmap) limitedRoi.recycle()

            result
        } catch (e: Exception) {
            Log.e(TAG, "Digital zoom error: ${e.message}")
            null
        }
    }

    fun release() {
        try {
            RealEsrganBridge.nativeRelease()
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}")
        }
        initialized = false
    }

    /**
     * 入力の長辺を MAX_INPUT_SIDE (1024px) に制限
     * 4×超解像後の出力が MAX_OUTPUT_SIDE (4096px) を超えないようにする
     */
    private fun limitSize(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= MAX_INPUT_SIDE) return bitmap

        val ratio = MAX_INPUT_SIDE.toFloat() / maxSide
        val newW = (bitmap.width * ratio).toInt()
        val newH = (bitmap.height * ratio).toInt()
        Log.i(TAG, "Limiting input: ${bitmap.width}x${bitmap.height} -> ${newW}x${newH}")
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
