# ファイルパス: app/src/main/jni/CMakeLists.txt
cmake_minimum_required(VERSION 3.22)
project(realesrgan_native CXX)

set(CMAKE_CXX_STANDARD 17)

# ncnn (CPU版 static)
set(ncnn_DIR "${CMAKE_SOURCE_DIR}/ncnn-android/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

# Android 標準ライブラリ
find_library(jnigraphics-lib jnigraphics)
find_library(log-lib log)
find_library(android-lib android)

add_library(realesrgan_native SHARED
    realesrgan_simple.cpp
    realesrgan_jni.cpp
)

target_link_libraries(realesrgan_native
    ncnn
    ${jnigraphics-lib}
    ${log-lib}
    ${android-lib}
)
