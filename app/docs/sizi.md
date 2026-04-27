Now I have complete knowledge of the codebase. Let me write the comprehensive implementation.

---

現在のコードベース全体を精査しました。以下に、**Guided Filter + Haar DWT Fusion + IBP** の3段パイプラインを現在のプロジェクトに完全統合するための変更内容と、コード生成AIに渡すプロンプトを提示します。

---

## コード生成AIに渡すプロンプト

以下をそのままコピーしてコード生成AI（Claude, Cursor, etc.）に貼り付けてください。

---

````markdown
# NexusVision: Guided Filter + DWT Fusion + IBP 統合パイプライン実装

## プロジェクト概要
Android (Kotlin + NDK/C++) の画像超解像アプリ。Real-ESRGAN (ncnn Vulkan) で4×超解像し、
元画像とAI結果を高品質に融合する。現在のラプラシアンピラミッド合成は元画像のセンサーノイズを
再注入してしまうため、以下の3段階パイプラインに置き換える。

## 新パイプライン（数学的根拠）

```
入力: original (W×H), ai_enhanced (W×H, AI超解像を元サイズにリサイズ済み)

Stage 1: Guided Filter Edge Transfer (He & Sun, TPAMI 2013)
  - guidance = ai_enhanced, input = original
  - ウィンドウ ωk 内で: Ak = cov(G,I)/(var(G)+ε), Bk = μI - Ak·μG
  - 出力: Oi = mean(A)·Gi + mean(B)
  - Fast版: s=4でサブサンプリング → O(N/16) 計算量
  → guided_result (ノイズ除去 + AIエッジ転写)

Stage 2: Haar DWT Frequency Fusion
  - DWT(guided_result) → LL_g, LH_g, HL_g, HH_g
  - DWT(ai_enhanced)   → LL_a, LH_a, HL_a, HH_a
  - LL_out = LL_a (AIのクリーンな低周波)
  - LH_out = max(|LH_g|,|LH_a|) の符号付き選択 (水平エッジ)
  - HL_out = max(|HL_g|,|HL_a|) の符号付き選択 (垂直エッジ)
  - HH_out = HH_a (ノイズ排除: 元画像のHHは捨てる)
  - IDWT → dwt_fused

Stage 3: Iterative Back-Projection (IBP, 2反復)
  - lowRes = original を512pxに縮小したもの (AI入力と同じ)
  - X₀ = dwt_fused
  - X_{t+1} = Xt + λ · Upsample(lowRes - Downsample(Xt))
  - λ=0.2, iterations=2
  → final_output (元サイズ W×H)
```

## 現在のファイル構成と変更方針

変更するファイルは4つ + 新規1つ:

### 1. 新規: `app/src/main/jni/image_fusion.h` (新ファイル)
### 2. 新規: `app/src/main/jni/image_fusion.cpp` (新ファイル)
### 3. 変更: `app/src/main/jni/realesrgan_jni.cpp` (JNI関数追加)
### 4. 変更: `app/src/main/jni/CMakeLists.txt` (ソース追加)
### 5. 変更: `app/src/main/java/com/nexus/vision/ncnn/RealEsrganBridge.kt` (JNI宣言追加)
### 6. 変更: `app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt` (パイプライン書換)

RouteCProcessor.kt と MainViewModel.kt は変更不要（インターフェース不変）。

## 既存コード（変更しないもの、参照用）

### realesrgan_simple.h（変更なし）
```cpp
// ファイルパス: app/src/main/jni/realesrgan_simple.h
#ifndef REALESRGAN_SIMPLE_H
#define REALESRGAN_SIMPLE_H

#include <string>
#include <net.h>

class RealESRGANSimple {
public:
    RealESRGANSimple(int gpuid = -1);
    ~RealESRGANSimple();

    int load(const unsigned char* paramBuffer, int paramLen,
             const unsigned char* modelBuffer, int modelLen);
    int load(const std::string& paramPath, const std::string& modelPath);

    int process(const unsigned char* inputPixels, int w, int h,
                unsigned char* outputPixels);

    static int sharpen(const unsigned char* inputPixels, int w, int h,
                       unsigned char* outputPixels, float strength);

    // ★ laplacianBlend は残すが使わなくなる（後方互換）
    static int laplacianBlend(const unsigned char* originalPixels,
                              const unsigned char* enhancedPixels,
                              int w, int h,
                              unsigned char* outputPixels,
                              float detailStrength,
                              float sharpenStrength);

    bool isLoaded() const { return loaded; }

    int scale = 4;
    int tileSize = 32;
    int prepadding = 10;

private:
    ncnn::Net net;
    bool loaded;
    int gpuid;
    bool useGpu;
};

#endif
```

### RealEsrganBridge.kt（現在の状態）
```kotlin
package com.nexus.vision.ncnn

import android.content.res.AssetManager
import android.graphics.Bitmap

object RealEsrganBridge {
    init { System.loadLibrary("realesrgan_native") }

    external fun nativeInit(assetManager: AssetManager, paramPath: String,
                            modelPath: String, scale: Int, tileSize: Int): Boolean
    external fun nativeProcess(inputBitmap: Bitmap): Bitmap?
    external fun nativeSharpen(inputBitmap: Bitmap, strength: Float): Bitmap?
    external fun nativeLaplacianBlend(originalBitmap: Bitmap, enhancedBitmap: Bitmap,
                                      detailStrength: Float, sharpenStrength: Float): Bitmap?
    external fun nativeRelease()
    external fun nativeIsLoaded(): Boolean
}
```

### RouteCProcessor.kt（変更なし）
```kotlin
package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class RouteCProcessor(private val context: Context) {
    companion object { private const val TAG = "RouteCProcessor" }
    private var sr: com.nexus.vision.ncnn.NcnnSuperResolution? = null

    fun initialize(): Boolean {
        sr = com.nexus.vision.ncnn.NcnnSuperResolution()
        return sr?.initialize(context) ?: false
    }

    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()
        val result = sr?.upscale(bitmap)
        val elapsed = System.currentTimeMillis() - startTime
        return if (result != null) {
            val method = when {
                result.width > bitmap.width -> "NCNN Real-ESRGAN 4× (Vulkan GPU)"
                result.width == bitmap.width && result.height == bitmap.height ->
                    "GuidedFilter+DWT+IBP Fusion"
                else -> "Unsharp Mask シャープ化 (Native)"
            }
            Log.i(TAG, "Success: ${bitmap.width}x${bitmap.height} -> " +
                  "${result.width}x${result.height} in ${elapsed}ms [$method]")
            ProcessResult(result, method, elapsed, true)
        } else {
            Log.w(TAG, "Failed, returning original")
            ProcessResult(bitmap, "passthrough (failed)", elapsed, false)
        }
    }

    fun release() { sr?.release(); sr = null }

    data class ProcessResult(val bitmap: Bitmap, val method: String,
                             val elapsedMs: Long, val success: Boolean) {
        val timeMs: Long get() = elapsedMs
    }
}
```

### CMakeLists.txt（現在の状態）
```cmake
cmake_minimum_required(VERSION 3.22)
project(realesrgan_native CXX)
set(CMAKE_CXX_STANDARD 17)
set(ncnn_DIR "${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)
find_library(JNIGRAPHICS_LIB jnigraphics)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
add_library(realesrgan_native SHARED
    realesrgan_simple.cpp
    realesrgan_jni.cpp
)
target_link_libraries(realesrgan_native ncnn ${JNIGRAPHICS_LIB} ${LOG_LIB} ${ANDROID_LIB})
```

## 重要な制約条件

1. **Android NDK はデフォルトで `-fno-exceptions`**: try-catch は絶対に使わない。エラーはreturn値で処理。
2. **外部ライブラリ不可**: OpenCV, Eigen等は使えない。純粋C++17 + Android NDK + ncnn のみ。
3. **メモリ制約**: DOOGEE S200 (6GB RAM, Mali-G68 MC4)。1枚のビットマップは最大 4096×4096 ARGB_8888 (≈64MB)。
   中間バッファは最小限に。float配列はチャンネル別に確保し、使い終わったらすぐ解放 (vector::clear + shrink_to_fit)。
4. **入力画像サイズ**: `NcnnSuperResolution.kt` から呼ばれる時点で最大 4096×4096。
   AI超解像への入力は最大512px (4×で2048px出力)。最終出力は元入力と同サイズ。
5. **タイル処理**: 大きい画像 (>512px) は PROCESS_TILE=512, PROCESS_OVERLAP=32 でタイル分割して
   タイルごとに3段パイプラインを適用。出力サイズ＝入力サイズ。
6. **JNI関数のシグネチャ**: パッケージ名は `com.nexus.vision.ncnn.RealEsrganBridge`。
   JNI関数名は `Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeFusionPipeline`。

---

## 出力してほしいファイル（全6ファイルの完全なコード）

### ファイル1: `app/src/main/jni/image_fusion.h`

新規ファイル。以下の関数を宣言:

```cpp
#ifndef IMAGE_FUSION_H
#define IMAGE_FUSION_H
#include <cstdint>

namespace ImageFusion {

// Stage 1: Fast Guided Filter (He & Sun 2013)
// guidance/input/output: RGBA uint8 バッファ (w*h*4 bytes)
// r: フィルタ半径 (推奨: 8)
// eps: 正規化パラメータ (推奨: 0.01, [0,1]スケールで)
// s: サブサンプリング比 (推奨: 4)
// 戻り値: 0=成功, -1=失敗
int guidedFilter(const uint8_t* guidance, const uint8_t* input,
                 int w, int h, uint8_t* output,
                 int r, float eps, int s);

// Stage 2: Haar DWT Fusion
// img1: guided filter結果, img2: AI enhanced (共にRGBA, 同サイズ)
// output: RGBA (同サイズ)
// w, h は偶数であること (奇数の場合は内部で-1して処理、最後の行/列はコピー)
int dwtFusion(const uint8_t* img1, const uint8_t* img2,
              int w, int h, uint8_t* output);

// Stage 3: Iterative Back-Projection
// highRes: 高解像度推定 (RGBA, hrW*hrH), IN-PLACEで更新
// lowRes: 低解像度参照 (RGBA, lrW*lrH)
// lambda: 更新ステップ (推奨: 0.2)
// iterations: 反復回数 (推奨: 2)
int iterativeBackProjection(uint8_t* highRes, int hrW, int hrH,
                            const uint8_t* lowRes, int lrW, int lrH,
                            float lambda, int iterations);

// 3段統合パイプライン
// original: 元画像RGBA (w*h)
// aiEnhanced: AI超解像をwxhにリサイズ済みRGBA
// aiLowRes: AI入力(512px版) RGBA (lrW*lrH)
// output: 出力RGBA (w*h)
int fusionPipeline(const uint8_t* original, const uint8_t* aiEnhanced,
                   int w, int h,
                   const uint8_t* aiLowRes, int lrW, int lrH,
                   uint8_t* output);

} // namespace ImageFusion
#endif
```

### ファイル2: `app/src/main/jni/image_fusion.cpp`

完全な実装。以下の詳細仕様に従うこと:

**guidedFilter の実装 (Fast Guided Filter, Algorithm 2 from He 2015):**
1. guidance I と input p をサブサンプリング (s=4, bilinear) → I', p' (サイズ w/s × h/s)
2. r' = r / s
3. Box filter (積分画像で O(1) 実装) を使って:
   - meanI = boxFilter(I', r')
   - meanP = boxFilter(p', r')
   - corrI = boxFilter(I'*I', r')
   - corrIP = boxFilter(I'*p', r')
4. varI = corrI - meanI*meanI
5. covIP = corrIP - meanI*meanP
6. a = covIP / (varI + eps)
7. b = meanP - a*meanI
8. meanA = boxFilter(a, r')
9. meanB = boxFilter(b, r')
10. meanA, meanB を bilinear upsample して元サイズ w×h へ
11. output = meanA * I_fullres + meanB
12. 各チャンネル (R,G,B) 独立に処理。Alpha は original からコピー。
13. Box filter は積分画像 (integral image / summed area table) で実装し O(1)。
    integralImage[y][x] = sum of all pixels in [0,0]-[x,y]。
    boxSum(x0,y0,x1,y1) = I[y1][x1] - I[y0-1][x1] - I[y1][x0-1] + I[y0-1][x0-1]。

**dwtFusion の実装 (2D Haar DWT):**
1. 各チャンネル (R,G,B) を float [0,255] に変換。
2. 2D Haar DWT (1レベル):
   - 行方向: 各行について、偶数/奇数ペアから LL=(a+b)/2, HL=(a-b)/2 を計算。出力は左半分にLL、右半分にHL。
   - 列方向: 上記結果に対し各列で同様。上半分にLL/LH、下半分にHL/HH。
   結果: 4サブバンド LL(左上), LH(右上), HL(左下), HH(右下)。各 w/2 × h/2。
3. 融合ルール:
   - LL: ai_enhanced のLL を採用 (クリーンな構造)
   - LH: |img1_LH| >= |img2_LH| なら img1_LH, そうでなければ img2_LH (強いエッジ優先)
   - HL: 同上
   - HH: ai_enhanced のHH を採用 (ノイズ排除)
4. 逆2D Haar DWT (IDWT):
   - 列方向逆: LL+HL, LL-HL で偶数行/奇数行を復元
   - 行方向逆: 同様
5. [0,255] にクランプして uint8 に変換。

**iterativeBackProjection の実装:**
1. Downsample: highRes (hrW×hrH) → temp (lrW×lrH) をbilinear補間で縮小。
2. Residual: diff[i] = lowRes[i] - temp[i] (各チャンネル, float演算)
3. Upsample: diff (lrW×lrH) → correction (hrW×hrH) をbilinear補間で拡大。
4. Update: highRes[i] = clamp(highRes[i] + lambda * correction[i], 0, 255)
5. 上記を iterations 回繰り返す。

**fusionPipeline:**
1. Stage 1: guidedFilter(aiEnhanced, original, w, h, guided_buf, 8, 0.01f, 4)
2. Stage 2: dwtFusion(guided_buf, aiEnhanced, w, h, fused_buf)
3. Stage 3: iterativeBackProjection(fused_buf, w, h, aiLowRes, lrW, lrH, 0.2f, 2)
4. memcpy(output, fused_buf, w*h*4)
5. 中間バッファは vector で確保し、各stage完了後に clear()+shrink_to_fit() でメモリ解放。

**メモリ最適化:**
- float バッファはチャンネル1つずつ処理する場合は w*h*sizeof(float)。
- Guided Filterのサブサンプリング版は (w/4)*(h/4) サイズで動作するためメモリ効率的。
- DWT は in-place でなく別バッファ使用 (安全のため)。
- ログは `__android_log_print(ANDROID_LOG_INFO, "ImageFusion", ...)` を使用。
  各Stage の開始/完了とミリ秒を出力。

### ファイル3: `app/src/main/jni/realesrgan_jni.cpp`

既存コードに以下のJNI関数を**追加** (既存関数は全て残す):

```cpp
JNIEXPORT jobject JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeFusionPipeline(
    JNIEnv* env, jclass clazz,
    jobject originalBitmap,    // 元画像 (W×H ARGB_8888)
    jobject aiEnhancedBitmap,  // AI結果を元サイズにリサイズ済み (W×H ARGB_8888)
    jobject aiLowResBitmap     // AI入力の低解像度版 (lrW×lrH ARGB_8888)
)
```

処理内容:
1. 3つのBitmapからピクセルデータを取得 (lockPixels, stride考慮コピー, unlockPixels)
2. 出力バッファを確保
3. `ImageFusion::fusionPipeline()` を呼び出し
4. 結果から新しいBitmap (W×H ARGB_8888) を作成して返す
5. 失敗時は nullptr を返す

### ファイル4: `app/src/main/jni/CMakeLists.txt`

image_fusion.cpp を追加:

```cmake
cmake_minimum_required(VERSION 3.22)
project(realesrgan_native CXX)
set(CMAKE_CXX_STANDARD 17)
set(ncnn_DIR "${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)
find_library(JNIGRAPHICS_LIB jnigraphics)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
add_library(realesrgan_native SHARED
    realesrgan_simple.cpp
    realesrgan_jni.cpp
    image_fusion.cpp
)
target_link_libraries(realesrgan_native ncnn ${JNIGRAPHICS_LIB} ${LOG_LIB} ${ANDROID_LIB})
```

### ファイル5: `app/src/main/java/com/nexus/vision/ncnn/RealEsrganBridge.kt`

既存の宣言は全て残し、以下を追加:

```kotlin
// 3段融合パイプライン (Guided Filter + DWT + IBP)
external fun nativeFusionPipeline(
    originalBitmap: Bitmap,    // 元画像
    aiEnhancedBitmap: Bitmap,  // AI超解像→元サイズにリサイズ
    aiLowResBitmap: Bitmap     // AI入力の低解像度版
): Bitmap?
```

### ファイル6: `app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt`

完全に書き換え。以下の設計:

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

        // AI超解像の入力最大サイズ
        private const val SR_MAX_INPUT = 512

        // タイル処理パラメータ
        private const val PROCESS_TILE = 512
        private const val PROCESS_OVERLAP = 32
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        // 既存と同じ
    }

    /**
     * メイン超解像メソッド
     * ≤512px: 直接4× SR
     * >512px: タイル方式 3段融合パイプライン (出力=入力と同サイズ)
     */
    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !RealEsrganBridge.nativeIsLoaded()) return null
        val maxSide = maxOf(bitmap.width, bitmap.height)
        return if (maxSide <= SR_MAX_INPUT) {
            processSuperResolution(bitmap)
        } else {
            processTiledFusionPipeline(bitmap)
        }
    }

    /**
     * タイル方式3段融合パイプライン
     * 各タイル(512×512)ごとに:
     *   1. origTile = 元解像度タイル切り出し
     *   2. srInput = origTile を ≤512に縮小
     *   3. aiResult = nativeProcess(srInput) → 4×拡大
     *   4. aiUpscaled = aiResult を origTile サイズにリサイズ
     *   5. result = nativeFusionPipeline(origTile, aiUpscaled, srInput)
     *   6. 出力キャンバスに書き込み
     */
    private fun processTiledFusionPipeline(bitmap: Bitmap): Bitmap? {
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
        return output
    }

    /**
     * 1タイルの3段融合処理
     */
    private fun processOneTileFusion(origTile: Bitmap): Bitmap? {
        val tileW = origTile.width
        val tileH = origTile.height

        // Step 1: 縮小してAI入力を作る
        val srInput = limitSize(origTile, SR_MAX_INPUT)
        val srArgb = ensureArgb(srInput) ?: return null

        // Step 2: AI 4× 超解像
        val aiResult = RealEsrganBridge.nativeProcess(srArgb)
        if (aiResult == null) {
            if (srArgb !== srInput) srArgb.recycle()
            if (srInput !== origTile) srInput.recycle()
            return null
        }

        // Step 3: AI結果を元タイルサイズにリサイズ
        val aiUpscaled = if (aiResult.width != tileW || aiResult.height != tileH) {
            val scaled = Bitmap.createScaledBitmap(aiResult, tileW, tileH, true)
            aiResult.recycle()
            ensureArgb(scaled) ?: return null
        } else {
            ensureArgb(aiResult) ?: return null
        }

        // Step 4: nativeFusionPipeline(origTile, aiUpscaled, srArgb)
        //   srArgb = AI入力 (低解像度版) → IBP のリファレンス
        val origArgb = ensureArgb(origTile) ?: run {
            aiUpscaled.recycle()
            srArgb.recycle()
            return null
        }

        val fused = RealEsrganBridge.nativeFusionPipeline(origArgb, aiUpscaled, srArgb)

        // クリーンアップ
        if (origArgb !== origTile) origArgb.recycle()
        aiUpscaled.recycle()
        if (srArgb !== srInput) srArgb.recycle()
        if (srInput !== origTile) srInput.recycle()

        return fused
    }

    // processSuperResolution, release, ensureArgb, limitSize は既存と同じ
    private fun processSuperResolution(bitmap: Bitmap): Bitmap? {
        val argb = ensureArgb(bitmap) ?: return null
        Log.i(TAG, "SR: ${argb.width}x${argb.height} -> ${argb.width*SCALE}x${argb.height*SCALE}")
        val result = RealEsrganBridge.nativeProcess(argb)
        if (argb !== bitmap) argb.recycle()
        return result
    }

    fun release() {
        RealEsrganBridge.nativeRelease()
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

## 出力フォーマット

各ファイルについて:
1. ファイルパスをコメントで先頭に記述
2. 完全なコードを出力（省略なし）
3. 全てのインクルード/インポートを含める
4. Android NDK の制約を守る（-fno-exceptions: try-catchなし）
5. ログ出力を適切に含める（各Stageの開始/完了/時間）

全6ファイルの完全なコードを出力してください。
````

---

## プロンプトの補足説明

このプロンプトが正確に動作する理由を以下にまとめます。

**既存コードとの接続点が明確**

NcnnSuperResolution.kt の `upscale()` メソッドのシグネチャ（`Bitmap → Bitmap?`）は変わりません。RouteCProcessor.kt がこのメソッドを呼び出す部分もそのまま動きます。MainViewModel.kt も変更不要です。変更の影響範囲を最小化し、C++ 層に新しい `image_fusion.cpp` を追加して JNI 経由で繋ぐだけの設計です。

**メモリ安全性の根拠**

タイル 512×512 の場合の各Stage のピーク使用量を計算すると、Guided Filter では サブサンプリング後 128×128 の float バッファ 6本（meanI, meanP, corrI, corrIP, a, b）で約 400KB。DWT では 512×512 の float バッファ 6本（2画像×3ch）で約 6MB。IBP では 512×512 と 128×128 の float バッファ各3本で約 3.2MB。fusionPipeline 全体のピークは約 10MB 以下であり、DOOGEE S200 の 6GB RAM で十分余裕があります。

**数学的正確性**

Guided Filter は He & Sun (TPAMI 2013, arXiv 1505.00996) の Algorithm 2 に完全準拠しています。Haar DWT は標準的な行→列の分離型実装で、融合ルールは IEEE の画像融合研究で広く使われる max-absolute-value 選択法です。IBP は Irani & Peleg (1991) の古典的手法で、2回反復・λ=0.2 は収束と品質のバランスが取れた設定です。

**`-fno-exceptions` 対応**

プロンプト内で明示的に「try-catch は絶対に使わない」と指定しています。全てのエラーは return 値（0=成功, 負値=失敗）で処理し、呼び出し側で分岐します。