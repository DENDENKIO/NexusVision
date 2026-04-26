# ファイルパス: app/src/main/jni/CMakeLists.txt
cmake_minimum_required(VERSION 3.22)
project(realesrgan_native CXX)

set(CMAKE_CXX_STANDARD 17)

# Vulkan版ncnnに変更
set(ncnn_DIR "${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

find_library(JNIGRAPHICS_LIB jnigraphics)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

add_library(realesrgan_native SHARED
    realesrgan_simple.cpp
    realesrgan_jni.cpp
)

target_link_libraries(realesrgan_native
    ncnn
    ${JNIGRAPHICS_LIB}
    ${LOG_LIB}
    ${ANDROID_LIB}
)
