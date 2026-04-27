# NexusVision: 3段融合パイプラインの致命的バグ修正

## 修正するファイル
`app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt` のみ。
他のファイル（C++側、JNI、RouteCProcessor、MainViewModel等）は一切変更しない。

## 現在のバグ（3つ）

### バグ1: AI超解像の結果を即座に捨てている
現在の `processOneTileFusion()`:
```
origTile (512×512) → AI 4× → 2048×2048 → createScaledBitmap(2048→512) → 融合(512×512)
```
AI が 4倍に拡大した 2048×2048 の結果を、512×512 にバイリニア縮小して捨てている。
AI超解像が生成した高周波ディテールの75%が失われる。

### バグ2: IBP の HR/LR が同サイズで恒等変換に退化
fusionPipeline に渡される3画像が全て 512×512:
- original = 512×512 (元タイル)
- aiEnhanced = 512×512 (AI結果を縮小したもの)
- aiLowRes = 512×512 (AI入力 = 元タイルそのもの)

IBP 内部で Downsample(512→512) が恒等変換になり、
結果として X += 0.2*(input - X) という単純ブレンドに退化。
IBP 本来の「解像度スケール間の誤差伝搬補正」が機能していない。

### バグ3: 出力サイズが入力と同じ（解像度が上がらない）
タイルサイズ = SR_MAX_INPUT = 512 のため、タイルがAI入力上限と同じ。
出力キャンバスも入力と同サイズで作られ、解像度向上が一切発生しない。

## 修正方針

### 核心的変更: タイルサイズを小さくし、AI 4×の結果をそのまま使う

```
【修正前】
入力タイル 512px → AI入力 512px → AI出力 2048px → 512pxに縮小 → 融合(512) → 出力=入力サイズ

【修正後】
入力タイル 128px → AI入力 128px → AI出力 512px → 融合(512) → 出力=入力×4サイズ
                                                   ↑          ↑
                                          AI結果をそのまま使う  4倍拡大された出力
```

### 定数変更
```kotlin
// 変更前
private const val SR_MAX_INPUT = 512
private const val PROCESS_TILE = 512
private const val PROCESS_OVERLAP = 32

// 変更後
private const val SR_MAX_INPUT = 128        // AI入力の最大辺（128→AI出力512、MAX_OUTPUT_PIXELS内）
private const val PROCESS_TILE = 128         // 元画像から切り出すタイルサイズ
private const val PROCESS_OVERLAP = 16       // タイル重なり（継ぎ目防止）
private const val MAX_OUTPUT_SIDE = 4096     // 出力画像の最大辺（メモリ安全のため）
```

### 修正後の processTiledFusionPipeline() のフロー

```
入力: bitmap (2172×2896)

Step 1: 出力サイズ計算
  理想出力 = 2172×4 = 8688, 2896×4 = 11584
  MAX_OUTPUT_SIDE = 4096 なので制限
  → 実際の出力倍率を計算:
    scaleFactor = min(4, MAX_OUTPUT_SIDE / max(inputW, inputH))
    = min(4, 4096 / 2896) = min(4, 1.41) = 1.41
  → 出力サイズ = 2172*1.41 × 2896*1.41 = 3063×4083
  
  ★ ただし入力が小さい場合 (例: 800×600):
    scaleFactor = min(4, 4096/800) = min(4, 5.12) = 4
    → 出力 = 3200×2400 (フル4×拡大)

Step 2: AI入力サイズの逆算
  AI出力 = AI入力 × 4
  出力キャンバスの1タイル = PROCESS_TILE × scaleFactor
  AI入力タイル = PROCESS_TILE (≤128)
  AI出力タイル = PROCESS_TILE × 4 = 512

  ★ scaleFactor < 4 の場合:
    AI出力(512×512) を PROCESS_TILE*scaleFactor (例:180×180) にリサイズして書き込む

Step 3: タイルループ
  出力キャンバス: Bitmap.createBitmap(outW, outH, ARGB_8888)
  canvas = Canvas(output)

  step = PROCESS_TILE - PROCESS_OVERLAP  (= 112)
  tilesX, tilesY を計算

  for each tile:
    ① origTile = Bitmap.createBitmap(original, srcLeft, srcTop, tileW, tileH)
       → 最大 128×128 の元画像タイル

    ② srInput = limitSize(origTile, SR_MAX_INPUT)
       → 128≤128 なのでそのまま (縮小なし)

    ③ aiResult = nativeProcess(srInput)
       → 128×128 → AI 4× → 512×512

    ④ original をAI出力サイズにアップスケール
       origUpscaled = createScaledBitmap(origTile, 512, 512, true)
       → バイリニア補間で 128→512

    ⑤ nativeFusionPipeline(origUpscaled, aiResult, srInput)
       - origUpscaled: 512×512  ← original を拡大したもの（高周波ソース）
       - aiResult:     512×512  ← AI超解像結果（低周波ソース）
       - srInput:      128×128  ← 低解像度参照（IBP用、★サイズが異なる）
       
       Stage 1: GuidedFilter(ai=512, orig=512) → guided(512)
       Stage 2: DWT(guided=512, ai=512) → fused(512)
       Stage 3: IBP(fused=512×512, lowRes=128×128, λ=0.2, iter=2)
         - Downsample(512→128): 実際の縮小が発生
         - diff = srInput(128) - downsampled(128): 意味のある残差
         - Upsample(diff 128→512): 実際の拡大が発生
         - fused += 0.2 * correction
         ★ IBP が正しく機能する！

    ⑥ 出力タイルを出力キャンバスに書き込み
       出力位置 = (srcLeft * scaleFactor, srcTop * scaleFactor)
       出力サイズ = tileW * scaleFactor × tileH * scaleFactor
       
       fusedResult(512×512) が出力タイルサイズと異なる場合は
       createScaledBitmap でリサイズしてから描画
```

### 修正後の processOneTileFusion() の引数関係

```
origTile:     128×128  (元画像の1タイル)
srInput:      128×128  (= origTile、AI入力)
aiResult:     512×512  (AI 4× 出力)
origUpscaled: 512×512  (origTile をバイリニア4×拡大)

fusionPipeline(origUpscaled=512, aiResult=512, srInput=128)

C++ 側の引数:
  original:    512×512 (w=512, h=512) ← origUpscaled
  aiEnhanced:  512×512 (w=512, h=512) ← aiResult
  aiLowRes:    128×128 (lrW=128, lrH=128) ← srInput

IBP 内部:
  hrW=512, hrH=512, lrW=128, lrH=128
  Downsample(512→128): 4:1 の実際の縮小
  Upsample(128→512):   1:4 の実際の拡大
  ★ 解像度スケール間の誤差伝搬補正が正しく動作！
```

## メモリ安全性の検証

デバイス: DOOGEE S200 (Dimensity 7050, Mali-G68 MC4, 12GB RAM)
Android の largeHeap でアプリに割当可能: 約 256-512MB

### 1タイル処理時のピークメモリ
- origTile: 128×128×4 = 64KB
- srInput: 128×128×4 = 64KB
- aiResult: 512×512×4 = 1MB
- origUpscaled: 512×512×4 = 1MB
- Kotlin側ピーク: ≈ 2.2MB

C++ fusionPipeline 内部:
- original buffer: 512×512×4 = 1MB
- aiEnhanced buffer: 512×512×4 = 1MB
- aiLowRes buffer: 128×128×4 = 64KB
- output buffer: 512×512×4 = 1MB
- GuidedFilter中間バッファ (128×128 float × 数本): ≈ 400KB
- DWT中間バッファ (512×512 float × 6): ≈ 6MB
- IBP中間バッファ (512×512 float × 3 + 128×128 float × 3): ≈ 3.2MB
- C++側ピーク: ≈ 12.7MB（各Stage完了後にclear+shrink_to_fit済み）

### 出力キャンバス
- 最大 4096×4096×4 = 64MB (MAX_OUTPUT_SIDE制限)
- 入力 2172×2896 → 出力 3063×4083 の場合: ≈ 50MB

### 入力画像
- decodeSafe(4096) でデコードされた入力: 最大 4096×4096×4 = 64MB

### 合計ピーク: ≈ 64(入力) + 50(出力) + 13(処理) = ≈ 127MB → 安全

## 小画像モード（≤512px）の処理

maxSide <= SR_MAX_INPUT(128) の画像は processSuperResolution() で直接4× SR。
128px以下の画像のみが対象。

★ 注意: SR_MAX_INPUT を 128 に変えたので、
  128 < maxSide ≤ 512 の画像もタイルパイプラインに回る。
  例: 300×400 → タイル128で分割 → AI 4× → 出力 1200×1600
  これは正しい動作（解像度が4倍になる）。

## 出力形式

`NcnnSuperResolution.kt` の完全なコードを出力してください。
省略なし。全メソッド、全インポート、全定数を含む。

### 要件チェックリスト
- [ ] PROCESS_TILE = 128, PROCESS_OVERLAP = 16, SR_MAX_INPUT = 128
- [ ] MAX_OUTPUT_SIDE = 4096 定数を追加
- [ ] processTiledFusionPipeline() で出力キャンバスを拡大サイズで作成
- [ ] scaleFactor の計算: min(SCALE, MAX_OUTPUT_SIDE / maxOf(w, h))
- [ ] processOneTileFusion() で origTile を AI出力サイズにアップスケール
- [ ] processOneTileFusion() で aiResult を縮小せずそのまま使う
- [ ] nativeFusionPipeline(origUpscaled=512, aiResult=512, srInput=128) の呼び出し
- [ ] 出力タイルの書き込み位置を scaleFactor で計算
- [ ] 出力タイルサイズが融合結果と異なる場合のリサイズ処理
- [ ] 小画像判定を maxSide <= SR_MAX_INPUT(128) に変更
- [ ] ensureArgb, limitSize ヘルパーは既存のまま
- [ ] initialize, release は既存のまま
- [ ] ログ出力: 入力サイズ、出力サイズ、scaleFactor、タイル数、進捗、経過時間
- [ ] 全てのBitmapの適切なrecycle
- [ ] パッケージ名: com.nexus.vision.ncnn
- [ ] RealEsrganBridge の呼び出しシグネチャ（nativeProcess, nativeFusionPipeline）は変更しない

### 既存のJNI関数シグネチャ（変更不可）
```kotlin
// RealEsrganBridge.kt より
external fun nativeProcess(inputBitmap: Bitmap): Bitmap?  // 4×超解像

external fun nativeFusionPipeline(
    originalBitmap: Bitmap,    // 元画像（高解像度側、W×H）
    aiEnhancedBitmap: Bitmap,  // AI超解像結果（同サイズ W×H）
    aiLowResBitmap: Bitmap     // 低解像度参照（小サイズ lrW×lrH）
): Bitmap?
```

### nativeProcess の制約（realesrgan_jni.cpp より）
```
MAX_OUTPUT_PIXELS = 2048 * 2048 = 4,194,304
入力 128×128 → 出力 512×512 = 262,144 ✓ (上限内)
入力 256×256 → 出力 1024×1024 = 1,048,576 ✓
入力 512×512 → 出力 2048×2048 = 4,194,304 ✓ (ギリギリ)
入力 513×513 → 出力 2052×2052 = 4,210,704 ✗ (上限超過、nullが返る)
```

### 現在の NcnnSuperResolution.kt（これを全面書き換え）
```kotlin
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"
        private const val PARAM_FILE = "models/realesr-animevideov3-x4.param"
        private const val MODEL_FILE = "models/realesr-animevideov3-x4.bin"
        private const val SCALE = 4
        private const val TILE_SIZE = 32
        private const val SR_MAX_INPUT = 512
        private const val PROCESS_TILE = 512
        private const val PROCESS_OVERLAP = 32
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            val result = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "Initialized (Vulkan+FP16, tile=$TILE_SIZE)")
            else Log.e(TAG, "Init failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) {
            Log.e(TAG, "Model not loaded")
            return null
        }
        val maxSide = maxOf(bitmap.width, bitmap.height)
        return if (maxSide <= SR_MAX_INPUT) {
            Log.i(TAG, "Small image: direct 4x SR")
            processSuperResolution(bitmap)
        } else {
            Log.i(TAG, "Large image: Guided+DWT+IBP fusion pipeline")
            processTiledFusionPipeline(bitmap)
        }
    }

    private fun processTiledFusionPipeline(bitmap: Bitmap): Bitmap? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val startTime = System.currentTimeMillis()
            val original = ensureArgb(bitmap) ?: return null
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val step = PROCESS_TILE - PROCESS_OVERLAP
            val tilesX = (w + step - 1) / step
            val tilesY = (h + step - 1) / step
            val totalTiles = tilesX * tilesY
            Log.i(TAG, "Fusion pipeline: ${w}x${h}, tiles=$totalTiles")
            var processed = 0
            var successCount = 0
            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val srcLeft = (tx * step).coerceAtMost(w - 1)
                    val srcTop = (ty * step).coerceAtMost(h - 1)
                    val srcRight = (srcLeft + PROCESS_TILE).coerceAtMost(w)
                    val srcBottom = (srcTop + PROCESS_TILE).coerceAtMost(h)
                    val tileW = srcRight - srcLeft
                    val tileH = srcBottom - srcTop
                    if (tileW < 16 || tileH < 16) continue
                    val origTile = Bitmap.createBitmap(original, srcLeft, srcTop, tileW, tileH)
                    val processedTile = processOneTileFusion(origTile)
                    if (processedTile != null) {
                        canvas.drawBitmap(processedTile, null,
                            Rect(srcLeft, srcTop, srcRight, srcBottom), null)
                        processedTile.recycle()
                        successCount++
                    } else {
                        canvas.drawBitmap(origTile, null,
                            Rect(srcLeft, srcTop, srcRight, srcBottom), null)
                    }
                    origTile.recycle()
                    processed++
                    if (processed % 4 == 0 || processed == totalTiles) {
                        Log.i(TAG, "Progress: $processed/$totalTiles")
                    }
                }
            }
            if (original !== bitmap) original.recycle()
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Fusion done: ${w}x${h}, $successCount/$totalTiles tiles, ${elapsed}ms")
            output
        } catch (e: Exception) {
            Log.e(TAG, "Fusion pipeline error: ${e.message}")
            null
        }
    }

    private fun processOneTileFusion(origTile: Bitmap): Bitmap? {
        return try {
            val tileW = origTile.width
            val tileH = origTile.height
            val srInput = limitSize(origTile, SR_MAX_INPUT)
            val srArgb = ensureArgb(srInput) ?: return null
            val aiResult = RealEsrganBridge.nativeProcess(srArgb)
            if (aiResult == null) {
                if (srArgb !== srInput) srArgb.recycle()
                if (srInput !== origTile) srInput.recycle()
                return null
            }
            val aiUpscaled = if (aiResult.width != tileW || aiResult.height != tileH) {
                val scaled = Bitmap.createScaledBitmap(aiResult, tileW, tileH, true)
                aiResult.recycle()
                ensureArgb(scaled) ?: return null
            } else {
                ensureArgb(aiResult) ?: return null
            }
            val origArgb = ensureArgb(origTile) ?: run {
                aiUpscaled.recycle()
                if (srArgb !== srInput) srArgb.recycle()
                if (srInput !== origTile) srInput.recycle()
                return null
            }
            val fused = RealEsrganBridge.nativeFusionPipeline(origArgb, aiUpscaled, srArgb)
            if (origArgb !== origTile) origArgb.recycle()
            aiUpscaled.recycle()
            if (srArgb !== srInput) srArgb.recycle()
            if (srInput !== origTile) srInput.recycle()
            fused
        } catch (e: Exception) {
            Log.e(TAG, "Tile fusion error: ${e.message}")
            null
        }
    }

    private fun processSuperResolution(bitmap: Bitmap): Bitmap? {
        return try {
            val argb = ensureArgb(bitmap) ?: return null
            Log.i(TAG, "SR: ${argb.width}x${argb.height} -> ${argb.width * SCALE}x${argb.height * SCALE}")
            val result = RealEsrganBridge.nativeProcess(argb)
            if (argb !== bitmap) argb.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "SR error: ${e.message}")
            null
        }
    }

    fun release() {
        try { RealEsrganBridge.nativeRelease() } catch (e: Exception) { Log.e(TAG, "Release: ${e.message}") }
        initialized = false
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap? {
        return if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap
    }

    private fun limitSize(bitmap: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / max
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(16)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(16)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
```

## 出力
`NcnnSuperResolution.kt` の完全な書き換え後のコードを1ファイルで出力してください。
ファイルパスをコメントで先頭に記述。省略なし。
