package com.nexus.vision.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
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
        const val EXTRA_SOURCE_LANG = "source_lang"  // "en", "ja", "zh", "ko" etc.
        const val EXTRA_TARGET_LANG = "target_lang"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var originalTextView: TextView? = null
    private var translatedTextView: TextView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ML Kit Translator
    private var translator: com.google.mlkit.nl.translate.Translator? = null
    private var translatorReady = false

    private var sourceLang = "en"
    private var targetLang = "ja"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                sourceLang = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "en"
                targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "ja"

                startForegroundNotification()
                setupTranslator()
                createOverlay()
                startListening()
                START_STICKY
            }
            ACTION_STOP -> {
                stopEverything()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    // ── フォアグラウンド通知 ──
    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NEXUS リアルタイム翻訳")
            .setContentText("音声を翻訳中...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── ML Kit Translator セットアップ ──
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
                Log.i(TAG, "Translation model ready: $sourceLang -> $targetLang")
                updateTranslatedText("翻訳モデル準備完了")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Translation model download failed", e)
                updateTranslatedText("翻訳モデルのダウンロードに失敗")
            }
    }

    private fun mapToTranslateLanguage(code: String): String {
        return when (code) {
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
    }

    // ── フローティング字幕オーバーレイ ──
    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000")) // 半透明黒
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // 原文テキスト
        originalTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            typeface = Typeface.DEFAULT
        }

        // 翻訳テキスト
        translatedTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 3
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }

        container.addView(originalTextView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        container.addView(translatedTextView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = dp(36)
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = dp(48)
        }

        // ドラッグ対応
        var initialY = 0
        var touchY = 0f
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.y = initialY - (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        overlayView = container
        windowManager?.addView(container, params)
        Log.i(TAG, "Subtitle overlay created")
    }

    // ── 音声認識 (SpeechRecognizer) ──
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer not available")
            updateTranslatedText("音声認識が利用できません")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech, restarting...")
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "認識なし"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "タイムアウト"
                    SpeechRecognizer.ERROR_AUDIO -> "音声エラー"
                    SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラー"
                    else -> "エラー($error)"
                }
                Log.d(TAG, "Recognition error: $errorMsg")
                // 自動再開
                if (isListening) {
                    android.os.Handler(mainLooper).postDelayed({ restartListening() }, 500)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    onSpeechResult(text, isFinal = true)
                }
                // 自動再開
                if (isListening) {
                    restartListening()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    onSpeechResult(text, isFinal = false)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        isListening = true
        restartListening()
    }

    private fun restartListening() {
        if (!isListening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // できるだけ長く聞く
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
        }
    }

    // ── 認識結果 → 翻訳 → 表示 ──
    private fun onSpeechResult(text: String, isFinal: Boolean) {
        // 原文を表示
        android.os.Handler(mainLooper).post {
            originalTextView?.text = if (isFinal) text else "$text ..."
        }

        // 翻訳
        if (translatorReady && text.isNotBlank()) {
            translator?.translate(text)
                ?.addOnSuccessListener { translated ->
                    updateTranslatedText(translated)
                }
                ?.addOnFailureListener { e ->
                    Log.e(TAG, "Translation failed", e)
                }
        }
    }

    private fun updateTranslatedText(text: String) {
        android.os.Handler(mainLooper).post {
            translatedTextView?.text = text
        }
    }

    // ── クリーンアップ ──
    private fun stopEverything() {
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null

        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null

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
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "リアルタイム翻訳", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "音声翻訳サービス通知" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
