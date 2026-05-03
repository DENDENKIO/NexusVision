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

        // 再起動の間隔（長いほど動画への影響が小さい）
        private const val RESTART_DELAY_MS = 2000L
        private const val TRANSLATE_THROTTLE_MS = 1000L
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

    private var currentButtonBg: View? = null
    private var lastTranslateTime = 0L
    private var lastTranslatedText = ""

    private var restartJob: android.os.Handler? = android.os.Handler(android.os.Looper.getMainLooper())
    private var restartRunnable: Runnable? = null
    private var pendingRestart = false

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

    private fun createSubtitleOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        historyTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 2
        }
        container.addView(historyTextView)

        originalTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
        }
        container.addView(originalTextView)

        translatedTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(translatedTextView)

        statusTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#4CAF50"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        }
        container.addView(statusTextView)

        // ── 上部に配置（ボタンと干渉しない） ──
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 0
        }

        subtitleView = container
        windowManager?.addView(container, params)
    }

    private fun createFloatingButton() {
        if (!Settings.canDrawOverlays(this)) return

        val size = dp(52)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        button.addView(icon, FrameLayout.LayoutParams(size, size))

        // ── 右下に配置 ──
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = dp(16)
            y = dp(80)
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
                    if (Math.abs(dx.toFloat()) > 10 || Math.abs(dy.toFloat()) > 10) moved = true
                    if (moved) {
                        params.x = initialX - dx
                        params.y = initialY - dy
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

    private fun onTranslateButtonTap(buttonBg: View) {
        if (isCurrentlyListening) {
            Log.i(TAG, ">>> ボタン押下 — 停止")
            isCurrentlyListening = false
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
            resetButtonColor(buttonBg)
            updateStatus("停止中 — ボタンを押して再開")
            return
        }

        Log.i(TAG, ">>> ボタン押下 — 開始")
        isCurrentlyListening = true
        (buttonBg.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(Color.parseColor("#FF5722"))
        updateStatus("翻訳中...")
        currentButtonBg = buttonBg
        startContinuousListening()
    }

    private fun startContinuousListening() {
        if (!isCurrentlyListening) return

        Log.i(TAG, "=== 認識セッション開始 ===")
        val sessionStart = System.currentTimeMillis()

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "[${elapsed(sessionStart)}] onReadyForSpeech — マイク取得完了")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "[${elapsed(sessionStart)}] onBeginningOfSpeech — 音声検出開始")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 音量ログ（頻度が高いので間引きが必要ならここにロジックを追加）
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                Log.v(TAG, "[${elapsed(sessionStart)}] onBufferReceived — ${buffer?.size ?: 0} bytes")
            }

            override fun onEndOfSpeech() {
                Log.i(TAG, "[${elapsed(sessionStart)}] onEndOfSpeech — 音声途切れ検出")
                // ここでは scheduleRestart しない。onResults または onError が必ず後続するため
            }

            override fun onError(error: Int) {
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                    SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                    SpeechRecognizer.ERROR_SERVER -> "SERVER"
                    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
                    else -> "UNKNOWN($error)"
                }
                Log.w(TAG, "[${elapsed(sessionStart)}] onError — $errorName")

                if (!isCurrentlyListening) return
                updateStatus("$errorName — 再試行中...")
                
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 4000L  // 無音時は4秒待つ
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 5000L
                    else -> RESTART_DELAY_MS
                }
                scheduleRestart(delay)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val text = matches?.firstOrNull() ?: ""
                val confidence = scores?.firstOrNull() ?: 0f

                Log.i(TAG, "[${elapsed(sessionStart)}] onResults — " +
                        "text=\"$text\", confidence=${"%.2f".format(confidence)}, " +
                        "candidates=${matches?.size ?: 0}")

                if (text.isNotBlank()) {
                    processResult(text, isFinal = true)
                }
                scheduleRestart(RESTART_DELAY_MS)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""

                Log.d(TAG, "[${elapsed(sessionStart)}] onPartialResults — \"$text\"")

                if (text.isNotBlank()) {
                    processResult(text, isFinal = false)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "[${elapsed(sessionStart)}] onEvent — type=$eventType")
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceLang)
            // 言語をさらに明示的に指定
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, sourceLang)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf<String>())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
        }

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "startListening called — lang=$sourceLang")
        } catch (e: Exception) {
            Log.e(TAG, "startListening FAILED", e)
            scheduleRestart()
        }
    }

    private fun scheduleRestart(delayMs: Long = RESTART_DELAY_MS) {
        if (!isCurrentlyListening) {
            Log.d(TAG, "scheduleRestart — skipped (not listening)")
            return
        }
        
        if (pendingRestart) {
            Log.d(TAG, "scheduleRestart — 既にスケジュール済み、スキップ")
            return
        }
        pendingRestart = true

        // 前のタイマーがあればキャンセル
        restartRunnable?.let { restartJob?.removeCallbacks(it) }

        restartRunnable = Runnable {
            pendingRestart = false
            if (isCurrentlyListening) {
                Log.i(TAG, "--- 再起動実行 ---")
                startContinuousListening()
            }
        }
        
        Log.i(TAG, "scheduleRestart — ${delayMs}ms 後に再開")
        restartJob?.postDelayed(restartRunnable!!, delayMs)
    }

    private fun processResult(text: String, isFinal: Boolean) {
        android.os.Handler(mainLooper).post {
            originalTextView?.text = if (isFinal) text else "$text ..."
        }

        val now = System.currentTimeMillis()
        if (!translatorReady || text.isBlank()) return
        if (text == lastTranslatedText && !isFinal) return
        if (!isFinal && now - lastTranslateTime < TRANSLATE_THROTTLE_MS) {
            Log.v(TAG, "translate throttled — ${now - lastTranslateTime}ms since last")
            return
        }

        lastTranslateTime = now
        lastTranslatedText = text

        Log.d(TAG, "translate request — \"$text\" (final=$isFinal)")
        val translateStart = System.currentTimeMillis()

        translator?.translate(text)
            ?.addOnSuccessListener { translated ->
                val translateMs = System.currentTimeMillis() - translateStart
                Log.i(TAG, "translate done — \"$translated\" (${translateMs}ms)")
                android.os.Handler(mainLooper).post {
                    translatedTextView?.text = translated
                    if (isFinal) {
                        subtitleHistory.add(text to translated)
                        if (subtitleHistory.size > 5) subtitleHistory.removeAt(0)
                        updateHistory()
                    }
                }
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "translate FAILED", e)
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

    private fun resetButtonColor(buttonBg: View) {
        android.os.Handler(mainLooper).post {
            (buttonBg.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(Color.parseColor("#2196F3"))
        }
    }

    private fun stopEverything() {
        isCurrentlyListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        currentButtonBg = null

        restartRunnable?.let { restartJob?.removeCallbacks(it) }
        pendingRestart = false

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

    private fun elapsed(start: Long): String {
        val ms = System.currentTimeMillis() - start
        return "${ms / 1000}.${(ms % 1000) / 100}s"
    }
}
