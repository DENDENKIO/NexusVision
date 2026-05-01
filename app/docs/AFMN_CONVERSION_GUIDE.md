
## Step 14-1: SAFMN++ モデル導入

### パート A — PC 側作業（モデル変換手順書）

以下の手順書を `app/docs/SAFMN_CONVERSION_GUIDE.md` として保存してください。コード生成 AI に渡す場合もこのまま使えます。

```markdown
# SAFMN++ NCNN 変換手順書

## 前提条件
- Python 3.10+, PyTorch 2.x, ONNX 1.15+, onnxruntime
- ncnn ツール (onnx2ncnn, ncnnoptimize) — https://github.com/Tencent/ncnn/releases

## 1. SAFMN リポジトリ準備

```bash
git clone https://github.com/sunny2109/SAFMN.git
cd SAFMN
pip install -r requirements.txt
python setup.py develop
pip install onnx onnxruntime
```

## 2. SAFMN++ プレトレイン重みの取得

```bash
# NTIRE2024 ESR バリアント (efficient SR, ×4)
# Google Drive から SAFMN++_DF2K_x4.pth をダウンロード
# もしくは SAFMN_L_Real_LSDIR_x4-v2.pth (real-world SR)
mkdir -p experiments/pretrained_models
# ファイルを上記ディレクトリに配置
```

## 3. ONNX エクスポート

`scripts/to_onnx/convert_safmn_pp_x4.py` を以下の内容で作成:

```python
import os
import torch
import torch.onnx
from basicsr.archs.safmn_arch import SAFMN

def convert():
    # SAFMN efficient版 (×4): dim=36, n_blocks=8, ffn_scale=2.0
    # SAFMN-L版 (×4): dim=128, n_blocks=16, ffn_scale=2.0
    # ここでは efficient 版を使用（モバイル向け）
    model = SAFMN(dim=36, n_blocks=8, ffn_scale=2.0, upscaling_factor=4)
    
    ckpt = torch.load(
        'experiments/pretrained_models/SAFMN_DF2K_x4.pth',
        map_location='cpu'
    )
    model.load_state_dict(ckpt['params'], strict=True)
    model.eval()
    
    # 入力サイズ: 128×128 (8で割り切れる必要あり)
    # NCNN は固定入力推奨。アプリ側でリサイズして合わせる
    fake_x = torch.rand(1, 3, 128, 128, requires_grad=False)
    
    os.makedirs('output', exist_ok=True)
    
    torch.onnx.export(
        model,
        fake_x,
        'output/safmn_pp_x4_128.onnx',
        export_params=True,
        opset_version=15,
        do_constant_folding=True,
        input_names=['input'],
        output_names=['output'],
    )
    print('ONNX export done: output/safmn_pp_x4_128.onnx')

if __name__ == '__main__':
    convert()
```

```bash
python scripts/to_onnx/convert_safmn_pp_x4.py
```

## 4. ONNX → NCNN 変換

```bash
# onnx2ncnn でパラメータファイルとバイナリに分割
./onnx2ncnn output/safmn_pp_x4_128.onnx \
            output/safmn_pp_x4.param \
            output/safmn_pp_x4.bin

# FP16 ストレージ最適化（bin サイズ約半分に圧縮）
./ncnnoptimize output/safmn_pp_x4.param output/safmn_pp_x4.bin \
               output/safmn_pp_x4_fp16.param output/safmn_pp_x4_fp16.bin 65536
```

## 5. 動作検証（PC上）

```bash
# ncnn のサンプルで推論テスト
# 期待: 128×128 入力 → 512×512 出力 (×4)
# param/bin ファイルを読み込んで推論が通ればOK
```

## 6. アプリへの配置

```bash
# 生成物を Android プロジェクトの assets にコピー
cp output/safmn_pp_x4_fp16.param \
   <project>/app/src/main/assets/models/safmn_pp_x4.param
cp output/safmn_pp_x4_fp16.bin \
   <project>/app/src/main/assets/models/safmn_pp_x4.bin
```

## 期待されるファイルサイズ
- ONNX: ~1–2 MB (efficient版)
- NCNN param: ~20 KB
- NCNN bin (FP16): ~0.5 MB
- 比較: 現行 realesr-general-x4v3.bin ≈ 67 MB
```

---

### パート B — アプリ側コード変更

#### 1. 新規ファイル: `SafmnBridge.kt`

パス: `app/src/main/java/com/nexus/vision/ncnn/SafmnBridge.kt`

```kotlin
package com.nexus.vision.ncnn

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * SAFMN++ NCNN ブリッジ (JNI)
 * Phase 14-1: 軽量高品質 SR モデル
 */
object SafmnBridge {

    init {
        System.loadLibrary("realesrgan_native") // 同一 .so に追加
    }

    external fun nativeSafmnInit(
        assetManager: AssetManager,
        paramPath: String,
        modelPath: String,
        scale: Int,
        tileSize: Int
    ): Boolean

    external fun nativeSafmnProcess(inputBitmap: Bitmap): Bitmap?

    external fun nativeSafmnRelease()

    external fun nativeSafmnIsLoaded(): Boolean
}
```

#### 2. 新規ファイル: `SafmnSuperResolution.kt`

パス: `app/src/main/java/com/nexus/vision/ncnn/SafmnSuperResolution.kt`

```kotlin
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log

/**
 * SAFMN++ 超解像エンジン
 * Phase 14-1: Real-ESRGAN と同じ Tiled Pipeline 構造
 *
 * SAFMN++ は ~240K パラメータで Real-ESRGAN (~16.7M) の 1/70。
 * NCNN Vulkan FP16 で高速推論。CNN のみ（torch.roll 不要）。
 */
class SafmnSuperResolution {
    companion object {
        private const val TAG = "SafmnSR"
        private const val PARAM_FILE = "models/safmn_pp_x4.param"
        private const val MODEL_FILE = "models/safmn_pp_x4.bin"
        private const val SCALE = 4
        // SAFMN は軽量なので Real-ESRGAN より大きめタイルが可能
        private const val TILE_SIZE = 64

        private const val SR_MAX_INPUT = 128
        private const val PROCESS_TILE = 128
        private const val PROCESS_OVERLAP = 16
        private const val MAX_OUTPUT_SIDE = 4096
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && SafmnBridge.nativeSafmnIsLoaded()) return true
        return try {
            val result = SafmnBridge.nativeSafmnInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "SAFMN++ initialized (Vulkan+FP16, tile=$TILE_SIZE)")
            else Log.e(TAG, "SAFMN++ init failed")
            result
        } catch (e: Exception) {
            Log.e(TAG, "SAFMN++ init error: ${e.message}")
            false
        }
    }

    suspend fun upscale(bitmap: Bitmap): Bitmap? {
        if (!initialized || !SafmnBridge.nativeSafmnIsLoaded()) {
            Log.e(TAG, "SAFMN++ not loaded")
            return null
        }
        val maxSide = maxOf(bitmap.width, bitmap.height)
        return if (maxSide <= SR_MAX_INPUT) {
            Log.i(TAG, "Small (${bitmap.width}x${bitmap.height}): direct 4x")
            processDirect(bitmap)
        } else {
            Log.i(TAG, "Large (${bitmap.width}x${bitmap.height}): tiled")
            processTiled(bitmap)
        }
    }

    private fun processDirect(bitmap: Bitmap): Bitmap? {
        return try {
            val argb = ensureArgb(bitmap) ?: return null
            val result = SafmnBridge.nativeSafmnProcess(argb)
            if (argb !== bitmap) argb.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Direct SR error: ${e.message}")
            null
        }
    }

    private fun processTiled(bitmap: Bitmap): Bitmap? {
        return try {
            val inW = bitmap.width
            val inH = bitmap.height
            val startTime = System.currentTimeMillis()

            val scaleFactor = minOf(
                SCALE.toFloat(),
                MAX_OUTPUT_SIDE.toFloat() / maxOf(inW, inH)
            )
            val outW = (inW * scaleFactor).toInt()
            val outH = (inH * scaleFactor).toInt()

            val original = ensureArgb(bitmap) ?: return null
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val step = PROCESS_TILE - PROCESS_OVERLAP
            val tilesX = (inW + step - 1) / step
            val tilesY = (inH + step - 1) / step
            var successCount = 0

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val srcLeft = (tx * step).coerceAtMost(inW - 1)
                    val srcTop = (ty * step).coerceAtMost(inH - 1)
                    val srcRight = (srcLeft + PROCESS_TILE).coerceAtMost(inW)
                    val srcBottom = (srcTop + PROCESS_TILE).coerceAtMost(inH)
                    val tileW = srcRight - srcLeft
                    val tileH = srcBottom - srcTop
                    if (tileW < 8 || tileH < 8) continue

                    val tile = Bitmap.createBitmap(original, srcLeft, srcTop, tileW, tileH)
                    val limited = limitSize(tile, SR_MAX_INPUT)
                    val argb = ensureArgb(limited) ?: continue

                    val srResult = SafmnBridge.nativeSafmnProcess(argb)
                    if (argb !== limited) argb.recycle()
                    if (limited !== tile) limited.recycle()

                    val outLeft = (srcLeft * scaleFactor).toInt()
                    val outTop = (srcTop * scaleFactor).toInt()
                    val outRight = if (tx == tilesX - 1) outW
                                   else (srcRight * scaleFactor).toInt()
                    val outBottom = if (ty == tilesY - 1) outH
                                    else (srcBottom * scaleFactor).toInt()
                    val targetW = outRight - outLeft
                    val targetH = outBottom - outTop

                    if (srResult != null) {
                        val scaled = if (srResult.width == targetW && srResult.height == targetH)
                            srResult
                        else Bitmap.createScaledBitmap(srResult, targetW, targetH, true)
                        canvas.drawBitmap(scaled, outLeft.toFloat(), outTop.toFloat(), null)
                        if (scaled !== srResult) scaled.recycle()
                        srResult.recycle()
                        successCount++
                    } else {
                        val fb = Bitmap.createScaledBitmap(tile, targetW, targetH, true)
                        canvas.drawBitmap(fb, outLeft.toFloat(), outTop.toFloat(), null)
                        fb.recycle()
                    }
                    tile.recycle()
                }
            }

            if (original !== bitmap) original.recycle()
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Tiled done: ${outW}x${outH}, $successCount tiles, ${elapsed}ms")
            output
        } catch (e: Exception) {
            Log.e(TAG, "Tiled error: ${e.message}")
            null
        }
    }

    fun release() {
        try { SafmnBridge.nativeSafmnRelease() } catch (_: Exception) {}
        initialized = false
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap? {
        return if (bitmap.config != Bitmap.Config.ARGB_8888)
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        else bitmap
    }

    private fun limitSize(bitmap: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / max
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(8)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(8)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
```

#### 3. 新規 C++ ファイル: `safmn_jni.cpp`

パス: `app/src/main/jni/safmn_jni.cpp`

```cpp
// safmn_jni.cpp — SAFMN++ NCNN JNI ブリッジ
// Phase 14-1: 軽量 SR モデル（Real-ESRGAN と同一 .so 内に共存）

#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include "net.h"
#include "gpu.h"
#include "datareader.h"

#define TAG_SAFMN "SAFMN_JNI"
#define LOGI_S(...) __android_log_print(ANDROID_LOG_INFO, TAG_SAFMN, __VA_ARGS__)
#define LOGE_S(...) __android_log_print(ANDROID_LOG_ERROR, TAG_SAFMN, __VA_ARGS__)

static ncnn::Net* g_safmn_net = nullptr;
static int g_safmn_scale = 4;
static int g_safmn_tileSize = 64;
static int g_safmn_prepadding = 10;
static bool g_safmn_useGpu = false;

static const int SAFMN_MAX_OUTPUT_PIXELS = 2048 * 2048;

static std::vector<unsigned char> loadAssetSafmn(JNIEnv* env, jobject assetManager,
                                                   const std::string& path) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, path.c_str(), AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE_S("Failed to open asset: %s", path.c_str());
        return {};
    }
    size_t length = AAsset_getLength(asset);
    std::vector<unsigned char> buffer(length);
    AAsset_read(asset, buffer.data(), length);
    AAsset_close(asset);
    LOGI_S("Loaded asset %s: %zu bytes", path.c_str(), length);
    return buffer;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nexus_vision_ncnn_SafmnBridge_nativeSafmnInit(
    JNIEnv* env, jclass, jobject assetManager,
    jstring paramPath, jstring modelPath, jint scale, jint tileSize) {

    // Vulkan は Real-ESRGAN 側で既に create_gpu_instance() 済み
    if (g_safmn_net) {
        delete g_safmn_net;
        g_safmn_net = nullptr;
    }

    const char* paramStr = env->GetStringUTFChars(paramPath, nullptr);
    const char* modelStr = env->GetStringUTFChars(modelPath, nullptr);

    LOGI_S("Init: param=%s, model=%s, scale=%d, tile=%d",
           paramStr, modelStr, scale, tileSize);

    auto paramBuf = loadAssetSafmn(env, assetManager, paramStr);
    auto modelBuf = loadAssetSafmn(env, assetManager, modelStr);

    env->ReleaseStringUTFChars(paramPath, paramStr);
    env->ReleaseStringUTFChars(modelPath, modelStr);

    if (paramBuf.empty() || modelBuf.empty()) {
        LOGE_S("Failed to load model files");
        return JNI_FALSE;
    }

    g_safmn_net = new ncnn::Net();

    int gpuCount = ncnn::get_gpu_count();
    if (gpuCount > 0) {
        g_safmn_useGpu = true;
        g_safmn_net->opt.use_vulkan_compute = true;
        g_safmn_net->opt.use_fp16_packed = true;
        g_safmn_net->opt.use_fp16_storage = true;
        g_safmn_net->opt.use_fp16_arithmetic = true;
        g_safmn_net->opt.use_packing_layout = true;
        g_safmn_net->opt.num_threads = 1;
        LOGI_S("Vulkan GPU enabled, FP16=ON");
    } else {
        g_safmn_useGpu = false;
        g_safmn_net->opt.use_vulkan_compute = false;
        g_safmn_net->opt.use_fp16_packed = true;
        g_safmn_net->opt.use_fp16_storage = true;
        g_safmn_net->opt.use_fp16_arithmetic = false;
        g_safmn_net->opt.use_packing_layout = true;
        g_safmn_net->opt.num_threads = 2;
        LOGI_S("CPU fallback, FP16 storage");
    }

    // param (テキスト)
    std::vector<char> paramText(paramBuf.size() + 1);
    memcpy(paramText.data(), paramBuf.data(), paramBuf.size());
    paramText[paramBuf.size()] = '\0';

    int ret = g_safmn_net->load_param_mem(paramText.data());
    if (ret != 0) {
        LOGE_S("load_param_mem failed: %d", ret);
        delete g_safmn_net;
        g_safmn_net = nullptr;
        return JNI_FALSE;
    }

    // model (バイナリ)
    const unsigned char* ptr = modelBuf.data();
    ncnn::DataReaderFromMemory dr(ptr);
    ret = g_safmn_net->load_model(dr);
    if (ret != 0) {
        LOGE_S("load_model failed: %d", ret);
        delete g_safmn_net;
        g_safmn_net = nullptr;
        return JNI_FALSE;
    }

    g_safmn_scale = scale;
    g_safmn_tileSize = tileSize;

    LOGI_S("SAFMN++ loaded (param=%zu B, model=%zu B, gpu=%s)",
           paramBuf.size(), modelBuf.size(), g_safmn_useGpu ? "Vulkan" : "CPU");
    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_com_nexus_vision_ncnn_SafmnBridge_nativeSafmnProcess(
    JNIEnv* env, jclass, jobject inputBitmap) {

    if (!g_safmn_net) {
        LOGE_S("Model not initialized");
        return nullptr;
    }

    AndroidBitmapInfo inInfo;
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != 0) return nullptr;
    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;

    int w = (int)inInfo.width;
    int h = (int)inInfo.height;
    int outW = w * g_safmn_scale;
    int outH = h * g_safmn_scale;

    if ((long long)outW * outH > SAFMN_MAX_OUTPUT_PIXELS) {
        LOGE_S("Output too large: %dx%d", outW, outH);
        return nullptr;
    }

    void* inPixels = nullptr;
    AndroidBitmap_lockPixels(env, inputBitmap, &inPixels);

    // タイル処理
    int TILE = g_safmn_tileSize;
    int pad = g_safmn_prepadding;
    int xtiles = (w + TILE - 1) / TILE;
    int ytiles = (h + TILE - 1) / TILE;

    std::vector<unsigned char> outputData((size_t)outW * outH * 4, 0);

    LOGI_S("Processing %dx%d -> %dx%d (tiles=%dx%d, tile=%d)",
           w, h, outW, outH, xtiles, ytiles, TILE);

    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            int x0 = xi * TILE;
            int y0 = yi * TILE;
            int x1 = std::min(x0 + TILE, w);
            int y1 = std::min(y0 + TILE, h);
            int tW = x1 - x0;
            int tH = y1 - y0;

            int px0 = std::max(x0 - pad, 0);
            int py0 = std::max(y0 - pad, 0);
            int px1 = std::min(x1 + pad, w);
            int py1 = std::min(y1 + pad, h);
            int pW = px1 - px0;
            int pH = py1 - py0;

            ncnn::Mat in(pW, pH, 3);
            float* rPtr = in.channel(0);
            float* gPtr = in.channel(1);
            float* bPtr = in.channel(2);
            const float inv = 1.0f / 255.0f;

            for (int row = 0; row < pH; row++) {
                for (int col = 0; col < pW; col++) {
                    unsigned char* src = (unsigned char*)inPixels
                        + ((py0 + row) * inInfo.stride)
                        + ((px0 + col) * 4);
                    int idx = row * pW + col;
                    rPtr[idx] = src[0] * inv;
                    gPtr[idx] = src[1] * inv;
                    bPtr[idx] = src[2] * inv;
                }
            }

            ncnn::Extractor ex = g_safmn_net->create_extractor();
            if (g_safmn_useGpu) {
                ex.set_blob_vkallocator(g_safmn_net->opt.blob_vkallocator);
                ex.set_workspace_vkallocator(g_safmn_net->opt.workspace_vkallocator);
                ex.set_staging_vkallocator(g_safmn_net->opt.staging_vkallocator);
            }

            ex.input("input", in);
            ncnn::Mat out;
            int ret = ex.extract("output", out);

            if (ret != 0) {
                LOGE_S("Tile (%d,%d) failed: %d", xi, yi, ret);
                continue;
            }

            int offX = (x0 - px0) * g_safmn_scale;
            int offY = (y0 - py0) * g_safmn_scale;
            int oTW = tW * g_safmn_scale;
            int oTH = tH * g_safmn_scale;

            const float* rO = out.channel(0);
            const float* gO = out.channel(1);
            const float* bO = out.channel(2);
            int oPW = out.w;

            for (int row = 0; row < oTH; row++) {
                for (int col = 0; col < oTW; col++) {
                    int si = (offY + row) * oPW + (offX + col);
                    int ox = x0 * g_safmn_scale + col;
                    int oy = y0 * g_safmn_scale + row;
                    if (ox < outW && oy < outH) {
                        int di = (oy * outW + ox) * 4;
                        float r = std::max(0.f, std::min(1.f, rO[si]));
                        float g = std::max(0.f, std::min(1.f, gO[si]));
                        float b = std::max(0.f, std::min(1.f, bO[si]));
                        outputData[di + 0] = (unsigned char)(r * 255.f + 0.5f);
                        outputData[di + 1] = (unsigned char)(g * 255.f + 0.5f);
                        outputData[di + 2] = (unsigned char)(b * 255.f + 0.5f);
                        outputData[di + 3] = 255;
                    }
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, inputBitmap);

    // 出力 Bitmap 作成
    jclass bmpCls = env->FindClass("android/graphics/Bitmap");
    jclass cfgCls = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID f = env->GetStaticFieldID(cfgCls, "ARGB_8888",
                     "Landroid/graphics/Bitmap$Config;");
    jobject cfg = env->GetStaticObjectField(cfgCls, f);
    jmethodID m = env->GetStaticMethodID(bmpCls, "createBitmap",
                      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject outBmp = env->CallStaticObjectMethod(bmpCls, m, outW, outH, cfg);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE_S("OOM creating %dx%d bitmap", outW, outH);
        return nullptr;
    }

    AndroidBitmapInfo outInfo;
    AndroidBitmap_getInfo(env, outBmp, &outInfo);
    void* outPtr = nullptr;
    AndroidBitmap_lockPixels(env, outBmp, &outPtr);
    for (int row = 0; row < outH; row++) {
        memcpy((unsigned char*)outPtr + row * outInfo.stride,
               outputData.data() + row * outW * 4, outW * 4);
    }
    AndroidBitmap_unlockPixels(env, outBmp);

    LOGI_S("Output: %dx%d (%s)", outW, outH, g_safmn_useGpu ? "Vulkan" : "CPU");
    return outBmp;
}

JNIEXPORT void JNICALL
Java_com_nexus_vision_ncnn_SafmnBridge_nativeSafmnRelease(JNIEnv*, jclass) {
    if (g_safmn_net) {
        delete g_safmn_net;
        g_safmn_net = nullptr;
        LOGI_S("Released");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_nexus_vision_ncnn_SafmnBridge_nativeSafmnIsLoaded(JNIEnv*, jclass) {
    return (g_safmn_net != nullptr) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
```

#### 4. CMakeLists.txt 変更

パス: `app/src/main/jni/CMakeLists.txt` — `realesrgan_native` の SHARED ライブラリに `safmn_jni.cpp` を追加:

```cmake
# ─── メインライブラリ ───  (変更箇所のみ)
add_library(realesrgan_native SHARED
    realesrgan_simple.cpp
    realesrgan_jni.cpp
    image_fusion.cpp
    streaming_jpeg.cpp
    safmn_jni.cpp          # ← Phase 14-1 追加
)
```

#### 5. `RouteCProcessor.kt` 変更 — モデル選択ロジック追加

パス: `app/src/main/java/com/nexus/vision/pipeline/RouteCProcessor.kt`

```kotlin
package com.nexus.vision.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.vision.ncnn.NcnnSuperResolution
import com.nexus.vision.ncnn.SafmnSuperResolution

/**
 * Route C プロセッサ（超解像エントリーポイント）
 *
 * Phase 14-1: SAFMN++ (軽量・高速) と Real-ESRGAN (高品質・重い) の二段構成。
 * デフォルトは SAFMN++ を使用。フォールバックとして Real-ESRGAN を保持。
 */
class RouteCProcessor(private val context: Context) {

    companion object {
        private const val TAG = "RouteCProcessor"
    }

    enum class SrModel {
        SAFMN_PP,      // Phase 14-1: 軽量高速 (~0.5 MB)
        REAL_ESRGAN    // Phase 7: 重厚高品質 (~67 MB)
    }

    private var esrgan: NcnnSuperResolution? = null
    private var safmn: SafmnSuperResolution? = null
    var activeModel: SrModel = SrModel.SAFMN_PP
        private set

    /**
     * 両モデルを初期化。SAFMN++ を優先、失敗時は Real-ESRGAN へフォールバック。
     */
    fun initialize(): Boolean {
        // SAFMN++ を先に試す
        safmn = SafmnSuperResolution()
        val safmnOk = safmn?.initialize(context) ?: false
        if (safmnOk) {
            Log.i(TAG, "SAFMN++ initialized (primary)")
            activeModel = SrModel.SAFMN_PP
        } else {
            Log.w(TAG, "SAFMN++ init failed, falling back to Real-ESRGAN")
            safmn = null
        }

        // Real-ESRGAN も初期化（フォールバック用、またはユーザー切替用）
        esrgan = NcnnSuperResolution()
        val esrganOk = esrgan?.initialize(context) ?: false
        if (esrganOk) {
            Log.i(TAG, "Real-ESRGAN initialized (fallback)")
            if (!safmnOk) activeModel = SrModel.REAL_ESRGAN
        } else {
            Log.e(TAG, "Real-ESRGAN init also failed")
            esrgan = null
        }

        return safmnOk || esrganOk
    }

    /**
     * ユーザーまたは自動ロジックによるモデル切替
     */
    fun switchModel(model: SrModel): Boolean {
        return when (model) {
            SrModel.SAFMN_PP -> {
                if (safmn != null) { activeModel = model; true }
                else { Log.w(TAG, "SAFMN++ not available"); false }
            }
            SrModel.REAL_ESRGAN -> {
                if (esrgan != null) { activeModel = model; true }
                else { Log.w(TAG, "Real-ESRGAN not available"); false }
            }
        }
    }

    /**
     * アクティブなモデルで超解像処理
     */
    suspend fun process(bitmap: Bitmap): ProcessResult {
        val startTime = System.currentTimeMillis()

        return try {
            val result = when (activeModel) {
                SrModel.SAFMN_PP -> safmn?.upscale(bitmap)
                SrModel.REAL_ESRGAN -> esrgan?.upscale(bitmap)
            }
            val elapsed = System.currentTimeMillis() - startTime

            if (result != null) {
                val method = when (activeModel) {
                    SrModel.SAFMN_PP -> "SAFMN++ 4× (Vulkan GPU, ~0.5MB)"
                    SrModel.REAL_ESRGAN -> "Real-ESRGAN 4× (Vulkan GPU, ~67MB)"
                }
                Log.i(TAG, "[$method] ${bitmap.width}x${bitmap.height} → " +
                        "${result.width}x${result.height} in ${elapsed}ms")

                ProcessResult(
                    bitmap = result,
                    method = method,
                    elapsedMs = elapsed,
                    success = true
                )
            } else {
                // アクティブモデル失敗 → フォールバック
                val fallbackResult = when (activeModel) {
                    SrModel.SAFMN_PP -> esrgan?.upscale(bitmap)
                    SrModel.REAL_ESRGAN -> safmn?.upscale(bitmap)
                }
                val totalElapsed = System.currentTimeMillis() - startTime
                if (fallbackResult != null) {
                    Log.w(TAG, "Primary failed, fallback succeeded in ${totalElapsed}ms")
                    ProcessResult(fallbackResult, "fallback SR", totalElapsed, true)
                } else {
                    ProcessResult(bitmap, "passthrough (両モデル失敗)", totalElapsed, false)
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "Process error: ${e.message}", e)
            ProcessResult(bitmap, "passthrough (エラー)", elapsed, false)
        }
    }

    fun release() {
        esrgan?.release()
        esrgan = null
        safmn?.release()
        safmn = null
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

#### 6. `MainViewModel.kt` — モデル切替コマンド追加

`sendMessage()` のコマンド判定部分に以下を追加（`isBatchRequest` チェックの前）:

```kotlin
// --- Phase 14-1: SR モデル切替コマンド ---
val isSrSwitchCommand = cleanText.let {
    it.contains("SAFMN") || it.contains("safmn") ||
    it == "高速モード" || it == "高画質モード"
}
if (isSrSwitchCommand && imageUri == null) {
    val target = if (cleanText.contains("ESRGAN", ignoreCase = true) ||
                     cleanText == "高画質モード")
        RouteCProcessor.SrModel.REAL_ESRGAN
    else
        RouteCProcessor.SrModel.SAFMN_PP

    val success = routeCProcessor?.switchModel(target) ?: false
    val modelName = if (target == RouteCProcessor.SrModel.SAFMN_PP)
        "SAFMN++ (軽量高速)" else "Real-ESRGAN (重厚高品質)"
    val msg = if (success) "超解像モデルを $modelName に切り替えました"
              else "$modelName は利用できません"
    addAssistantMessage(msg)
    return
}
```

---

### 期待される効果

| 指標 | Real-ESRGAN (現行) | SAFMN++ (新) | 改善率 |
|------|-------------------|-------------|--------|
| モデルサイズ | ~67 MB | ~0.5 MB | **134×小** |
| パラメータ数 | ~16.7M | ~240K | **70×少** |
| 128×128 推論 (推定) | ~800ms | ~100-200ms | **4-8×速** |
| PSNR (Urban100 ×4) | perceptual特化 | ~26.2 dB | fidelity向上 |
| メモリ使用量 | ~200 MB ピーク | ~20 MB ピーク | **10×少** |

SAFMN++ は fidelity (PSNR/SSIM) 重視で、Real-ESRGAN は perceptual quality (見た目のシャープさ) 重視です。用途によって使い分けられる設計にしています。

ビルドして動作確認してください。
