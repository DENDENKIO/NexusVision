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
#include "image_fusion.h"
#include "gpu.h"
#include "streaming_jpeg.h"

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

JNIEXPORT jobject JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeSharpen(
    JNIEnv* env, jclass clazz,
    jobject inputBitmap,
    jfloat strength) {

    AndroidBitmapInfo inInfo;
    if (AndroidBitmap_getInfo(env, inputBitmap, &inInfo) != 0) {
        LOGE("Sharpen: failed to get bitmap info");
        return nullptr;
    }

    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Sharpen: unsupported format");
        return nullptr;
    }

    int w = (int)inInfo.width;
    int h = (int)inInfo.height;

    LOGI("Sharpen: %dx%d, strength=%.2f", w, h, (float)strength);

    void* inPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inPixels) != 0) {
        LOGE("Sharpen: lock failed");
        return nullptr;
    }

    // stride考慮コピー
    std::vector<unsigned char> inputData((size_t)w * h * 4);
    for (int row = 0; row < h; row++) {
        unsigned char* srcRow = (unsigned char*)inPixels + row * inInfo.stride;
        unsigned char* dstRow = inputData.data() + row * w * 4;
        memcpy(dstRow, srcRow, w * 4);
    }
    AndroidBitmap_unlockPixels(env, inputBitmap);

    std::vector<unsigned char> outputData((size_t)w * h * 4);
    int ret = RealESRGANSimple::sharpen(inputData.data(), w, h, outputData.data(), (float)strength);
    inputData.clear();
    inputData.shrink_to_fit();

    if (ret != 0) {
        LOGE("Sharpen failed: %d", ret);
        return nullptr;
    }

    // 出力Bitmap（入力と同サイズ）
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                     "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);
    jmethodID createMethod = env->GetStaticMethodID(bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject outBitmap = env->CallStaticObjectMethod(bitmapClass, createMethod,
                                                      w, h, argb8888);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Sharpen: OOM creating bitmap");
        return nullptr;
    }
    if (!outBitmap) return nullptr;

    AndroidBitmapInfo outInfo;
    AndroidBitmap_getInfo(env, outBitmap, &outInfo);
    void* outPixelsPtr = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixelsPtr) != 0) return nullptr;

    for (int row = 0; row < h; row++) {
        unsigned char* srcRow = outputData.data() + row * w * 4;
        unsigned char* dstRow = (unsigned char*)outPixelsPtr + row * outInfo.stride;
        memcpy(dstRow, srcRow, w * 4);
    }
    AndroidBitmap_unlockPixels(env, outBitmap);

    LOGI("Sharpen output: %dx%d", w, h);
    return outBitmap;
}

JNIEXPORT jobject JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeLaplacianBlend(
    JNIEnv* env, jclass clazz,
    jobject originalBitmap,
    jobject enhancedBitmap,
    jfloat detailStrength,
    jfloat sharpenStrength) {

    AndroidBitmapInfo oInfo, eInfo;
    AndroidBitmap_getInfo(env, originalBitmap, &oInfo);
    AndroidBitmap_getInfo(env, enhancedBitmap, &eInfo);

    if (oInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        eInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("LaplacianBlend: unsupported format");
        return nullptr;
    }

    int w = (int)oInfo.width;
    int h = (int)oInfo.height;

    if (w != (int)eInfo.width || h != (int)eInfo.height) {
        LOGE("LaplacianBlend: size mismatch %dx%d vs %dx%d",
             w, h, (int)eInfo.width, (int)eInfo.height);
        return nullptr;
    }

    LOGI("LaplacianBlend: %dx%d, detail=%.2f, sharpen=%.2f",
         w, h, (float)detailStrength, (float)sharpenStrength);

    void* oPixels = nullptr;
    AndroidBitmap_lockPixels(env, originalBitmap, &oPixels);
    std::vector<unsigned char> origData((size_t)w * h * 4);
    for (int row = 0; row < h; row++) {
        memcpy(origData.data() + row * w * 4,
               (unsigned char*)oPixels + row * oInfo.stride, w * 4);
    }
    AndroidBitmap_unlockPixels(env, originalBitmap);

    void* ePixels = nullptr;
    AndroidBitmap_lockPixels(env, enhancedBitmap, &ePixels);
    std::vector<unsigned char> enhData((size_t)w * h * 4);
    for (int row = 0; row < h; row++) {
        memcpy(enhData.data() + row * w * 4,
               (unsigned char*)ePixels + row * eInfo.stride, w * 4);
    }
    AndroidBitmap_unlockPixels(env, enhancedBitmap);

    std::vector<unsigned char> outputData((size_t)w * h * 4);
    int ret = RealESRGANSimple::laplacianBlend(
        origData.data(), enhData.data(), w, h, outputData.data(),
        (float)detailStrength, (float)sharpenStrength);

    origData.clear(); origData.shrink_to_fit();
    enhData.clear(); enhData.shrink_to_fit();

    if (ret != 0) {
        LOGE("LaplacianBlend failed: %d", ret);
        return nullptr;
    }

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                     "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);
    jmethodID createMethod = env->GetStaticMethodID(bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject outBitmap = env->CallStaticObjectMethod(bitmapClass, createMethod, w, h, argb8888);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }
    if (!outBitmap) return nullptr;

    AndroidBitmapInfo outInfo;
    AndroidBitmap_getInfo(env, outBitmap, &outInfo);
    void* outPtr = nullptr;
    AndroidBitmap_lockPixels(env, outBitmap, &outPtr);
    for (int row = 0; row < h; row++) {
        memcpy((unsigned char*)outPtr + row * outInfo.stride,
               outputData.data() + row * w * 4, w * 4);
    }
    AndroidBitmap_unlockPixels(env, outBitmap);

    LOGI("LaplacianBlend output: %dx%d", w, h);
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

JNIEXPORT jobject JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeFusionPipeline(
    JNIEnv* env, jclass clazz,
    jobject originalBitmap,
    jobject aiEnhancedBitmap,
    jobject aiLowResBitmap) {

    AndroidBitmapInfo oInfo, eInfo, lInfo;
    if (AndroidBitmap_getInfo(env, originalBitmap, &oInfo) != 0 ||
        AndroidBitmap_getInfo(env, aiEnhancedBitmap, &eInfo) != 0 ||
        AndroidBitmap_getInfo(env, aiLowResBitmap, &lInfo) != 0) {
        LOGE("FusionPipeline: failed to get bitmap info");
        return nullptr;
    }

    if (oInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        eInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        lInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("FusionPipeline: unsupported format");
        return nullptr;
    }

    int w = (int)oInfo.width;
    int h = (int)oInfo.height;
    int lrW = (int)lInfo.width;
    int lrH = (int)lInfo.height;

    if (w != (int)eInfo.width || h != (int)eInfo.height) {
        LOGE("FusionPipeline: size mismatch orig=%dx%d vs enhanced=%dx%d",
             w, h, (int)eInfo.width, (int)eInfo.height);
        return nullptr;
    }

    LOGI("FusionPipeline: orig=%dx%d, lowRes=%dx%d", w, h, lrW, lrH);

    // 元画像ピクセル取得
    void* oPixels = nullptr;
    AndroidBitmap_lockPixels(env, originalBitmap, &oPixels);
    std::vector<unsigned char> origData((size_t)w * h * 4);
    for (int row = 0; row < h; row++) {
        memcpy(origData.data() + row * w * 4,
               (unsigned char*)oPixels + row * oInfo.stride, w * 4);
    }
    AndroidBitmap_unlockPixels(env, originalBitmap);

    // AI enhanced ピクセル取得
    void* ePixels = nullptr;
    AndroidBitmap_lockPixels(env, aiEnhancedBitmap, &ePixels);
    std::vector<unsigned char> enhData((size_t)w * h * 4);
    for (int row = 0; row < h; row++) {
        memcpy(enhData.data() + row * w * 4,
               (unsigned char*)ePixels + row * eInfo.stride, w * 4);
    }
    AndroidBitmap_unlockPixels(env, aiEnhancedBitmap);

    // AI low-res ピクセル取得
    void* lPixels = nullptr;
    AndroidBitmap_lockPixels(env, aiLowResBitmap, &lPixels);
    std::vector<unsigned char> lowData((size_t)lrW * lrH * 4);
    for (int row = 0; row < lrH; row++) {
        memcpy(lowData.data() + row * lrW * 4,
               (unsigned char*)lPixels + row * lInfo.stride, lrW * 4);
    }
    AndroidBitmap_unlockPixels(env, aiLowResBitmap);

    // 出力バッファ
    std::vector<unsigned char> outputData((size_t)w * h * 4);

    int ret = ImageFusion::fusionPipeline(
        origData.data(), enhData.data(), w, h,
        lowData.data(), lrW, lrH, outputData.data());

    origData.clear(); origData.shrink_to_fit();
    enhData.clear(); enhData.shrink_to_fit();
    lowData.clear(); lowData.shrink_to_fit();

    if (ret != 0) {
        LOGE("FusionPipeline: native fusion failed: %d", ret);
        return nullptr;
    }

    // 出力Bitmap作成
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                     "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);
    jmethodID createMethod = env->GetStaticMethodID(bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject outBitmap = env->CallStaticObjectMethod(bitmapClass, createMethod, w, h, argb8888);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("FusionPipeline: OOM creating bitmap %dx%d", w, h);
        return nullptr;
    }
    if (!outBitmap) return nullptr;

    AndroidBitmapInfo outInfo;
    AndroidBitmap_getInfo(env, outBitmap, &outInfo);
    void* outPtr = nullptr;
    AndroidBitmap_lockPixels(env, outBitmap, &outPtr);
    for (int row = 0; row < h; row++) {
        memcpy((unsigned char*)outPtr + row * outInfo.stride,
               outputData.data() + row * w * 4, w * 4);
    }
    AndroidBitmap_unlockPixels(env, outBitmap);

    LOGI("FusionPipeline output: %dx%d", w, h);
    return outBitmap;
}

JNIEXPORT jlong JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeJpegBeginWrite(
    JNIEnv* env, jclass clazz, jint fd, jint width, jint height, jint quality) {
    StreamingJpegCtx* ctx = streamingJpegBegin(fd, width, height, quality);
    return (jlong)ctx;
}

JNIEXPORT jint JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeJpegWriteRows(
    JNIEnv* env, jclass clazz, jlong ctxPtr, jobject bitmap, jint startRow, jint numRows) {
    StreamingJpegCtx* ctx = (StreamingJpegCtx*)ctxPtr;
    if (!ctx) return -1;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0) return -1;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) return -1;

    // Bitmap 全体を対象にして、指定した開始行から numRows 分のデータを渡す
    unsigned char* startPtr = (unsigned char*)pixels + (size_t)startRow * info.stride;
    
    // streamingJpegWriteRows は内部で 1行ずつ RGB 変換するので、
    // ここから渡すデータは RGBA (stride を考慮したスキャンライン) である必要があるが、
    // streaming_jpeg.cpp の実装に合わせて RGBA 密なデータを想定するか、
    // もしくは stride を考慮するように実装を調整する。
    // 今回の streaming_jpeg.cpp は rgbaRows を 4倍して進めているので、
    // 密なRGBAデータを渡す必要がある。
    
    // もし bitmap の stride が width * 4 と一致しない場合は、一旦密なバッファにコピーする必要がある。
    // 通常 Android の Bitmap.Config.ARGB_8888 では stride == width * 4 であることが多いが、
    // 安全のためにチェックまたはコピーを行う。
    
    int ret = 0;
    if (info.stride == info.width * 4) {
        ret = streamingJpegWriteRows(ctx, startPtr, numRows);
    } else {
        // Stride が異なる場合のフォールバック（低速だが安全）
        std::vector<unsigned char> denseBuffer(info.width * 4 * numRows);
        for (int i = 0; i < numRows; i++) {
            memcpy(denseBuffer.data() + i * info.width * 4, 
                   (unsigned char*)pixels + (size_t)(startRow + i) * info.stride, 
                   info.width * 4);
        }
        ret = streamingJpegWriteRows(ctx, denseBuffer.data(), numRows);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return (jint)ret;
}

JNIEXPORT jint JNICALL
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeJpegEndWrite(
    JNIEnv* env, jclass clazz, jlong ctxPtr) {
    StreamingJpegCtx* ctx = (StreamingJpegCtx*)ctxPtr;
    if (!ctx) return -1;
    return streamingJpegEnd(ctx);
}

} // extern "C"
