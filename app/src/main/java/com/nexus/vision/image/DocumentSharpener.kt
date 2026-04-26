// ファイルパス: app/src/main/java/com/nexus/vision/image/DocumentSharpener.kt

package com.nexus.vision.image

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.nexus.vision.ocr.MlKitOcrEngine
import com.nexus.vision.ocr.OcrResult
import java.io.InputStream

/**
 * 文書鮮鋭化パイプライン
 *
 * ケース A（近距離文書 / ホワイトボード）:
 *   1. inSampleSize=2 でロード (≈25MP)
 *   2. Sauvola 適応二値化
 *   3. アンシャープマスク
 *   4. ML Kit OCR
 *
 * ケース B（遠距離看板 / 小さな文字）:
 *   1. inSampleSize=4 で粗 OCR → 大きなテキストブロック検出
 *   2. 未検出候補領域を特定
 *   3. 100MP ダイレクトクロップ（等倍）
 *   4. コントラスト不足なら Sauvola + アンシャープマスク
 *   5. 再 OCR → 結果統合
 *
 * Phase 5: 基本実装
 * Phase 6: EASS ルート判定と連携
 */
object DocumentSharpener {

    private const val TAG = "DocSharpener"

    /** 文字サイズの最低基準 (px) — これ以下なら補正が必要 */
    private const val MIN_CHAR_SIZE_PX = 16

    /** コントラスト比の閾値 — これ以下なら二値化を適用 */
    private const val CONTRAST_THRESHOLD = 0.4f

    /**
     * ケース A: 近距離文書の鮮鋭化 + OCR
     *
     * @param inputStream JPEG 入力ストリーム
     * @param ocrEngine   ML Kit OCR エンジン
     * @return OCR 結果
     */
    suspend fun processCaseA(
        inputStream: InputStream,
        ocrEngine: MlKitOcrEngine
    ): DocumentResult {
        val startTime = System.currentTimeMillis()

        // 1. inSampleSize=2 でロード
        val bitmap = DirectCrop100MP.loadDownsampled(inputStream, inSampleSize = 2)
        Log.d(TAG, "CaseA: loaded ${bitmap.width}x${bitmap.height}")

        // 2. コントラスト判定
        val contrast = ImageCorrector.calculateContrastRatio(bitmap)
        Log.d(TAG, "CaseA: contrast=%.3f".format(contrast))

        // 3. 補正適用
        val corrected = if (contrast <= CONTRAST_THRESHOLD) {
            val binarized = ImageCorrector.sauvolaBinarize(bitmap)
            bitmap.recycle()
            val sharpened = ImageCorrector.unsharpMask(binarized, radius = 1, alpha = 0.5f)
            binarized.recycle()
            sharpened
        } else {
            // コントラスト十分ならアンシャープマスクだけ
            val sharpened = ImageCorrector.unsharpMask(bitmap, radius = 1, alpha = 0.5f)
            if (sharpened !== bitmap) bitmap.recycle()
            sharpened
        }

        // 4. OCR
        val ocrResult = ocrEngine.recognize(corrected)
        corrected.recycle()

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "CaseA complete: ${ocrResult.fullText.length} chars, ${elapsed}ms")

        return DocumentResult(
            fullText = ocrResult.fullText,
            blocks = ocrResult.blocks,
            processingTimeMs = elapsed,
            pipeline = "CaseA"
        )
    }

    /**
     * ケース B: 遠距離看板の段階的 OCR
     *
     * @param inputStreamForCoarse  粗スキャン用ストリーム
     * @param inputStreamForCrop    ダイレクトクロップ用ストリーム
     * @param ocrEngine             ML Kit OCR エンジン
     * @return OCR 結果
     */
    suspend fun processCaseB(
        inputStreamForCoarse: InputStream,
        inputStreamForCrop: InputStream,
        ocrEngine: MlKitOcrEngine
    ): DocumentResult {
        val startTime = System.currentTimeMillis()

        // 1. 粗スキャン (inSampleSize=4)
        val coarseBitmap = DirectCrop100MP.loadDownsampled(inputStreamForCoarse, inSampleSize = 4)
        Log.d(TAG, "CaseB: coarse ${coarseBitmap.width}x${coarseBitmap.height}")

        val coarseResult = ocrEngine.recognize(coarseBitmap)
        Log.d(TAG, "CaseB: coarse found ${coarseResult.blocks.size} blocks")

        // 2. 検出済みブロックの領域を収集
        val detectedRects = coarseResult.blocks.mapNotNull { it.boundingBox }

        // 3. 画像全体のサイズを取得
        val coarseWidth = coarseBitmap.width
        val coarseHeight = coarseBitmap.height
        coarseBitmap.recycle()

        // 4. 未検出候補領域を特定（画像を 3×3 グリッドに分割し、テキスト検出されなかった領域）
        val candidateRegions = findUndetectedRegions(
            coarseWidth, coarseHeight, detectedRects,
            gridCols = 3, gridRows = 3
        )

        Log.d(TAG, "CaseB: ${candidateRegions.size} candidate regions for high-res crop")

        // 5. 各候補領域をダイレクトクロップ → 補正 → 再 OCR
        val additionalTexts = mutableListOf<String>()

        for (region in candidateRegions) {
            try {
                val cropResult = DirectCrop100MP.cropByRatio(
                    inputStream = inputStreamForCrop,
                    leftRatio = region.leftRatio,
                    topRatio = region.topRatio,
                    widthRatio = region.widthRatio,
                    heightRatio = region.heightRatio,
                    padding = 0.02f,
                    inSampleSize = 1
                )

                // 補正判定
                val contrast = ImageCorrector.calculateContrastRatio(cropResult.bitmap)
                val corrected = if (contrast <= CONTRAST_THRESHOLD) {
                    val sharpened = ImageCorrector.unsharpMask(cropResult.bitmap, radius = 2, alpha = 0.8f)
                    if (sharpened !== cropResult.bitmap) cropResult.bitmap.recycle()
                    sharpened
                } else {
                    cropResult.bitmap
                }

                // 再 OCR
                val cropOcr = ocrEngine.recognize(corrected)
                if (corrected !== cropResult.bitmap) corrected.recycle()
                if (cropOcr.fullText.isNotBlank()) {
                    additionalTexts.add(cropOcr.fullText)
                }
            } catch (e: Exception) {
                Log.w(TAG, "CaseB: crop OCR failed for region $region", e)
            }
        }

        // 6. 結果統合
        val combinedText = buildString {
            append(coarseResult.fullText)
            for (extra in additionalTexts) {
                if (extra.isNotBlank() && !coarseResult.fullText.contains(extra)) {
                    append("\n")
                    append(extra)
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "CaseB complete: ${combinedText.length} chars, ${elapsed}ms")

        return DocumentResult(
            fullText = combinedText,
            blocks = coarseResult.blocks,
            processingTimeMs = elapsed,
            pipeline = "CaseB"
        )
    }

    /**
     * 自動判定: 画像サイズと最初の OCR 結果から CaseA / CaseB を選択。
     *
     * @param imageWidth  元画像の幅
     * @param imageHeight 元画像の高さ
     * @return "A" or "B"
     */
    fun detectCase(imageWidth: Int, imageHeight: Int): String {
        // 100MP 級 (10000px 以上) → CaseB (遠距離の可能性)
        // それ以外 → CaseA
        return if (imageWidth >= 8000 || imageHeight >= 8000) "B" else "A"
    }

    // ── 内部データクラス ──

    data class DocumentResult(
        val fullText: String,
        val blocks: List<OcrResult.TextBlock>,
        val processingTimeMs: Long,
        val pipeline: String
    )

    data class CandidateRegion(
        val leftRatio: Float,
        val topRatio: Float,
        val widthRatio: Float,
        val heightRatio: Float
    )

    /**
     * 検出されなかったグリッド領域を返す。
     */
    private fun findUndetectedRegions(
        imageWidth: Int,
        imageHeight: Int,
        detectedRects: List<Rect>,
        gridCols: Int,
        gridRows: Int
    ): List<CandidateRegion> {
        val cellWidth = imageWidth / gridCols
        val cellHeight = imageHeight / gridRows
        val candidates = mutableListOf<CandidateRegion>()

        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val cellRect = Rect(
                    col * cellWidth,
                    row * cellHeight,
                    (col + 1) * cellWidth,
                    (row + 1) * cellHeight
                )

                // このセルに検出済みテキストが含まれるか
                val hasText = detectedRects.any { Rect.intersects(it, cellRect) }

                if (!hasText) {
                    candidates.add(
                        CandidateRegion(
                            leftRatio = col.toFloat() / gridCols,
                            topRatio = row.toFloat() / gridRows,
                            widthRatio = 1f / gridCols,
                            heightRatio = 1f / gridRows
                        )
                    )
                }
            }
        }

        return candidates
    }
}
