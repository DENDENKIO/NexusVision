package com.nexus.vision.audio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    val pitchHistory by viewModel.pitchHistory.collectAsState()
    val isActive by viewModel.isActive.collectAsState()
    val captureMode by viewModel.captureMode.collectAsState()
    val context = LocalContext.current

    var isOverlayRunning by remember { mutableStateOf(false) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) viewModel.startMicTuner()
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, SystemAudioCaptureService::class.java).apply {
                action = SystemAudioCaptureService.ACTION_START
                putExtra(SystemAudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            context.startForegroundService(serviceIntent)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                SystemAudioCaptureService.activeProjection?.let { projection ->
                    viewModel.startSystemTuner(projection)
                }
            }, 500)
        }
    }

    // ★ オーバーレイモード用のランチャー
    val overlayProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, TunerOverlayService::class.java).apply {
                action = TunerOverlayService.ACTION_START
                putExtra(TunerOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            context.startForegroundService(intent)
            isOverlayRunning = true
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
            text = "NEXUS チューナー",
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── モード切替タブ ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ModeTab("マイク", captureMode == AudioCaptureManager.CaptureMode.MIC) {
                if (isActive) viewModel.stopTuner()
                viewModel.setCaptureMode(AudioCaptureManager.CaptureMode.MIC)
            }
            ModeTab("システム音声", captureMode == AudioCaptureManager.CaptureMode.SYSTEM) {
                if (isActive) viewModel.stopTuner()
                viewModel.setCaptureMode(AudioCaptureManager.CaptureMode.SYSTEM)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── メイン表示 ──
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(0.4f),
            verticalArrangement = Arrangement.Center
        ) {
            val displayResult = pitchResult ?: pitchHistory.lastOrNull { it != null }
            if (displayResult != null) {
                Text("${displayResult.noteName}${displayResult.octave}",
                    color = if (pitchResult != null) Color.White else Color.White.copy(alpha = 0.5f),
                    fontSize = 72.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("%.1f Hz".format(displayResult.frequency),
                    color = if (pitchResult != null) Color(0xFFBBBBBB) else Color(0xFF666666),
                    fontSize = 18.sp)
                Spacer(Modifier.height(20.dp))
                CentsMeter(centsDiff = displayResult.centsDiff)
                Spacer(Modifier.height(8.dp))
                val centsColor = when {
                    abs(displayResult.centsDiff) < 5f -> Color(0xFF4CAF50)
                    abs(displayResult.centsDiff) < 15f -> Color(0xFFFFC107)
                    else -> Color(0xFFFF5722)
                }
                Text("%+.0f cents".format(displayResult.centsDiff),
                    color = if (pitchResult != null) centsColor else centsColor.copy(alpha = 0.4f),
                    fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            } else if (isActive) {
                Text("音を検出中...", color = Color(0xFF888888), fontSize = 24.sp)
                Spacer(Modifier.height(8.dp))
                val hint = if (captureMode == AudioCaptureManager.CaptureMode.MIC)
                    "楽器の音をマイクに向けてください" else "アプリで音声を再生してください"
                Text(hint, color = Color(0xFF666666), fontSize = 14.sp)
            } else {
                Text("スタートを押して開始", color = Color(0xFF888888), fontSize = 20.sp)
            }
        }

        // ── 波形履歴 ──
        if (pitchHistory.isNotEmpty()) {
            Text("検出履歴", color = Color(0xFF888888), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(4.dp))
            PitchHistoryWave(history = pitchHistory,
                modifier = Modifier.fillMaxWidth().weight(0.35f))
        } else {
            Spacer(Modifier.weight(0.35f))
        }

        Spacer(Modifier.height(12.dp))

        // ── スタート/ストップボタン ──
        Button(
            onClick = {
                if (isActive) {
                    viewModel.stopTuner()
                    if (captureMode == AudioCaptureManager.CaptureMode.SYSTEM) {
                        context.startService(
                            Intent(context, SystemAudioCaptureService::class.java)
                                .apply { action = SystemAudioCaptureService.ACTION_STOP })
                    }
                } else {
                    when (captureMode) {
                        AudioCaptureManager.CaptureMode.MIC -> {
                            if (!hasAudioPermission) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else viewModel.startMicTuner()
                        }
                        AudioCaptureManager.CaptureMode.SYSTEM -> {
                            val pm = context.getSystemService(MediaProjectionManager::class.java)
                            projectionLauncher.launch(pm.createScreenCaptureIntent())
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActive) Color(0xFFFF5722) else Color(0xFF4CAF50)
            )
        ) {
            Text(if (isActive) "停止" else "スタート", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(8.dp))

        // ★ オーバーレイモードボタン
        OutlinedButton(
            onClick = {
                if (isOverlayRunning) {
                    context.startService(
                        Intent(context, TunerOverlayService::class.java)
                            .apply { action = TunerOverlayService.ACTION_STOP })
                    isOverlayRunning = false
                } else {
                    if (!Settings.canDrawOverlays(context)) {
                        context.startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        ))
                        return@OutlinedButton
                    }
                    val pm = context.getSystemService(MediaProjectionManager::class.java)
                    overlayProjectionLauncher.launch(pm.createScreenCaptureIntent())
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (isOverlayRunning) Color(0xFFFF5722) else Color(0xFF4CAF50)
            )
        ) {
            Text(
                if (isOverlayRunning) "オーバーレイ停止" else "オーバーレイモード（他アプリ上に表示）",
                fontSize = 14.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── 以下のComposable関数は変更なし（元のコードをそのまま残す） ──

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF4CAF50) else Color.Transparent,
            contentColor = if (selected) Color.White else Color(0xFF888888)
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun CentsMeter(centsDiff: Float) {
    val targetPosition = ((centsDiff + 50f) / 100f).coerceIn(0f, 1f)
    val animatedPosition by animateFloatAsState(
        targetValue = targetPosition, animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f), label = "cents_anim")
    val indicatorColor = when {
        abs(centsDiff) < 5f -> Color(0xFF4CAF50)
        abs(centsDiff) < 15f -> Color(0xFFFFC107)
        else -> Color(0xFFFF5722)
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp)) {
        val trackY = size.height / 2; val trackH = 6.dp.toPx()
        drawRoundRect(Color(0xFF333333), Offset(0f, trackY - trackH / 2), Size(size.width, trackH), CornerRadius(trackH / 2))
        val centerX = size.width / 2
        drawLine(Color(0xFF4CAF50).copy(alpha = 0.5f), Offset(centerX, trackY - 14.dp.toPx()), Offset(centerX, trackY + 14.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        val ix = animatedPosition * size.width; val ir = 10.dp.toPx()
        if (abs(centsDiff) < 5f) drawCircle(indicatorColor.copy(alpha = 0.3f), ir * 1.6f, Offset(ix, trackY))
        drawCircle(indicatorColor, ir, Offset(ix, trackY))
    }
}

@Composable
private fun PitchHistoryWave(history: List<PitchDetector.PitchResult?>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))) {
        if (history.isEmpty()) return@Canvas
        val w = size.width; val h = size.height
        val leftPadding = 36.dp.toPx(); val rightPadding = 8.dp.toPx()
        val topPadding = 8.dp.toPx(); val bottomPadding = 8.dp.toPx()
        val drawW = w - leftPadding - rightPadding; val drawH = h - topPadding - bottomPadding
        val validResults = history.filterNotNull()
        if (validResults.isEmpty()) return@Canvas
        val rawMin = validResults.minOf { it.midiNumber }; val rawMax = validResults.maxOf { it.midiNumber }
        val center = (rawMin + rawMax) / 2f
        val halfRange = ((rawMax - rawMin) / 2f).coerceAtLeast(6f) + 2f
        val minMidi = (center - halfRange).toInt(); val maxMidi = (center + halfRange).toInt()
        val midiRange = (maxMidi - minMidi).toFloat()
        fun midiToY(midi: Float): Float = topPadding + drawH * (1f - (midi - minMidi) / midiRange)
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val whiteKeyIndices = setOf(0, 2, 4, 5, 7, 9, 11)
        val textPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#AAAAAA"); textSize = 10.dp.toPx(); isAntiAlias = true }
        val textPaintDim = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#555555"); textSize = 9.dp.toPx(); isAntiAlias = true }
        for (midi in minMidi..maxMidi) {
            val noteIndex = ((midi % 12) + 12) % 12; val octave = (midi / 12) - 1
            val noteName = noteNames[noteIndex]; val isWhiteKey = noteIndex in whiteKeyIndices; val isC = noteIndex == 0
            val y = midiToY(midi.toFloat())
            val lineColor = when { isC -> Color(0xFF555555); isWhiteKey -> Color(0xFF333333); else -> Color(0xFF252525) }
            if (!isWhiteKey) { val bandH = drawH / midiRange; drawRect(Color(0xFF1E1E1E), Offset(leftPadding, y - bandH / 2), Size(drawW, bandH)) }
            drawLine(lineColor, Offset(leftPadding, y), Offset(w - rightPadding, y), strokeWidth = if (isC) 1.5f else 0.8f)
            if (isWhiteKey) {
                val label = if (isC) "C$octave" else noteName
                val paint = if (isC) textPaint else textPaintDim
                drawContext.canvas.nativeCanvas.drawText(label, 4.dp.toPx(), y + 4.dp.toPx(), paint)
            }
        }
        val dotRadius = 3.dp.toPx(); val step = drawW / (AudioCaptureManager.HISTORY_SIZE - 1).toFloat()
        val linePath = Path(); var pathStarted = false
        for (i in history.indices) {
            val result = history[i] ?: continue
            val x = leftPadding + i * step; val midiVal = result.midiNumber.toFloat() + result.centsDiff / 100f
            val y = midiToY(midiVal); val alpha = 0.3f + 0.7f * (i.toFloat() / history.size)
            val dotColor = when { abs(result.centsDiff) < 5f -> Color(0xFF4CAF50); abs(result.centsDiff) < 15f -> Color(0xFFFFC107); else -> Color(0xFF66BB6A) }.copy(alpha = alpha)
            drawCircle(dotColor, dotRadius, Offset(x, y))
            if (!pathStarted) { linePath.moveTo(x, y); pathStarted = true } else linePath.lineTo(x, y)
        }
        if (pathStarted) drawPath(linePath, Color(0xFF4CAF50).copy(alpha = 0.4f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val latest = history.lastOrNull { it != null }
        if (latest != null) {
            val midiVal = latest.midiNumber.toFloat() + latest.centsDiff / 100f
            val y = midiToY(midiVal); val x = leftPadding + (history.indexOfLast { it != null }) * step
            drawCircle(Color(0xFF4CAF50).copy(alpha = 0.2f), dotRadius * 5f, Offset(x, y))
            drawCircle(Color(0xFF4CAF50).copy(alpha = 0.5f), dotRadius * 3f, Offset(x, y))
            drawCircle(Color(0xFF4CAF50), dotRadius * 1.8f, Offset(x, y))
            val badgeText = "${latest.noteName}${latest.octave}"
            val badgePaint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 11.dp.toPx(); isAntiAlias = true; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
            val badgeBgPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#CC4CAF50"); isAntiAlias = true }
            val textWidth = badgePaint.measureText(badgeText)
            val badgeX = (x + 8.dp.toPx()).coerceAtMost(w - textWidth - 12.dp.toPx())
            val badgeY = (y - 10.dp.toPx()).coerceAtLeast(topPadding + 14.dp.toPx())
            drawContext.canvas.nativeCanvas.drawRoundRect(badgeX - 4.dp.toPx(), badgeY - 12.dp.toPx(), badgeX + textWidth + 4.dp.toPx(), badgeY + 4.dp.toPx(), 6.dp.toPx(), 6.dp.toPx(), badgeBgPaint)
            drawContext.canvas.nativeCanvas.drawText(badgeText, badgeX, badgeY, badgePaint)
        }
    }
}
