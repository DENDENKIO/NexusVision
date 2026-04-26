// ファイルパス: app/src/main/java/com/nexus/vision/image/DirectCrop100MP.kt

package com.nexus.vision.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import java.io.InputStream

/**
 * 100MP 画像のゼロコピーダイレクトクロップ
 *
 * BitmapRegionDecoder を使い、JPEG の指定矩形だけをデコードする。
 * 12000×9000 px (≈400MB ARGB) の全画像をメモリに載せず、
 * 部分領域（例: 40%×50% → ≈82MB）だけをロードして OOM を回避する。
 *
 * Phase 5: 基本実装
 * Phase 6: EASS タイル分割でも利用
 */
object DirectCrop100MP {

    private const val TAG = "DirectCrop100MP"

    /**
     * 画像全体のサイズだけを取得する（デコードなし）。
     *
     * @param inputStream JPEG 入力ストリーム
     * @return Pair(幅, 高さ)
     */
    fun getImageDimensions(inputStream: InputStream): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(inputStream, null, options)
        return Pair(options.outWidth, options.outHeight)
    }

    /**
     * 指定された矩形領域をデコードする。
     *
     * @param inputStream JPEG 入力ストリーム（シーク可能であること）
     * @param rect         クロップ矩形 (left, top, right, bottom in px)
     * @param inSampleSize サンプリングサイズ（1=等倍, 2=1/2, 4=1/4）
     * @return クロップされた Bitmap
     */
    fun cropRegion(
        inputStream: InputStream,
        rect: Rect,
        inSampleSize: Int = 1
    ): Bitmap {
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(inputStream, false)
            ?: throw IllegalStateException("BitmapRegionDecoder の作成に失敗しました")

        try {
            val imageWidth = decoder.width
            val imageHeight = decoder.height

            // 矩形を画像範囲内にクランプ
            val safeRect = Rect(
                rect.left.coerceIn(0, imageWidth),
                rect.top.coerceIn(0, imageHeight),
                rect.right.coerceIn(0, imageWidth),
                rect.bottom.coerceIn(0, imageHeight)
            )

            if (safeRect.isEmpty) {
                throw IllegalArgumentException(
                    "クロップ領域が空です: $safeRect (image: ${imageWidth}x${imageHeight})"
                )
            }

            val options = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val cropped = decoder.decodeRegion(safeRect, options)

            val memoryMb = (cropped.byteCount / (1024.0 * 1024.0))
            Log.i(
                TAG,
                "Cropped: ${safeRect.width()}x${safeRect.height()} " +
                        "(sample=$inSampleSize) → ${cropped.width}x${cropped.height} " +
                        "(%.1f MB)".format(memoryMb)
            )

            return cropped
        } finally {
            decoder.recycle()
        }
    }

    /**
     * ROI（関心領域）を比率で指定してクロップする。
     *
     * @param inputStream  JPEG 入力ストリーム
     * @param leftRatio    左端比率 (0.0 ~ 1.0)
     * @param topRatio     上端比率 (0.0 ~ 1.0)
     * @param widthRatio   幅比率 (0.0 ~ 1.0)
     * @param heightRatio  高さ比率 (0.0 ~ 1.0)
     * @param padding      ROI の周囲に追加するマージン比率 (0.0 ~ 0.5)
     * @param inSampleSize サンプリングサイズ
     * @return クロップされた Bitmap
     */
    fun cropByRatio(
        inputStream: InputStream,
        leftRatio: Float,
        topRatio: Float,
        widthRatio: Float,
        heightRatio: Float,
        padding: Float = 0.1f,
        inSampleSize: Int = 1
    ): CropResult {
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(inputStream, false)
            ?: throw IllegalStateException("BitmapRegionDecoder の作成に失敗しました")

        try {
            val imageWidth = decoder.width
            val imageHeight = decoder.height

            // パディング適用
            val padLeft = (leftRatio - padding).coerceIn(0f, 1f)
            val padTop = (topRatio - padding).coerceIn(0f, 1f)
            val padRight = (leftRatio + widthRatio + padding).coerceIn(0f, 1f)
            val padBottom = (topRatio + heightRatio + padding).coerceIn(0f, 1f)

            val rect = Rect(
                (padLeft * imageWidth).toInt(),
                (padTop * imageHeight).toInt(),
                (padRight * imageWidth).toInt(),
                (padBottom * imageHeight).toInt()
            )

            val options = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val cropped = decoder.decodeRegion(rect, options)

            Log.i(
                TAG,
                "CropByRatio: roi=(%.2f,%.2f,%.2f,%.2f) pad=%.2f → ${rect} " +
                        "→ ${cropped.width}x${cropped.height}".format(
                            leftRatio, topRatio, widthRatio, heightRatio, padding
                        )
            )

            return CropResult(
                bitmap = cropped,
                cropRect = rect,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                inSampleSize = inSampleSize
            )
        } finally {
            decoder.recycle()
        }
    }

    /**
     * 画像をダウンサンプルしてロードする（全体を低解像度で取得）。
     *
     * @param inputStream  JPEG 入力ストリーム
     * @param inSampleSize サンプリングサイズ
     * @return ダウンサンプルされた Bitmap
     */
    fun loadDownsampled(inputStream: InputStream, inSampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeStream(inputStream, null, options)
            ?: throw IllegalStateException("画像のデコードに失敗しました")
    }

    /**
     * クロップ結果
     */
    data class CropResult(
        val bitmap: Bitmap,
        val cropRect: Rect,
        val imageWidth: Int,
        val imageHeight: Int,
        val inSampleSize: Int
    )
}
