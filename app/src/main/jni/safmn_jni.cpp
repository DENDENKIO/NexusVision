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
static int g_safmn_tileSize = 128;
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

    // Small モデル (0.5MB) は CPU で十分高速
    {
        g_safmn_useGpu = false;
        g_safmn_net->opt.use_vulkan_compute = false;
        g_safmn_net->opt.use_fp16_packed = false;
        g_safmn_net->opt.use_fp16_storage = false;
        g_safmn_net->opt.use_fp16_arithmetic = false;
        g_safmn_net->opt.use_packing_layout = true;
        g_safmn_net->opt.num_threads = 4;
        LOGI_S("CPU mode, FP32, threads=4");
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
            // Vulkan disabled
            /*
            if (g_safmn_useGpu) {
                ex.set_blob_vkallocator(g_safmn_net->opt.blob_vkallocator);
                ex.set_workspace_vkallocator(g_safmn_net->opt.workspace_vkallocator);
                ex.set_staging_vkallocator(g_safmn_net->opt.staging_vkallocator);
            }
            */

            // 入力デバッグ（ex.input の直前に追加）
            {
                const float* dbgIn = in.channel(0);
                float inMin = dbgIn[0], inMax = dbgIn[0];
                for (int i = 1; i < in.w * in.h; i++) {
                    if (dbgIn[i] < inMin) inMin = dbgIn[i];
                    if (dbgIn[i] > inMax) inMax = dbgIn[i];
                }
                LOGI_S("Input range: min=%.4f, max=%.4f, w=%d, h=%d, c=%d",
                       inMin, inMax, in.w, in.h, in.c);
            }

            ex.input("in0", in);
            ncnn::Mat out;
            int ret = ex.extract("out0", out);

            if (ret == 0) {
                const float* dbg = out.channel(0);
                float minVal = dbg[0], maxVal = dbg[0];
                for (int i = 1; i < out.w * out.h; i++) {
                    if (dbg[i] < minVal) minVal = dbg[i];
                    if (dbg[i] > maxVal) maxVal = dbg[i];
                }
                LOGI_S("Output range: min=%.4f, max=%.4f, w=%d, h=%d, c=%d",
                       minVal, maxVal, out.w, out.h, out.c);
            }

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
