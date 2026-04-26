// ファイルパス: app/src/main/java/com/nexus/vision/image/TileManager.kt

package com.nexus.vision.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlin.math.ceil
import kotlin.math.min

/**
 * タイル分割・結合マネージャ
 *
 * 画像を 128×128 px のタイルに分割し、処理後に 8px オーバーラップ
 * 線形ブレンドで結合する。
 *
 * Phase 6: 基本実装
 */
object TileManager {

    private const val TAG = "TileManager"

    /**
     * タイル情報
     */
    data class Tile(
        val col: Int,
        val row: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        var processedBitmap: Bitmap? = null
    )

    /**
     * 画像をタイルに分割する。
     *
     * @param bitmap   入力画像
     * @param tileSize タイルサイズ (デフォルト 128)
     * @param overlap  オーバーラップ量 (デフォルト 8)
     * @return タイルのリスト
     */
    fun splitIntoTiles(
        bitmap: Bitmap,
        tileSize: Int = 128,
        overlap: Int = 8
    ): List<Tile> {
        val width = bitmap.width
        val height = bitmap.height
        val step = tileSize - overlap

        val cols = ceil(width.toDouble() / step).toInt()
        val rows = ceil(height.toDouble() / step).toInt()

        val tiles = mutableListOf<Tile>()

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x = (col * step).coerceAtMost(width - 1)
                val y = (row * step).coerceAtMost(height - 1)
                val tileW = min(tileSize, width - x)
                val tileH = min(tileSize, height - y)

                if (tileW <= 0 || tileH <= 0) continue

                val pixels = IntArray(tileW * tileH)
                bitmap.getPixels(pixels, 0, tileW, x, y, tileW, tileH)

                tiles.add(
                    Tile(
                        col = col,
                        row = row,
                        x = x,
                        y = y,
                        width = tileW,
                        height = tileH,
                        pixels = pixels
                    )
                )
            }
        }

        Log.d(TAG, "Split: ${width}x${height} → ${tiles.size} tiles (${cols}x${rows})")
        return tiles
    }

    /**
     * 処理済みタイルを結合する（線形ブレンド付き）。
     *
     * @param tiles       処理済みタイルリスト
     * @param outputWidth 出力画像幅
     * @param outputHeight 出力画像高さ
     * @param overlap     オーバーラップ量
     * @return 結合された Bitmap
     */
    fun mergeTiles(
        tiles: List<Tile>,
        outputWidth: Int,
        outputHeight: Int,
        overlap: Int = 8
    ): Bitmap {
        val startTime = System.currentTimeMillis()

        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        // Canvas/Paint はここでは使わず、直接ピクセル操作でブレンドを制御する（精度の追求）
        
        // 重み累積用バッファ
        val weightSum = FloatArray(outputWidth * outputHeight)
        val rBuf = FloatArray(outputWidth * outputHeight)
        val gBuf = FloatArray(outputWidth * outputHeight)
        val bBuf = FloatArray(outputWidth * outputHeight)

        for (tile in tiles) {
            val tileBitmap = tile.processedBitmap ?: continue
            val tileW = tileBitmap.width
            val tileH = tileBitmap.height
            val tilePixels = IntArray(tileW * tileH)
            tileBitmap.getPixels(tilePixels, 0, tileW, 0, 0, tileW, tileH)

            for (ty in 0 until tileH) {
                for (tx in 0 until tileW) {
                    val outX = tile.x + tx
                    val outY = tile.y + ty
                    if (outX >= outputWidth || outY >= outputHeight) continue

                    // オーバーラップ領域で線形ブレンド重みを計算
                    val weight = calculateBlendWeight(
                        tx, ty, tileW, tileH, overlap
                    )

                    val pixel = tilePixels[ty * tileW + tx]
                    val idx = outY * outputWidth + outX

                    rBuf[idx] += android.graphics.Color.red(pixel) * weight
                    gBuf[idx] += android.graphics.Color.green(pixel) * weight
                    bBuf[idx] += android.graphics.Color.blue(pixel) * weight
                    weightSum[idx] += weight
                }
            }
        }

        // 正規化して出力
        val outputPixels = IntArray(outputWidth * outputHeight)
        for (i in outputPixels.indices) {
            val w = weightSum[i]
            if (w > 0f) {
                val r = (rBuf[i] / w).toInt().coerceIn(0, 255)
                val g = (gBuf[i] / w).toInt().coerceIn(0, 255)
                val b = (bBuf[i] / w).toInt().coerceIn(0, 255)
                outputPixels[i] = android.graphics.Color.argb(255, r, g, b)
            }
        }
        output.setPixels(outputPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Merge: ${tiles.size} tiles → ${outputWidth}x${outputHeight} (${elapsed}ms)")

        return output
    }

    /**
     * オーバーラップ領域の線形ブレンド重みを計算する。
     * タイル端から overlap 以内は 0→1 に線形増加。
     */
    private fun calculateBlendWeight(
        x: Int, y: Int,
        width: Int, height: Int,
        overlap: Int
    ): Float {
        if (overlap <= 0) return 1f

        val wx = when {
            x < overlap -> (x + 1f) / (overlap + 1f)
            x >= width - overlap -> (width - x).toFloat() / (overlap + 1f)
            else -> 1f
        }
        val wy = when {
            y < overlap -> (y + 1f) / (overlap + 1f)
            y >= height - overlap -> (height - y).toFloat() / (overlap + 1f)
            else -> 1f
        }

        return wx * wy
    }

    /**
     * Merge tiles that may have different scales (e.g., some tiles are 4x upscaled by NCNN).
     * Downsamples oversized tiles to match the target output dimensions.
     */
    fun mergeTilesWithScaleMismatch(
        tiles: List<Tile>,
        outputWidth: Int,
        outputHeight: Int,
        overlap: Int
    ): Bitmap {
        // For tiles that have been upscaled by NCNN, downsample their processedBitmap
        for (tile in tiles) {
            val bmp = tile.processedBitmap ?: continue
            if (bmp.width > tile.width || bmp.height > tile.height) {
                // This tile was upscaled — downsample to original tile size
                val downsampled = Bitmap.createScaledBitmap(bmp, tile.width, tile.height, true)
                if (downsampled !== bmp) bmp.recycle()
                tile.processedBitmap = downsampled
            }
        }

        return mergeTiles(tiles, outputWidth, outputHeight, overlap)
    }

    /**
     * Simple area-average downsampling for pixel arrays.
     */
    private fun downsamplePixels(
        pixels: IntArray,
        srcWidth: Int, srcHeight: Int,
        dstWidth: Int, dstHeight: Int
    ): IntArray {
        val result = IntArray(dstWidth * dstHeight)
        val scaleX = srcWidth.toFloat() / dstWidth
        val scaleY = srcHeight.toFloat() / dstHeight

        for (dy in 0 until dstHeight) {
            for (dx in 0 until dstWidth) {
                val srcX = (dx * scaleX).toInt().coerceIn(0, srcWidth - 1)
                val srcY = (dy * scaleY).toInt().coerceIn(0, srcHeight - 1)
                result[dy * dstWidth + dx] = pixels[srcY * srcWidth + srcX]
            }
        }
        return result
    }

    /**
     * タイルの processedBitmap を解放する。
     */
    fun recycleTiles(tiles: List<Tile>) {
        for (tile in tiles) {
            tile.processedBitmap?.recycle()
            tile.processedBitmap = null
        }
    }
}
