// ファイルパス: app/src/main/java/com/nexus/vision/ocr/MlKitOcrEngine.kt

package com.nexus.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * ML Kit テキスト認識ラッパー
 *
 * - 日本語 + 英語 対応
 * - TextBlock / Line / Element の階層を OcrResult に変換
 * - suspend 関数で非同期を同期的に呼び出し可能
 *
 * Phase 5: 基本実装
 * Phase 9: 表復元 (TableReconstructor) と連携
 * Phase 10: recognizeFromUri 追加 (OS 統合 ShareReceiver 用)
 */
class MlKitOcrEngine {

    companion object {
        private const val TAG = "MlKitOcr"
    }

    private val recognizer: TextRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    /**
     * Bitmap からテキストを認識する。
     *
     * @param bitmap 入力画像
     * @return OCR 結果
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCoroutine { cont ->
        val startTime = System.currentTimeMillis()
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val elapsed = System.currentTimeMillis() - startTime

                val blocks = visionText.textBlocks.map { block ->
                    OcrResult.TextBlock(
                        text = block.text,
                        boundingBox = block.boundingBox,
                        confidence = block.lines.firstOrNull()?.confidence ?: 0f,
                        lines = block.lines.map { line ->
                            OcrResult.TextLine(
                                text = line.text,
                                boundingBox = line.boundingBox,
                                confidence = line.confidence ?: 0f,
                                elements = line.elements.map { element ->
                                    OcrResult.TextElement(
                                        text = element.text,
                                        boundingBox = element.boundingBox,
                                        confidence = element.confidence ?: 0f
                                    )
                                }
                            )
                        }
                    )
                }

                val result = OcrResult(
                    fullText = visionText.text,
                    blocks = blocks,
                    processingTimeMs = elapsed
                )

                Log.i(
                    TAG,
                    "OCR: ${result.blocks.size} blocks, " +
                            "${result.fullText.length} chars, ${elapsed}ms"
                )

                cont.resume(result)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                cont.resumeWithException(e)
            }
    }

    /**
     * Uri から直接 OCR を実行する。
     * ShareReceiver 等、外部から画像 Uri を受け取って処理する場合に使用。
     *
     * @param context アプリケーションコンテキスト
     * @param uri 画像の Uri
     * @return 認識されたテキスト（空文字列の場合はテキスト未検出）
     *
     * Phase 10: OS 統合
     */
    suspend fun recognizeFromUri(context: Context, uri: Uri): String = suspendCoroutine { cont ->
        try {
            val inputImage = InputImage.fromFilePath(context, uri)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    Log.i(TAG, "recognizeFromUri: ${visionText.text.length} chars")
                    cont.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "recognizeFromUri failed: ${e.message}")
                    cont.resume("")
                }
        } catch (e: Exception) {
            Log.e(TAG, "recognizeFromUri error: ${e.message}")
            cont.resume("")
        }
    }

    /**
     * リソースを解放する。
     */
    fun close() {
        recognizer.close()
    }
}

/**
 * OCR 結果データクラス
 */
data class OcrResult(
    val fullText: String,
    val blocks: List<TextBlock>,
    val processingTimeMs: Long
) {
    data class TextBlock(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Float,
        val lines: List<TextLine>
    )

    data class TextLine(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Float,
        val elements: List<TextElement>
    )

    data class TextElement(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Float
    )
}
