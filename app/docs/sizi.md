

---

# EASSPipeline 実装指示書

## 目的

EASS（Entropy-Adaptive Selective Super-Resolution）パイプラインを作成する。画像をタイルに分割し、タイルごとに FECS スコアで 3 段階のルート（A: バイキュービック、B: シャープ強化、C: Real-ESRGAN AI）に振り分けて処理し、結合する。これにより、青空や白壁などの単純な領域は AI をスキップし、処理時間を大幅に短縮する。

## 既存コードの前提（すべて存在し動作済み）

以下のクラスを呼び出して使う。**変更不要**。

**`com.nexus.vision.deor.FECSScorer`** — `scoreAndRoute(pixels: IntArray, width: Int, height: Int): Pair<Double, Route>`。Route は `A`, `B`, `C`。閾値: A/B = 1.5、B/C = 4.0。

**`com.nexus.vision.image.TileManager`** — `splitIntoTiles(bitmap, tileSize=128, overlap=8): List<Tile>`、`mergeTiles(tiles, outputWidth, outputHeight, overlap=8): Bitmap`、`recycleTiles(tiles)`。Tile は `col, row, x, y, width, height, pixels: IntArray, processedBitmap: Bitmap?`。

**`com.nexus.vision.image.RouteAProcessor`** — `process(pixels: IntArray, width: Int, height: Int, scale: Int): Bitmap`。バイキュービック補間のみ。< 1ms/タイル。

**`com.nexus.vision.image.RouteBProcessor`** — `process(pixels: IntArray, width: Int, height: Int, scale: Int): Bitmap`。ヒストグラム均一化 + アンシャープマスク。50-100ms/タイル。

**`com.nexus.vision.pipeline.RouteCProcessor`** — `initialize(): Boolean`、`suspend process(bitmap: Bitmap): ProcessResult`、`release()`。ProcessResult は `bitmap, method, elapsedMs, success`。Real-ESRGAN 4× AI 超解像。3-5分/画像全体。**タイル単位ではなく画像全体を渡す設計**であることに注意。

**`com.nexus.vision.ncnn.RealEsrganBridge`** — `nativeProcess(bitmap: Bitmap): Bitmap?`。単一タイルを直接 4× 超解像できる。**こちらをタイル単位で使う**。

**`com.nexus.vision.ncnn.NcnnSuperResolution`** — `initialize(context): Boolean`、`release()`。内部で RealEsrganBridge を使用。

## 作成するファイル

### ファイル 1: `app/src/main/java/com/nexus/vision/image/EASSPipeline.kt`

**新規作成**

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/image/EASSPipeline.kt

package com.nexus.vision.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.vision.deor.FECSScorer
import com.nexus.vision.ncnn.NcnnSuperResolution
import com.nexus.vision.ncnn.RealEsrganBridge

/**
 * EASS (Entropy-Adaptive Selective Super-Resolution) パイプライン
 *
 * 画像をタイル(128×128)に分割し、FECS スコアでルートを振り分ける:
 *   Route A (F < 1.5): バイキュービック補間のみ (< 1ms/tile)
 *   Route B (1.5 ≤ F < 4.0): アンシャープマスク + ヒストグラム均一化 (50-100ms/tile)
 *   Route C (F ≥ 4.0): Real-ESRGAN AI 超解像 (0.5-1s/tile)
 *
 * 全タイルに AI を適用する従来方式に比べて、処理時間を 30-60% 短縮する。
 *
 * Phase 6: 基本実装
 */
class EASSPipeline(private val context: Context) {

    companion object {
        private const val TAG = "EASSPipeline"
        private const val TILE_SIZE = 128
        private const val OVERLAP = 8
        private const val SR_MAX_INPUT = 128  // AI 入力の最大辺
    }

    private var ncnnSr: NcnnSuperResolution? = null
    private var initialized = false

    /**
     * NCNN モデルの初期化
     */
    fun initialize(): Boolean {
        if (initialized) return true
        ncnnSr = NcnnSuperResolution()
        initialized = ncnnSr?.initialize(context) == true
        if (initialized) {
            Log.i(TAG, "EASS Pipeline initialized")
        } else {
            Log.e(TAG, "EASS Pipeline init failed")
        }
        return initialized
    }

    /**
     * リソース解放
     */
    fun release() {
        ncnnSr?.release()
        ncnnSr = null
        initialized = false
    }

    /**
     * EASS パイプラインで画像を処理する。
     *
     * @param bitmap 入力画像 (ARGB_8888)
     * @param scale  拡大倍率 (1 = 等倍で品質向上のみ、4 = 4倍拡大)
     * @return 処理結果
     */
    suspend fun process(bitmap: Bitmap, scale: Int = 1): EASSResult {
        val startTime = System.currentTimeMillis()
        val inputW = bitmap.width
        val inputH = bitmap.height

        Log.i(TAG, "EASS Start: ${inputW}x${inputH}, scale=$scale")

        // 1. タイル分割
        val tiles = TileManager.splitIntoTiles(bitmap, TILE_SIZE, OVERLAP)
        Log.i(TAG, "Split into ${tiles.size} tiles")

        // 2. 各タイルをスコアリング & ルート振り分け
        var countA = 0
        var countB = 0
        var countC = 0

        for ((index, tile) in tiles.withIndex()) {
            val (score, route) = FECSScorer.scoreAndRoute(tile.pixels, tile.width, tile.height)

            try {
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
                        processRouteC(tile.pixels, tile.width, tile.height, scale)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tile $index (Route $route) failed: ${e.message}, falling back to RouteA")
                tile.processedBitmap = RouteAProcessor.process(tile.pixels, tile.width, tile.height, scale)
            }

            if ((index + 1) % 20 == 0 || index == tiles.size - 1) {
                Log.i(TAG, "Progress: ${index + 1}/${tiles.size} (A=$countA, B=$countB, C=$countC)")
            }
        }

        // 3. タイル結合
        val outputW = inputW * scale
        val outputH = inputH * scale

        val output = if (scale > 1) {
            TileManager.mergeTilesWithScaleMismatch(tiles, outputW, outputH, OVERLAP * scale)
        } else {
            TileManager.mergeTiles(tiles, outputW, outputH, OVERLAP)
        }

        // 4. クリーンアップ
        TileManager.recycleTiles(tiles)

        val elapsed = System.currentTimeMillis() - startTime
        val totalTiles = tiles.size
        val ratioA = if (totalTiles > 0) countA * 100 / totalTiles else 0
        val ratioB = if (totalTiles > 0) countB * 100 / totalTiles else 0
        val ratioC = if (totalTiles > 0) countC * 100 / totalTiles else 0

        Log.i(TAG, "EASS Done: ${outputW}x${outputH} in ${elapsed}ms")
        Log.i(TAG, "Route distribution: A=$countA($ratioA%), B=$countB($ratioB%), C=$countC($ratioC%)")

        return EASSResult(
            bitmap = output,
            elapsedMs = elapsed,
            totalTiles = totalTiles,
            routeACnt = countA,
            routeBCnt = countB,
            routeCCnt = countC
        )
    }

    /**
     * Route C: タイル単位の AI 超解像
     *
     * RealEsrganBridge.nativeProcess で 1 タイルを 4× 拡大する。
     * scale=1 の場合は 4× 拡大後にタイルサイズに縮小して品質だけ向上させる。
     */
    private fun processRouteC(pixels: IntArray, width: Int, height: Int, scale: Int): Bitmap {
        // ピクセルから Bitmap を作成
        val tileBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        tileBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // AI 入力サイズに制限（大きすぎるとメモリ不足）
        val inputBitmap = if (maxOf(width, height) > SR_MAX_INPUT) {
            val ratio = SR_MAX_INPUT.toFloat() / maxOf(width, height)
            val newW = (width * ratio).toInt().coerceAtLeast(8)
            val newH = (height * ratio).toInt().coerceAtLeast(8)
            val scaled = Bitmap.createScaledBitmap(tileBitmap, newW, newH, true)
            tileBitmap.recycle()
            scaled
        } else {
            tileBitmap
        }

        // ARGB_8888 を保証
        val argbInput = if (inputBitmap.config != Bitmap.Config.ARGB_8888) {
            val copy = inputBitmap.copy(Bitmap.Config.ARGB_8888, false)
            inputBitmap.recycle()
            copy ?: throw RuntimeException("ARGB copy failed")
        } else {
            inputBitmap
        }

        // AI 4× 超解像
        val aiResult = RealEsrganBridge.nativeProcess(argbInput)
        argbInput.recycle()

        if (aiResult == null) {
            // AI 失敗時: バイキュービックにフォールバック
            Log.w(TAG, "Route C AI failed, falling back to RouteA")
            return RouteAProcessor.process(pixels, width, height, scale)
        }

        // 出力サイズ調整
        val targetW = width * scale
        val targetH = height * scale

        return if (aiResult.width == targetW && aiResult.height == targetH) {
            aiResult
        } else {
            val resized = Bitmap.createScaledBitmap(aiResult, targetW, targetH, true)
            if (resized !== aiResult) aiResult.recycle()
            resized
        }
    }

    /**
     * EASS 処理結果
     */
    data class EASSResult(
        val bitmap: Bitmap,
        val elapsedMs: Long,
        val totalTiles: Int,
        val routeACnt: Int,
        val routeBCnt: Int,
        val routeCCnt: Int
    ) {
        val success: Boolean get() = true

        fun summary(): String {
            val ratioA = if (totalTiles > 0) routeACnt * 100 / totalTiles else 0
            val ratioB = if (totalTiles > 0) routeBCnt * 100 / totalTiles else 0
            val ratioC = if (totalTiles > 0) routeCCnt * 100 / totalTiles else 0
            return "EASS ($totalTiles tiles: A=$ratioA% B=$ratioB% C=$ratioC%, ${elapsedMs}ms)"
        }
    }
}
```

---

### ファイル 2: `app/src/main/java/com/nexus/vision/pipeline/RouteCProcessor.kt`

**既存ファイルを修正**。EASSPipeline を優先的に使い、失敗時に従来の NcnnSuperResolution にフォールバックする。

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/pipeline/RouteCProcessor.kt

package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.vision.image.EASSPipeline

/**
 * Route C プロセッサ（統合超解像エントリーポイント）
 *
 * EASS パイプラインを使用し、タイルごとに最適なルートで処理する。
 * EASS 初期化に失敗した場合は従来の NcnnSuperResolution にフォールバックする。
 *
 * Phase 6: EASS 統合
 * Phase 7: Real-ESRGAN 接続済み
 */
class RouteCProcessor(private val context: Context) {

    companion object {
        private const val TAG = "RouteCProcessor"
    }

    private var eassPipeline: EASSPipeline? = null
    private var fallbackSr: com.nexus.vision.ncnn.NcnnSuperResolution? = null
    private var useEASS = false

    fun initialize(): Boolean {
        // まず EASS を試す
        eassPipeline = EASSPipeline(context)
        useEASS = eassPipeline?.initialize() == true

        if (useEASS) {
            Log.i(TAG, "EASS Pipeline ready")
            return true
        }

        // EASS 失敗時は従来の NcnnSuperResolution にフォールバック
        Log.w(TAG, "EASS init failed, falling back to NcnnSuperResolution")
        fallbackSr = com.nexus.vision.ncnn.NcnnSuperResolution()
        return fallbackSr?.initialize(context) ?: false
    }

    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()

        if (useEASS && eassPipeline != null) {
            return processWithEASS(bitmap, startTime)
        }

        return processWithFallback(bitmap, startTime)
    }

    private suspend fun processWithEASS(bitmap: Bitmap, startTime: Long): ProcessResult {
        return try {
            // scale=1: 等倍で品質向上。大画像(>128px)は EASS で効率的に処理。
            // scale=4: 4倍拡大。小画像向け。
            val maxSide = maxOf(bitmap.width, bitmap.height)
            val scale = if (maxSide <= 128) 4 else 1

            val result = eassPipeline!!.process(bitmap, scale)
            val elapsed = System.currentTimeMillis() - startTime

            Log.i(TAG, "EASS: ${bitmap.width}x${bitmap.height} → ${result.bitmap.width}x${result.bitmap.height} in ${elapsed}ms")
            Log.i(TAG, "  ${result.summary()}")

            ProcessResult(
                bitmap = result.bitmap,
                method = result.summary(),
                elapsedMs = elapsed,
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "EASS failed: ${e.message}, trying fallback")
            val elapsed = System.currentTimeMillis() - startTime
            // EASS 失敗時にフォールバック
            processWithFallback(bitmap, startTime)
        }
    }

    private suspend fun processWithFallback(bitmap: Bitmap, startTime: Long): ProcessResult {
        val result = fallbackSr?.upscale(bitmap) ?: eassPipeline?.let {
            // fallbackSr も null の場合は再初期化を試みる
            fallbackSr = com.nexus.vision.ncnn.NcnnSuperResolution()
            fallbackSr?.initialize(context)
            fallbackSr?.upscale(bitmap)
        }

        val elapsed = System.currentTimeMillis() - startTime

        return if (result != null) {
            val method = when {
                result.width > bitmap.width -> "NCNN Real-ESRGAN 4× (Vulkan GPU)"
                result.width == bitmap.width -> "Tiled Laplacian Synthesis (周波数分離)"
                else -> "Unsharp Mask シャープ化 (Native)"
            }
            Log.i(TAG, "Fallback: ${bitmap.width}x${bitmap.height} → ${result.width}x${result.height} in ${elapsed}ms [$method]")
            ProcessResult(result, method, elapsed, true)
        } else {
            Log.w(TAG, "All processing failed, returning original")
            ProcessResult(bitmap, "passthrough (failed)", elapsed, false)
        }
    }

    fun release() {
        eassPipeline?.release()
        eassPipeline = null
        fallbackSr?.release()
        fallbackSr = null
        useEASS = false
    }

    data class ProcessResult(
        val bitmap: Bitmap,
        val method: String,
        val elapsedMs: Long,
        val success: Boolean
    ) {
        val timeMs: Long get() = elapsedMs
    }
}
```

---

## ビルド確認手順

1. `EASSPipeline.kt` を `app/src/main/java/com/nexus/vision/image/` に新規作成
2. `RouteCProcessor.kt` を上記の内容で上書き
3. Build → Rebuild Project
4. 実機テスト:
   - 1 枚の画像を「高画質化」で処理 → チャットの結果に「EASS (XXX tiles: A=XX% B=XX% C=XX%, XXXms)」と表示されれば成功
   - A の比率が高いほど処理時間が短くなっている
   - 「バッチ高画質化」でも同じ EASS が使われる（BatchEnhanceWorker が RouteCProcessor を呼んでいるため）

## 注意事項

- **Route C のタイル処理は `RealEsrganBridge.nativeProcess` を直接呼ぶ**。NcnnSuperResolution のタイル分割ロジック（processTiledFusionPipeline）は使わない。EASSPipeline 自身がタイル分割を行うため、二重にタイル分割すると非効率。
- **scale=1 の場合（大画像）**: EASS はタイルの品質を向上させるが拡大はしない。Route C タイルは AI で 4× 拡大後にタイルサイズに縮小して高品質なピクセルを得る。
- **scale=4 の場合（小画像 ≤ 128px）**: タイル数が少ないのでそのまま 4× 拡大。
- `EASSPipeline.initialize()` は内部で `NcnnSuperResolution` を初期化するため、`RouteCProcessor` が `EASSPipeline` と `fallbackSr` の両方を初期化すると GPU リソースが二重確保になる。そのため EASS 成功時は `fallbackSr` を初期化しない設計にしている。