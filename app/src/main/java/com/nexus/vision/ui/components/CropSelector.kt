// ファイルパス: app/src/main/java/com/nexus/vision/ui/components/CropSelector.kt
package com.nexus.vision.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 画像の範囲選択コンポーネント
 * 「この範囲を超解像」ボタンで確定
 */
@Composable
fun CropSelector(
    thumbnail: Bitmap,
    imageWidth: Int,
    imageHeight: Int,
    onConfirm: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var confirmed by remember { mutableStateOf(false) }

    val imageBitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
    val aspectRatio = thumbnail.width.toFloat() / thumbnail.height.toFloat()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "拡大したい範囲をドラッグで選択 (${imageWidth}×${imageHeight})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // キャンバス（サムネイル＋選択矩形）
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(8.dp))
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragEnd = offset
                            confirmed = false
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragEnd = change.position
                        },
                        onDragEnd = { confirmed = true }
                    )
                }
        ) {
            // サムネイル描画
            drawImage(
                image = imageBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(imageBitmap.width, imageBitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )

            // 選択矩形
            val s = dragStart
            val e = dragEnd
            if (s != null && e != null) {
                val rLeft = minOf(s.x, e.x).coerceIn(0f, size.width)
                val rTop = minOf(s.y, e.y).coerceIn(0f, size.height)
                val rRight = maxOf(s.x, e.x).coerceIn(0f, size.width)
                val rBottom = maxOf(s.y, e.y).coerceIn(0f, size.height)
                val rW = rRight - rLeft
                val rH = rBottom - rTop

                if (rW > 4f && rH > 4f) {
                    // 暗いオーバーレイ（選択外）
                    val dimColor = Color.Black.copy(alpha = 0.5f)
                    // 上
                    drawRect(dimColor, Offset.Zero, Size(size.width, rTop))
                    // 下
                    drawRect(dimColor, Offset(0f, rBottom), Size(size.width, size.height - rBottom))
                    // 左
                    drawRect(dimColor, Offset(0f, rTop), Size(rLeft, rH))
                    // 右
                    drawRect(dimColor, Offset(rRight, rTop), Size(size.width - rRight, rH))

                    // 選択枠
                    drawRect(
                        Color.Cyan,
                        Offset(rLeft, rTop),
                        Size(rW, rH),
                        style = Stroke(width = 2.5f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ボタン
        Row {
            OutlinedButton(onClick = onCancel) {
                Text("キャンセル")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    val s = dragStart
                    val e = dragEnd
                    if (s != null && e != null && canvasSize.width > 0 && canvasSize.height > 0) {
                        val left = (minOf(s.x, e.x) / canvasSize.width).coerceIn(0f, 1f)
                        val top = (minOf(s.y, e.y) / canvasSize.height).coerceIn(0f, 1f)
                        val right = (maxOf(s.x, e.x) / canvasSize.width).coerceIn(0f, 1f)
                        val bottom = (maxOf(s.y, e.y) / canvasSize.height).coerceIn(0f, 1f)
                        if (right - left > 0.02f && bottom - top > 0.02f) {
                            onConfirm(left, top, right, bottom)
                        }
                    }
                },
                enabled = confirmed && dragStart != null && dragEnd != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("この範囲を超解像")
            }
        }
    }
}
