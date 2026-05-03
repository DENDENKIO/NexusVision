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
        private const val BUFFER_SAMPLES = 3200  // 200ms chunks
        private const val TRANSLATE_THROTTLE_MS = 1200L
    }

    // sherpa-onnx
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

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

                startForegroundNotification()

                // MediaProjection を取得
                if (resultData != null) {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)
                    Log.i(TAG, "MediaProjection acquired")
                } else {
                    Log.e(TAG, "resultData is null — cannot start MediaProjection")
                }

                initSherpaOnnx()
                initTranslator(sourceLang, targetLang)
                createSubtitleOverlay()
                createFloatingButton()
            }
            ACTION_STOP -> {
                stopEverything()
            }
        }
        return START_NOT_STICKY
    }

    // ============================================================
    //  sherpa-onnx 初期化
    // ============================================================
    private fun initSherpaOnnx() {
        try {
            val modelDir = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.onnx",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "zipformer",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.2f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
            recognizer = OnlineRecognizer(assetManager = assets, config = config)
            stream = recognizer!!.createStream()
            Log.i(TAG, "sherpa-onnx initialized OK")
        } catch (e: Exception) {
            Log.e(TAG, "sherpa-onnx init FAILED: ${e.message}", e)
            updateStatus("STTエンジン初期化失敗")
        }
    }

    // ============================================================
    //  AudioPlaybackCapture でシステム音声取得
    // ============================================================
    private fun startSystemAudioCapture() {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null")
            updateStatus("MediaProjection エラー")
            return
        }
        if (recognizer == null || stream == null) {
            Log.e(TAG, "sherpa-onnx not initialized")
            updateStatus("STTエンジン未初期化")
            return
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
            val bufferSize = maxOf(minBuf, BUFFER_SAMPLES * 2)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to init")
                updateStatus("AudioRecord エラー")
                return
            }

            isCapturing = true
            audioRecord?.startRecording()
            Log.i(TAG, "AudioRecord started, bufferSize=$bufferSize")

            captureThread = Thread({
                Log.i(TAG, "=== Capture thread started ===")
                val shortBuffer = ShortArray(BUFFER_SAMPLES)
                var totalSamples = 0L

                while (isCapturing) {
                    val read = audioRecord?.read(shortBuffer, 0, BUFFER_SAMPLES) ?: -1
                    if (read > 0) {
                        totalSamples += read

                        // Short → Float (-1.0 ~ 1.0)
                        val floatBuffer = FloatArray(read) { shortBuffer[it] / 32768.0f }

                        // sherpa-onnx に送信
                        stream?.acceptWaveform(floatBuffer, SAMPLE_RATE)

                        // デコード
                        while (recognizer?.isReady(stream!!) == true) {
                            recognizer?.decode(stream!!)
                        }

                        // 部分結果を取得
                        val result = recognizer?.getResult(stream!!)
                        val text = result?.text?.trim() ?: ""

                        if (text.isNotEmpty()) {
                            Handler(Looper.getMainLooper()).post {
                                onSpeechResult(text, false)
                            }
                        }

                        // エンドポイント検出（発話の終わり）
                        if (recognizer?.isEndpoint(stream!!) == true) {
                            val finalText = recognizer?.getResult(stream!!)?.text?.trim() ?: ""
                            if (finalText.isNotEmpty()) {
                                Handler(Looper.getMainLooper()).post {
                                    onSpeechResult(finalText, true)
                                }
                            }
                            recognizer?.reset(stream!!)
                            Log.d(TAG, "Endpoint detected, stream reset. Total samples: $totalSamples")
                        }
                    }
                }
                Log.i(TAG, "=== Capture thread stopped === total samples: $totalSamples")
            }, "SherpaCapture")
            captureThread?.start()

            updateStatus("システム音声キャプチャ中...")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}", e)
            updateStatus("権限エラー: ${e.message}")
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
            Log.i(TAG, "Audio capture stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture: ${e.message}")
        }
    }

    // ============================================================
    //  認識結果の処理
    // ============================================================
    private fun onSpeechResult(text: String, isFinal: Boolean) {
        val displayText = if (isFinal) text else "$text ..."
        originalTextView?.text = displayText
        Log.d(TAG, "STT ${if (isFinal) "FINAL" else "partial"}: \"$text\"")

        val now = System.currentTimeMillis()
        if (text == lastTranslatedText && !isFinal) return
        if (now - lastTranslateTime < TRANSLATE_THROTTLE_MS && !isFinal) return

        lastTranslateTime = now
        lastTranslatedText = text

        translator?.translate(text)
            ?.addOnSuccessListener { translated ->
                translatedTextView?.text = translated
                Log.i(TAG, "translate: \"$text\" → \"$translated\"")
                if (isFinal && text.length > 2) {
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
            "pt" -> TranslateLanguage.PORTUGUESE
            "ru" -> TranslateLanguage.RUSSIAN
            "it" -> TranslateLanguage.ITALIAN
            else -> TranslateLanguage.ENGLISH
        }
    }

    // ============================================================
    //  フローティングボタン（トグル）
    // ============================================================
    private fun createFloatingButton() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dp = resources.displayMetrics.density
        val size = (52 * dp).toInt()

        val btn = TextView(this).apply {
            text = "翻"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2196F3"))
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
            x = (16 * dp).toInt()
            y = (80 * dp).toInt()
        }

        var initX = 0; var initY = 0
        var touchX = 0f; var touchY = 0f
        var dragging = false

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) dragging = true
                    if (dragging) {
                        params.x = initX - dx.toInt()
                        params.y = initY - dy.toInt()
                        windowManager?.updateViewLayout(btn, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onTranslateButtonTap(); true
                }
                else -> false
            }
        }

        windowManager?.addView(btn, params)
        floatingButton = btn
    }

    private fun onTranslateButtonTap() {
        if (!isCurrentlyListening) {
            Log.i(TAG, ">>> ボタン押下 — キャプチャ開始")
            isCurrentlyListening = true
            (currentButtonBg as? TextView)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F44336"))
            }
            startSystemAudioCapture()
        } else {
            Log.i(TAG, ">>> ボタン押下 — キャプチャ停止")
            isCurrentlyListening = false
            (currentButtonBg as? TextView)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2196F3"))
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
        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(24, 16, 24, 16)
        }

        statusTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 11f
            text = "初期化中..."
        }
        originalTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#B0B0B0"))
            textSize = 13f
        }
        translatedTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        historyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(statusTextView)
        container.addView(originalTextView)
        container.addView(translatedTextView)
        container.addView(historyView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        windowManager?.addView(container, params)
        overlayView = container
    }

    private fun updateStatus(text: String) {
        Handler(Looper.getMainLooper()).post {
            statusTextView?.text = text
        }
    }

    private fun updateHistory() {
        historyView?.removeAllViews()
        history.takeLast(3).forEach { (orig, trans) ->
            val tv = TextView(this).apply {
                setTextColor(Color.parseColor("#60FFFFFF"))
                textSize = 11f
                text = "$orig → $trans"
            }
            historyView?.addView(tv)
        }
    }

    // ============================================================
    //  通知
    // ============================================================
    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Live Translation", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("リアルタイム翻訳")
            .setContentText("システム音声をキャプチャ中...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(
            NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    // ============================================================
    //  クリーンアップ
    // ============================================================
    private fun stopEverything() {
        isCurrentlyListening = false
        stopSystemAudioCapture()

        stream?.release(); stream = null
        recognizer?.release(); recognizer = null
        translator?.close(); translator = null
        mediaProjection?.stop(); mediaProjection = null

        try {
            overlayView?.let { windowManager?.removeView(it) }
            floatingButton?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing views: ${e.message}")
        }
        overlayView = null; floatingButton = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
    }
}
