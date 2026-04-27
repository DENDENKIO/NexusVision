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

        private const val SR_MAX_INPUT = 512
        private const val DETAIL_STRENGTH = 1.2f
        private const val SHARPEN_STRENGTH = 0.4f

        // 処理タイルサイズ（元画像から切り出す1タイルの辺）
        // 512px のタイルなら AI処理可能、かつメモリ安全
        private const val PROCESS_TILE = 512
        // タイル重なり（継ぎ目防止）
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
     * 超解像パイプライン
     * 小画像(≤512px): 4×拡大
     * 大画像(>512px): タイル方式ラプラシアン合成（出力＝入力と同サイズ）
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
            Log.i(TAG, "Large image: tiled Laplacian SR")
            processTiledLaplacianSR(bitmap)
        }
    }

    /**
     * タイル方式ラプラシアン合成
     *
     * 入力Bitmap全体を PROCESS_TILE サイズのタイルに分割し、
     * 各タイルごとに:
     *   1. タイルを切り出し（元解像度のピクセル＝鮮明な高周波ソース）
     *   2. タイルを縮小 → AI超解像 → 元タイルサイズにアップスケール
     *   3. ラプラシアン合成（元タイルの高周波 + AIの低周波）
     *   4. 出力キャンバスに書き込み
     *
     * 出力サイズ ＝ 入力サイズ（縮小しない）
     * 1タイルずつ処理するのでメモリは常に安全
     */
    private fun processTiledLaplacianSR(bitmap: Bitmap): Bitmap? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val startTime = System.currentTimeMillis()

            val original = ensureArgb(bitmap) ?: return null

            // 出力キャンバス（入力と同サイズ）
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val step = PROCESS_TILE - PROCESS_OVERLAP
            val tilesX = (w + step - 1) / step
            val tilesY = (h + step - 1) / step
            val totalTiles = tilesX * tilesY

            Log.i(TAG, "Tiled Laplacian: ${w}x${h}, tiles=${tilesX}x${tilesY}=$totalTiles, tileSize=$PROCESS_TILE, overlap=$PROCESS_OVERLAP")

            var processed = 0
            var aiSuccessCount = 0

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val srcLeft = (tx * step).coerceAtMost(w - 1)
                    val srcTop = (ty * step).coerceAtMost(h - 1)
                    val srcRight = (srcLeft + PROCESS_TILE).coerceAtMost(w)
                    val srcBottom = (srcTop + PROCESS_TILE).coerceAtMost(h)
                    val tileW = srcRight - srcLeft
                    val tileH = srcBottom - srcTop

                    if (tileW < 16 || tileH < 16) continue

                    // 元解像度タイルを切り出し
                    val origTile = Bitmap.createBitmap(original, srcLeft, srcTop, tileW, tileH)

                    // このタイルを処理
                    val processedTile = processOneTile(origTile)

                    if (processedTile != null) {
                        canvas.drawBitmap(processedTile, null,
                            Rect(srcLeft, srcTop, srcRight, srcBottom), null)
                        processedTile.recycle()
                        aiSuccessCount++
                    } else {
                        // 失敗時は元タイルをそのまま書き込み
                        canvas.drawBitmap(origTile, null,
                            Rect(srcLeft, srcTop, srcRight, srcBottom), null)
                    }
                    origTile.recycle()

                    processed++
                    if (processed % 4 == 0 || processed == totalTiles) {
                        Log.i(TAG, "Tiled progress: $processed/$totalTiles (${(processed * 100.0 / totalTiles).toInt()}%)")
                    }
                }
            }

            if (original !== bitmap) original.recycle()

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Tiled Laplacian done: ${w}x${h}, ${totalTiles} tiles, AI success=$aiSuccessCount, ${elapsed}ms")
            output
        } catch (e: Exception) {
            Log.e(TAG, "Tiled Laplacian error: ${e.message}")
            null
        }
    }

    /**
     * 1タイルの処理:
     * 1. タイル(例512×512)をSR_MAX_INPUT以下に縮小
     * 2. AI 4×超解像
     * 3. AI結果をタイルサイズにアップスケール
     * 4. ラプラシアン合成
     */
    private fun processOneTile(origTile: Bitmap): Bitmap? {
        return try {
            val tileW = origTile.width
            val tileH = origTile.height

            // 縮小 → AI超解像
            val srInput = limitSize(origTile, SR_MAX_INPUT)
            val srArgb = ensureArgb(srInput) ?: return null

            val aiResult = RealEsrganBridge.nativeProcess(srArgb)
            if (srArgb !== srInput) srArgb.recycle()
            if (srInput !== origTile) srInput.recycle()

            if (aiResult == null) return null

            // AI結果を元タイルサイズにアップスケール
            val aiUpscaled = if (aiResult.width != tileW || aiResult.height != tileH) {
                val scaled = Bitmap.createScaledBitmap(aiResult, tileW, tileH, true)
                aiResult.recycle()
                scaled
            } else {
                aiResult
            }

            val aiArgb = ensureArgb(aiUpscaled) ?: return null
            if (aiUpscaled !== aiArgb) aiUpscaled.recycle()

            // ラプラシアン合成
            val blended = RealEsrganBridge.nativeLaplacianBlend(
                origTile, aiArgb, DETAIL_STRENGTH, SHARPEN_STRENGTH
            )
            aiArgb.recycle()

            blended
        } catch (e: Exception) {
            Log.e(TAG, "Tile process error: ${e.message}")
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
