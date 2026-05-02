package com.nexus.vision.translate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun TranslateScreen() {
    val context = LocalContext.current

    var isRunning by remember { mutableStateOf(false) }
    var selectedSource by remember { mutableStateOf("en") }
    var selectedTarget by remember { mutableStateOf("ja") }
    var pendingStart by remember { mutableStateOf(false) }

    val languages = listOf(
        "en" to "English",
        "ja" to "日本語",
        "zh" to "中文",
        "ko" to "한국어",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "pt" to "Português",
        "ru" to "Русский",
        "ar" to "العربية"
    )

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted && pendingStart) {
            pendingStart = false
            startTranslateService(context, selectedSource, selectedTarget)
            isRunning = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "NEXUS リアルタイム翻訳",
            color = Color.White, fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )

        // ── 言語選択 ──
        Text("認識言語（入力）", color = Color(0xFFBBBBBB), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(4.dp))
        LanguageSelector(
            languages = languages,
            selected = selectedSource,
            onSelect = { selectedSource = it },
            enabled = !isRunning
        )

        Spacer(Modifier.height(16.dp))

        Text("翻訳先（出力）", color = Color(0xFFBBBBBB), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(4.dp))
        LanguageSelector(
            languages = languages,
            selected = selectedTarget,
            onSelect = { selectedTarget = it },
            enabled = !isRunning
        )

        Spacer(Modifier.weight(1f))

        // ── 使い方説明 ──
        if (!isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("使い方", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. 言語を選択して「開始」を押す\n" +
                        "2. YouTubeなどをスピーカーで再生\n" +
                        "3. 画面下部に字幕が表示されます\n" +
                        "4. 字幕バーはドラッグで移動可能",
                        color = Color(0xFF999999), fontSize = 12.sp, lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 開始/停止ボタン ──
        Button(
            onClick = {
                if (isRunning) {
                    // 停止
                    context.startService(
                        Intent(context, LiveTranslateService::class.java)
                            .apply { action = LiveTranslateService.ACTION_STOP }
                    )
                    isRunning = false
                } else {
                    // 1. オーバーレイ権限チェック
                    if (!Settings.canDrawOverlays(context)) {
                        val overlayIntent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(overlayIntent)
                        return@Button
                    }
                    // 2. RECORD_AUDIO 権限チェック（これがないとサービス起動でクラッシュ）
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingStart = true
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@Button
                    }
                    // 3. 権限OK → サービス開始
                    startTranslateService(context, selectedSource, selectedTarget)
                    isRunning = true
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFFF5722) else Color(0xFF2196F3)
            )
        ) {
            Text(
                if (isRunning) "翻訳を停止" else "翻訳を開始",
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSelector(
    languages: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        languages.forEach { (code, name) ->
            FilterChip(
                selected = selected == code,
                onClick = { if (enabled) onSelect(code) },
                label = { Text(name, fontSize = 12.sp) },
                enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF2196F3),
                    selectedLabelColor = Color.White
                )
            )
        }
    }
}

private fun startTranslateService(context: Context, source: String, target: String) {
    val serviceIntent = Intent(context, LiveTranslateService::class.java).apply {
        action = LiveTranslateService.ACTION_START
        putExtra(LiveTranslateService.EXTRA_SOURCE_LANG, source)
        putExtra(LiveTranslateService.EXTRA_TARGET_LANG, target)
    }
    context.startForegroundService(serviceIntent)
}
