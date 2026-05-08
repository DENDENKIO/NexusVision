// app/src/main/java/com/nexus/vision/retail/ocr/BitmapPreprocessor.kt
package com.nexus.vision.retail.ocr

import android.graphics.*
import android.util.Log

/**
 * OCR前処理パイプライン
 *
 * 適用順:
 *  1. リサイズ（長辺2048px上限 / 短辺800px下限）
 *  2. グレースケール変換
 *  3. コントラスト強調（LinearContrast）
 *  4. 適応的二値化 (Otsu風 簡易実装)
 *  5. ノイズ除去（孤立点除去 3×3 Erosion → Dilation）
 */
object BitmapPreprocessor {

    private const val TAG = "BitmapPreprocessor"

    // 長辺の最大・最小ピクセル
    private const val MAX_LONG_SIDE  = 2048
    private const val MIN_SHORT_SIDE = 800

    /**
     * OCR用に最適化したBitmapを返す
     * 元のBitmapはリサイクルしない（呼び出し元の責任）
     */
    fun process(src: Bitmap): Bitmap {
        val t0 = System.currentTimeMillis()

        var bmp = resize(src)
        bmp     = toGrayscale(bmp)
        bmp     = enhanceContrast(bmp)
        bmp     = adaptiveThreshold(bmp)
        bmp     = removeNoise(bmp)

        Log.d(TAG, "前処理: ${src.width}×${src.height} → " +
                "${bmp.width}×${bmp.height} " +
                "(${System.currentTimeMillis() - t0}ms)")
        return bmp
    }

    // ─────────────────────────────────────────────────────
    // Step 1: リサイズ
    // ─────────────────────────────────────────────────────
    private fun resize(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val longSide  = maxOf(w, h)
        val shortSide = minOf(w, h)

        val scale = when {
            longSide  > MAX_LONG_SIDE  -> MAX_LONG_SIDE.toFloat()  / longSide
            shortSide < MIN_SHORT_SIDE -> MIN_SHORT_SIDE.toFloat() / shortSide
            else                       -> return src  // そのまま
        }

        val nw = (w * scale).toInt()
        val nh = (h * scale).toInt()
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    // ─────────────────────────────────────────────────────
    // Step 2: グレースケール
    // ─────────────────────────────────────────────────────
    private fun toGrayscale(src: Bitmap): Bitmap {
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val paint  = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dst
    }

    // ─────────────────────────────────────────────────────
    // Step 3: コントラスト強調
    //   output = clamp(alpha * (input - 128) + 128 + beta, 0, 255)
    //   alpha=1.4 (コントラスト強調), beta=10 (明度微増)
    // ─────────────────────────────────────────────────────
    private fun enhanceContrast(src: Bitmap): Bitmap {
        val alpha = 1.4f
        val beta  = 10f

        // ColorMatrixColorFilter で一発処理
        // R' = alpha*R + beta  (G,B も同様)
        val cm = ColorMatrix(floatArrayOf(
            alpha, 0f, 0f, 0f, beta - 128f * (alpha - 1f),
            0f, alpha, 0f, 0f, beta - 128f * (alpha - 1f),
            0f, 0f, alpha, 0f, beta - 128f * (alpha - 1f),
            0f, 0f, 0f, 1f, 0f
        ))

        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        canvas.drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return dst
    }

    // ─────────────────────────────────────────────────────
    // Step 4: 適応的二値化（Otsu法で閾値自動決定）
    // ─────────────────────────────────────────────────────
    private fun adaptiveThreshold(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // グレー値取得（Rチャンネル = グレースケール済みなのでR=G=B）
        val gray = IntArray(pixels.size) { Color.red(pixels[it]) }

        // Otsu法で閾値計算
        val threshold = otsuThreshold(gray)
        Log.d(TAG, "Otsu閾値: $threshold")

        // 二値化: 閾値以下 → 黒(0), 超過 → 白(255)
        val result = IntArray(pixels.size) { i ->
            if (gray[i] <= threshold) Color.BLACK else Color.WHITE
        }

        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dst.setPixels(result, 0, w, 0, 0, w, h)
        return dst
    }

    private fun otsuThreshold(gray: IntArray): Int {
        val hist = IntArray(256)
        gray.forEach { hist[it]++ }
        val total = gray.size.toDouble()

        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]

        var sumB = 0.0; var wB = 0; var wF: Int
        var maxVar = 0.0; var threshold = 128

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            wF = gray.size - wB
            if (wF == 0) break

            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar    = between
                threshold = t
            }
        }
        return threshold
    }

    // ─────────────────────────────────────────────────────
    // Step 5: ノイズ除去
    //   孤立点（周囲8近傍がすべて白のに黒い点）を除去
    //   1px Erosion → 1px Dilation (Opening)
    // ─────────────────────────────────────────────────────
    private fun removeNoise(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        fun isBlack(x: Int, y: Int): Boolean {
            if (x < 0 || x >= w || y < 0 || y >= h) return false
            return pixels[y * w + x] == Color.BLACK
        }

        // Erosion: 周囲8近傍にひとつでも白があれば白にする（黒を縮小）
        val eroded = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (!isBlack(x, y)) Color.WHITE
            else {
                var allBlack = true
                outer@ for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (!isBlack(x + dx, y + dy)) { allBlack = false; break@outer }
                }
                if (allBlack) Color.BLACK else Color.WHITE
            }
        }

        // Dilation: 周囲8近傍にひとつでも黒があれば黒にする（黒を拡大）
        fun isBlackE(x: Int, y: Int): Boolean {
            if (x < 0 || x >= w || y < 0 || y >= h) return false
            return eroded[y * w + x] == Color.BLACK
        }
        val result = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (isBlackE(x, y)) Color.BLACK
            else {
                var anyBlack = false
                outer@ for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (isBlackE(x + dx, y + dy)) { anyBlack = true; break@outer }
                }
                if (anyBlack) Color.BLACK else Color.WHITE
            }
        }

        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dst.setPixels(result, 0, w, 0, 0, w, h)
        return dst
    }
}
