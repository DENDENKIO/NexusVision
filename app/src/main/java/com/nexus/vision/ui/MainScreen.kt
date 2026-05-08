// ファイルパス: app/src/main/java/com/nexus/vision/ui/MainScreen.kt
package com.nexus.vision.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import com.nexus.vision.ui.components.ChatBubble
import com.nexus.vision.ui.components.ChatInput
import com.nexus.vision.ui.components.CropSelector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ShoppingCart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onPickImage: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onPickMultipleImages: () -> Unit = {},
    onNavigateToTuner: () -> Unit = {},
    onNavigateToTranslate: () -> Unit = {},
    onNavigateToRetail: () -> Unit = {},
    onImageSelected: ((android.net.Uri) -> Unit) -> Unit = {},
    onMultipleImagesSelected: ((List<android.net.Uri>) -> Unit) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        onImageSelected { uri ->
            viewModel.setSelectedImage(uri)
        }
        onMultipleImagesSelected { uris ->
            viewModel.startBatchEnhance(uris)
        }
    }

    LaunchedEffect(uiState.requestBatchPicker) {
        if (uiState.requestBatchPicker) {
            viewModel.consumeBatchPickerRequest()
            onPickMultipleImages()
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
                actions = {
                    androidx.compose.material3.IconButton(onClick = onNavigateToRetail) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.ShoppingCart,
                            contentDescription = "業務ツール",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = onNavigateToTuner) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "チューナー",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = onNavigateToTranslate) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "翻訳",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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

                if (uiState.isBatchRunning && uiState.batchProgressText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = uiState.batchProgressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (uiState.cropMode && uiState.cropThumbnail != null) {
                    val (confirmLabel, headerLabel) = when (uiState.cropPurpose) {
                        CropPurpose.ENHANCE -> "この範囲を高画質化" to "高画質化したい範囲をドラッグで選択"
                        CropPurpose.ZOOM -> "この範囲を超解像" to "拡大したい範囲をドラッグで選択"
                    }

                    CropSelector(
                        thumbnail = uiState.cropThumbnail!!,
                        imageWidth = uiState.cropImageWidth,
                        imageHeight = uiState.cropImageHeight,
                        headerLabel = headerLabel,
                        confirmLabel = confirmLabel,
                        onConfirm = { left, top, right, bottom ->
                            viewModel.onCropConfirmed(left, top, right, bottom)
                        },
                        onCancel = { viewModel.cancelCropMode() }
                    )
                } else {
                    // DB選択チップ
                    val selectedSources by viewModel.selectedSources.collectAsState()
                    val allSources = com.nexus.vision.search.SearchSourceRegistry.getAll()
                    
                    DbSourceSelector(
                        sources      = allSources,
                        selectedIds  = selectedSources,
                        currentInput = uiState.inputText,
                        onToggle     = { sourceId, displayName, nowSelected ->
                            viewModel.toggleSourceWithText(sourceId, displayName, nowSelected)
                        },
                        onClearAll   = { viewModel.clearAllSources() }
                    )

                    ChatInput(
                        text = uiState.inputText,
                        onTextChange = { viewModel.updateInputText(it) },
                        selectedImageUri = uiState.selectedImageUri,
                        onPickImage = onPickImage,
                        onPickFile = onPickFile,
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
                itemsIndexed(
                    items = uiState.messages,
                    key = { _, msg -> msg.id }
                ) { index, message ->
                    val messageNumber = index + 1 // 1始まり
                    ChatBubble(
                        message = message,
                        messageNumber = messageNumber,
                        onQuote = { quoteTag ->
                            // 引用タグを入力テキストに追加
                            viewModel.appendQuoteTag(quoteTag)
                        },
                        onCopy = { text ->
                            // クリップボードにコピー
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("NEXUS Vision", text)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "コピーしました", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbSourceSelector(
    sources:      List<com.nexus.vision.search.SearchSource>,
    selectedIds:  Set<String>,
    currentInput: String,
    onToggle:     (sourceId: String, displayName: String, nowSelected: Boolean) -> Unit,
    onClearAll:   () -> Unit
) {
    if (sources.isEmpty()) return

    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 「全DB」チップ（選択中DBが1つ以上あるときに表示）
        if (selectedIds.isNotEmpty()) {
            FilterChip(
                selected = false,
                onClick  = onClearAll,
                label    = { Text("✕ 全解除") }
            )
        } else {
            FilterChip(
                selected = true,
                onClick  = { /* 全DB状態は維持 */ },
                label    = { Text("🗄️ 全DB") }
            )
        }

        sources.forEach { src ->
            val id = (src as? com.nexus.vision.search.IdentifiableSource)?.sourceId
                ?: return@forEach
            val isSelected = id in selectedIds

            FilterChip(
                selected = isSelected,
                onClick  = {
                    // nowSelected: タップ後の状態（現在の逆）
                    onToggle(id, src.displayName, !isSelected)
                },
                label = {
                    Text(
                        text = "${src.icon} ${src.displayName}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }
}

@Composable
fun SearchResultCard(result: com.nexus.vision.search.SearchResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text  = "${result.sourceName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            // 横スクロール対応の項目表示
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                result.fields.forEach { (key, value) ->
                    if (value.isNotBlank()) {
                        Column(modifier = Modifier.padding(end = 12.dp)) {
                            Text(key,   style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
