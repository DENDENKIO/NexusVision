package com.nexus.vision.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class TunerOverlayService : Service() {
    companion object {
        private const val TAG = "TunerOverlay"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "tuner_overlay_channel"
        const val ACTION_START = "com.nexus.vision.audio.TUNER_START"
        const val ACTION_STOP = "com.nexus.vision.audio.TUNER_STOP"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SAMPLES = 8192
        private const val HISTORY_SIZE = 60
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var floatingButton: View? = null
    private var currentButtonBg: View? = null
    private var isCurrentlyListening = false

    private var noteTextView: TextView? = null
    private var freqTextView: TextView? = null
    private var centsTextView: TextView? = null
    private var statusTextView: TextView? = null
    private var graphView: PitchGraphView? = null
    private var meterLeftView: View? = null
    private var meterRightView: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val historyBuffer = ArrayDeque<PitchDetector.PitchResult?>(HISTORY_SIZE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData == null) {
                    Log.e(TAG, "resultData is null"); stopSelf(); return START_NOT_STICKY
                }
                startForegroundNotification()
                try {
                    val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = pm.getMediaProjection(Activity.RESULT_OK, resultData)
                    Log.i(TAG, "MediaProjection acquired")
                } catch (e: SecurityException) {
                    Log.e(TAG, "getMediaProjection failed: ${e.message}"); stopSelf(); return START_NOT_STICKY
                }
                createTunerOverlay()
                createFloatingButton()
            }
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    // ============================================================
    //  AudioPlaybackCapture + PitchDetector
    // ============================================================
    private fun startSystemAudioCapture() {
        if (mediaProjection == null) { updateStatus("MediaProjection エラー"); return }
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minBuf, BUFFER_SAMPLES * 2))
                .build()
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { updateStatus("AudioRecord エラー"); return }

            isCapturing = true
            audioRecord?.startRecording()
            historyBuffer.clear()

            captureThread = Thread({
                Log.i(TAG, "=== Tuner capture started ===")
                val shortBuf = ShortArray(BUFFER_SAMPLES)
                val floatBuf = FloatArray(BUFFER_SAMPLES)
                while (isCapturing) {
                    val read = audioRecord?.read(shortBuf, 0, BUFFER_SAMPLES) ?: -1
                    if (read <= 0) continue
                    for (i in 0 until read) floatBuf[i] = shortBuf[i] / 32768.0f
                    val result = PitchDetector.detect(floatBuf, SAMPLE_RATE)
                    synchronized(historyBuffer) {
                        if (historyBuffer.size >= HISTORY_SIZE) historyBuffer.removeFirst()
                        historyBuffer.addLast(result)
                    }
                    mainHandler.post {
                        if (result != null) updateTunerDisplay(result) else showWaiting()
                        graphView?.updateHistory(synchronized(historyBuffer) { historyBuffer.toList() })
                    }
                }
                Log.i(TAG, "=== Tuner capture stopped ===")
            }, "TunerCaptureThread")
            captureThread?.start()
            updateStatus("解析中...")
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed: ${e.message}", e); updateStatus("キャプチャ開始失敗")
        }
    }

    private fun stopSystemAudioCapture() {
        isCapturing = false
        try { captureThread?.join(3000); audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null; captureThread = null
    }

    // ============================================================
    //  表示更新
    // ============================================================
    private fun updateTunerDisplay(result: PitchDetector.PitchResult) {
        noteTextView?.text = "${result.noteName}${result.octave}"
        freqTextView?.text = "%.1f Hz".format(result.frequency)
        val cents = result.centsDiff
        centsTextView?.text = "%+.0f ¢".format(cents)
        val color = when {
            abs(cents) < 5f -> Color.parseColor("#4CAF50")
            abs(cents) < 15f -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#FF5722")
        }
        noteTextView?.setTextColor(color)
        centsTextView?.setTextColor(color)
        updateMeterBar(((cents + 50f) / 100f).coerceIn(0f, 1f), color)
        statusTextView?.text = "解析中..."
    }

    private fun showWaiting() {
        noteTextView?.text = "--"; noteTextView?.setTextColor(Color.parseColor("#666666"))
        freqTextView?.text = "-- Hz"
        centsTextView?.text = "±0 ¢"; centsTextView?.setTextColor(Color.parseColor("#666666"))
        updateMeterBar(0.5f, Color.parseColor("#333333"))
    }

    private fun updateMeterBar(ratio: Float, color: Int) {
        meterLeftView?.let { left ->
            meterRightView?.let { right ->
                val lp1 = left.layoutParams as LinearLayout.LayoutParams
                val lp2 = right.layoutParams as LinearLayout.LayoutParams
                lp1.weight = ratio; lp2.weight = 1f - ratio
                left.layoutParams = lp1; right.layoutParams = lp2
                (left.background as? GradientDrawable)?.setColor(color)
            }
        }
    }

    // ============================================================
    //  ピアノロール風グラフ View
    // ============================================================
    inner class PitchGraphView(context: Context) : View(context) {
        private var history: List<PitchDetector.PitchResult?> = emptyList()
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#804CAF50"); style = Paint.Style.STROKE
            strokeWidth = 3f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#333333"); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        private val gridPaintC = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555"); style = Paint.Style.STROKE; strokeWidth = 1.5f
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#999999"); textSize = 22f
        }
        private val labelPaintDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555"); textSize = 20f
        }
        private val bandPaint = Paint().apply { color = Color.parseColor("#1E1E1E") }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC4CAF50")
        }
        private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val whiteKeys = setOf(0, 2, 4, 5, 7, 9, 11)

        fun updateHistory(data: List<PitchDetector.PitchResult?>) {
            history = data; invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawColor(Color.parseColor("#1A1A1A"))

            val valid = history.filterNotNull()
            if (valid.isEmpty()) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#444444"); textSize = 28f; textAlign = Paint.Align.CENTER }
                canvas.drawText("音を検出すると波形が表示されます", w / 2f, h / 2f, p)
                return
            }

            val leftPad = 72f; val rightPad = 16f; val topPad = 16f; val bottomPad = 16f
            val drawW = w - leftPad - rightPad; val drawH = h - topPad - bottomPad

            val rawMin = valid.minOf { it.midiNumber }; val rawMax = valid.maxOf { it.midiNumber }
            val center = (rawMin + rawMax) / 2f
            val halfRange = ((rawMax - rawMin) / 2f).coerceAtLeast(6f) + 2f
            val minMidi = (center - halfRange).toInt(); val maxMidi = (center + halfRange).toInt()
            val midiRange = (maxMidi - minMidi).toFloat()

            fun midiToY(midi: Float): Float = topPad + drawH * (1f - (midi - minMidi) / midiRange)

            // グリッド + 音名
            for (midi in minMidi..maxMidi) {
                val ni = ((midi % 12) + 12) % 12; val oct = (midi / 12) - 1
                val isWhite = ni in whiteKeys; val isC = ni == 0
                val y = midiToY(midi.toFloat())
                if (!isWhite) {
                    val bandH = drawH / midiRange
                    canvas.drawRect(leftPad, y - bandH / 2, w - rightPad, y + bandH / 2, bandPaint)
                }
                canvas.drawLine(leftPad, y, w - rightPad, y, if (isC) gridPaintC else gridPaint)
                if (isWhite) {
                    val label = if (isC) "C$oct" else noteNames[ni]
                    canvas.drawText(label, 8f, y + 6f, if (isC) labelPaint else labelPaintDim)
                }
            }

            // 折れ線 + ドット
            val step = drawW / (HISTORY_SIZE - 1).toFloat()
            val path = Path(); var started = false
            for (i in history.indices) {
                val r = history[i] ?: continue
                val x = leftPad + i * step
                val midiVal = r.midiNumber.toFloat() + r.centsDiff / 100f
                val y = midiToY(midiVal)
                val alpha = (0.3f + 0.7f * (i.toFloat() / history.size) * 255).toInt().coerceIn(0, 255)
                val c = when {
                    abs(r.centsDiff) < 5f -> Color.parseColor("#4CAF50")
                    abs(r.centsDiff) < 15f -> Color.parseColor("#FFC107")
                    else -> Color.parseColor("#FF5722")
                }
                dotPaint.color = c; dotPaint.alpha = alpha
                canvas.drawCircle(x, y, 5f, dotPaint)
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            if (started) canvas.drawPath(path, linePaint)

            // 最新ポイント（光るドット + バッジ）
            val latest = history.lastOrNull { it != null } ?: return
            val li = history.indexOfLast { it != null }
            val lx = leftPad + li * step
            val lMidi = latest.midiNumber.toFloat() + latest.centsDiff / 100f
            val ly = midiToY(lMidi)

            glowPaint.color = Color.parseColor("#4CAF50")
            glowPaint.alpha = 50; canvas.drawCircle(lx, ly, 24f, glowPaint)
            glowPaint.alpha = 120; canvas.drawCircle(lx, ly, 14f, glowPaint)
            glowPaint.alpha = 255; canvas.drawCircle(lx, ly, 8f, glowPaint)

            val badge = "${latest.noteName}${latest.octave}"
            val tw = badgePaint.measureText(badge)
            val bx = (lx + 14f).coerceAtMost(w - tw - 20f)
            val by = (ly - 18f).coerceAtLeast(topPad + 28f)
            canvas.drawRoundRect(bx - 6f, by - 22f, bx + tw + 6f, by + 6f, 10f, 10f, badgeBgPaint)
            canvas.drawText(badge, bx, by, badgePaint)
        }
    }

    // ============================================================
    //  フローティング「♪」ボタン
    // ============================================================
    private fun createFloatingButton() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density; val size = (52 * dp).toInt()
        val btn = TextView(this).apply {
            text = "♪"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4CAF50")) }
            elevation = 8f
        }
        currentButtonBg = btn
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.BOTTOM; x = (16 * dp).toInt(); y = (160 * dp).toInt() }

        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { initX = params.x; initY = params.y; touchX = event.rawX; touchY = event.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (abs(dx) > 10 || abs(dy) > 10) dragging = true
                    if (dragging) { params.x = initX - dx.toInt(); params.y = initY - dy.toInt(); windowManager?.updateViewLayout(btn, params) }; true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) onTunerButtonTap(); true }
                else -> false
            }
        }
        windowManager?.addView(btn, params); floatingButton = btn
    }

    private fun onTunerButtonTap() {
        if (!isCurrentlyListening) {
            isCurrentlyListening = true
            (currentButtonBg as? TextView)?.apply {
                text = "■"; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF5722")) }
            }
            startSystemAudioCapture()
        } else {
            isCurrentlyListening = false
            (currentButtonBg as? TextView)?.apply {
                text = "♪"; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4CAF50")) }
            }
            stopSystemAudioCapture(); updateStatus("停止中"); showWaiting()
            graphView?.updateHistory(emptyList())
        }
    }

    // ============================================================
    //  オーバーレイ（ドラッグ移動対応）
    // ============================================================
    private fun createTunerOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (windowManager == null) windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dp = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE000000"))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }

        // ドラッグハンドル
        val dragHandle = TextView(this).apply {
            text = "⋮⋮ チューナー ⋮⋮"; textSize = 10f
            setTextColor(Color.parseColor("#60FFFFFF")); gravity = Gravity.CENTER
            setPadding(0, 0, 0, (2 * dp).toInt())
        }

        // 音名 + 周波数（横並び）
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        noteTextView = TextView(this).apply {
            text = "--"; textSize = 32f; setTextColor(Color.parseColor("#666666"))
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        freqTextView = TextView(this).apply { text = "-- Hz"; textSize = 13f; setTextColor(Color.parseColor("#999999")); gravity = Gravity.CENTER }
        centsTextView = TextView(this).apply { text = "±0 ¢"; textSize = 16f; setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER }
        rightCol.addView(freqTextView); rightCol.addView(centsTextView)
        infoRow.addView(noteTextView); infoRow.addView(rightCol)

        // メーターバー
        val meterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt())
        }
        meterLeftView = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 3 * dp }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.5f)
        }
        val centerMk = View(this).apply {
            setBackgroundColor(Color.parseColor("#4CAF50"))
            layoutParams = LinearLayout.LayoutParams((2 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        meterRightView = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#222222")); cornerRadius = 3 * dp }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.5f)
        }
        meterContainer.addView(meterLeftView); meterContainer.addView(centerMk); meterContainer.addView(meterRightView)

        // ピアノロール風グラフ
        graphView = PitchGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (140 * dp).toInt()
            )
        }

        // ステータス
        statusTextView = TextView(this).apply {
            text = "「♪」ボタンで開始"; textSize = 10f
            setTextColor(Color.parseColor("#80FFFFFF")); gravity = Gravity.CENTER
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }

        container.addView(dragHandle)
        container.addView(infoRow)
        container.addView(meterContainer)
        container.addView(graphView)
        container.addView(statusTextView)

        val params = WindowManager.LayoutParams(
            (280 * dp).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (40 * dp).toInt(); y = (80 * dp).toInt() }

        // ドラッグ移動
        var tx = 0f; var ty = 0f; var px = 0; var py = 0; var drag = false
        container.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { tx = ev.rawX; ty = ev.rawY; px = params.x; py = params.y; drag = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - tx; val dy = ev.rawY - ty
                    if (abs(dx) > 8 || abs(dy) > 8) drag = true
                    if (drag) { params.x = px + dx.toInt(); params.y = py + dy.toInt(); try { windowManager?.updateViewLayout(container, params) } catch (_: Exception) {} }; true
                }
                MotionEvent.ACTION_UP -> { drag = false; true }
                else -> false
            }
        }
        windowManager?.addView(container, params); overlayView = container
    }

    private fun updateStatus(text: String) { mainHandler.post { statusTextView?.text = text } }

    // ============================================================
    //  通知
    // ============================================================
    private fun startForegroundNotification() {
        val ch = NotificationChannel(CHANNEL_ID, "Tuner Overlay", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NEXUS チューナー").setContentText("システム音声解析中...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            Log.i(TAG, "startForeground OK")
        } catch (e: SecurityException) { Log.e(TAG, "startForeground failed: ${e.message}"); stopSelf() }
    }

    // ============================================================
    //  クリーンアップ
    // ============================================================
    private fun stopEverything() {
        isCurrentlyListening = false; stopSystemAudioCapture()
        mediaProjection?.stop(); mediaProjection = null
        try { overlayView?.let { windowManager?.removeView(it) }; floatingButton?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null; floatingButton = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { super.onDestroy(); stopEverything() }
}
