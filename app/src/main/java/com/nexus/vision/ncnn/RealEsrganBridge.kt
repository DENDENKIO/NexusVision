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

    // ガイデッドブレンド: 元画像の構造 + AI結果を合成
    external fun nativeGuidedBlend(
        guideBitmap: Bitmap,
        enhancedBitmap: Bitmap,
        aiWeight: Float
    ): Bitmap?

    external fun nativeRelease()

    external fun nativeIsLoaded(): Boolean
}
