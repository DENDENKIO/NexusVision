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

#define TAG "RealESRGAN_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static RealESRGANSimple* g_realesrgan = nullptr;

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

    g_realesrgan = new RealESRGANSimple();
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

    LOGI("RealESRGAN initialized successfully");
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
        LOGE("Unsupported bitmap format: %d (need RGBA_8888)", inInfo.format);
        return nullptr;
    }

    int w = inInfo.width;
    int h = inInfo.height;
    int sc = g_realesrgan->scale;
    int outW = w * sc;
    int outH = h * sc;

    LOGI("Input: %dx%d, Output: %dx%d", w, h, outW, outH);

    void* inPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != 0) {
        LOGE("Failed to lock input pixels");
        return nullptr;
    }

    // stride を考慮してコピー
    std::vector<unsigned char> inputData(w * h * 4);
    for (int row = 0; row < h; row++) {
        unsigned char* srcRow = (unsigned char*)inPixels + row * inInfo.stride;
        unsigned char* dstRow = inputData.data() + row * w * 4;
        memcpy(dstRow, srcRow, w * 4);
    }

    AndroidBitmap_unlockPixels(env, inputBitmap);

    // 出力バッファ
    std::vector<unsigned char> outputData(outW * outH * 4, 0);

    int ret = g_realesrgan->process(inputData.data(), w, h, outputData.data());
    if (ret != 0) {
        LOGE("Process failed: %d", ret);
        return nullptr;
    }

    // 出力 Bitmap 作成
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");

    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                     "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);

    jmethodID createMethod = env->GetStaticMethodID(bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject outBitmap = env->CallStaticObjectMethod(bitmapClass, createMethod,
                                                      outW, outH, argb8888);
    if (!outBitmap) {
        LOGE("Failed to create output bitmap");
        return nullptr;
    }

    AndroidBitmapInfo outInfo;
    AndroidBitmap_getInfo(env, outBitmap, &outInfo);

    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != 0) {
        LOGE("Failed to lock output pixels");
        return nullptr;
    }

    for (int row = 0; row < outH; row++) {
        unsigned char* srcRow = outputData.data() + row * outW * 4;
        unsigned char* dstRow = (unsigned char*)outPixels + row * outInfo.stride;
        memcpy(dstRow, srcRow, outW * 4);
    }

    AndroidBitmap_unlockPixels(env, outBitmap);

    LOGI("Output bitmap created: %dx%d", outW, outH);
    return outBitmap;
}

JNIEXPORT void JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeRelease(
    JNIEnv* env, jclass clazz) {
    if (g_realesrgan) {
        delete g_realesrgan;
        g_realesrgan = nullptr;
        LOGI("RealESRGAN released");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeIsLoaded(
    JNIEnv* env, jclass clazz) {
    return (g_realesrgan && g_realesrgan->isLoaded()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
