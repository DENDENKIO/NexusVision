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

    // ラプラシアンピラミッド合成
    external fun nativeLaplacianBlend(
        originalBitmap: Bitmap,   // 元画像（出力サイズにリサイズ済み）
        enhancedBitmap: Bitmap,   // AI超解像結果
        detailStrength: Float,    // 元画像ディテールの強度（1.0=完全保持）
        sharpenStrength: Float    // 仕上げのシャープ化強度（0.3〜0.5推奨）
    ): Bitmap?

    external fun nativeRelease()

    external fun nativeIsLoaded(): Boolean
}
