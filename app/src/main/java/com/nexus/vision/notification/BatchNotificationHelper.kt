// ファイルパス: app/src/main/java/com/nexus/vision/notification/BatchNotificationHelper.kt

package com.nexus.vision.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * バッチ高画質化の通知管理
 */
object BatchNotificationHelper {

    const val CHANNEL_ID_PROGRESS = "nexus_batch_progress"
    const val CHANNEL_ID_RESULT = "nexus_batch_result"
    const val NOTIFICATION_ID_PROGRESS = 10001
    private var nextResultId = 20001

    /**
     * 通知チャンネルを作成する。Application.onCreate() または Worker 開始時に呼ぶ。
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progressChannel = NotificationChannel(
            CHANNEL_ID_PROGRESS,
            "バッチ処理の進捗",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "高画質化バッチ処理の進行状況を表示します"
            setShowBadge(false)
        }

        val resultChannel = NotificationChannel(
            CHANNEL_ID_RESULT,
            "バッチ処理の結果",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "高画質化バッチ処理の完了・エラーを通知します"
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(resultChannel)
    }

    /**
     * フォアグラウンドサービス用の進捗通知（ongoing）
     */
    fun createProgressNotification(
        context: Context,
        current: Int,
        total: Int,
        fileName: String,
        estimatedRemainingMs: Long = -1L
    ): Notification {
        val remainingText = if (estimatedRemainingMs > 0) {
            val minutes = estimatedRemainingMs / 60_000
            val seconds = (estimatedRemainingMs % 60_000) / 1_000
            " 残り約 ${minutes}分${seconds}秒"
        } else {
            ""
        }

        return NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("バッチ高画質化 $current/$total")
            .setContentText("${fileName} を処理中...$remainingText")
            .setProgress(total, current, false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * 1 枚完了通知
     */
    fun notifyItemComplete(
        context: Context,
        current: Int,
        total: Int,
        fileName: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RESULT)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("✅ 保存完了 ($current/$total)")
            .setContentText("$fileName を Pictures/NexusVision/ に保存しました")
            .setAutoCancel(true)
            .build()
        manager.notify(nextResultId++, notification)
    }

    /**
     * 全完了通知
     */
    fun notifyBatchComplete(
        context: Context,
        total: Int,
        successCount: Int,
        failCount: Int
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 進捗通知を消す
        manager.cancel(NOTIFICATION_ID_PROGRESS)

        val title = if (failCount == 0) {
            "✅ バッチ高画質化完了"
        } else {
            "⚠️ バッチ高画質化完了（一部失敗）"
        }
        val text = if (failCount == 0) {
            "$total 枚すべて保存しました"
        } else {
            "成功: $successCount 枚、失敗: $failCount 枚"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RESULT)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(nextResultId++, notification)
    }

    /**
     * 発熱一時停止通知
     */
    fun notifyThermalPause(context: Context, level: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⏸ 発熱のためバッチ処理を一時停止中")
            .setContentText("熱レベル: $level — 温度が下がると自動で再開します")
            .setOngoing(true)
            .setSilent(true)
            .build()
        manager.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    /**
     * 発熱中断通知
     */
    fun notifyThermalAbort(context: Context, completed: Int, total: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_PROGRESS)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RESULT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🛑 発熱によりバッチ処理を中断しました")
            .setContentText("$completed/$total 枚まで保存済み")
            .setAutoCancel(true)
            .build()
        manager.notify(nextResultId++, notification)
    }
}
