package com.nexus.vision.os

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager
import com.nexus.vision.notification.InlineReplyHandler

class QuickSettingsTile : TileService() {

    companion object {
        private const val TAG = "QSTile"

        @Volatile
        var isHudActive: Boolean = false
            private set

        fun setHudActive(active: Boolean) {
            isHudActive = active
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.i(TAG, "Tile added")
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Tile clicked, current HUD state: $isHudActive")

        if (isHudActive) {
            stopHud()
        } else {
            // オーバーレイ権限チェック
            if (!Settings.canDrawOverlays(applicationContext)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted")
                // 設定画面を開く
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${applicationContext.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivityAndCollapse(intent)
                return
            }

            startHud()

            val engineState = NexusEngineManager.getInstance().state.value
            if (engineState is EngineState.Ready || engineState is EngineState.Degraded) {
                InlineReplyHandler.showReplyNotification(
                    applicationContext,
                    "NEXUS Vision HUD 起動中 — 通知からも質問できます"
                )
            }
        }

        updateTileState()
    }

    private fun startHud() {
        try {
            val intent = Intent(this, HudOverlay::class.java).apply {
                action = HudOverlay.ACTION_SHOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isHudActive = true
            Log.i(TAG, "HUD started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HUD: ${e.message}")
        }
    }

    private fun stopHud() {
        try {
            val intent = Intent(this, HudOverlay::class.java).apply {
                action = HudOverlay.ACTION_HIDE
            }
            startService(intent)
            isHudActive = false

            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(InlineReplyHandler.NOTIFICATION_ID)

            Log.i(TAG, "HUD stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop HUD: ${e.message}")
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        
        val hasOverlayPermission = Settings.canDrawOverlays(applicationContext)
        
        tile.state = when {
            isHudActive -> Tile.STATE_ACTIVE
            !hasOverlayPermission -> Tile.STATE_INACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "NEXUS HUD"
        tile.subtitle = when {
            isHudActive -> "ON"
            !hasOverlayPermission -> "権限が必要"
            else -> "OFF"
        }
        tile.updateTile()
    }
}
