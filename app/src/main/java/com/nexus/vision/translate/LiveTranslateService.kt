package com.nexus.vision.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.nexus.vision.R

class LiveTranslateService : Service() {

    companion object {
        private const val TAG = "LiveTranslate"
        private const val CHANNEL_ID = "nexus_live_translate"
        private const val NOTIFICATION_ID = 9002

        const val ACTION_START = "com.nexus.vision.translate.START"
        const val ACTION_STOP = "com.nexus.vision.translate.STOP"
        const val EXTRA_SOURCE_LANG = "source_lang"
        const val EXTRA_TARGET_LANG = "target_lang"

        private const val LISTEN_DURATION_MS = 8000L // 1回の認識時間
    }

    private var windowManager: WindowManager? = null
    private var subtitleView: View? = null
    private var fabView: View? = null
    private var originalTextView: TextView? = null
    private var translatedTextView: TextView? = null
    private var statusTextView: TextView? = null
    private var listenButton: FrameLayout? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isCurrentlyListening = false

    private var translator: com.google.mlkit.nl.translate.Translator? = null
    private var translatorReady = false

    private var sourceLang = "en"
    private var targetLang = "ja"

    // 字幕履歴（直近5件を表示）
    private val subtitleHistory = mutableListOf<Pair<String, String>>() // original, translated
    private var historyTextView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                sourceLang = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "en"
                targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "ja"
                try {
                    startForegroundNotification()
                    setupTranslator()
                    createSubtitleOverlay()
                    createFloatingButton()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start", e)
                    stopSelf()
                }
                START_STICKY
            }
            ACTION_STOP -> {
                stopEverything()
                START_NOT_STICKY
            }
            else -> {
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NEXUS リアルタイム翻訳")
            .setContentText("フローティングボタンで翻訳")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupTranslator() {
        val srcLang = mapToTranslateLanguage(sourceLang)
        val tgtLang = mapToTranslateLanguage(targetLang)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcLang)
            .setTargetLanguage(tgtLang)
            .build()
        translator = Translation.getClient(options)
        translator!!.downloadModelIfNeeded()
            .addOnSuccessListener {
                translatorReady = true
                Log.i(TAG, "Translator ready: $sourceLang -> $targetLang")
                updateStatus("準備完了 — ボタンを押して翻訳")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed", e)
                updateStatus("モデルのダウンロードに失敗")
            }
    }

    private fun mapToTranslateLanguage(code: String): String = when (code) {
        "ja" -> TranslateLanguage.JAPANESE
        "en" -> TranslateLanguage.ENGLISH
        "zh" -> TranslateLanguage.CHINESE
        "ko" -> TranslateLanguage.KOREAN
        "es" -> TranslateLanguage.SPANISH
        "fr" -> TranslateLanguage.FRENCH
        "de" -> TranslateLanguage.GERMAN
        "pt" -> TranslateLanguage.PORTUGUESE
        "ru" -> TranslateLanguage.RUSSIAN
        "ar" -> TranslateLanguage.ARABIC
        else -> TranslateLanguage.ENGLISH
    }

    // ── 字幕オーバーレイ（画面下部、常に表示） ──
    private fun createSubtitleOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD000000"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        // 履歴テキスト
        historyTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 4
        }
        container.addView(historyTextView)

        // 原文
        originalTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
        }
        container.addView(originalTextView)

        // 翻訳文
        translatedTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 3
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        }
        container.addView(translatedTextView)

        // ステータス
        statusTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#4CAF50"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(2), 0, 0)
        }
        container.addView(statusTextView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 0
        }

        subtitleView = container
        windowManager?.addView(container, params)
    }

    // ── フローティング翻訳ボタン（丸ボタン、ドラッグ可能） ──
    private fun createFloatingButton() {
        if (!Settings.canDrawOverlays(this)) return

        val size = dp(56)
        val button = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 丸い背景
        val circle = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#2196F3"))
            }
        }
        button.addView(circle, FrameLayout.LayoutParams(size, size))

        // アイコン代わりのテキスト
        val icon = TextView(this).apply {
            text = "翻"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        button.addView(icon, FrameLayout.LayoutParams(size, size))

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(16)
        }

        // タッチ: 短押し=翻訳開始、ドラッグ=移動
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) {
                        params.x = initialX - dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(button, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // タップ → 翻訳トリガー
                        onTranslateButtonTap(circle)
                    }
                    true
                }
                else -> false
            }
        }

        listenButton = button
        fabView = button
        windowManager?.addView(button, params)
    }

    // ── ボタンタップ → 数秒間だけ認識 ──
    private fun onTranslateButtonTap(buttonBg: View) {
        if (isCurrentlyListening) {
            // 既に認識中なら停止
            speechRecognizer?.stopListening()
            return
        }

        // ボタン色を変えて認識中を示す
        (buttonBg.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(Color.parseColor("#FF5722"))
        updateStatus("聴いています...")

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }

        speechRecognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isCurrentlyListening = false
                resetButtonColor(buttonBg)
                updateStatus("認識完了")
            }

            override fun onError(error: Int) {
                isCurrentlyListening = false
                resetButtonColor(buttonBg)
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "音声が検出されませんでした"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "タイムアウト"
                    else -> "エラー($error)"
                }
                updateStatus(msg)
            }

            override fun onResults(results: Bundle?) {
                isCurrentlyListening = false
                resetButtonColor(buttonBg)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    processResult(text)
                }
                updateStatus("準備完了 — ボタンを押して翻訳")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    android.os.Handler(mainLooper).post {
                        originalTextView?.text = "$text ..."
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }

        try {
            isCurrentlyListening = true
            speechRecognizer?.startListening(intent)

            // 安全タイマー: LISTEN_DURATION_MS 後に自動停止
            android.os.Handler(mainLooper).postDelayed({
                if (isCurrentlyListening) {
                    speechRecognizer?.stopListening()
                }
            }, LISTEN_DURATION_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Listen failed", e)
            isCurrentlyListening = false
            resetButtonColor(buttonBg)
        }
    }

    private fun resetButtonColor(buttonBg: View) {
        android.os.Handler(mainLooper).post {
            (buttonBg.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(Color.parseColor("#2196F3"))
        }
    }

    private fun processResult(text: String) {
        android.os.Handler(mainLooper).post {
            originalTextView?.text = text
        }

        if (translatorReady) {
            translator?.translate(text)
                ?.addOnSuccessListener { translated ->
                    android.os.Handler(mainLooper).post {
                        translatedTextView?.text = translated

                        // 履歴に追加
                        subtitleHistory.add(text to translated)
                        if (subtitleHistory.size > 5) subtitleHistory.removeAt(0)
                        updateHistory()
                    }
                }
                ?.addOnFailureListener { e ->
                    Log.e(TAG, "Translate failed", e)
                }
        }
    }

    private fun updateHistory() {
        val historyText = subtitleHistory.dropLast(1).joinToString("\n") { (_, tr) -> tr }
        historyTextView?.text = historyText
    }

    private fun updateStatus(text: String) {
        android.os.Handler(mainLooper).post {
            statusTextView?.text = text
        }
    }

    private fun stopEverything() {
        isCurrentlyListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null

        subtitleView?.let { windowManager?.removeView(it) }
        subtitleView = null
        fabView?.let { windowManager?.removeView(it) }
        fabView = null

        translator?.close()
        translator = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            resources.displayMetrics).toInt()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "リアルタイム翻訳",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "音声翻訳サービス通知"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
