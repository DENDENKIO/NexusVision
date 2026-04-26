// ファイルパス: app/src/main/java/com/nexus/vision/ui/components/CropSelector.kt
package com.nexus.vision.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import android.graphics.Bitmap

/**
 * 画像の範囲選択コンポーネント
 * サムネイルを表示し、ユーザーがドラッグで矩形を選択
 * onCropSelected: 選択範囲を元画像上の座標比率(0.0〜1.0)で返す
 */
@Composable
fun CropSelector(
    thumbnail: Bitmap,
    onCropSelected: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }

    Box(
        modifier = modifier
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStart = offset
                        dragEnd = offset
                    },
                    onDrag = { change, _ ->
                        dragEnd = change.position
                    },
                    onDragEnd = {
                        val start = dragStart
                        val end = dragEnd
                        if (start != null && end != null && viewSize.width > 0 && viewSize.height > 0) {
                            val left = (minOf(start.x, end.x) / viewSize.width).coerceIn(0f, 1f)
                            val top = (minOf(start.y, end.y) / viewSize.height).coerceIn(0f, 1f)
                            val right = (maxOf(start.x, end.x) / viewSize.width).coerceIn(0f, 1f)
                            val bottom = (maxOf(start.y, end.y) / viewSize.height).coerceIn(0f, 1f)
                            if (right - left > 0.02f && bottom - top > 0.02f) {
                                onCropSelected(left, top, right, bottom)
                            }
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // サムネイル描画
            drawImage(
                image = imageBitmap,
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )

            // 選択矩形描画
            val s = dragStart
            val e = dragEnd
            if (s != null && e != null) {
                val rect = Rect(
                    left = minOf(s.x, e.x),
                    top = minOf(s.y, e.y),
                    right = maxOf(s.x, e.x),
                    bottom = maxOf(s.y, e.y)
                )
                // 半透明オーバーレイ（選択外）
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset.Zero,
                    size = Size(size.width, rect.top)
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(0f, rect.bottom),
                    size = Size(size.width, size.height - rect.bottom)
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(0f, rect.top),
                    size = Size(rect.left, rect.height)
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(rect.right, rect.top),
                    size = Size(size.width - rect.right, rect.height)
                )
                // 選択枠
                drawRect(
                    color = Color.White,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = 3f)
                )
                drawRect(
                    color = Color.Cyan,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = 1.5f)
                )
            }
        }
    }
}
