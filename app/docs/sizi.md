app/docs/SAFMN_CONVERSION_GUIDE.md を読み込んだ上で、以下のファイルの完全な実装コードを作成してほしい。既存の RealEsrganBridge.kt と realesrgan_jni.cpp のインターフェースに合わせること。

モデル仕様：

SAFMN ×4 SR、NCNN 形式（safmn_x4.ncnn.param / safmn_x4.ncnn.bin）
assets パス: models/safmn_x4.ncnn.param, models/safmn_x4.ncnn.bin
入力: RGB, [0,1] 正規化, 128×128 タイル
出力: RGB, [0,1], 512×512（×4）
Vulkan GPU 優先、フォールバック CPU
作成するファイル：

SafmnBridge.kt — nativeInit / nativeProcess / nativeRelease / nativeIsLoaded
safmn_jni.cpp — ncnn 推論（タイルサイズ 64、×4 スケール）
CMakeLists.txt の差分
RouteCProcessor.kt の差分（SAFMN_PP モデルタイプ追加）
参考ファイル: RealEsrganBridge.kt, realesrgan_jni.cpp を読み込んで同じパターンで作成すること。