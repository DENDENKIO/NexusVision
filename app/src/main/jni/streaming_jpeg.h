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
