package com.nexus.vision.ncnn

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * SAFMN++ NCNN ブリッジ (JNI)
 * Phase 14-1: 軽量高品質 SR モデル
 */
object SafmnBridge {

    init {
        System.loadLibrary("realesrgan_native") // 同一 .so に追加
    }

    external fun nativeSafmnInit(
        assetManager: AssetManager,
        paramPath: String,
        modelPath: String,
        scale: Int,
        tileSize: Int
    ): Boolean

    external fun nativeSafmnProcess(inputBitmap: Bitmap): Bitmap?

    external fun nativeSafmnRelease()

    external fun nativeSafmnIsLoaded(): Boolean
}
