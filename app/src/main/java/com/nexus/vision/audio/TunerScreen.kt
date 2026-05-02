package com.nexus.vision.audio

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs

@Composable
fun TunerScreen(
    viewModel: TunerViewModel = viewModel()
) {
    val pitchResult by viewModel.pitchResult.collectAsState()
    val isActive by viewModel.isActive.collectAsState()
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.toggleTuner()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── ヘッダー ──
        Text(
            text = "NEXUS チューナー",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp)
        )

        // ── メイン表示エリア ──
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            if (pitchResult != null) {
                val result = pitchResult!!

                // 音名 + オクターブ
                Text(
                    text = "${result.noteName}${result.octave}",
                    color = Color.White,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 周波数
                Text(
                    text = "%.1f Hz".format(result.frequency),
                    color = Color(0xFFBBBBBB),
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(32.dp))

                // ── セント差メーター ──
                CentsMeter(centsDiff = result.centsDiff)

                Spacer(modifier = Modifier.height(16.dp))

                // セント差の数値
                val centsColor = when {
                    abs(result.centsDiff) < 5f -> Color(0xFF4CAF50) // 緑: ほぼ合っている
                    abs(result.centsDiff) < 15f -> Color(0xFFFFC107) // 黄
                    else -> Color(0xFFFF5722) // 赤
                }
                Text(
                    text = "%+.0f cents".format(result.centsDiff),
                    color = centsColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // チューニング方向のヒント
                val hint = when {
                    result.centsDiff < -5f -> "↑ 高くしてください"
                    result.centsDiff > 5f -> "↓ 低くしてください"
                    else -> "合っています"
                }
                Text(
                    text = hint,
                    color = centsColor.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )

            } else if (isActive) {
                // 音を待っている状態
                Text(
                    text = "音を検出中...",
                    color = Color(0xFF888888),
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "楽器の音をマイクに向けてください",
                    color = Color(0xFF666666),
                    fontSize = 14.sp
                )
            } else {
                // 停止状態
                Text(
                    text = "スタートを押して開始",
                    color = Color(0xFF888888),
                    fontSize = 20.sp
                )
            }
        }

        // ── スタート/ストップボタン ──
        Button(
            onClick = {
                if (!hasPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    viewModel.toggleTuner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActive) Color(0xFFFF5722) else Color(0xFF4CAF50)
            )
        ) {
            Text(
                text = if (isActive) "停止" else "スタート",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun CentsMeter(centsDiff: Float) {
    // -50 ~ +50 を 0.0 ~ 1.0 にマッピング
    val targetPosition = ((centsDiff + 50f) / 100f).coerceIn(0f, 1f)
    val animatedPosition by animateFloatAsState(
        targetValue = targetPosition,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "cents_anim"
    )

    val inTune = abs(centsDiff) < 5f
    val indicatorColor = when {
        abs(centsDiff) < 5f -> Color(0xFF4CAF50)
        abs(centsDiff) < 15f -> Color(0xFFFFC107)
        else -> Color(0xFFFF5722)
    }
    val trackColor = Color(0xFF333333)
    val centerColor = Color(0xFF4CAF50).copy(alpha = 0.5f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp)
    ) {
        val trackY = size.height / 2
        val trackHeight = 6.dp.toPx()
        val indicatorRadius = 12.dp.toPx()

        // トラック背景
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, trackY - trackHeight / 2),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2)
        )

        // 中央マーク（合っている位置）
        val centerX = size.width / 2
        drawLine(
            color = centerColor,
            start = Offset(centerX, trackY - 16.dp.toPx()),
            end = Offset(centerX, trackY + 16.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )

        // インジケーター
        val indicatorX = animatedPosition * size.width
        drawCircle(
            color = indicatorColor,
            radius = indicatorRadius,
            center = Offset(indicatorX, trackY)
        )

        // 合っている時は光る効果
        if (inTune) {
            drawCircle(
                color = indicatorColor.copy(alpha = 0.3f),
                radius = indicatorRadius * 1.6f,
                center = Offset(indicatorX, trackY)
            )
        }
    }
}
