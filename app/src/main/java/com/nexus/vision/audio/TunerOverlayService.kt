package com.nexus.vision.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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

    // UI要素
    private var noteTextView: TextView? = null
    private var freqTextView: TextView? = null
    private var centsTextView: TextView? = null
    private var centsBarView: View? = null
    private var statusTextView: TextView? = null
    private var meterLeftView: View? = null
    private var meterRightView: View? = null

    // オーバーレイ ドラッグ用
    private var overlayParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())

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
                    Log.e(TAG, "resultData is null")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundNotification()

                try {
                    val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = pm.getMediaProjection(Activity.RESULT_OK, resultData)
                    Log.i(TAG, "MediaProjection acquired")
                } catch (e: SecurityException) {
                    Log.e(TAG, "getMediaProjection failed: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
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

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf, BUFFER_SAMPLES * 2)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                updateStatus("AudioRecord エラー"); return
            }

            isCapturing = true
            audioRecord?.startRecording()

            captureThread = Thread({
                Log.i(TAG, "=== Tuner capture thread started ===")
                val shortBuffer = ShortArray(BUFFER_SAMPLES)
                val floatBuffer = FloatArray(BUFFER_SAMPLES)

                while (isCapturing) {
                    val read = audioRecord?.read(shortBuffer, 0, BUFFER_SAMPLES) ?: -1
                    if (read <= 0) continue

                    for (i in 0 until read) {
                        floatBuffer[i] = shortBuffer[i] / 32768.0f
                    }

                    val result = PitchDetector.detect(floatBuffer, SAMPLE_RATE)

                    mainHandler.post {
                        if (result != null) {
                            updateTunerDisplay(result)
                        } else {
                            showWaiting()
                        }
                    }
                }
                Log.i(TAG, "=== Tuner capture thread stopped ===")
            }, "TunerCaptureThread")
            captureThread?.start()
            updateStatus("解析中...")

        } catch (e: Exception) {
            Log.e(TAG, "Capture start failed: ${e.message}", e)
            updateStatus("キャプチャ開始失敗")
        }
    }

    private fun stopSystemAudioCapture() {
        isCapturing = false
        try {
            captureThread?.join(3000)
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            captureThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}")
        }
    }

    // ============================================================
    //  チューナー表示更新
    // ============================================================
    private fun updateTunerDisplay(result: PitchDetector.PitchResult) {
        noteTextView?.text = "${result.noteName}${result.octave}"
        freqTextView?.text = "%.1f Hz".format(result.frequency)

        val cents = result.centsDiff
        centsTextView?.text = "%+.0f cents".format(cents)

        // 色: 緑=正確, 黄=やや外れ, 赤=大きく外れ
        val color = when {
            abs(cents) < 5f -> Color.parseColor("#4CAF50")
            abs(cents) < 15f -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#FF5722")
        }
        noteTextView?.setTextColor(color)
        centsTextView?.setTextColor(color)

        // メーターバー更新
        val ratio = ((cents + 50f) / 100f).coerceIn(0f, 1f)
        updateMeterBar(ratio, color)

        statusTextView?.text = "解析中..."
    }

    private fun showWaiting() {
        noteTextView?.text = "--"
        noteTextView?.setTextColor(Color.parseColor("#666666"))
        freqTextView?.text = "-- Hz"
        centsTextView?.text = "±0 cents"
        centsTextView?.setTextColor(Color.parseColor("#666666"))
        updateMeterBar(0.5f, Color.parseColor("#333333"))
    }

    private fun updateMeterBar(ratio: Float, color: Int) {
        // ratio: 0.0=左端(-50), 0.5=中央(0), 1.0=右端(+50)
        meterLeftView?.let { left ->
            meterRightView?.let { right ->
                val lp1 = left.layoutParams as LinearLayout.LayoutParams
                val lp2 = right.layoutParams as LinearLayout.LayoutParams
                lp1.weight = ratio
                lp2.weight = 1f - ratio
                left.layoutParams = lp1
                right.layoutParams = lp2
                (left.background as? GradientDrawable)?.setColor(color)
            }
        }
    }

    // ============================================================
    //  フローティング「音」ボタン
    // ============================================================
    private fun createFloatingButton() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val size = (52 * dp).toInt()

        val btn = TextView(this).apply {
            text = "♪"; textSize = 20f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4CAF50"))
            }
            elevation = 8f
        }
        currentButtonBg = btn

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = (16 * dp).toInt(); y = (160 * dp).toInt()
        }

        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (abs(dx) > 10 || abs(dy) > 10) dragging = true
                    if (dragging) {
                        params.x = initX - dx.toInt(); params.y = initY - dy.toInt()
                        windowManager?.updateViewLayout(btn, params)
                    }; true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) onTunerButtonTap(); true }
                else -> false
            }
        }
        windowManager?.addView(btn, params); floatingButton = btn
    }

    private fun onTunerButtonTap() {
        if (!isCurrentlyListening) {
            Log.i(TAG, ">>> 解析開始")
            isCurrentlyListening = true
            (currentButtonBg as? TextView)?.apply {
                text = "■"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF5722"))
                }
            }
            startSystemAudioCapture()
        } else {
            Log.i(TAG, ">>> 解析停止")
            isCurrentlyListening = false
            (currentButtonBg as? TextView)?.apply {
                text = "♪"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4CAF50"))
                }
            }
            stopSystemAudioCapture()
            updateStatus("停止中")
            showWaiting()
        }
    }

    // ============================================================
    //  チューナーオーバーレイ（ドラッグ移動対応）
    // ============================================================
    private fun createTunerOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (windowManager == null) windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dp = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD000000"))
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        }

        // ドラッグハンドル
        val dragHandle = TextView(this).apply {
            text = "⋮⋮ チューナー ⋮⋮"
            textSize = 10f
            setTextColor(Color.parseColor("#60FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (4 * dp).toInt())
        }

        // 音名（大きく表示）
        noteTextView = TextView(this).apply {
            text = "--"
            textSize = 36f
            setTextColor(Color.parseColor("#666666"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        // 周波数
        freqTextView = TextView(this).apply {
            text = "-- Hz"
            textSize = 13f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER
        }

        // メーターバー
        val meterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * dp).toInt()
            )
        }
        meterLeftView = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = 4 * dp
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.5f)
        }
        // 中央マーカー
        val centerMarker = View(this).apply {
            setBackgroundColor(Color.parseColor("#4CAF50"))
            layoutParams = LinearLayout.LayoutParams((2 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        meterRightView = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                cornerRadius = 4 * dp
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.5f)
        }
        meterContainer.addView(meterLeftView)
        meterContainer.addView(centerMarker)
        meterContainer.addView(meterRightView)

        // セント差
        centsTextView = TextView(this).apply {
            text = "±0 cents"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
        }

        // ステータス
        statusTextView = TextView(this).apply {
            text = "「♪」ボタンで開始"
            textSize = 10f
            setTextColor(Color.parseColor("#80FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }

        container.addView(dragHandle)
        container.addView(noteTextView)
        container.addView(freqTextView)
        container.addView(meterContainer)
        container.addView(centsTextView)
        container.addView(statusTextView)

        val params = WindowManager.LayoutParams(
            (220 * dp).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (80 * dp).toInt()
            y = (100 * dp).toInt()
        }
        overlayParams = params

        // ドラッグ移動
        var touchStartX = 0f; var touchStartY = 0f
        var paramStartX = 0; var paramStartY = 0
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX; touchStartY = event.rawY
                    paramStartX = params.x; paramStartY = params.y
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX; val dy = event.rawY - touchStartY
                    if (abs(dx) > 8 || abs(dy) > 8) isDragging = true
                    if (isDragging) {
                        params.x = paramStartX + dx.toInt()
                        params.y = paramStartY + dy.toInt()
                        try { windowManager?.updateViewLayout(container, params) } catch (_: Exception) {}
                    }; true
                }
                MotionEvent.ACTION_UP -> { isDragging = false; true }
                else -> false
            }
        }

        windowManager?.addView(container, params)
        overlayView = container
    }

    private fun updateStatus(text: String) {
        mainHandler.post { statusTextView?.text = text }
    }

    // ============================================================
    //  通知
    // ============================================================
    private fun startForegroundNotification() {
        val ch = NotificationChannel(CHANNEL_ID, "Tuner Overlay", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NEXUS チューナー")
            .setContentText("システム音声解析中...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        try {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            Log.i(TAG, "startForeground OK")
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            stopSelf()
        }
    }

    // ============================================================
    //  クリーンアップ
    // ============================================================
    private fun stopEverything() {
        isCurrentlyListening = false
        stopSystemAudioCapture()
        mediaProjection?.stop(); mediaProjection = null
        try {
            overlayView?.let { windowManager?.removeView(it) }
            floatingButton?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null; floatingButton = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { super.onDestroy(); stopEverything() }
}
