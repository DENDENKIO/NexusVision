package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * NCNN Real-ESRGAN 4× Super-Resolution with EASS tile selection
 * Phase 14 optimized: Fusion OFF, TILE_SIZE=128, EASS/FECS routing
 */
class NcnnSuperResolution {

    companion object {
        private const val TAG = "NcnnSR"
        private const val PARAM_FILE = "models/realesr-general-x4v3.param"
        private const val MODEL_FILE = "models/realesr-general-x4v3.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 128
        private const val SR_MAX_INPUT = 256
        private const val PROCESS_TILE = 256
        private const val PROCESS_OVERLAP = 16
        private const val MAX_OUTPUT_SIDE = 4096

        // ── EASS/FECS パラメータ ──
        // FECS スコアがこの値未満のタイルは平坦と判定し、AI をスキップ
        private const val FECS_THRESHOLD_LOW = 1.5f
        // エッジ画素の割合（Sobel 閾値超え）
        private const val EDGE_THRESHOLD = 30
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            // NOTE: Keep using AssetManager-based nativeInit to match existing JNI signature
            val success = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            if (success) {
                initialized = true
                Log.i(TAG, "Initialized NcnnSR (tile=$TILE_SIZE)")
            } else {
                Log.e(TAG, "nativeInit returned false")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized) return null
        val src = ensureArgb(bitmap)
        val w = src.width
        val h = src.height

        return if (w <= SR_MAX_INPUT && h <= SR_MAX_INPUT) {
            Log.i(TAG, "Direct SR: ${w}x${h} -> ${w * SCALE}x${h * SCALE}")
            processSuperResolution(src)
        } else {
            processTiledEass(src)
        }
    }

    // ════════════════════════════════════════
    //  EASS タイルパイプライン
    // ════════════════════════════════════════

    private fun processTiledEass(src: Bitmap): Bitmap? {
        val srcW = src.width
        val srcH = src.height

        // 出力サイズ計算（MAX_OUTPUT_SIDE 制限）
        val rawOutW = srcW * SCALE
        val rawOutH = srcH * SCALE
        val maxSide = max(rawOutW, rawOutH)
        val outScale = if (maxSide > MAX_OUTPUT_SIDE) {
            MAX_OUTPUT_SIDE.toFloat() / maxSide
        } else 1f
        val outW = (rawOutW * outScale).toInt()
        val outH = (rawOutH * outScale).toInt()
        val effectiveScale = outW.toFloat() / srcW

        Log.i(TAG, "EASS Pipeline: ${srcW}x${srcH} -> ${outW}x${outH} " +
                "(scale=${String.format("%.2f", effectiveScale)})")

        val step = PROCESS_TILE - PROCESS_OVERLAP
        val tilesX = ceil(srcW.toFloat() / step).toInt()
        val tilesY = ceil(srcH.toFloat() / step).toInt()
        val totalTiles = tilesX * tilesY

        Log.i(TAG, "Tiles: ${tilesX}x${tilesY} = $totalTiles " +
                "(tile=$PROCESS_TILE, step=$step, FECS_threshold=$FECS_THRESHOLD_LOW)")

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val startTime = System.currentTimeMillis()

        var successCount = 0
        var aiCount = 0
        var skipCount = 0

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val srcX = min(tx * step, srcW - 1)
                val srcY = min(ty * step, srcH - 1)
                val tileW = min(PROCESS_TILE, srcW - srcX)
                val tileH = min(PROCESS_TILE, srcH - srcY)

                if (tileW < 4 || tileH < 4) continue

                // タイル切り出し
                val tile = Bitmap.createBitmap(src, srcX, srcY, tileW, tileH)

                // ── FECS スコア計算 ──
                val fecs = calculateFecs(tile)

                // 出力領域
                val dstX = (srcX * effectiveScale).toInt()
                val dstY = (srcY * effectiveScale).toInt()
                val dstW = (tileW * effectiveScale).toInt().coerceAtLeast(1)
                val dstH = (tileH * effectiveScale).toInt().coerceAtLeast(1)
                val dstRect = Rect(dstX, dstY, dstX + dstW, dstY + dstH)

                val resultTile: Bitmap? = if (fecs < FECS_THRESHOLD_LOW) {
                    // ── Route A: 平坦タイル → バイキュービック ──
                    skipCount++
                    Bitmap.createScaledBitmap(tile, dstW, dstH, true)
                } else {
                    // ── Route C: 高情報タイル → AI (Real-ESRGAN) ──
                    aiCount++
                    processOneTileDirect(tile, dstW, dstH)
                }

                if (resultTile != null) {
                    canvas.drawBitmap(resultTile, null, dstRect, null)
                    successCount++
                    if (resultTile !== tile) resultTile.recycle()
                }
                tile.recycle()

                // 進捗ログ（5タイルごと）
                val done = ty * tilesX + tx + 1
                if (done % 5 == 0 || done == totalTiles) {
                    Log.i(TAG, "Progress: $done/$totalTiles " +
                            "(AI=$aiCount, skip=$skipCount)")
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "EASS Done: ${outW}x${outH}, " +
                "$successCount/$totalTiles tiles, " +
                "AI=$aiCount, skip=$skipCount (${skipPercent(skipCount, totalTiles)}%), " +
                "${elapsed}ms")

        return output
    }

    // ════════════════════════════════════════
    //  FECS スコア計算
    //  FECS = H × (E_mid + 2 × E_high) / (1 + E_low)
    // ════════════════════════════════════════

    private fun calculateFecs(tile: Bitmap): Float {
        val w = tile.width
        val h = tile.height
        val pixels = IntArray(w * h)
        tile.getPixels(pixels, 0, w, 0, 0, w, h)

        // グレースケール輝度
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
        }

        // ── シャノンエントロピー H ──
        val hist = IntArray(256)
        for (v in gray) hist[v]++
        val total = gray.size.toFloat()
        var entropy = 0f
        for (count in hist) {
            if (count > 0) {
                val p = count / total
                entropy -= p * (ln(p.toDouble()) / ln(2.0)).toFloat()
            }
        }

        // ── Sobel エッジ強度分布 ──
        var eLow = 0
        var eMid = 0
        var eHigh = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = -gray[(y - 1) * w + (x - 1)] +
                        gray[(y - 1) * w + (x + 1)] -
                        2 * gray[y * w + (x - 1)] +
                        2 * gray[y * w + (x + 1)] -
                        gray[(y + 1) * w + (x - 1)] +
                        gray[(y + 1) * w + (x + 1)]
                val gy = -gray[(y - 1) * w + (x - 1)] -
                        2 * gray[(y - 1) * w + x] -
                        gray[(y - 1) * w + (x + 1)] +
                        gray[(y + 1) * w + (x - 1)] +
                        2 * gray[(y + 1) * w + x] +
                        gray[(y + 1) * w + (x + 1)]
                val mag = kotlin.math.sqrt((gx * gx + gy * gy).toFloat()).toInt()

                when {
                    mag < EDGE_THRESHOLD -> eLow++
                    mag < EDGE_THRESHOLD * 3 -> eMid++
                    else -> eHigh++
                }
            }
        }

        // FECS = H × (E_mid + 2 × E_high) / (1 + E_low)
        val fecs = entropy * (eMid + 2f * eHigh) / (1f + eLow)
        return fecs
    }

    // ════════════════════════════════════════
    //  タイル処理（AI）
    // ════════════════════════════════════════

    private fun processOneTileDirect(
        tile: Bitmap, targetW: Int, targetH: Int
    ): Bitmap? {
        val input = limitSize(ensureArgb(tile))
        val aiResult = RealEsrganBridge.nativeProcess(input) ?: return null
        // ターゲットサイズにリサイズ
        return if (aiResult.width != targetW || aiResult.height != targetH) {
            val scaled = Bitmap.createScaledBitmap(aiResult, targetW, targetH, true)
            aiResult.recycle()
            scaled
        } else {
            aiResult
        }
    }

    // ════════════════════════════════════════
    //  小画像用ダイレクト処理
    // ════════════════════════════════════════

    private fun processSuperResolution(bitmap: Bitmap): Bitmap? {
        val input = limitSize(ensureArgb(bitmap))
        return RealEsrganBridge.nativeProcess(input)
    }

    // ════════════════════════════════════════
    //  ユーティリティ
    // ════════════════════════════════════════

    private fun ensureArgb(bitmap: Bitmap): Bitmap {
        return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun limitSize(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxSide = max(w, h)
        if (maxSide <= SR_MAX_INPUT) return bitmap
        val scale = SR_MAX_INPUT.toFloat() / maxSide
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun skipPercent(skip: Int, total: Int): Int {
        return if (total > 0) (skip * 100 / total) else 0
    }

    fun release() {
        if (initialized) {
            RealEsrganBridge.nativeRelease()
            initialized = false
            Log.i(TAG, "Released NcnnSR")
        }
    }
}
