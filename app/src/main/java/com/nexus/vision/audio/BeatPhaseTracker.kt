package com.nexus.vision.audio

import kotlin.math.*

/**
 * PLL（位相ロックループ）ベースのビート位相トラッカー
 * BeatDetectorのオンセット検出と組み合わせて使用する
 * 
 * 使用例:
 *   val tracker = BeatPhaseTracker()
 *   // オンセット検出時:
 *   tracker.onBeat(onsetTimeMs, strength)
 *   // 毎フレーム:
 *   val state = tracker.getState(System.currentTimeMillis())
 */
class BeatPhaseTracker {

    companion object {
        private const val MIN_BPM = 40
        private const val MAX_BPM = 220
        private const val PLL_KP = 0.15        // 位相補正ゲイン（大きいほど速く追従）
        private const val PLL_KI = 0.003       // 周期補正ゲイン（ドリフト修正）
        private const val CONFIDENCE_DECAY = 0.995f  // 毎フレームの信頼度減衰
        private const val MAX_CORRECT_ERROR = 0.45   // 補正する最大誤差（拍単位）
    }

    data class PhaseState(
        val bpm: Float,               // 現在のBPM
        val beatPhase: Float,         // 0.0〜1.0（現在の拍内位置）
        val barPhase: Float,          // 0.0〜1.0（4/4拍子の小節内位置）
        val beatNumber: Int,          // 1〜4（小節内拍番号）
        val nextBeatMs: Long,         // 次の拍頭まで何ms
        val confidence: Float,        // 追跡信頼度（0.0〜1.0）
        val periodMs: Double,         // 現在の推定周期（ms）
        val isLocked: Boolean         // PLLがロック状態か（confidence > 0.6）
    )

    // PLL状態
    private var pllAnchorMs = 0L        // グリッド基準時刻
    private var pllPeriodMs = 500.0     // 推定周期（500ms = 120 BPM）
    private var confidence = 0f
    private var beatCount = 0           // 小節カウント用

    // タップテンポ用
    private val tapHistory = ArrayDeque<Long>(8)

    /**
     * ビート検出時に呼ぶ（BeatDetectorのオンセット時刻を渡す）
     * @param onsetTimeMs フレーム開始時刻から計算した正確なオンセット時刻
     * @param strength ビートの強度（0.0〜1.0）
     */
    fun onBeat(onsetTimeMs: Long, strength: Float = 1f) {
        if (pllAnchorMs == 0L) {
            // 初回：アンカーを設定
            pllAnchorMs = onsetTimeMs
            confidence = 0.3f
            return
        }

        val elapsed = (onsetTimeMs - pllAnchorMs).toDouble()
        val beatsElapsed = elapsed / pllPeriodMs
        val nearestBeat = round(beatsElapsed)

        if (nearestBeat < 1) return  // 最短間隔チェック

        val errorBeats = beatsElapsed - nearestBeat
        val errorMs = errorBeats * pllPeriodMs

        // 誤差が MAX_CORRECT_ERROR 拍以内の場合のみ補正
        if (abs(errorBeats) < MAX_CORRECT_ERROR) {
            // 位相補正
            pllAnchorMs += (PLL_KP * errorMs).toLong()
            // 周期補正
            val newPeriod = pllPeriodMs + PLL_KI * errorMs
            pllPeriodMs = newPeriod.coerceIn(
                60_000.0 / MAX_BPM,
                60_000.0 / MIN_BPM
            )
            // 誤差が小さいほど信頼度UP
            val errorRatio = abs(errorBeats).toFloat()
            confidence = (confidence + (1f - errorRatio * 2f) * strength * 0.1f).coerceIn(0f, 1f)
            beatCount++
        } else {
            // 大きくズレている場合は信頼度を下げてリセット気味に
            confidence *= 0.7f
            if (confidence < 0.2f) {
                // 完全リセット
                pllAnchorMs = onsetTimeMs
                confidence = 0.25f
                beatCount = 0
            }
        }
    }

    /**
     * 現在の位相状態を取得（毎フレーム呼ぶ）
     * @param nowMs 現在時刻（System.currentTimeMillis()）
     */
    fun getState(nowMs: Long): PhaseState {
        // 時間経過で信頼度を減衰
        confidence *= CONFIDENCE_DECAY

        val bpm = (60_000.0 / pllPeriodMs).toFloat().coerceIn(MIN_BPM.toFloat(), MAX_BPM.toFloat())

        if (pllAnchorMs == 0L || confidence < 0.05f) {
            return PhaseState(
                bpm = 0f, beatPhase = 0f, barPhase = 0f,
                beatNumber = 1, nextBeatMs = 0L, confidence = 0f,
                periodMs = pllPeriodMs, isLocked = false
            )
        }

        val elapsed = (nowMs - pllAnchorMs).toDouble()
        // 経過拍数（正の値に正規化）
        val totalBeats = if (elapsed >= 0) elapsed / pllPeriodMs
                         else (elapsed % pllPeriodMs + pllPeriodMs) / pllPeriodMs

        val beatPhase = (totalBeats % 1.0).toFloat().coerceIn(0f, 1f)
        val barBeat = totalBeats % 4.0
        val barPhase = (barBeat / 4.0).toFloat().coerceIn(0f, 1f)
        val beatNumber = (barBeat.toInt() % 4) + 1
        val nextBeatMs = ((1.0 - beatPhase) * pllPeriodMs).toLong()

        return PhaseState(
            bpm = bpm,
            beatPhase = beatPhase,
            barPhase = barPhase,
            beatNumber = beatNumber,
            nextBeatMs = nextBeatMs,
            confidence = confidence,
            periodMs = pllPeriodMs,
            isLocked = confidence > 0.6f
        )
    }

    /**
     * タップテンポ入力（UIのタップボタンから呼ぶ）
     */
    fun tapTempo(nowMs: Long) {
        if (tapHistory.isNotEmpty() && nowMs - tapHistory.last() > 3000L) {
            tapHistory.clear()
        }
        tapHistory.addLast(nowMs)
        if (tapHistory.size >= 2) {
            val intervals = tapHistory.zipWithNext().map { (a, b) -> (b - a).toDouble() }
            val avgInterval = intervals.average()
            if (avgInterval in 60_000.0 / MAX_BPM..60_000.0 / MIN_BPM) {
                pllPeriodMs = avgInterval
                pllAnchorMs = nowMs
                confidence = 0.5f
                beatCount = 0
            }
        }
        if (tapHistory.size > 8) tapHistory.removeFirst()
    }

    /**
     * リセット
     */
    fun reset() {
        pllAnchorMs = 0L
        pllPeriodMs = 500.0
        confidence = 0f
        beatCount = 0
        tapHistory.clear()
    }
}
