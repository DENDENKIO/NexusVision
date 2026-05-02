package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import com.nexus.vision.ncnn.RealEsrganBridge
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * NCNN Real-ESRGAN 4× Super-Resolution with EASS 3-Route tile selection
 * Route A: 平坦タイル → バイキュービック（FECS < 1.5）
 * Route B: 文字/エッジ密集タイル → AI + バイキュービック ブレンド（高エッジ密度）
 * Route C: 通常タイル → AI のみ
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
        private const val FECS_THRESHOLD_LOW = 1.5f
        private const val EDGE_THRESHOLD = 30

        // ── Route B パラメータ ──
        // エッジ密度がこの値以上のタイルを「文字/エッジ密集」と判定
        private const val EDGE_DENSITY_THRESHOLD = 0.15f
        // Route B でのバイキュービックのブレンド比率（0.0=AI100%, 1.0=バイキュービック100%）
        private const val TEXT_BLEND_RATIO = 0.35f

        // ── NLM デノイズパラメータ ──
        private const val NOISE_ENTROPY_THRESHOLD = 6.5f
        private const val NLM_STRENGTH = 15.0f
        private const val NLM_PATCH_SIZE = 3
        private const val NLM_SEARCH_SIZE = 7
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized) return true
        return try {
            val assetManager = context.assets
            val paramData = assetManager.open(PARAM_FILE).use { it.readBytes() }
            val modelData = assetManager.open(MODEL_FILE).use { it.readBytes() }
            val success = RealEsrganBridge.nativeInit(
                paramData, modelData, SCALE, TILE_SIZE
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
    //  EASS 3-Route タイルパイプライン
    // ════════════════════════════════════════

    private fun processTiledEass(src: Bitmap): Bitmap? {
        val srcW = src.width
        val srcH = src.height

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
                "(scale=${"%.2f".format(effectiveScale)})")

        val step = PROCESS_TILE - PROCESS_OVERLAP
        val tilesX = ceil(srcW.toFloat() / step).toInt()
        val tilesY = ceil(srcH.toFloat() / step).toInt()
        val totalTiles = tilesX * tilesY

        Log.i(TAG, "Tiles: ${tilesX}x${tilesY} = $totalTiles " +
                "(tile=$PROCESS_TILE, step=$step)")

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val startTime = System.currentTimeMillis()

        var successCount = 0
        var aiCount = 0
        var blendCount = 0
        var skipCount = 0
        var denoiseCount = 0

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val srcX = min(tx * step, srcW - 1)
                val srcY = min(ty * step, srcH - 1)
                val tileW = min(PROCESS_TILE, srcW - srcX)
                val tileH = min(PROCESS_TILE, srcH - srcY)

                if (tileW < 4 || tileH < 4) continue

                val tile = Bitmap.createBitmap(src, srcX, srcY, tileW, tileH)

                // ── FECS スコア + エッジ密度計算 ──
                val tileAnalysis = analyzeTile(tile)
                val fecs = tileAnalysis.fecs
                val edgeDensity = tileAnalysis.edgeDensity

                val dstX = (srcX * effectiveScale).toInt()
                val dstY = (srcY * effectiveScale).toInt()
                val dstW = (tileW * effectiveScale).toInt().coerceAtLeast(1)
                val dstH = (tileH * effectiveScale).toInt().coerceAtLeast(1)
                val dstRect = Rect(dstX, dstY, dstX + dstW, dstY + dstH)

                val resultTile: Bitmap? = if (fecs < FECS_THRESHOLD_LOW) {
                    // ── Route A: 平坦タイル → バイキュービック ──
                    skipCount++
                    Bitmap.createScaledBitmap(tile, dstW, dstH, true)
                } else if (edgeDensity >= EDGE_DENSITY_THRESHOLD) {
                    // ── Route B: 文字/エッジ密集 → AI + バイキュービック ブレンド ──
                    blendCount++
                    aiCount++

                    val tileEntropy = tileAnalysis.entropy
                    val inputTile = if (tileEntropy > NOISE_ENTROPY_THRESHOLD) {
                        denoiseCount++
                        val denoised = RealEsrganBridge.nativeNlmDenoise(
                            ensureArgb(tile), NLM_STRENGTH, NLM_PATCH_SIZE, NLM_SEARCH_SIZE
                        )
                        denoised ?: tile
                    } else {
                        tile
                    }

                    val aiResult = processOneTileDirect(inputTile, dstW, dstH)
                    val bicubicResult = Bitmap.createScaledBitmap(tile, dstW, dstH, true)

                    if (aiResult != null) {
                        val blended = blendBitmaps(aiResult, bicubicResult, TEXT_BLEND_RATIO)
                        aiResult.recycle()
                        bicubicResult.recycle()
                        blended
                    } else {
                        bicubicResult
                    }
                } else {
                    // ── Route C: 通常タイル → AI のみ ──
                    aiCount++

                    val tileEntropy = tileAnalysis.entropy
                    val inputTile = if (tileEntropy > NOISE_ENTROPY_THRESHOLD) {
                        denoiseCount++
                        val denoised = RealEsrganBridge.nativeNlmDenoise(
                            ensureArgb(tile), NLM_STRENGTH, NLM_PATCH_SIZE, NLM_SEARCH_SIZE
                        )
                        denoised ?: tile
                    } else {
                        tile
                    }
                    processOneTileDirect(inputTile, dstW, dstH)
                }

                if (resultTile != null) {
                    canvas.drawBitmap(resultTile, null, dstRect, null)
                    successCount++
                    if (resultTile !== tile) resultTile.recycle()
                }
                tile.recycle()

                val done = ty * tilesX + tx + 1
                if (done % 5 == 0 || done == totalTiles) {
                    Log.i(TAG, "Progress: $done/$totalTiles " +
                            "(AI=$aiCount, blend=$blendCount, skip=$skipCount)")
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "EASS Done: ${outW}x${outH}, " +
                "$successCount/$totalTiles tiles, " +
                "AI=$aiCount (blend=$blendCount, denoise=$denoiseCount), " +
                "skip=$skipCount (${skipPercent(skipCount, totalTiles)}%), " +
                "${elapsed}ms")

        return output
    }

    // ════════════════════════════════════════
    //  タイル分析（FECS + エッジ密度）
    // ════════════════════════════════════════

    data class TileAnalysis(
        val fecs: Float,
        val edgeDensity: Float,
        val entropy: Float
    )

    private fun analyzeTile(tile: Bitmap): TileAnalysis {
        val w = tile.width
        val h = tile.height
        val pixels = IntArray(w * h)
        tile.getPixels(pixels, 0, w, 0, 0, w, h)

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

        // ── Sobel エッジ強度分布 + エッジ密度 ──
        var eLow = 0
        var eMid = 0
        var eHigh = 0
        var edgePixels = 0
        val innerPixels = (w - 2) * (h - 2)

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
                val mag = sqrt((gx * gx + gy * gy).toFloat()).toInt()

                when {
                    mag < EDGE_THRESHOLD -> eLow++
                    mag < EDGE_THRESHOLD * 3 -> eMid++
                    else -> eHigh++
                }

                // 文字検出用：中〜高エッジをカウント
                if (mag >= EDGE_THRESHOLD) {
                    edgePixels++
                }
            }
        }

        val fecs = entropy * (eMid + 2f * eHigh) / (1f + eLow)
        val edgeDensity = if (innerPixels > 0) edgePixels.toFloat() / innerPixels else 0f

        return TileAnalysis(fecs, edgeDensity, entropy)
    }

    // ════════════════════════════════════════
    //  ビットマップブレンド（Route B）
    // ════════════════════════════════════════

    private fun blendBitmaps(ai: Bitmap, bicubic: Bitmap, bicubicRatio: Float): Bitmap {
        val w = ai.width
        val h = ai.height
        val aiRatio = 1f - bicubicRatio

        val aiPixels = IntArray(w * h)
        val bicPixels = IntArray(w * h)
        ai.getPixels(aiPixels, 0, w, 0, 0, w, h)
        bicubic.getPixels(bicPixels, 0, w, 0, 0, w, h)

        val outPixels = IntArray(w * h)

        for (i in 0 until w * h) {
            val ap = aiPixels[i]
            val bp = bicPixels[i]

            val aR = (ap shr 16) and 0xFF
            val aG = (ap shr 8) and 0xFF
            val aB = ap and 0xFF

            val bR = (bp shr 16) and 0xFF
            val bG = (bp shr 8) and 0xFF
            val bB = bp and 0xFF

            val oR = (aR * aiRatio + bR * bicubicRatio).toInt().coerceIn(0, 255)
            val oG = (aG * aiRatio + bG * bicubicRatio).toInt().coerceIn(0, 255)
            val oB = (aB * aiRatio + bB * bicubicRatio).toInt().coerceIn(0, 255)

            outPixels[i] = (0xFF shl 24) or (oR shl 16) or (oG shl 8) or oB
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    // ════════════════════════════════════════
    //  タイル処理（AI）
    // ════════════════════════════════════════

    private fun processOneTileDirect(
        tile: Bitmap, targetW: Int, targetH: Int
    ): Bitmap? {
        val input = limitSize(ensureArgb(tile))
        val aiResult = RealEsrganBridge.nativeProcess(input) ?: return null
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
