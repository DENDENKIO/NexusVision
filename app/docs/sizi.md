**EASSPipeline.kt を先にお勧めします。** 理由はシンプルで、RouteA・RouteB・RouteCProcessor がすでに存在しているのに、それらを束ねるパイプラインがないため現状はすべての処理が Real-ESRGAN（RouteC）に流れているからです。EASSPipeline が完成してこそアプリの「AI振り分け」が初めて機能します。

実装前に既存ファイルを確認します。必要なファイルを読み込めました。これで正確な実装が書けます。

---

## 実装: `EASSPipeline.kt`

以下のファイルをそのままコピーして作成してください。

**ファイルパス: `app/src/main/java/com/nexus/vision/image/EASSPipeline.kt`**

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/image/EASSPipeline.kt

package com.nexus.vision.image

import android.graphics.Bitmap
import android.util.Log
import com.nexus.vision.deor.FECSScorer
import com.nexus.vision.pipeline.RouteCProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EASS (Entropy-Adaptive Selective Scaling) Pipeline
 *
 * タイルごとに FECS スコアを算出し、ルート A / B / C へ振り分ける。
 *
 *   Route A  (FECS < 1.5) : バイリニア拡大のみ       … 低コントラスト/単色タイル
 *   Route B  (1.5 ≤ F < 4): ヒストグラム均等化 + USM … 中程度テクスチャ
 *   Route C  (FECS ≥ 4.0) : Real-ESRGAN 4× AI超解像  … 高周波/複雑テクスチャ
 *
 * Phase 6: EASSPipeline 実装
 */
class EASSPipeline(
    /** 初期化済みの RouteCProcessor を外部から渡す（ライフサイクルは呼び出し元が管理）*/
    private val routeC: RouteCProcessor
) {

    companion object {
        private const val TAG       = "EASSPipeline"
        private const val TILE_SIZE = 128
        private const val OVERLAP   = 8
    }

    // ─────────────────────────────────────────────
    // 結果データクラス
    // ─────────────────────────────────────────────

    data class EASSResult(
        val bitmap:      Bitmap,
        val totalTiles:  Int,
        val routeACount: Int,
        val routeBCount: Int,
        val routeCCount: Int,
        val elapsedMs:   Long
    ) {
        val summary: String get() =
            "tiles=$totalTiles  A=$routeACount B=$routeBCount C=$routeCCount  (${elapsedMs}ms)"
    }

    // ─────────────────────────────────────────────
    // メイン処理
    // ─────────────────────────────────────────────

    /**
     * 入力 Bitmap を EASS パイプラインで処理する。
     *
     * @param bitmap 入力画像
     * @param scale  RouteA/B の拡大倍率（1 or 2）。RouteC は常に 4×。
     * @return [EASSResult]
     */
    suspend fun process(bitmap: Bitmap, scale: Int = 2): EASSResult =
        withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()

            // 1. タイル分割
            val tiles = TileManager.splitIntoTiles(bitmap, TILE_SIZE, OVERLAP)
            Log.i(TAG, "EASS start: ${bitmap.width}×${bitmap.height} → ${tiles.size} tiles (scale=$scale)")

            var countA = 0
            var countB = 0
            var countC = 0

            // 2. タイルごとに FECS スコア → ルート振り分け → 処理
            for (tile in tiles) {
                val (_, route) = FECSScorer.scoreAndRoute(tile.pixels, tile.width, tile.height)

                tile.processedBitmap = when (route) {

                    FECSScorer.Route.A -> {
                        countA++
                        RouteAProcessor.process(tile.pixels, tile.width, tile.height, scale)
                    }

                    FECSScorer.Route.B -> {
                        countB++
                        RouteBProcessor.process(tile.pixels, tile.width, tile.height, scale)
                    }

                    FECSScorer.Route.C -> {
                        countC++
                        // RouteCProcessor は Bitmap を受け取るため変換
                        val tileBmp = Bitmap.createBitmap(
                            tile.width, tile.height, Bitmap.Config.ARGB_8888
                        )
                        tileBmp.setPixels(tile.pixels, 0, tile.width, 0, 0, tile.width, tile.height)
                        val result = routeC.process(tileBmp)
                        // RouteCProcessor が別 Bitmap を返した場合は元を解放
                        if (result.bitmap !== tileBmp) tileBmp.recycle()
                        result.bitmap
                    }
                }
            }

            Log.i(TAG, "Route distribution: A=$countA B=$countB C=$countC")

            // 3. 出力サイズとスケールミスマッチ解消
            //    RouteC タイルが 1 つでもあると出力は 4× になる。
            //    RouteA/B タイルは targetScale × に揃えてからマージ。
            val targetScale = if (countC > 0) 4 else scale
            val outputWidth  = bitmap.width  * targetScale
            val outputHeight = bitmap.height * targetScale

            for (tile in tiles) {
                val processed = tile.processedBitmap ?: continue
                val expectedW = tile.width  * targetScale
                val expectedH = tile.height * targetScale
                if (processed.width != expectedW || processed.height != expectedH) {
                    val resized = Bitmap.createScaledBitmap(processed, expectedW, expectedH, true)
                    if (resized !== processed) processed.recycle()
                    tile.processedBitmap = resized
                }
            }

            // 4. マージ
            val merged = TileManager.mergeTiles(
                tiles,
                outputWidth,
                outputHeight,
                OVERLAP * targetScale
            )

            // 5. タイル用 Bitmap をすべて解放
            TileManager.recycleTiles(tiles)

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "EASS done: ${bitmap.width}×${bitmap.height} → " +
                    "${merged.width}×${merged.height} in ${elapsed}ms")

            EASSResult(
                bitmap      = merged,
                totalTiles  = tiles.size,
                routeACount = countA,
                routeBCount = countB,
                routeCCount = countC,
                elapsedMs   = elapsed
            )
        }
}
```

---

## あわせて `RouteCProcessor.kt` を 1 行だけ修正

`RouteCProcessor` 内にある `ENABLE_EASS = false` フラグは、EASSPipeline 完成により不要になりました。ただしコンパイルエラーは出ないので放置でも問題ありません。気になる場合は削除してください。

---

## MainViewModel との接続（次のステップ）

EASSPipeline を実際に呼び出すには、`MainViewModel` の `processImage` 関数内で現在 `routeCProcessor.process(bitmap)` を直接呼んでいる箇所を以下に差し替えます。ただしこれは**別ステップ**です。まず上記ファイルでビルドを通してください。

ビルドが通ったら教えてください。次の ViewModel 接続と、`BatchProcessingWorker.kt` の実装に進みます。