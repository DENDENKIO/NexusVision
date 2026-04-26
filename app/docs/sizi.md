



**方針：**
1. Vulkan GPUアクセラレーションを有効化（Mali-G68 MC4対応）
2. `animevideov3` 軽量モデルを使用
3. タイルサイズを32まで下げ可能に
4. FP16を最大活用（メモリ半減）
5. スレッド数を1:1:1に制限
6. 入力サイズを安全な範囲に制限
7. 段階的処理の基盤を用意

まずVulkan対応ncnnに戻すため、ビルド設定からC++、Kotlin全てを修正します。

---

### Step 1: ncnnライブラリを差し替え（差し替え済み）

`ncnn-20260113-android-vulkan.zip` を使います。解凍して配置：

```
app/src/main/jni/ncnn-android-vulkan/
├── arm64-v8a/
│   ├── include/ncnn/
│   └── lib/
│       ├── libncnn.a
│       └── cmake/ncnn/ncnnConfig.cmake
```

### Step 2: `CMakeLists.txt`

```cmake
# ファイルパス: app/src/main/jni/CMakeLists.txt
cmake_minimum_required(VERSION 3.22)
project(realesrgan_native CXX)

set(CMAKE_CXX_STANDARD 17)

# Vulkan版ncnnに変更
set(ncnn_DIR "${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

find_library(JNIGRAPHICS_LIB jnigraphics)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

add_library(realesrgan_native SHARED
    realesrgan_simple.cpp
    realesrgan_jni.cpp
)

target_link_libraries(realesrgan_native
    ncnn
    ${JNIGRAPHICS_LIB}
    ${LOG_LIB}
    ${ANDROID_LIB}
)
```

### Step 3: `realesrgan_simple.h`

```cpp
// ファイルパス: app/src/main/jni/realesrgan_simple.h
#ifndef REALESRGAN_SIMPLE_H
#define REALESRGAN_SIMPLE_H

#include <string>
#include <net.h>

class RealESRGANSimple {
public:
    RealESRGANSimple(int gpuid = -1); // -1=auto, 0=first GPU
    ~RealESRGANSimple();

    int load(const unsigned char* paramBuffer, int paramLen,
             const unsigned char* modelBuffer, int modelLen);
    int load(const std::string& paramPath, const std::string& modelPath);

    // 入力: RGBA, 出力: RGBA
    int process(const unsigned char* inputPixels, int w, int h,
                unsigned char* outputPixels);

    bool isLoaded() const { return loaded; }

    int scale = 4;
    int tileSize = 32;      // デフォルト32（安全重視）
    int prepadding = 10;

private:
    ncnn::Net net;
    bool loaded;
    int gpuid;
    bool useGpu;
};

#endif
```

### Step 4: `realesrgan_simple.cpp`

```cpp
// ファイルパス: app/src/main/jni/realesrgan_simple.cpp
#include "realesrgan_simple.h"
#include <algorithm>
#include <cstring>
#include <cmath>
#include <vector>
#include <android/log.h>
#include "datareader.h"
#include "gpu.h"

#define TAG "RealESRGAN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

RealESRGANSimple::RealESRGANSimple(int gpuid) : gpuid(gpuid), loaded(false), useGpu(false) {
    // Vulkan GPU が使えるか確認
    int gpuCount = ncnn::get_gpu_count();
    LOGI("Vulkan GPU count: %d", gpuCount);

    if (gpuCount > 0) {
        if (gpuid < 0) gpuid = 0; // auto -> first GPU
        this->gpuid = gpuid;
        useGpu = true;

        net.opt.use_vulkan_compute = true;
        // FP16で計算（メモリ半減・速度向上、画質差なし）
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = true; // Mali-G68はFP16演算対応
        net.opt.use_packing_layout = true;
        // スレッド: GPU使用時は1で十分（GPU側で並列化）
        net.opt.num_threads = 1;

        LOGI("Vulkan GPU enabled: gpu_id=%d, FP16=ON", gpuid);
    } else {
        // Vulkan非対応 → CPUフォールバック
        useGpu = false;
        net.opt.use_vulkan_compute = false;
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = false;
        net.opt.use_packing_layout = true;
        net.opt.num_threads = 2; // CPU時はスレッド2
        LOGW("No Vulkan GPU, falling back to CPU (FP16 storage)");
    }
}

RealESRGANSimple::~RealESRGANSimple() {
    net.clear();
}

int RealESRGANSimple::load(const unsigned char* paramBuffer, int paramLen,
                            const unsigned char* modelBuffer, int modelLen) {
    // param はテキスト → null終端必要
    std::vector<char> paramStr(paramLen + 1);
    memcpy(paramStr.data(), paramBuffer, paramLen);
    paramStr[paramLen] = '\0';

    int ret = net.load_param_mem(paramStr.data());
    if (ret != 0) {
        LOGE("load_param_mem failed: %d", ret);
        return -1;
    }
    LOGI("Param loaded, ret=%d", ret);

    // model はバイナリ → DataReaderFromMemory
    const unsigned char* modelPtr = modelBuffer;
    ncnn::DataReaderFromMemory dr(modelPtr);
    ret = net.load_model(dr);
    if (ret != 0) {
        LOGE("load_model failed: %d", ret);
        return -2;
    }

    loaded = true;
    LOGI("Model loaded (param=%d bytes, model=%d bytes, gpu=%s)",
         paramLen, modelLen, useGpu ? "Vulkan" : "CPU");
    return 0;
}

int RealESRGANSimple::load(const std::string& paramPath, const std::string& modelPath) {
    int ret = net.load_param(paramPath.c_str());
    if (ret != 0) {
        LOGE("load_param(%s) failed: %d", paramPath.c_str(), ret);
        return -1;
    }

    ret = net.load_model(modelPath.c_str());
    if (ret != 0) {
        LOGE("load_model(%s) failed: %d", modelPath.c_str(), ret);
        return -2;
    }

    loaded = true;
    LOGI("Model loaded from files (gpu=%s)", useGpu ? "Vulkan" : "CPU");
    return 0;
}

int RealESRGANSimple::process(const unsigned char* inputPixels, int w, int h,
                               unsigned char* outputPixels) {
    if (!loaded) {
        LOGE("Model not loaded!");
        return -1;
    }

    const int TILE = tileSize;
    const int outW = w * scale;
    const int outH = h * scale;

    const int xtiles = (w + TILE - 1) / TILE;
    const int ytiles = (h + TILE - 1) / TILE;
    const int totalTiles = xtiles * ytiles;

    LOGI("Processing %dx%d -> %dx%d (tiles=%dx%d=%d, tile=%d, pad=%d, gpu=%s)",
         w, h, outW, outH, xtiles, ytiles, totalTiles, TILE, prepadding,
         useGpu ? "Vulkan" : "CPU");

    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            int inTileX0 = xi * TILE;
            int inTileY0 = yi * TILE;
            int inTileX1 = std::min(inTileX0 + TILE, w);
            int inTileY1 = std::min(inTileY0 + TILE, h);

            int tileW = inTileX1 - inTileX0;
            int tileH = inTileY1 - inTileY0;

            // パディング付き入力
            int padX0 = std::max(inTileX0 - prepadding, 0);
            int padY0 = std::max(inTileY0 - prepadding, 0);
            int padX1 = std::min(inTileX1 + prepadding, w);
            int padY1 = std::min(inTileY1 + prepadding, h);

            int padW = padX1 - padX0;
            int padH = padY1 - padY0;

            // RGBA → RGB
            ncnn::Mat in(padW, padH, 3);
            {
                const float scale_val = 1.0f / 255.0f;
                float* rPtr = in.channel(0);
                float* gPtr = in.channel(1);
                float* bPtr = in.channel(2);

                for (int row = 0; row < padH; row++) {
                    for (int col = 0; col < padW; col++) {
                        int srcIdx = ((padY0 + row) * w + (padX0 + col)) * 4;
                        int dstIdx = row * padW + col;
                        rPtr[dstIdx] = inputPixels[srcIdx + 0] * scale_val;
                        gPtr[dstIdx] = inputPixels[srcIdx + 1] * scale_val;
                        bPtr[dstIdx] = inputPixels[srcIdx + 2] * scale_val;
                    }
                }
            }

            // 推論
            ncnn::Extractor ex = net.create_extractor();
            if (useGpu) {
                ex.set_blob_vkallocator(net.opt.blob_vkallocator);
                ex.set_workspace_vkallocator(net.opt.workspace_vkallocator);
                ex.set_staging_vkallocator(net.opt.staging_vkallocator);
            }

            ex.input("data", in);

            ncnn::Mat out;
            int ret = ex.extract("output", out);
            if (ret != 0) {
                LOGE("Inference failed tile (%d,%d): %d", xi, yi, ret);
                // タイル失敗時: 元ピクセルをコピー（クラッシュしない）
                for (int row = 0; row < tileH * scale; row++) {
                    for (int col = 0; col < tileW * scale; col++) {
                        int outX = inTileX0 * scale + col;
                        int outY = inTileY0 * scale + row;
                        if (outX < outW && outY < outH) {
                            // 元ピクセルを最近傍で引き伸ばし
                            int srcX = std::min(inTileX0 + col / scale, w - 1);
                            int srcY = std::min(inTileY0 + row / scale, h - 1);
                            int srcIdx = (srcY * w + srcX) * 4;
                            int dstIdx = (outY * outW + outX) * 4;
                            memcpy(outputPixels + dstIdx, inputPixels + srcIdx, 4);
                        }
                    }
                }
                LOGW("Tile (%d,%d) fallback to nearest-neighbor", xi, yi);
                continue;
            }

            // 出力 float[0..1] → byte → 出力バッファ
            int outPadW = out.w;
            int outPadH = out.h;

            int offsetX = (inTileX0 - padX0) * scale;
            int offsetY = (inTileY0 - padY0) * scale;
            int outTileW = tileW * scale;
            int outTileH = tileH * scale;

            const float* rOut = out.channel(0);
            const float* gOut = out.channel(1);
            const float* bOut = out.channel(2);

            for (int row = 0; row < outTileH; row++) {
                for (int col = 0; col < outTileW; col++) {
                    int srcIdx = (offsetY + row) * outPadW + (offsetX + col);
                    int outX = inTileX0 * scale + col;
                    int outY = inTileY0 * scale + row;

                    if (outX < outW && outY < outH && srcIdx < outPadW * outPadH) {
                        int dstIdx = (outY * outW + outX) * 4;
                        float r = std::max(0.0f, std::min(1.0f, rOut[srcIdx]));
                        float g = std::max(0.0f, std::min(1.0f, gOut[srcIdx]));
                        float b = std::max(0.0f, std::min(1.0f, bOut[srcIdx]));
                        outputPixels[dstIdx + 0] = (unsigned char)(r * 255.0f + 0.5f);
                        outputPixels[dstIdx + 1] = (unsigned char)(g * 255.0f + 0.5f);
                        outputPixels[dstIdx + 2] = (unsigned char)(b * 255.0f + 0.5f);
                        outputPixels[dstIdx + 3] = 255;
                    }
                }
            }

            int done = yi * xtiles + xi + 1;
            if (done % 4 == 0 || done == totalTiles) {
                LOGI("Progress: %d/%d (%.1f%%)", done, totalTiles,
                     (float)done / totalTiles * 100.0f);
            }
        }
    }

    LOGI("Complete: %dx%d (%s)", outW, outH, useGpu ? "Vulkan GPU" : "CPU");
    return 0;
}
```

### Step 5: `realesrgan_jni.cpp`

```cpp
// ファイルパス: app/src/main/jni/realesrgan_jni.cpp
#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include "realesrgan_simple.h"
#include "gpu.h"

#define TAG "RealESRGAN_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static RealESRGANSimple* g_realesrgan = nullptr;

static const int MAX_OUTPUT_PIXELS = 2048 * 2048;

static std::vector<unsigned char> loadAsset(JNIEnv* env, jobject assetManager,
                                             const std::string& path) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, path.c_str(), AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open asset: %s", path.c_str());
        return {};
    }
    size_t length = AAsset_getLength(asset);
    std::vector<unsigned char> buffer(length);
    AAsset_read(asset, buffer.data(), length);
    AAsset_close(asset);
    LOGI("Loaded asset %s: %zu bytes", path.c_str(), length);
    return buffer;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeInit(
    JNIEnv* env, jclass clazz,
    jobject assetManager,
    jstring paramPath,
    jstring modelPath,
    jint scale,
    jint tileSize) {

    // Vulkan初期化（アプリ起動後初回のみ）
    ncnn::create_gpu_instance();

    if (g_realesrgan) {
        delete g_realesrgan;
        g_realesrgan = nullptr;
    }

    const char* paramStr = env->GetStringUTFChars(paramPath, nullptr);
    const char* modelStr = env->GetStringUTFChars(modelPath, nullptr);

    LOGI("Init: param=%s, model=%s, scale=%d, tile=%d", paramStr, modelStr, scale, tileSize);

    std::vector<unsigned char> paramBuf = loadAsset(env, assetManager, paramStr);
    std::vector<unsigned char> modelBuf = loadAsset(env, assetManager, modelStr);

    env->ReleaseStringUTFChars(paramPath, paramStr);
    env->ReleaseStringUTFChars(modelPath, modelStr);

    if (paramBuf.empty() || modelBuf.empty()) {
        LOGE("Failed to load model files from assets");
        return JNI_FALSE;
    }

    // GPU自動検出（0=first GPU, -1にすると内部でauto）
    g_realesrgan = new RealESRGANSimple(0);
    g_realesrgan->scale = scale;
    g_realesrgan->tileSize = tileSize;
    g_realesrgan->prepadding = 10;

    int ret = g_realesrgan->load(paramBuf.data(), (int)paramBuf.size(),
                                  modelBuf.data(), (int)modelBuf.size());
    if (ret != 0) {
        LOGE("Model load failed: %d", ret);
        delete g_realesrgan;
        g_realesrgan = nullptr;
        return JNI_FALSE;
    }

    LOGI("RealESRGAN initialized (Vulkan GPU + FP16)");
    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeProcess(
    JNIEnv* env, jclass clazz,
    jobject inputBitmap) {

    if (!g_realesrgan || !g_realesrgan->isLoaded()) {
        LOGE("Model not initialized");
        return nullptr;
    }

    AndroidBitmapInfo inInfo;
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != 0) {
        LOGE("Failed to get bitmap info");
        return nullptr;
    }

    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported format: %d", inInfo.format);
        return nullptr;
    }

    int w = (int)inInfo.width;
    int h = (int)inInfo.height;
    int sc = g_realesrgan->scale;
    int outW = w * sc;
    int outH = h * sc;

    long long outPixels = (long long)outW * outH;
    if (outPixels > MAX_OUTPUT_PIXELS) {
        LOGE("Output too large: %dx%d = %lld pixels (max %d)", outW, outH, outPixels, MAX_OUTPUT_PIXELS);
        return nullptr;
    }

    LOGI("Input: %dx%d -> Output: %dx%d", w, h, outW, outH);

    void* inPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != 0) {
        LOGE("Failed to lock input pixels");
        return nullptr;
    }

    std::vector<unsigned char> inputData;
    try {
        inputData.resize(w * h * 4);
    } catch (...) {
        LOGE("Input buffer alloc failed");
        AndroidBitmap_unlockPixels(env, inputBitmap);
        return nullptr;
    }

    for (int row = 0; row < h; row++) {
        unsigned char* srcRow = (unsigned char*)inPixels + row * inInfo.stride;
        unsigned char* dstRow = inputData.data() + row * w * 4;
        memcpy(dstRow, srcRow, w * 4);
    }
    AndroidBitmap_unlockPixels(env, inputBitmap);

    std::vector<unsigned char> outputData;
    try {
        outputData.resize((size_t)outW * outH * 4, 0);
    } catch (...) {
        LOGE("Output buffer alloc failed: %dx%d", outW, outH);
        return nullptr;
    }

    int ret = g_realesrgan->process(inputData.data(), w, h, outputData.data());
    inputData.clear();
    inputData.shrink_to_fit();

    if (ret != 0) {
        LOGE("Process failed: %d", ret);
        return nullptr;
    }

    // Bitmap作成
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                     "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);
    jmethodID createMethod = env->GetStaticMethodID(bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject outBitmap = env->CallStaticObjectMethod(bitmapClass, createMethod,
                                                      outW, outH, argb8888);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("OOM creating bitmap %dx%d", outW, outH);
        return nullptr;
    }
    if (!outBitmap) {
        LOGE("Failed to create bitmap");
        return nullptr;
    }

    AndroidBitmapInfo outInfo;
    AndroidBitmap_getInfo(env, outBitmap, &outInfo);

    void* outPixelsPtr = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixelsPtr) != 0) {
        LOGE("Failed to lock output pixels");
        return nullptr;
    }

    for (int row = 0; row < outH; row++) {
        unsigned char* srcRow = outputData.data() + row * outW * 4;
        unsigned char* dstRow = (unsigned char*)outPixelsPtr + row * outInfo.stride;
        memcpy(dstRow, srcRow, outW * 4);
    }
    AndroidBitmap_unlockPixels(env, outBitmap);

    LOGI("Output bitmap: %dx%d", outW, outH);
    return outBitmap;
}

JNIEXPORT void JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeRelease(
    JNIEnv* env, jclass clazz) {
    if (g_realesrgan) {
        delete g_realesrgan;
        g_realesrgan = nullptr;
        LOGI("Released");
    }
    ncnn::destroy_gpu_instance();
}

JNIEXPORT jboolean JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeIsLoaded(
    JNIEnv* env, jclass clazz) {
    return (g_realesrgan && g_realesrgan->isLoaded()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
```

### Step 6: `NcnnSuperResolution.kt`

```kotlin
// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/NcnnSuperResolution.kt
package com.nexus.vision.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSR"
        // 軽量モデル（1.2MB、モバイル向け）
        private const val PARAM_FILE = "models/realesr-animevideov3-x4.param"
        private const val MODEL_FILE = "models/realesr-animevideov3-x4.bin"
        private const val SCALE = 4
        // タイルサイズ32（Mali-G68で安全なサイズ）
        private const val TILE_SIZE = 32
        // 入力上限: 4×で出力2048px → 入力512px
        private const val MAX_INPUT_SIDE = 512
    }

    private var initialized = false

    fun initialize(context: Context): Boolean {
        if (initialized && RealEsrganBridge.nativeIsLoaded()) return true
        return try {
            val result = RealEsrganBridge.nativeInit(
                context.assets, PARAM_FILE, MODEL_FILE, SCALE, TILE_SIZE
            )
            initialized = result
            if (result) Log.i(TAG, "Initialized (Vulkan GPU + FP16, tile=$TILE_SIZE)")
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
        return try {
            val input = limitSize(bitmap)
            val argbInput = if (input.config != Bitmap.Config.ARGB_8888) {
                input.copy(Bitmap.Config.ARGB_8888, false).also {
                    if (input !== bitmap) input.recycle()
                }
            } else {
                input
            }

            if (argbInput == null) {
                Log.e(TAG, "ARGB copy failed")
                return null
            }

            Log.i(TAG, "SR: ${argbInput.width}x${argbInput.height} -> ${argbInput.width * SCALE}x${argbInput.height * SCALE}")
            val startTime = System.currentTimeMillis()
            val result = RealEsrganBridge.nativeProcess(argbInput)
            val elapsed = System.currentTimeMillis() - startTime

            if (result != null) {
                Log.i(TAG, "SR done: ${result.width}x${result.height} in ${elapsed}ms")
            } else {
                Log.e(TAG, "nativeProcess returned null (${elapsed}ms)")
            }

            if (argbInput !== bitmap && argbInput !== input) argbInput.recycle()
            if (input !== bitmap) input.recycle()

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

    private fun limitSize(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= MAX_INPUT_SIDE) return bitmap
        val ratio = MAX_INPUT_SIDE.toFloat() / maxSide
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(16)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(16)
        Log.i(TAG, "Limit: ${bitmap.width}x${bitmap.height} -> ${newW}x${newH}")
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
```

---

**変更まとめ：**

| 対策 | 実装 |
|---|---|
| Vulkan GPU復活 | `ncnn-android-vulkan.zip` + `use_vulkan_compute=true` |
| FP16フル活用 | `fp16_packed/storage/arithmetic` 全ON → メモリ半減 |
| 軽量モデル | `animevideov3`（1.2MB、RRDB少ない） |
| タイルサイズ32 | 1タイルの必要メモリを最小化 |
| スレッド1 | GPU使用時はCPUスレッド1で十分 |
| 入力512px制限 | 出力最大2048px、メモリ16MB |
| タイル失敗時フォールバック | nearest-neighborで埋めてクラッシュ回避 |
| C++バッファ確保try-catch | OOM時nullptr返却 |
| Java OOMチェック | `ExceptionCheck`でBitmap作成失敗を検知 |

**変更ファイル一覧：**

| ファイル | 操作 |
|---|---|
| `jni/CMakeLists.txt` | 置換（Vulkan版パス） |
| `jni/realesrgan_simple.h` | 置換（gpuidコンストラクタ） |
| `jni/realesrgan_simple.cpp` | 置換（Vulkan対応） |
| `jni/realesrgan_jni.cpp` | 置換（GPU初期化/破棄） |
| `ncnn/NcnnSuperResolution.kt` | 置換（tile=32, limit=512） |

ncnnライブラリを `ncnn-android-vulkan` に差し替えてからリビルドしてください。`RouteCProcessor.kt`、`MainViewModel.kt`、`MainScreen.kt`、`CropSelector.kt`、`RegionDecoder.kt` は変更不要です。