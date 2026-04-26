// ファイルパス: app/src/main/java/com/nexus/vision/engine/EngineState.kt

package com.nexus.vision.engine

/**
 * エンジンのライフサイクル状態を表す sealed interface
 *
 * 状態遷移:
 *   Idle → Initializing → Ready ⇄ Processing
 *                ↓              ↓
 *             Error          Error
 *                ↓              ↓
 *           Idle (リセット後)
 */
sealed interface EngineState {

    /** エンジン未ロード */
    data object Idle : EngineState

    /** モデルロード中 */
    data object Initializing : EngineState

    /** 推論可能 */
    data object Ready : EngineState

    /** 推論実行中 */
    data class Processing(val taskDescription: String = "") : EngineState

    /** エラー発生 */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val consecutiveFailures: Int = 0
    ) : EngineState

    /** 発熱によりダウングレード中（テキストオンリー） */
    data object Degraded : EngineState

    /** エンジン解放済み（アプリ終了時） */
    data object Released : EngineState
}

/**
 * 発熱レベル（Android PowerManager.THERMAL_STATUS_* に対応）
 */
enum class ThermalLevel(val code: Int) {
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
    EMERGENCY(5),
    SHUTDOWN(6);

    /** SEVERE 以上で画像推論を無効にすべきか */
    fun shouldDegradeVision(): Boolean = code >= SEVERE.code

    /** CRITICAL 以上でエンジン停止すべきか */
    fun shouldStopEngine(): Boolean = code >= CRITICAL.code

    companion object {
        fun fromAndroidStatus(status: Int): ThermalLevel =
            entries.firstOrNull { it.code == status } ?: NONE
    }
}
