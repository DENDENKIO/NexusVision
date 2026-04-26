// ファイルパス: app/src/main/java/com/nexus/vision/engine/ThermalMonitor.kt

package com.nexus.vision.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 端末の発熱レベルを監視し、StateFlow で公開する。
 *
 * SEVERE 以上: 画像推論を無効化（テキストオンリー）
 * CRITICAL 以上: エンジン停止
 *
 * Phase 2: 基本実装
 * Phase 4: UI にサーマルインジケータを表示
 */
class ThermalMonitor(context: Context) {

    companion object {
        private const val TAG = "ThermalMonitor"
    }

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _thermalLevel = MutableStateFlow(ThermalLevel.NONE)
    val thermalLevel: StateFlow<ThermalLevel> = _thermalLevel.asStateFlow()

    private var listener: Any? = null // PowerManager.OnThermalStatusChangedListener の参照保持

    /**
     * 監視を開始する。Application.onCreate() で呼ぶ。
     */
    fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                val level = ThermalLevel.fromAndroidStatus(status)
                Log.d(TAG, "Thermal status changed: $level (code=$status)")
                _thermalLevel.value = level
            }
            listener = thermalListener
            powerManager.addThermalStatusListener(thermalListener)

            // 初期値を取得
            val currentStatus = powerManager.currentThermalStatus
            _thermalLevel.value = ThermalLevel.fromAndroidStatus(currentStatus)
            Log.i(TAG, "ThermalMonitor started — initial level: ${_thermalLevel.value}")
        } else {
            Log.w(TAG, "Thermal monitoring not available below API 29")
        }
    }

    /**
     * 監視を停止する。
     */
    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermalListener = listener as? PowerManager.OnThermalStatusChangedListener
            if (thermalListener != null) {
                powerManager.removeThermalStatusListener(thermalListener)
                listener = null
                Log.i(TAG, "ThermalMonitor stopped")
            }
        }
    }

    /**
     * 現在、画像推論が許可されるかどうか
     */
    fun isVisionAllowed(): Boolean = !_thermalLevel.value.shouldDegradeVision()

    /**
     * 現在、エンジン動作が許可されるかどうか
     */
    fun isEngineAllowed(): Boolean = !_thermalLevel.value.shouldStopEngine()
}
