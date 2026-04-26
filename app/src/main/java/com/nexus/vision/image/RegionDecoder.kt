// ファイルパス: app/src/main/java/com/nexus/vision/image/RegionDecoder.kt
package com.nexus.vision.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log

/**
 * 画像の一部領域だけをメモリに読み込む
 * 大画像でもOOMにならない
 */
object RegionDecoder {
    private const val TAG = "RegionDecoder"

    /**
     * 画像の実際のサイズを取得（メモリにデコードしない）
     */
    fun getImageSize(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                Pair(options.outWidth, options.outHeight)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "getImageSize error: ${e.message}")
            null
        }
    }

    /**
     * 低解像度サムネイルを取得（プレビュー用、長辺maxSide以下）
     */
    fun decodeThumbnail(context: Context, uri: Uri, maxSide: Int = 1024): Bitmap? {
        return try {
            val size = getImageSize(context, uri) ?: return null
            val (w, h) = size
            val sampleSize = calculateSampleSize(w, h, maxSide)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeThumbnail error: ${e.message}")
            null
        }
    }

    /**
     * 指定領域だけをデコード
     * ratioRect: 0.0〜1.0 の比率で指定（left, top, right, bottom）
     * maxOutputSide: 出力の長辺の最大値（超解像に渡すため制限）
     */
    fun decodeRegion(
        context: Context,
        uri: Uri,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        maxOutputSide: Int = 2048
    ): Bitmap? {
        return try {
            val size = getImageSize(context, uri) ?: return null
            val (imgW, imgH) = size

            // 比率からピクセル座標に変換
            val pixelLeft = (left * imgW).toInt().coerceIn(0, imgW)
            val pixelTop = (top * imgH).toInt().coerceIn(0, imgH)
            val pixelRight = (right * imgW).toInt().coerceIn(0, imgW)
            val pixelBottom = (bottom * imgH).toInt().coerceIn(0, imgH)

            val regionW = pixelRight - pixelLeft
            val regionH = pixelBottom - pixelTop
            if (regionW < 32 || regionH < 32) {
                Log.w(TAG, "Region too small: ${regionW}x${regionH}")
                return null
            }

            Log.i(TAG, "Decoding region: ($pixelLeft,$pixelTop)-($pixelRight,$pixelBottom) = ${regionW}x${regionH} from ${imgW}x${imgH}")

            // 領域がmaxOutputSideを超える場合はサンプリングで縮小
            val maxRegionSide = maxOf(regionW, regionH)
            val sampleSize = if (maxRegionSide > maxOutputSide) {
                calculateSampleSize(regionW, regionH, maxOutputSide)
            } else {
                1
            }

            val rect = Rect(pixelLeft, pixelTop, pixelRight, pixelBottom)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val stream = context.contentResolver.openInputStream(uri)
                ?: return null

            @Suppress("DEPRECATION")
            val decoder = if (android.os.Build.VERSION.SDK_INT >= 31) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                BitmapRegionDecoder.newInstance(stream, false)
            }
            val bitmap = decoder?.decodeRegion(rect, options)
            decoder?.recycle()
            stream.close()

            if (bitmap != null) {
                Log.i(TAG, "Region decoded: ${bitmap.width}x${bitmap.height} (sample=$sampleSize)")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "decodeRegion error: ${e.message}")
            null
        }
    }

    /**
     * 画像全体を安全にデコード（長辺maxSide以下にサンプリング）
     */
    fun decodeSafe(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? {
        return try {
            val size = getImageSize(context, uri) ?: return null
            val (w, h) = size
            val sampleSize = calculateSampleSize(w, h, maxSide)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeSafe error: ${e.message}")
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        val maxDim = maxOf(width, height)
        var sampleSize = 1
        while (maxDim / sampleSize > maxSide) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
