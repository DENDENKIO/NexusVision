

# Phase 4 – libjpeg-turbo ストリーミング JPEG 保存：コード作成AI向け指示書

## 1. 背景と目的

NexusVision は Android アプリで、NCNN + Vulkan による Real-ESRGAN 超解像処理を行います。Phase 2 で AI 出力解像度を保持するよう変更したため、最大 4096×4096 (約 64MB RGBA) の画像が生成されます。現在は `Bitmap.compress()` で JPEG 保存していますが、さらに大きい画像では OOM のリスクがあります。libjpeg-turbo を NDK に組み込み、RGBA ピクセルをスキャンライン単位で JPEG に書き出すことで、メモリ使用量を `幅×3 bytes` 程度に抑えます。

---

以下よりコードの実装をお願いします。

## 4. 新規作成するファイル（コード作成AIに依頼）

### 4-A. `app/src/main/jni/streaming_jpeg.h`

ストリーミング JPEG 書き出しの C API ヘッダ。以下の3関数を宣言：

```c
#ifndef STREAMING_JPEG_H
#define STREAMING_JPEG_H

#ifdef __cplusplus
extern "C" {
#endif

/* コンテキスト（不透明ポインタ） */
typedef struct StreamingJpegCtx StreamingJpegCtx;

/*
 * JPEG書き出し開始。
 * fd: 書き込み先ファイルディスクリプタ (Kotlin から ParcelFileDescriptor.detachFd() で取得)
 * width, height: 画像サイズ
 * quality: JPEG品質 (1-100, 推奨 92)
 * 戻り値: コンテキスト (失敗時 NULL)
 */
StreamingJpegCtx* streamingJpegBegin(int fd, int width, int height, int quality);

/*
 * RGBA行データを書き込む。
 * ctx: beginで得たコンテキスト
 * rgbaRows: RGBA ピクセルデータ (numRows * width * 4 bytes)
 * numRows: 書き込む行数
 * 戻り値: 0=成功, -1=失敗
 *
 * 内部で RGBA→RGB 変換してから jpeg_write_scanlines() に渡す。
 * RGB バッファは width*3 bytes のみ使用（1行ずつ変換して書き込み）。
 */
int streamingJpegWriteRows(StreamingJpegCtx* ctx, const unsigned char* rgbaRows, int numRows);

/*
 * 書き出し終了・リソース解放。
 * ctx: beginで得たコンテキスト
 * 戻り値: 0=成功, -1=失敗
 */
int streamingJpegEnd(StreamingJpegCtx* ctx);

#ifdef __cplusplus
}
#endif

#endif /* STREAMING_JPEG_H */
```

### 4-B. `app/src/main/jni/streaming_jpeg.cpp`

実装の要件：
- `#include "streaming_jpeg.h"` と libjpeg-turbo の `jpeglib.h`, `jerror.h` をインクルード
- `StreamingJpegCtx` 構造体に `jpeg_compress_struct`, `jpeg_error_mgr`, `FILE*`, `width`, `height`, RGB行バッファ (`unsigned char*`, サイズ width*3) を保持
- `streamingJpegBegin`: `fdopen(fd, "wb")` → `jpeg_create_compress` → `jpeg_stdio_dest` → `jpeg_set_defaults` → `jpeg_set_quality` → `jpeg_start_compress` → RGB行バッファをmalloc
- `streamingJpegWriteRows`: 引数の RGBA データを1行ずつ RGB に変換（alpha 除去）し `jpeg_write_scanlines` で書き出す
- `streamingJpegEnd`: `jpeg_finish_compress` → `jpeg_destroy_compress` → `fclose` → RGB行バッファ free → ctx free
- エラーログは `__android_log_print(ANDROID_LOG_ERROR, "StreamJPEG", ...)` で出力
- C++ 例外は使わない（`setjmp`/`longjmp` による libjpeg エラーハンドリング）

### 4-C. `realesrgan_jni.cpp` への追加（既存ファイルに3つのJNI関数を追加）

既存の `extern "C" { ... }` ブロック内の末尾（`nativeFusionPipeline` の後）に以下の3関数を追加：

```
Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeJpegBeginWrite
  (JNIEnv*, jclass, jint fd, jint width, jint height, jint quality) → jlong
  streamingJpegBegin() を呼び、コンテキストポインタを jlong で返す

Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeJpegWriteRows
  (JNIEnv*, jclass, jlong ctxPtr, jobject bitmap, jint startRow, jint numRows) → jint
  Bitmap の指定行範囲のピクセルを取得し streamingJpegWriteRows() に渡す
  ※ Bitmap 全体を lockPixels して startRow から numRows 行分だけを渡す

Java_com_nexus_vision_ncnn_RealEsrganBridge_nativeJpegEndWrite
  (JNIEnv*, jclass, jlong ctxPtr) → jint
  streamingJpegEnd() を呼ぶ
```

`#include "streaming_jpeg.h"` をファイル先頭に追加。

### 4-D. `RealEsrganBridge.kt` への追加（既存ファイルに3つの external fun を追加）

```kotlin
// Streaming JPEG 書き出し
external fun nativeJpegBeginWrite(fd: Int, width: Int, height: Int, quality: Int): Long
external fun nativeJpegWriteRows(ctxPtr: Long, bitmap: Bitmap, startRow: Int, numRows: Int): Int
external fun nativeJpegEndWrite(ctxPtr: Long): Int
```

### 4-E. `MainViewModel.kt` への追加

新しい関数 `saveBitmapToGalleryStreaming(bitmap: Bitmap, quality: Int = 92)` を追加。処理フロー：

1. `ContentResolver` で MediaStore に JPEG エントリを作成（`MediaStore.Images.Media.EXTERNAL_CONTENT_URI`）
2. `contentResolver.openFileDescriptor(uri, "w")` でファイルディスクリプタ取得
3. `ParcelFileDescriptor.detachFd()` でネイティブ fd を取得
4. `RealEsrganBridge.nativeJpegBeginWrite(fd, bitmap.width, bitmap.height, quality)` 呼び出し
5. 64行ずつバッチで `nativeJpegWriteRows(ctx, bitmap, startRow, 64)` を呼ぶループ
6. `nativeJpegEndWrite(ctx)` で終了
7. MediaStore の `IS_PENDING` を 0 に更新

既存の `saveBitmapToGallery` 関数で、`bitmap.width * bitmap.height > 4096 * 4096` の場合にこの関数を使う分岐を追加。

---

## 5. `CMakeLists.txt` の変更（既存ファイルを差し替え）

現在の内容：
```cmake
cmake_minimum_required(VERSION 3.22)
project(realesrgan_native CXX)
set(CMAKE_CXX_STANDARD 17)
set(ncnn_DIR "${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)
find_library(JNIGRAPHICS_LIB jnigraphics)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
add_library(realesrgan_native SHARED
    realesrgan_simple.cpp
    realesrgan_jni.cpp
    image_fusion.cpp
)
target_link_libraries(realesrgan_native
    ncnn
    ${JNIGRAPHICS_LIB}
    ${LOG_LIB}
    ${ANDROID_LIB}
)
```

新しい内容（全体を置き換え）：

```cmake
cmake_minimum_required(VERSION 3.22)
project(realesrgan_native C CXX)  # ← C を追加（libjpeg-turbo は C）

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# ─── ncnn (既存) ───
set(ncnn_DIR "${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

find_library(JNIGRAPHICS_LIB jnigraphics)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

# ─── libjpeg-turbo (static library, pure C, no SIMD) ───
set(LIBJPEG_DIR "${CMAKE_SOURCE_DIR}/libjpeg-turbo")

# 手動作成した jconfig.h, jconfigint.h, jversion.h はここにある
# src/*.h (jpeglib.h等) もここ経由で見つかる

file(GLOB LIBJPEG_CORE_SRCS    "${LIBJPEG_DIR}/src/*.c")
file(GLOB LIBJPEG_WRAPPER_SRCS "${LIBJPEG_DIR}/src/wrapper/*.c")

add_library(jpeg_static STATIC
    ${LIBJPEG_CORE_SRCS}
    ${LIBJPEG_WRAPPER_SRCS}
)

target_include_directories(jpeg_static PUBLIC
    ${LIBJPEG_DIR}          # jconfig.h, jconfigint.h, jversion.h
    ${LIBJPEG_DIR}/src      # jpeglib.h, jerror.h 等
)

# 警告抑制（libjpeg-turbo のコードはサードパーティなので）
target_compile_options(jpeg_static PRIVATE -w)

# ─── メインライブラリ ───
add_library(realesrgan_native SHARED
    realesrgan_simple.cpp
    realesrgan_jni.cpp
    image_fusion.cpp
    streaming_jpeg.cpp      # ← 新規追加
)

target_include_directories(realesrgan_native PRIVATE
    ${LIBJPEG_DIR}
    ${LIBJPEG_DIR}/src
)

target_link_libraries(realesrgan_native
    ncnn
    jpeg_static             # ← 新規追加
    ${JNIGRAPHICS_LIB}
    ${LOG_LIB}
    ${ANDROID_LIB}
)
```

---

## 6. 変更ファイル一覧（まとめ）

| ファイル | 操作 | 内容 |
|---|---|---|
| `app/src/main/jni/libjpeg-turbo/` | **新規フォルダ作成** | tar.gz から上記ファイルをコピー + 手動 jconfig.h/jconfigint.h/jversion.h |
| `app/src/main/jni/streaming_jpeg.h` | **新規作成** | ストリーミング JPEG API ヘッダ（セクション 4-A） |
| `app/src/main/jni/streaming_jpeg.cpp` | **新規作成** | 実装（セクション 4-B） |
| `app/src/main/jni/CMakeLists.txt` | **全体置き換え** | セクション 5 の内容に差し替え |
| `app/src/main/jni/realesrgan_jni.cpp` | **末尾に追加** | 3つの JNI 関数（セクション 4-C） |
| `app/src/main/java/.../RealEsrganBridge.kt` | **末尾に追加** | 3つの external fun（セクション 4-D） |
| `app/src/main/java/.../MainViewModel.kt` | **関数追加 + 分岐追加** | streaming 保存関数（セクション 4-E） |

---

## 7. 制約事項（コード作成AI向け）

- C++ 例外は使わない（NDK で例外を無効化している可能性あり）
- libjpeg-turbo のエラーハンドリングは `setjmp`/`longjmp` 方式
- ログタグ: C/C++ 側は `"StreamJPEG"`、既存の JNI は `"RealESRGAN_JNI"`
- `streaming_jpeg.cpp` は C++ ファイルだが、libjpeg-turbo のヘッダは C なので `extern "C"` でラップ済み（`jpeglib.h` は自前で `#ifdef __cplusplus extern "C"` を持っている）
- RGB 行バッファのメモリは `malloc`/`free`（C ライブラリとの互換性）
- `fdopen()` した `FILE*` は `fclose()` で閉じる（fd も自動的に閉じられる）
- ビルドターゲットは `arm64-v8a` のみ（`build.gradle.kts` の `abiFilters` で制限済み）

---

## 8. ビルド確認手順

1. libjpeg-turbo ファイルを `app/src/main/jni/libjpeg-turbo/` に配置
2. 手動ヘッダ 3 ファイルを同ディレクトリに作成
3. `streaming_jpeg.h` / `streaming_jpeg.cpp` を `app/src/main/jni/` に作成
4. `CMakeLists.txt` を差し替え
5. `realesrgan_jni.cpp` と `RealEsrganBridge.kt` に関数追加
6. Android Studio で Build → Make Project
7. ビルドエラーがなければ、大きい画像（4096×4096超）を保存してテスト