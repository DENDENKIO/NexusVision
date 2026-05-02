// ファイルパス: app/src/main/java/com/nexus/vision/ncnn/RealEsrganBridge.kt
package com.nexus.vision.ncnn

import android.content.res.AssetManager
import android.graphics.Bitmap

object RealEsrganBridge {

    init {
        System.loadLibrary("realesrgan_native")
    }

    external fun nativeInit(
        assetManager: AssetManager,
        paramPath: String,
        modelPath: String,
        scale: Int,
        tileSize: Int
    ): Boolean

    external fun nativeProcess(inputBitmap: Bitmap): Bitmap?

    external fun nativeSharpen(inputBitmap: Bitmap, strength: Float): Bitmap?
    
    // NLM デノイズ
    external fun nativeNlmDenoise(
        inputBitmap: Bitmap,
        strength: Float,      // 10.0〜30.0
        patchSize: Int,        // 3 or 5
        searchSize: Int        // 7〜13
    ): Bitmap?

    // ラプラシアンピラミッド合成
    external fun nativeLaplacianBlend(
        originalBitmap: Bitmap,   // 元画像（出力サイズにリサイズ済み）
        enhancedBitmap: Bitmap,   // AI超解像結果
        detailStrength: Float,    // 元画像ディテールの強度（1.0=完全保持）
        sharpenStrength: Float    // 仕上げのシャープ化強度（0.3〜0.5推奨）
    ): Bitmap?

    external fun nativeRelease()

    external fun nativeIsLoaded(): Boolean

    // 3段融合パイプライン (Guided Filter + DWT + IBP)
    external fun nativeFusionPipeline(
        originalBitmap: Bitmap,    // 元画像
        aiEnhancedBitmap: Bitmap,  // AI超解像→元サイズにリサイズ
        aiLowResBitmap: Bitmap     // AI入力の低解像度版
    ): Bitmap?

    // Streaming JPEG 書き出し
    external fun nativeJpegBeginWrite(fd: Int, width: Int, height: Int, quality: Int): Long
    external fun nativeJpegWriteRows(ctxPtr: Long, bitmap: Bitmap, startRow: Int, numRows: Int): Int
    external fun nativeJpegEndWrite(ctxPtr: Long): Int
    
    // NEON SIMD 最適化機能
    external fun nativeShannonEntropy(inputBitmap: Bitmap): Float
    external fun nativeBuildHistogram(inputBitmap: Bitmap): IntArray
    external fun nativeISauvolaBinarize(
        inputBitmap: Bitmap,
        windowSize: Int,
        k: Float,
        r: Float,
        contrastThreshold: Float
    ): Bitmap?
}
