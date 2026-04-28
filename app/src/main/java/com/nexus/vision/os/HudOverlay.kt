// ファイルパス: app/src/main/java/com/nexus/vision/os/HudOverlay.kt
package com.nexus.vision.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import androidx.core.app.NotificationCompat
import com.nexus.vision.R
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * フローティング HUD オーバーレイ
 *
 * SYSTEM_ALERT_WINDOW で画面上にフローティングウィンドウを表示。
 * 他アプリを使いながら NEXUS Vision に質問を投げられる。
 *
 * - ドラッグで移動可能
 * - 入力欄に質問を入力して送信
 * - Gemma-4 の回答を表示
 * - 最小化/閉じるボタン
 *
 * Accessibility Service 非依存（Android 17 Advanced Protection Mode 対応）
 *
 * Phase 10: OS 統合
 */
class HudOverlay : Service() {

    companion object {
        private const val TAG = "HudOverlay"
        private const val NOTIFICATION_ID = 20001
        private const val CHANNEL_ID = "nexus_hud_channel"

        const val ACTION_SHOW = "com.nexus.vision.HUD_SHOW"
        const val ACTION_HIDE = "com.nexus.vision.HUD_HIDE"
    }

    private var windowManager: WindowManager? = null
    private var hudView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // HUD 内の View 参照
    private var resultTextView: TextView? = null
    private var inputEditText: EditText? = null
    private var scrollView: ScrollView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startForeground(NOTIFICATION_ID, createNotification())
                showHud()
            }
            ACTION_HIDE -> {
                hideHud()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification())
                showHud()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideHud()
        scope.cancel()
        QuickSettingsTile.setHudActive(false)
        super.onDestroy()
    }

    /**
     * HUD ウィンドウを表示する
     */
    private fun showHud() {
        if (hudView != null) return // すでに表示中

        val wm = windowManager ?: return

        // --- レイアウトを XML ではなくコードで構築 ---
        val context = this

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE0202020.toInt()) // 半透明ダーク
            setPadding(24, 16, 24, 16)
        }

        // タイトルバー（ドラッグ領域 + 閉じるボタン）
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(context).apply {
            text = "NEXUS Vision HUD"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFFF5555.toInt())
            textSize = 18f
            setPadding(16, 0, 0, 0)
            setOnClickListener {
                hideHud()
                QuickSettingsTile.setHudActive(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        titleBar.addView(titleText)
        titleBar.addView(closeButton)

        // 結果表示エリア
        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300
            ).apply { topMargin = 8 }
        }

        resultTextView = TextView(context).apply {
            text = "質問を入力してください"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
        }
        scrollView?.addView(resultTextView)

        // 入力行
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        inputEditText = EditText(context).apply {
            hint = "質問を入力..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x80FFFFFF.toInt())
            textSize = 13f
            setBackgroundColor(0x40FFFFFF.toInt())
            setPadding(12, 8, 12, 8)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sendButton = TextView(context).apply {
            text = "送信"
            setTextColor(0xFF4FC3F7.toInt())
            textSize = 14f
            setPadding(16, 8, 8, 8)
            setOnClickListener { onSendClicked() }
        }

        inputRow.addView(inputEditText)
        inputRow.addView(sendButton)

        container.addView(titleBar)
        container.addView(scrollView)
        container.addView(inputRow)

        // WindowManager パラメータ
        val params = WindowManager.LayoutParams(
            dpToPx(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        // ドラッグ移動
        setupDrag(titleBar, params)

        wm.addView(container, params)
        hudView = container
        QuickSettingsTile.setHudActive(true)

        Log.i(TAG, "HUD shown")
    }

    /**
     * HUD ウィンドウを非表示にする
     */
    private fun hideHud() {
        hudView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove HUD view: ${e.message}")
            }
        }
        hudView = null
        resultTextView = null
        inputEditText = null
        scrollView = null
        Log.i(TAG, "HUD hidden")
    }

    /**
     * 送信ボタンが押された
     */
    private fun onSendClicked() {
        val query = inputEditText?.text?.toString()?.trim() ?: return
        if (query.isBlank()) return

        inputEditText?.setText("")
        resultTextView?.text = "処理中..."

        scope.launch {
            val answer = processQuery(query)
            resultTextView?.text = "Q: $query\n\nA: $answer"
            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * クエリを処理する
     */
    private suspend fun processQuery(query: String): String = withContext(Dispatchers.IO) {
        val engine = NexusEngineManager.getInstance()

        if (engine.state.value is EngineState.Ready) {
            val result = engine.inferText(query)
            result.getOrElse { "エラー: ${it.message}" }
        } else {
            "エンジン未ロード。メインアプリでエンジンをロードしてください。"
        }
    }

    /**
     * タイトルバーのドラッグ移動セットアップ
     */
    private fun setupDrag(dragHandle: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    hudView?.let { windowManager?.updateViewLayout(it, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NEXUS HUD",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "HUD オーバーレイの動作通知"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NEXUS Vision HUD")
            .setContentText("HUD が動作中です")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}
