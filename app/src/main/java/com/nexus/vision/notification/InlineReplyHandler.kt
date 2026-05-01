// ファイルパス: app/src/main/java/com/nexus/vision/notification/InlineReplyHandler.kt

package com.nexus.vision.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.nexus.vision.R
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phase 12 – Step 12-2: 通知インライン応答
 *
 * 通知バーから直接 Gemma に質問を送信し、結果を通知で返す。
 */
class InlineReplyHandler : BroadcastReceiver() {

    companion object {
        private const val TAG = "InlineReply"
        const val CHANNEL_ID = "nexus_inline_reply"
        const val NOTIFICATION_ID = 9001
        private const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_REPLY = "com.nexus.vision.ACTION_INLINE_REPLY"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * インライン応答可能な通知を表示する。
         */
        fun showReplyNotification(context: Context, promptText: String = "NEXUS Vision に質問") {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // チャンネル作成
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI 応答",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "通知からAIに質問できます"
            }
            nm.createNotificationChannel(channel)

            // RemoteInput (テキスト入力)
            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel("質問を入力...")
                .build()

            val replyIntent = Intent(context, InlineReplyHandler::class.java).apply {
                action = ACTION_REPLY
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                replyIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_launcher_foreground,
                "返信",
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .build()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("NEXUS Vision")
                .setContentText(promptText)
                .addAction(action)
                .setOngoing(false)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val userText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()

        if (userText.isNullOrBlank()) return

        Log.i(TAG, "Inline reply received: ${userText.take(50)}")

        // 即座に「処理中...」通知に更新
        updateNotification(context, "処理中...", "「$userText」を分析しています")

        // 非同期で推論
        scope.launch {
            val engineManager = NexusEngineManager.getInstance()
            val response = if (engineManager.state.value is EngineState.Ready ||
                engineManager.state.value is EngineState.Degraded
            ) {
                val result = engineManager.inferText(userText)
                result.getOrElse { "エラー: ${it.message}" }
            } else {
                "エンジンが未ロードです。アプリを開いてエンジンをロードしてください。"
            }

            // 結果を通知で表示
            updateNotification(
                context,
                "回答",
                response.take(300),
                showReplyAction = true
            )
        }
    }

    private fun updateNotification(
        context: Context,
        title: String,
        text: String,
        showReplyAction: Boolean = false
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))

        if (showReplyAction) {
            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel("続けて質問...")
                .build()

            val replyIntent = Intent(context, InlineReplyHandler::class.java).apply {
                action = ACTION_REPLY
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context, 0, replyIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_launcher_foreground,
                "続けて質問",
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .build()

            builder.addAction(action)
        }

        nm.notify(NOTIFICATION_ID, builder.build())
    }
}
