// ファイルパス: app/src/main/java/com/nexus/vision/ui/components/ChatInput.kt
package com.nexus.vision.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * チャット入力エリア
 *
 * Phase 4: 基本実装
 * Phase 8.5: ファイル添付ボタン追加
 */
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    selectedImageUri: Uri?,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onClearImage: () -> Unit,
    onSend: () -> Unit,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        // 選択済み画像プレビュー
        if (selectedImageUri != null) {
            Box(
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                AsyncImage(
                    model = selectedImageUri,
                    contentDescription = "選択された画像",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                IconButton(
                    onClick = onClearImage,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .background(
                            MaterialTheme.colorScheme.error,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "画像を削除",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom
        ) {
            // 画像選択ボタン
            IconButton(onClick = onPickImage) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "画像を選択",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // ファイル添付ボタン（CSV / XLSX / PDF）
            IconButton(onClick = onPickFile) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "ファイルを添付 (CSV/Excel/PDF)",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }

            // テキスト入力フィールド
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("メッセージを入力...") },
                maxLines = 4,
                enabled = isEnabled,
                shape = RoundedCornerShape(24.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // 送信ボタン
            IconButton(
                onClick = onSend,
                enabled = isEnabled && (text.isNotBlank() || selectedImageUri != null)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "送信",
                    tint = if (isEnabled && (text.isNotBlank() || selectedImageUri != null))
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        }
    }
}
