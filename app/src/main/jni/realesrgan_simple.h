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

    // アンシャープマスク
    static int sharpen(const unsigned char* inputPixels, int w, int h,
                       unsigned char* outputPixels, float strength);

    // ラプラシアンピラミッド合成
    // original: 元画像を出力サイズにリサイズしたもの（高周波ソース）
    // enhanced: AI超解像結果（低周波ソース）
    // 同サイズであること
    static int laplacianBlend(const unsigned char* originalPixels,
                              const unsigned char* enhancedPixels,
                              int w, int h,
                              unsigned char* outputPixels,
                              float detailStrength,
                              float sharpenStrength);

    // NLM (Non-Local Means) デノイズ
    // strength: デノイズ強度（10.0〜30.0推奨、大きいほど強い）
    // patchSize: パッチサイズ（3 or 5推奨）
    // searchSize: 探索窓サイズ（7〜13推奨）
    static int nlmDenoise(const unsigned char* inputPixels, int w, int h,
                          unsigned char* outputPixels,
                          float strength, int patchSize, int searchSize);

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
