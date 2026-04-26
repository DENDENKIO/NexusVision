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

    // シャープ化（同サイズ出力、超解像不要な大画像向け）
    external fun nativeSharpen(inputBitmap: Bitmap, strength: Float): Bitmap?

    external fun nativeRelease()

    external fun nativeIsLoaded(): Boolean
}
