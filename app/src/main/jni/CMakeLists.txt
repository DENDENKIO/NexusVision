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
