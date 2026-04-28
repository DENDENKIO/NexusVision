// ファイルパス: app/src/main/java/com/nexus/vision/parser/PdfExtractor.kt
package com.nexus.vision.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nexus.vision.ocr.MlKitOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF テキスト抽出
 *
 * Android PdfRenderer でページを画像化 → ML Kit OCR でテキスト抽出。
 * ネイティブ PDF テキスト抽出ライブラリを使わず、
 * 画像ベースで処理するため、スキャン PDF にも対応。
 *
 * Phase 8: ファイル解析
 */
object PdfExtractor {

    private const val TAG = "PdfExtractor"
    private const val MAX_PAGES = 20        // 処理上限ページ数
    private const val RENDER_DPI = 200      // レンダリング解像度
    private const val RENDER_MAX_SIDE = 2048 // レンダリング最大辺

    /**
     * Uri から PDF を解析する
     */
    suspend fun extractFromUri(context: Context, uri: Uri): PdfResult =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val ocrEngine = MlKitOcrEngine()

            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext PdfResult.error("PDF を開けません")

                val renderer = PdfRenderer(pfd)
                val totalPages = renderer.pageCount
                val pagesToProcess = minOf(totalPages, MAX_PAGES)

                Log.i(TAG, "PDF: $totalPages pages, processing $pagesToProcess")

                val pages = mutableListOf<PageResult>()

                for (i in 0 until pagesToProcess) {
                    val page = renderer.openPage(i)

                    // DPI に基づいたレンダリングサイズ計算
                    val scale = RENDER_DPI.toFloat() / 72f // PDF 標準は 72dpi
                    var renderWidth = (page.width * scale).toInt()
                    var renderHeight = (page.height * scale).toInt()

                    // 最大辺制限
                    if (maxOf(renderWidth, renderHeight) > RENDER_MAX_SIDE) {
                        val ratio = RENDER_MAX_SIDE.toFloat() / maxOf(renderWidth, renderHeight)
                        renderWidth = (renderWidth * ratio).toInt()
                        renderHeight = (renderHeight * ratio).toInt()
                    }

                    val bitmap = Bitmap.createBitmap(
                        renderWidth, renderHeight, Bitmap.Config.ARGB_8888
                    )

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // OCR
                    val ocrResult = ocrEngine.recognize(bitmap)
                    bitmap.recycle()

                    pages.add(
                        PageResult(
                            pageNumber = i + 1,
                            text = ocrResult.fullText,
                            blockCount = ocrResult.blocks.size
                        )
                    )

                    Log.d(TAG, "Page ${i + 1}: ${ocrResult.fullText.length} chars")
                }

                renderer.close()
                pfd.close()

                val elapsed = System.currentTimeMillis() - startTime
                val fullText = pages.joinToString("\n\n") { "--- ページ ${it.pageNumber} ---\n${it.text}" }

                Log.i(TAG, "PDF done: $pagesToProcess pages, ${fullText.length} chars, ${elapsed}ms")

                PdfResult(
                    pages = pages,
                    totalPages = totalPages,
                    processedPages = pagesToProcess,
                    fullText = fullText,
                    processingTimeMs = elapsed
                )
            } catch (e: Exception) {
                Log.e(TAG, "PDF extract error: ${e.message}")
                PdfResult.error("PDF 解析エラー: ${e.message}")
            } finally {
                ocrEngine.close()
            }
        }

    /**
     * PDF 解析結果
     */
    data class PdfResult(
        val pages: List<PageResult> = emptyList(),
        val totalPages: Int = 0,
        val processedPages: Int = 0,
        val fullText: String = "",
        val processingTimeMs: Long = 0,
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = errorMessage == null

        fun toSummaryText(): String {
            if (!isSuccess) return errorMessage ?: "解析失敗"
            return buildString {
                appendLine("【PDF 解析結果】$processedPages/$totalPages ページ (${processingTimeMs}ms)")
                appendLine()
                appendLine(fullText.take(3000))
                if (fullText.length > 3000) appendLine("... (省略)")
            }
        }

        companion object {
            fun error(message: String) = PdfResult(errorMessage = message)
        }
    }

    data class PageResult(
        val pageNumber: Int,
        val text: String,
        val blockCount: Int
    )
}
