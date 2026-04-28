/* jconfig.h - manual configuration for Android arm64-v8a */
#define JPEG_LIB_VERSION  62
#define LIBJPEG_TURBO_VERSION  "3.1.4.1"
#define LIBJPEG_TURBO_VERSION_NUMBER  3001004
#define C_ARITH_CODING_SUPPORTED  1
#define D_ARITH_CODING_SUPPORTED  1
#define MEM_SRCDST_SUPPORTED  1
/* SIMD disabled - using jsimd_none.c */
/* #undef WITH_SIMD */
#ifndef BITS_IN_JSAMPLE
#define BITS_IN_JSAMPLE  8
#endif
/* #undef RIGHT_SHIFT_IS_UNSIGNED */
