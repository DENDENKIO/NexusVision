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
    inputData.resize(w * h * 4);

    for (int row = 0; row < h; row++) {
        unsigned char* srcRow = (unsigned char*)inPixels + row * inInfo.stride;
        unsigned char* dstRow = inputData.data() + row * w * 4;
        memcpy(dstRow, srcRow, w * 4);
    }
    AndroidBitmap_unlockPixels(env, inputBitmap);

    std::vector<unsigned char> outputData;
    outputData.resize((size_t)outW * outH * 4, 0);

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
