// ファイルパス: app/src/main/jni/realesrgan_simple.h
#ifndef REALESRGAN_SIMPLE_H
#define REALESRGAN_SIMPLE_H

#include <string>
#include <vector>
#include "net.h"

class RealESRGANSimple {
public:
    RealESRGANSimple();
    ~RealESRGANSimple();

    // メモリバッファからロード
    int load(const unsigned char* paramBuffer, int paramLen,
             const unsigned char* modelBuffer, int modelLen);

    // ファイルパスからロード
    int load(const std::string& paramPath, const std::string& modelPath);

    // RGBA ピクセル入力 → RGBA ピクセル出力（呼び出し側で w*scale * h*scale * 4 確保）
    int process(const unsigned char* inputPixels, int w, int h,
                unsigned char* outputPixels);

    int scale = 4;
    int tileSize = 200;
    int prepadding = 10;

    bool isLoaded() const { return loaded; }

private:
    ncnn::Net net;
    bool loaded = false;
};

#endif // REALESRGAN_SIMPLE_H
