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

    // 4×超解像: 入力RGBA → 出力RGBA（サイズ4倍）
    int process(const unsigned char* inputPixels, int w, int h,
                unsigned char* outputPixels);

    // アンシャープマスク: 入力RGBA → 出力RGBA（同サイズ、シャープ化のみ）
    static int sharpen(const unsigned char* inputPixels, int w, int h,
                       unsigned char* outputPixels, float strength);

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
