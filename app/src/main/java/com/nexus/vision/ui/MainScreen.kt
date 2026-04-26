// ファイルパス: app/src/main/java/com/nexus/vision/ui/MainScreen.kt

package com.nexus.vision.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.vision.ui.components.ChatBubble
import com.nexus.vision.ui.components.ChatInput
import com.nexus.vision.ui.components.CropSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onPickImage: () -> Unit = {},
    onImageSelected: ((android.net.Uri) -> Unit) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        onImageSelected { uri ->
            viewModel.setSelectedImage(uri)
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NEXUS Vision",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        ThermalBadge(levelName = uiState.thermalLevelName)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                HorizontalDivider()

                // 範囲選択モード中はクロップUIを表示
                if (uiState.cropMode && uiState.cropThumbnail != null) {
                    CropSelector(
                        thumbnail = uiState.cropThumbnail!!,
                        imageWidth = uiState.cropImageWidth,
                        imageHeight = uiState.cropImageHeight,
                        onConfirm = { left, top, right, bottom ->
                            viewModel.onCropConfirmed(left, top, right, bottom)
                        },
                        onCancel = { viewModel.cancelCropMode() }
                    )
                } else {
                    ChatInput(
                        text = uiState.inputText,
                        onTextChange = { viewModel.updateInputText(it) },
                        selectedImageUri = uiState.selectedImageUri,
                        onPickImage = onPickImage,
                        onClearImage = { viewModel.clearSelectedImage() },
                        onSend = { viewModel.sendMessage() },
                        isEnabled = !uiState.isProcessing
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!uiState.isEngineReady && !uiState.isProcessing) {
                EngineLoadBanner(
                    statusMessage = uiState.statusMessage,
                    onLoadClick = { viewModel.loadEngine() }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    ChatBubble(message = message)
                }
            }
        }
    }
}

@Composable
private fun EngineLoadBanner(
    statusMessage: String,
    onLoadClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Button(
                onClick = onLoadClick,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("エンジンをロード")
            }
        }
    }
}

@Composable
fun ThermalBadge(levelName: String) {
    val color = when (levelName) {
        "NONE", "LIGHT" -> MaterialTheme.colorScheme.outline
        "MODERATE" -> MaterialTheme.colorScheme.tertiary
        "SEVERE" -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.error
    }

    if (levelName != "NONE") {
        Text(
            text = levelName,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
