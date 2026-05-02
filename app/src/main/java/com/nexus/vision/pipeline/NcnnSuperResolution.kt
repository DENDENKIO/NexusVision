package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import com.nexus.vision.ncnn.RealEsrganBridge
import com.nexus.vision.ocr.MlKitOcrEngine
import com.nexus.vision.ocr.OcrResult
import kotlinx.coroutines.runBlocking
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * NCNN Real-ESRGAN 4× Super-Resolution with EASS 4-Route tile selection
 *
 * Route A: 平坦タイル → バイキュービック（FECS < 1.5）
 * Route B: 文字/エッジ密集タイル → AI + バイキュービック ブレンド（高エッジ密度）
 * Route T: テキスト検出タイル → バイキュービック + シャープ + ラプラシアンブレンド（文字保存）
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
        private const val EDGE_DENSITY_THRESHOLD = 0.15f
        private const val TEXT_BLEND_RATIO = 0.35f

        // ── Route T パラメータ ──
        // テキストタイルでの AI 出力とバイキュービック＋シャープの合成比率
        // AI の寄与を下げて文字保存を優先（AI 40%, テキスト保存 60%）
        private const val TEXT_PRESERVE_RATIO = 0.60f
        private const val TEXT_SHARPEN_STRENGTH = 0.6f
        private const val TEXT_DETAIL_STRENGTH = 1.2f
        private const val TEXT_SHARPEN_BLEND = 0.4f

        // ── NLM デノイズパラメータ ──
        private const val NOISE_ENTROPY_THRESHOLD = 6.5f
        private const val NLM_STRENGTH = 15.0f
        private const val NLM_PATCH_SIZE = 3
        private const val NLM_SEARCH_SIZE = 7
    }

    private var initialized = false
    private var ocrEngine: MlKitOcrEngine? = null

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
                ocrEngine = MlKitOcrEngine()
                Log.i(TAG, "Initialized NcnnSR (tile=$TILE_SIZE, textDetect=ON)")
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
    //  テキスト領域検出（ML Kit OCR）
    // ════════════════════════════════════════

    /**
     * 入力画像全体から文字領域の Rect リストを取得する。
     * タイル座標との交差判定に使用。
     */
    private fun detectTextRegions(bitmap: Bitmap): List<Rect> {
        val engine = ocrEngine ?: return emptyList()
        return try {
            val result = runBlocking {
                engine.recognize(bitmap)
            }
            val rects = mutableListOf<Rect>()
            for (block in result.blocks) {
                for (line in block.lines) {
                    line.boundingBox?.let { rects.add(it) }
                }
            }
            Log.i(TAG, "TextDetect: ${rects.size} text lines found")
            rects
        } catch (e: Exception) {
            Log.w(TAG, "TextDetect failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * タイル矩形がテキスト領域と重なっているか判定
     */
    private fun isTextTile(tileRect: Rect, textRects: List<Rect>): Boolean {
        for (textRect in textRects) {
            if (Rect.intersects(tileRect, textRect)) {
                // テキスト領域がタイルの面積の 5% 以上を占めるか確認
                val intersection = Rect()
                intersection.setIntersect(tileRect, textRect)
                val intersectArea = intersection.width() * intersection.height()
                val tileArea = tileRect.width() * tileRect.height()
                if (tileArea > 0 && intersectArea.toFloat() / tileArea > 0.05f) {
                    return true
                }
            }
        }
        return false
    }

    // ════════════════════════════════════════
    //  EASS 4-Route タイルパイプライン
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

        // ── テキスト領域を事前検出 ──
        val textDetectStart = System.currentTimeMillis()
        val textRects = detectTextRegions(src)
        val textDetectMs = System.currentTimeMillis() - textDetectStart
        Log.i(TAG, "TextDetect: ${textRects.size} regions in ${textDetectMs}ms")

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
        var textCount = 0
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
                val tileRect = Rect(srcX, srcY, srcX + tileW, srcY + tileH)

                val tileAnalysis = analyzeTile(tile)
                val fecs = tileAnalysis.fecs
                val edgeDensity = tileAnalysis.edgeDensity

                val dstX = (srcX * effectiveScale).toInt()
                val dstY = (srcY * effectiveScale).toInt()
                val dstW = (tileW * effectiveScale).toInt().coerceAtLeast(1)
                val dstH = (tileH * effectiveScale).toInt().coerceAtLeast(1)
                val dstRect = Rect(dstX, dstY, dstX + dstW, dstY + dstH)

                // ── ルーティング判定 ──
                val hasText = textRects.isNotEmpty() && isTextTile(tileRect, textRects)

                val resultTile: Bitmap? = if (fecs < FECS_THRESHOLD_LOW) {
                    // ── Route A: 平坦タイル → バイキュービック ──
                    skipCount++
                    Bitmap.createScaledBitmap(tile, dstW, dstH, true)

                } else if (hasText) {
                    // ── Route T: テキスト検出タイル → 文字保存パス ──
                    textCount++
                    aiCount++
                    processTextPreserveTile(tile, dstW, dstH, tileAnalysis.entropy)

                } else if (edgeDensity >= EDGE_DENSITY_THRESHOLD) {
                    // ── Route B: エッジ密集（テキストなし）→ AI + バイキュービック ブレンド ──
                    blendCount++
                    aiCount++
                    processBlendTile(tile, dstW, dstH, tileAnalysis.entropy)

                } else {
                    // ── Route C: 通常タイル → AI のみ ──
                    aiCount++
                    processAiOnlyTile(tile, dstW, dstH, tileAnalysis.entropy)
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
                            "(AI=$aiCount, text=$textCount, blend=$blendCount, skip=$skipCount)")
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "EASS Done: ${outW}x${outH}, " +
                "$successCount/$totalTiles tiles, " +
                "AI=$aiCount (text=$textCount, blend=$blendCount, denoise=$denoiseCount), " +
                "skip=$skipCount (${skipPercent(skipCount, totalTiles)}%), " +
                "${elapsed}ms")

        return output
    }

    // ════════════════════════════════════════
    //  Route T: テキスト保存パス
    //  AI 出力 + バイキュービック拡大 → ラプラシアンブレンド
    //  文字のエッジを元画像から保持し、AI の塗りつぶしを抑制
    // ════════════════════════════════════════

    private fun processTextPreserveTile(
        tile: Bitmap, dstW: Int, dstH: Int, entropy: Float
    ): Bitmap? {
        // 1. バイキュービック拡大（文字構造を保持）
        val bicubic = Bitmap.createScaledBitmap(tile, dstW, dstH, true)

        // 2. バイキュービック結果にシャープネス適用（文字エッジ強調）
        val sharpened = RealEsrganBridge.nativeSharpen(bicubic, TEXT_SHARPEN_STRENGTH)

        // 3. AI 処理（デノイズ付き）
        val inputTile = if (entropy > NOISE_ENTROPY_THRESHOLD) {
            val denoised = RealEsrganBridge.nativeNlmDenoise(
                ensureArgb(tile), NLM_STRENGTH, NLM_PATCH_SIZE, NLM_SEARCH_SIZE
            )
            denoised ?: tile
        } else {
            tile
        }
        val aiResult = processOneTileDirect(inputTile, dstW, dstH)

        if (aiResult == null) {
            // AI 失敗 → シャープ済みバイキュービックを返す
            bicubic.recycle()
            return sharpened ?: bicubic
        }

        // 4. ラプラシアンブレンド: シャープ済みバイキュービック(元画像)の高周波 + AI の低周波
        val textSource = sharpened ?: bicubic
        val blended = RealEsrganBridge.nativeLaplacianBlend(
            textSource,      // original: 文字エッジの高周波ソース
            aiResult,        // enhanced: AI の滑らかな低周波ソース
            TEXT_DETAIL_STRENGTH,   // 元画像ディテール強度 1.2（文字エッジを強めに保持）
            TEXT_SHARPEN_BLEND      // 仕上げシャープ 0.4
        )

        // クリーンアップ
        bicubic.recycle()
        if (sharpened != null && sharpened !== bicubic) sharpened.recycle()
        aiResult.recycle()

        return blended
    }

    // ════════════════════════════════════════
    //  Route B: エッジ密集ブレンド
    // ════════════════════════════════════════

    private fun processBlendTile(
        tile: Bitmap, dstW: Int, dstH: Int, entropy: Float
    ): Bitmap? {
        val inputTile = if (entropy > NOISE_ENTROPY_THRESHOLD) {
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
            return blended
        } else {
            return bicubicResult
        }
    }

    // ════════════════════════════════════════
    //  Route C: AI のみ
    // ════════════════════════════════════════

    private fun processAiOnlyTile(
        tile: Bitmap, dstW: Int, dstH: Int, entropy: Float
    ): Bitmap? {
        val inputTile = if (entropy > NOISE_ENTROPY_THRESHOLD) {
            val denoised = RealEsrganBridge.nativeNlmDenoise(
                ensureArgb(tile), NLM_STRENGTH, NLM_PATCH_SIZE, NLM_SEARCH_SIZE
            )
            denoised ?: tile
        } else {
            tile
        }
        return processOneTileDirect(inputTile, dstW, dstH)
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
            ocrEngine?.close()
            ocrEngine = null
            initialized = false
            Log.i(TAG, "Released NcnnSR")
        }
    }
}
