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

set(LIBJPEG_CORE_SRCS
    ${LIBJPEG_DIR}/src/jcapimin.c
    ${LIBJPEG_DIR}/src/jchuff.c
    ${LIBJPEG_DIR}/src/jcicc.c
    ${LIBJPEG_DIR}/src/jcinit.c
    ${LIBJPEG_DIR}/src/jclhuff.c
    ${LIBJPEG_DIR}/src/jcmarker.c
    ${LIBJPEG_DIR}/src/jcmaster.c
    ${LIBJPEG_DIR}/src/jcomapi.c
    ${LIBJPEG_DIR}/src/jcparam.c
    ${LIBJPEG_DIR}/src/jcphuff.c
    ${LIBJPEG_DIR}/src/jctrans.c
    ${LIBJPEG_DIR}/src/jdapimin.c
    ${LIBJPEG_DIR}/src/jdatadst.c
    ${LIBJPEG_DIR}/src/jdatasrc.c
    ${LIBJPEG_DIR}/src/jdhuff.c
    ${LIBJPEG_DIR}/src/jdicc.c
    ${LIBJPEG_DIR}/src/jdinput.c
    ${LIBJPEG_DIR}/src/jdlhuff.c
    ${LIBJPEG_DIR}/src/jdmarker.c
    ${LIBJPEG_DIR}/src/jdmaster.c
    ${LIBJPEG_DIR}/src/jdphuff.c
    ${LIBJPEG_DIR}/src/jdtrans.c
    ${LIBJPEG_DIR}/src/jerror.c
    ${LIBJPEG_DIR}/src/jfdctflt.c
    ${LIBJPEG_DIR}/src/jmemmgr.c
    ${LIBJPEG_DIR}/src/jmemnobs.c
    ${LIBJPEG_DIR}/src/jpeg_nbits.c
    ${LIBJPEG_DIR}/src/jaricom.c
    ${LIBJPEG_DIR}/src/jcarith.c
    ${LIBJPEG_DIR}/src/jdarith.c
)

set(LIBJPEG_WRAPPER_SRCS
    ${LIBJPEG_DIR}/src/wrapper/jcapistd-8.c
    ${LIBJPEG_DIR}/src/wrapper/jcapistd-12.c
    ${LIBJPEG_DIR}/src/wrapper/jcapistd-16.c
    ${LIBJPEG_DIR}/src/wrapper/jccoefct-8.c
    ${LIBJPEG_DIR}/src/wrapper/jccoefct-12.c
    ${LIBJPEG_DIR}/src/wrapper/jccolor-8.c
    ${LIBJPEG_DIR}/src/wrapper/jccolor-12.c
    ${LIBJPEG_DIR}/src/wrapper/jccolor-16.c
    ${LIBJPEG_DIR}/src/wrapper/jcdctmgr-8.c
    ${LIBJPEG_DIR}/src/wrapper/jcdctmgr-12.c
    ${LIBJPEG_DIR}/src/wrapper/jcdiffct-8.c
    ${LIBJPEG_DIR}/src/wrapper/jcdiffct-12.c
    ${LIBJPEG_DIR}/src/wrapper/jcdiffct-16.c
    ${LIBJPEG_DIR}/src/wrapper/jclossls-8.c
    ${LIBJPEG_DIR}/src/wrapper/jclossls-12.c
    ${LIBJPEG_DIR}/src/wrapper/jclossls-16.c
    ${LIBJPEG_DIR}/src/wrapper/jcmainct-8.c
    ${LIBJPEG_DIR}/src/wrapper/jcmainct-12.c
    ${LIBJPEG_DIR}/src/wrapper/jcmainct-16.c
    ${LIBJPEG_DIR}/src/wrapper/jcprepct-8.c
    ${LIBJPEG_DIR}/src/wrapper/jcprepct-12.c
    ${LIBJPEG_DIR}/src/wrapper/jcprepct-16.c
    ${LIBJPEG_DIR}/src/wrapper/jcsample-8.c
    ${LIBJPEG_DIR}/src/wrapper/jcsample-12.c
    ${LIBJPEG_DIR}/src/wrapper/jcsample-16.c
    ${LIBJPEG_DIR}/src/wrapper/jdapistd-8.c
    ${LIBJPEG_DIR}/src/wrapper/jdapistd-12.c
    ${LIBJPEG_DIR}/src/wrapper/jdapistd-16.c
    ${LIBJPEG_DIR}/src/wrapper/jdcoefct-8.c
    ${LIBJPEG_DIR}/src/wrapper/jdcoefct-12.c
    ${LIBJPEG_DIR}/src/wrapper/jdcolor-8.c
    ${LIBJPEG_DIR}/src/wrapper/jdcolor-12.c
    ${LIBJPEG_DIR}/src/wrapper/jdcolor-16.c
    ${LIBJPEG_DIR}/src/wrapper/jddctmgr-8.c
    ${LIBJPEG_DIR}/src/wrapper/jddctmgr-12.c
    ${LIBJPEG_DIR}/src/wrapper/jddiffct-8.c
    ${LIBJPEG_DIR}/src/wrapper/jddiffct-12.c
    ${LIBJPEG_DIR}/src/wrapper/jddiffct-16.c
    ${LIBJPEG_DIR}/src/wrapper/jdlossls-8.c
    ${LIBJPEG_DIR}/src/wrapper/jdlossls-12.c
    ${LIBJPEG_DIR}/src/wrapper/jdlossls-16.c
    ${LIBJPEG_DIR}/src/wrapper/jdmainct-8.c
    ${LIBJPEG_DIR}/src/wrapper/jdmainct-12.c
    ${LIBJPEG_DIR}/src/wrapper/jdmainct-16.c
    ${LIBJPEG_DIR}/src/wrapper/jdmerge-8.c
    ${LIBJPEG_DIR}/src/wrapper/jdmerge-12.c
    ${LIBJPEG_DIR}/src/wrapper/jdpostct-8.c
    ${LIBJPEG_DIR}/src/wrapper/jdpostct-12.c
    ${LIBJPEG_DIR}/src/wrapper/jdpostct-16.c
    ${LIBJPEG_DIR}/src/wrapper/jdsample-8.c
    ${LIBJPEG_DIR}/src/wrapper/jdsample-12.c
    ${LIBJPEG_DIR}/src/wrapper/jdsample-16.c
    ${LIBJPEG_DIR}/src/wrapper/jfdctfst-8.c
    ${LIBJPEG_DIR}/src/wrapper/jfdctfst-12.c
    ${LIBJPEG_DIR}/src/wrapper/jfdctint-8.c
    ${LIBJPEG_DIR}/src/wrapper/jfdctint-12.c
    ${LIBJPEG_DIR}/src/wrapper/jidctflt-8.c
    ${LIBJPEG_DIR}/src/wrapper/jidctflt-12.c
    ${LIBJPEG_DIR}/src/wrapper/jidctfst-8.c
    ${LIBJPEG_DIR}/src/wrapper/jidctfst-12.c
    ${LIBJPEG_DIR}/src/wrapper/jidctint-8.c
    ${LIBJPEG_DIR}/src/wrapper/jidctint-12.c
    ${LIBJPEG_DIR}/src/wrapper/jidctred-8.c
    ${LIBJPEG_DIR}/src/wrapper/jidctred-12.c
    ${LIBJPEG_DIR}/src/wrapper/jquant1-8.c
    ${LIBJPEG_DIR}/src/wrapper/jquant1-12.c
    ${LIBJPEG_DIR}/src/wrapper/jquant2-8.c
    ${LIBJPEG_DIR}/src/wrapper/jquant2-12.c
    ${LIBJPEG_DIR}/src/wrapper/jutils-8.c
    ${LIBJPEG_DIR}/src/wrapper/jutils-12.c
    ${LIBJPEG_DIR}/src/wrapper/jutils-16.c
)

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
    streaming_jpeg.cpp
    neon_entropy.cpp
    neon_dwt.cpp
    neon_isauvola.cpp
    safmn_jni.cpp          # ← Phase 14-1 追加
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
