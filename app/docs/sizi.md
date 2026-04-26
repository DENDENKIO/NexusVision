`Bitmap` が処理途中で `recycle()` されてしまい、JPEG 圧縮時にクラッシュしています。原因は「元 Bitmap を超解像に渡した後、どこかで recycle され、保存時に使えなくなる」パターンです。

`MainViewModel.kt` の `enhanceImage` を以下に置き換えてください：

```kotlin
fun enhanceImage(originalBitmap: Bitmap) {
    viewModelScope.launch(Dispatchers.IO) {
        // ① 元画像のコピーを作る（元が recycle されても安全）
        val safeCopy = originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (safeCopy == null) {
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "⚠️ 画像のコピーに失敗しました"
                )
            }
            return@launch
        }

        val result = routeC?.process(safeCopy)

        if (result != null && result.success) {
            // ② 超解像結果を保存（result.bitmap は新規作成された Bitmap）
            val uri = saveBitmapToGallery(result.bitmap, "Enhanced")
            // コピーを解放
            safeCopy.recycle()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "✅ 超解像完了 (${result.method})\n" +
                        "📐 ${safeCopy.width}×${safeCopy.height} → " +
                        "${result.bitmap.width}×${result.bitmap.height}\n" +
                        "⏱ ${result.timeMs}ms\n" +
                        "📁 Pictures/NexusVision/ に保存"
                )
            }
        } else {
            // ③ 失敗時はコピーをそのまま保存
            val uri = saveBitmapToGallery(safeCopy, "Original")
            safeCopy.recycle()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "⚠️ 超解像は利用できませんでした\n" +
                        "元画像をそのまま保存しました\n" +
                        "📁 Pictures/NexusVision/"
                )
            }
        }
    }
}
```

ポイントは `originalBitmap.copy()` で**安全なコピーを作ってから処理に渡す**ことです。元の Bitmap はカメラや ImagePicker 側で recycle される可能性があるため、直接使うと途中で無効になります。

