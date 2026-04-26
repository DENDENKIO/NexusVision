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
