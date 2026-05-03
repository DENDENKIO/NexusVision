package com.nexus.vision.translate

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
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.k2fsa.sherpa.onnx.*

class LiveTranslateService : Service() {
    companion object {
        private const val TAG = "LiveTranslate"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "live_translate_channel"
        const val ACTION_START = "com.nexus.vision.translate.START"
        const val ACTION_STOP = "com.nexus.vision.translate.STOP"
        const val EXTRA_SOURCE_LANG = "source_lang"
        const val EXTRA_TARGET_LANG = "target_lang"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW = 512  // Silero VAD requires 512 samples per call
        private const val TRANSLATE_THROTTLE_MS = 1000L
    }

    // sherpa-onnx (offline = SenseVoice)
    private var recognizer: OfflineRecognizer? = null

    // Silero VAD
    private var vad: Vad? = null

    // AudioPlaybackCapture
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false

    // Translation
    private var translator: Translator? = null
    private var lastTranslateTime = 0L
    private var lastTranslatedText = ""

    // UI
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var floatingButton: View? = null
    private var originalTextView: TextView? = null
    private var translatedTextView: TextView? = null
    private var statusTextView: TextView? = null
    private var isCurrentlyListening = false
    private var currentButtonBg: View? = null

    // History
    private val history = mutableListOf<Pair<String, String>>()
    private var historyView: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sourceLang = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "en"
                val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "ja"

                @Suppress("DEPRECATION")
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                // resultData が null なら何もせず終了（再起動ループ防止）
                if (resultData == null) {
                    Log.e(TAG, "resultData is null — cannot start")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // MediaProjection を先に取得
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = pm.getMediaProjection(Activity.RESULT_OK, resultData)
                Log.i(TAG, "MediaProjection acquired")

                // MediaProjection 取得後に foreground 開始
                startForegroundNotification()

                initSherpaOnnx(sourceLang)
                initTranslator(sourceLang, targetLang)
                createSubtitleOverlay()
                createFloatingButton()
            }
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    // ============================================================
    //  VAD + SenseVoice 初期化
    // ============================================================
    private fun initSherpaOnnx(sourceLang: String) {
        try {
            // --- Silero VAD ---
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.4f,
                    minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = 15.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
            vad = Vad(assetManager = assets, config = vadConfig)
            Log.i(TAG, "Silero VAD initialized")

            // --- SenseVoice (offline, multilingual) ---
            val senseVoiceDir = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
            val senseVoiceLang = when (sourceLang.lowercase().take(2)) {
                "ja" -> "ja"
                "en" -> "en"
                "zh" -> "zh"
                "ko" -> "ko"
                else -> "auto"
            }
            val offlineConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$senseVoiceDir/model.int8.onnx",
                        language = senseVoiceLang,
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$senseVoiceDir/tokens.txt",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(assetManager = assets, config = offlineConfig)
            Log.i(TAG, "SenseVoice initialized (lang=$senseVoiceLang)")

            updateStatus("STTエンジン準備完了")
        } catch (e: Exception) {
            Log.e(TAG, "sherpa-onnx init FAILED: ${e.message}", e)
            updateStatus("STTエンジン初期化失敗: ${e.message}")
        }
    }

    // ============================================================
    //  AudioPlaybackCapture + VAD + SenseVoice
    // ============================================================
    private fun startSystemAudioCapture() {
        if (mediaProjection == null) {
            updateStatus("MediaProjection エラー"); return
        }
        if (recognizer == null || vad == null) {
            updateStatus("STTエンジン未初期化"); return
        }

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
            val bufferSize = maxOf(minBuf, VAD_WINDOW * 2 * 4)

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
                Log.i(TAG, "=== Capture thread started (VAD + SenseVoice) ===")
                val shortBuffer = ShortArray(VAD_WINDOW)

                while (isCapturing) {
                    val read = audioRecord?.read(shortBuffer, 0, VAD_WINDOW) ?: -1
                    if (read > 0) {
                        val floatBuffer = FloatArray(read) { shortBuffer[it] / 32768.0f }

                        // VAD に渡す
                        vad?.acceptWaveform(floatBuffer)

                        // 音声区間が検出されたら認識
                        while (vad?.empty() == false) {
                            val segment = vad!!.front()
                            vad!!.pop()
                            Log.d(TAG, "VAD segment: ${segment.samples.size} samples (${segment.samples.size / SAMPLE_RATE.toFloat()}s)")

                            // SenseVoice で認識
                            val stream = recognizer!!.createStream()
                            stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                            recognizer!!.decode(stream)
                            val result = recognizer!!.getResult(stream)
                            stream.release()

                            val text = result.text.trim()
                            if (text.isNotEmpty()) {
                                Log.i(TAG, "STT FINAL: \"$text\" (lang=${result.lang})")
                                Handler(Looper.getMainLooper()).post {
                                    onSpeechResult(text, true)
                                }
                            }
                        }

                        // 話し中は部分表示
                        if (vad?.isSpeechDetected() == true) {
                            Handler(Looper.getMainLooper()).post {
                                statusTextView?.text = "音声検出中..."
                            }
                        }
                    }
                }
                Log.i(TAG, "=== Capture thread stopped ===")
            }, "VadSenseVoiceCapture")
            captureThread?.start()

            updateStatus("システム音声キャプチャ中...")
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
    //  認識結果処理
    // ============================================================
    private fun onSpeechResult(text: String, isFinal: Boolean) {
        originalTextView?.text = text
        Log.d(TAG, "STT: \"$text\"")

        val now = System.currentTimeMillis()
        if (text == lastTranslatedText) return
        if (now - lastTranslateTime < TRANSLATE_THROTTLE_MS && !isFinal) return

        lastTranslateTime = now
        lastTranslatedText = text

        translator?.translate(text)
            ?.addOnSuccessListener { translated ->
                translatedTextView?.text = translated
                Log.i(TAG, "translate: \"$text\" → \"$translated\"")
                if (text.length > 1) {
                    history.add(Pair(text, translated))
                    if (history.size > 5) history.removeAt(0)
                    updateHistory()
                }
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Translation failed: ${e.message}")
            }
    }

    // ============================================================
    //  ML Kit Translation
    // ============================================================
    private fun initTranslator(sourceLang: String, targetLang: String) {
        val srcLang = mapLang(sourceLang)
        val tgtLang = mapLang(targetLang)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcLang)
            .setTargetLanguage(tgtLang)
            .build()

        translator = Translation.getClient(options)
        translator?.downloadModelIfNeeded()
            ?.addOnSuccessListener {
                Log.i(TAG, "Translation model ready: $sourceLang → $targetLang")
                updateStatus("準備完了 — 「翻」ボタンで開始")
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Translation model download failed: ${e.message}")
                updateStatus("翻訳モデル取得失敗")
            }
    }

    private fun mapLang(lang: String): String {
        return when (lang.lowercase().take(2)) {
            "en" -> TranslateLanguage.ENGLISH
            "ja" -> TranslateLanguage.JAPANESE
            "zh" -> TranslateLanguage.CHINESE
            "ko" -> TranslateLanguage.KOREAN
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "es" -> TranslateLanguage.SPANISH
            "ru" -> TranslateLanguage.RUSSIAN
            else -> TranslateLanguage.ENGLISH
        }
    }

    // ============================================================
    //  フローティングボタン
    // ============================================================
    private fun createFloatingButton() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val size = (52 * dp).toInt()

        val btn = TextView(this).apply {
            text = "翻"; textSize = 18f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#2196F3"))
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
            x = (16 * dp).toInt(); y = (80 * dp).toInt()
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
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) dragging = true
                    if (dragging) {
                        params.x = initX - dx.toInt(); params.y = initY - dy.toInt()
                        windowManager?.updateViewLayout(btn, params)
                    }; true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) onTranslateButtonTap(); true }
                else -> false
            }
        }
        windowManager?.addView(btn, params); floatingButton = btn
    }

    private fun onTranslateButtonTap() {
        if (!isCurrentlyListening) {
            Log.i(TAG, ">>> キャプチャ開始")
            isCurrentlyListening = true
            (currentButtonBg as? TextView)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#F44336"))
            }
            startSystemAudioCapture()
        } else {
            Log.i(TAG, ">>> キャプチャ停止")
            isCurrentlyListening = false
            (currentButtonBg as? TextView)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#2196F3"))
            }
            stopSystemAudioCapture()
            updateStatus("停止中")
        }
    }

    // ============================================================
    //  字幕オーバーレイ
    // ============================================================
    private fun createSubtitleOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (windowManager == null) windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(24, 16, 24, 16)
        }
        statusTextView = TextView(this).apply { setTextColor(Color.parseColor("#80FFFFFF")); textSize = 11f; text = "初期化中..." }
        originalTextView = TextView(this).apply { setTextColor(Color.parseColor("#B0B0B0")); textSize = 13f }
        translatedTextView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, Typeface.BOLD) }
        historyView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        container.addView(statusTextView)
        container.addView(originalTextView)
        container.addView(translatedTextView)
        container.addView(historyView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        windowManager?.addView(container, params); overlayView = container
    }

    private fun updateStatus(text: String) {
        Handler(Looper.getMainLooper()).post { statusTextView?.text = text }
    }
    private fun updateHistory() {
        historyView?.removeAllViews()
        history.takeLast(3).forEach { (o, t) ->
            historyView?.addView(TextView(this).apply {
                setTextColor(Color.parseColor("#60FFFFFF")); textSize = 11f; text = "$o → $t"
            })
        }
    }

    // ============================================================
    //  通知
    // ============================================================
    private fun startForegroundNotification() {
        val ch = NotificationChannel(CHANNEL_ID, "Live Translation", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("リアルタイム翻訳")
            .setContentText("システム音声キャプチャ中...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        try {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
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
        vad?.release(); vad = null
        recognizer?.release(); recognizer = null
        translator?.close(); translator = null
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
