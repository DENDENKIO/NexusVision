package com.nexus.vision.audio

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
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

@Composable
fun TunerScreen() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, TunerOverlayService::class.java).apply {
                action = TunerOverlayService.ACTION_START
                putExtra(TunerOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            context.startForegroundService(intent)
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
            "NEXUS チューナー",
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )

        Spacer(Modifier.weight(1f))

        // 使い方カード
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
                        "1. 「スタート」を押してキャプチャ許可\n" +
                        "2. 他のアプリ（YouTube等）に切り替え\n" +
                        "3. フローティングの緑「♪」ボタンで解析開始\n" +
                        "4. ドラッグでオーバーレイ表示位置を移動\n" +
                        "5. ピアノロール風グラフで音程の推移を確認\n" +
                        "6. 「♪」ボタン再タップで解析停止",
                        color = Color(0xFF999999), fontSize = 12.sp, lineHeight = 18.sp
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2E1B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("チューナー実行中", color = Color(0xFF4CAF50), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "他のアプリに切り替えてご使用ください。\n画面上の「♪」ボタンで解析の開始/停止ができます。",
                        color = Color(0xFF88BB88), fontSize = 13.sp, lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ★ ボタン1つだけ
        Button(
            onClick = {
                if (isRunning) {
                    context.startService(
                        Intent(context, TunerOverlayService::class.java)
                            .apply { action = TunerOverlayService.ACTION_STOP }
                    )
                    isRunning = false
                } else {
                    if (!Settings.canDrawOverlays(context)) {
                        context.startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        ))
                        return@Button
                    }
                    val pm = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(pm.createScreenCaptureIntent())
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFFF5722) else Color(0xFF4CAF50)
            )
        ) {
            Text(
                if (isRunning) "チューナーを停止" else "スタート",
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
