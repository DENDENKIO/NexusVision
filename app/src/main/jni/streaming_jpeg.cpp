#include "streaming_jpeg.h"
#include <stdio.h>
#include <stdlib.h>
#include <setjmp.h>
#include <android/log.h>
#include "jpeglib.h"
#include "jerror.h"

#define TAG "StreamJPEG"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct StreamingJpegCtx {
    struct jpeg_compress_struct cinfo;
    struct jpeg_error_mgr jerr;
    jmp_buf setjmp_buffer;
    FILE* outfile;
    int width;
    int height;
    unsigned char* row_buffer; // RGB buffer (width * 3)
};

/* エラーハンドラ: libjpeg のデフォルトは exit() なので、longjmp で逃げる */
static void streaming_jpeg_error_exit(j_common_ptr cinfo) {
    StreamingJpegCtx* ctx = (StreamingJpegCtx*)cinfo->client_data;
    (*cinfo->err->output_message)(cinfo);
    longjmp(ctx->setjmp_buffer, 1);
}

StreamingJpegCtx* streamingJpegBegin(int fd, int width, int height, int quality) {
    StreamingJpegCtx* ctx = (StreamingJpegCtx*)malloc(sizeof(StreamingJpegCtx));
    if (!ctx) return NULL;

    memset(ctx, 0, sizeof(StreamingJpegCtx));
    ctx->width = width;
    ctx->height = height;

    /* FILE* に変換 */
    ctx->outfile = fdopen(fd, "wb");
    if (!ctx->outfile) {
        LOGE("Failed to fdopen fd %d", fd);
        free(ctx);
        return NULL;
    }

    /* libjpeg 初期化 */
    ctx->cinfo.err = jpeg_std_error(&ctx->jerr);
    ctx->jerr.error_exit = streaming_jpeg_error_exit;
    ctx->cinfo.client_data = ctx;

    if (setjmp(ctx->setjmp_buffer)) {
        LOGE("libjpeg error during begin");
        if (ctx->outfile) fclose(ctx->outfile);
        free(ctx);
        return NULL;
    }

    jpeg_create_compress(&ctx->cinfo);
    jpeg_stdio_dest(&ctx->cinfo, ctx->outfile);

    ctx->cinfo.image_width = width;
    ctx->cinfo.image_height = height;
    ctx->cinfo.input_components = 3;     /* RGB */
    ctx->cinfo.in_color_space = JCS_RGB;

    jpeg_set_defaults(&ctx->cinfo);
    jpeg_set_quality(&ctx->cinfo, quality, TRUE);
    jpeg_start_compress(&ctx->cinfo, TRUE);

    /* 1行分の RGB バッファを確保 */
    ctx->row_buffer = (unsigned char*)malloc(width * 3);
    if (!ctx->row_buffer) {
        LOGE("Failed to allocate row buffer");
        jpeg_destroy_compress(&ctx->cinfo);
        fclose(ctx->outfile);
        free(ctx);
        return NULL;
    }

    return ctx;
}

int streamingJpegWriteRows(StreamingJpegCtx* ctx, const unsigned char* rgbaRows, int numRows) {
    if (!ctx || !rgbaRows) return -1;

    if (setjmp(ctx->setjmp_buffer)) {
        LOGE("libjpeg error during write");
        return -1;
    }

    const unsigned char* pRgba = rgbaRows;
    for (int i = 0; i < numRows; i++) {
        /* RGBA -> RGB 変換 (Alpha 除去) */
        unsigned char* pRgb = ctx->row_buffer;
        for (int x = 0; x < ctx->width; x++) {
            pRgb[0] = pRgba[0]; // R
            pRgb[1] = pRgba[1]; // G
            pRgb[2] = pRgba[2]; // B
            pRgba += 4;         // A を飛ばす
            pRgb += 3;
        }

        /* 1行書き出し */
        JSAMPROW row_pointer[1];
        row_pointer[0] = ctx->row_buffer;
        jpeg_write_scanlines(&ctx->cinfo, row_pointer, 1);
    }

    return 0;
}

int streamingJpegEnd(StreamingJpegCtx* ctx) {
    if (!ctx) return -1;

    if (setjmp(ctx->setjmp_buffer)) {
        LOGE("libjpeg error during end");
        /* 失敗してもメモリ解放は進める */
    } else {
        jpeg_finish_compress(&ctx->cinfo);
    }

    jpeg_destroy_compress(&ctx->cinfo);
    if (ctx->outfile) fclose(ctx->outfile);
    if (ctx->row_buffer) free(ctx->row_buffer);
    free(ctx);

    return 0;
}
