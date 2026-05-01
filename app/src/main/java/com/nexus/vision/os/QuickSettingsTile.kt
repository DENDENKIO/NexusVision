// ファイルパス: app/src/main/java/com/nexus/vision/os/QuickSettingsTile.kt
package com.nexus.vision.os

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.nexus.vision.NexusApplication
import com.nexus.vision.notification.InlineReplyHandler
import com.nexus.vision.engine.EngineState
import com.nexus.vision.engine.NexusEngineManager

/**
 * クイック設定タイル「NEXUS Vision」
 *
 * 通知シェードのクイック設定にタイルを追加する。
 * タップで HUD オーバーレイの ON/OFF をトグルする。
 *
 * - タイル Active 状態: HUD 表示中
 * - タイル Inactive 状態: HUD 非表示
 *
 * Phase 10: OS 統合
 */
class QuickSettingsTile : TileService() {

    companion object {
        private const val TAG = "QSTile"

        // HUD の表示状態（アプリ全体で共有）
        @Volatile
        var isHudActive: Boolean = false
            private set

        fun setHudActive(active: Boolean) {
            isHudActive = active
        }
    }

    /**
     * タイルが追加された時
     */
    override fun onTileAdded() {
        super.onTileAdded()
        Log.i(TAG, "Tile added")
        updateTileState()
    }

    /**
     * タイルが表示開始された時（クイック設定パネルが開かれた時）
     */
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    /**
     * タイルがタップされた時
     */
    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Tile clicked, current HUD state: $isHudActive")

        if (isHudActive) {
            stopHud()
        } else {
            // HUD を起動 + 通知からの質問も有効にする
            startHud()

            // エンジンがReady なら通知インライン応答も出す
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

    /**
     * HUD を開始する
     */
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

    /**
     * HUD を停止する
     */
    private fun stopHud() {
        try {
            val intent = Intent(this, HudOverlay::class.java).apply {
                action = HudOverlay.ACTION_HIDE
            }
            startService(intent)
            isHudActive = false

            // 通知も消す
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(InlineReplyHandler.NOTIFICATION_ID)

            Log.i(TAG, "HUD stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop HUD: ${e.message}")
        }
    }

    /**
     * タイルの表示状態を更新する
     */
    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (isHudActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "NEXUS HUD"
        tile.subtitle = if (isHudActive) "ON" else "OFF"
        tile.updateTile()
    }
}
