package com.nexus.vision.audio

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nexus.vision.R

/**
 * MediaProjection を保持するフォアグラウンドサービス
 * Activity の結果データを受け取り、MediaProjection を生成して保持する
 */
class SystemAudioCaptureService : Service() {

    companion object {
        private const val TAG = "SysAudioCapture"
        private const val CHANNEL_ID = "nexus_audio_capture"
        private const val NOTIFICATION_ID = 9001

        const val ACTION_START = "com.nexus.vision.audio.START_CAPTURE"
        const val ACTION_STOP = "com.nexus.vision.audio.STOP_CAPTURE"
        const val EXTRA_RESULT_DATA = "result_data"

        // ViewModel からアクセスするための MediaProjection
        var activeProjection: MediaProjection? = null
            private set
    }

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        projectionManager = applicationContext.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                // フォアグラウンドサービスとして起動（mediaProjection タイプ）
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("NEXUS チューナー")
                    .setContentText("システム音声をキャプチャ中...")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setOngoing(true)
                    .build()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    activeProjection = projectionManager.getMediaProjection(
                        Activity.RESULT_OK, resultData
                    )
                    Log.i(TAG, "MediaProjection obtained")
                } else {
                    Log.e(TAG, "No result data")
                    stopSelf()
                }
                START_STICKY
            }
            ACTION_STOP -> {
                activeProjection?.stop()
                activeProjection = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "Service stopped")
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeProjection?.stop()
        activeProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "音声キャプチャ",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "システム音声キャプチャ用の通知"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
