// ファイルパス: app/src/main/jni/image_fusion.h
#ifndef IMAGE_FUSION_H
#define IMAGE_FUSION_H
#include <cstdint>

namespace ImageFusion {

// Stage 1: Fast Guided Filter (He & Sun, TPAMI 2013)
// guidance/input/output: RGBA uint8 バッファ (w*h*4 bytes)
// r: フィルタ半径 (推奨: 8)
// eps: 正規化パラメータ (推奨: 0.01, [0,1]スケールで)
// s: サブサンプリング比 (推奨: 4)
// 戻り値: 0=成功, -1=失敗
int guidedFilter(const uint8_t* guidance, const uint8_t* input,
                 int w, int h, uint8_t* output,
                 int r, float eps, int s);

// Stage 2: Haar DWT Fusion
// img1: guided filter結果, img2: AI enhanced (共にRGBA, 同サイズ)
// output: RGBA (同サイズ)
// w, h は偶数であること (奇数の場合は内部で-1して処理、最後の行/列はコピー)
int dwtFusion(const uint8_t* img1, const uint8_t* img2,
              int w, int h, uint8_t* output);

// Stage 3: Iterative Back-Projection
// highRes: 高解像度推定 (RGBA, hrW*hrH), IN-PLACEで更新
// lowRes: 低解像度参照 (RGBA, lrW*lrH)
// lambda: 更新ステップ (推奨: 0.2)
// iterations: 反復回数 (推奨: 2)
int iterativeBackProjection(uint8_t* highRes, int hrW, int hrH,
                            const uint8_t* lowRes, int lrW, int lrH,
                            float lambda, int iterations);

// 3段統合パイプライン
// original: 元画像RGBA (w*h)
// aiEnhanced: AI超解像をwxhにリサイズ済みRGBA
// aiLowRes: AI入力(512px版) RGBA (lrW*lrH)
// output: 出力RGBA (w*h)
int fusionPipeline(const uint8_t* original, const uint8_t* aiEnhanced,
                   int w, int h,
                   const uint8_t* aiLowRes, int lrW, int lrH,
                   uint8_t* output);

} // namespace ImageFusion
#endif
