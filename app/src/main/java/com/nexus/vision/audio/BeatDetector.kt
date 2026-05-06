package com.nexus.vision.audio

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * リアルタイムBPM検出 + ビート正確度スコアリング
 * - エネルギー差分によるオンセット検出
 * - ビート間隔の自己相関でBPM算出
 * - 各ビートのタイミング精度を採点
 */
class BeatDetector(private val sampleRate: Int) {

    companion object {
        private const val MIN_BPM = 40
        private const val MAX_BPM = 220
        private const val ONSET_THRESHOLD = 0.015f
        private const val MAX_BEATS = 120       // 保持するビート履歴数
        private const val ENERGY_HISTORY = 8    // エネルギー平均に使うフレーム数
    }

    data class BeatResult(
        val bpm: Float,
        val confidence: Float,          // 0.0-1.0
        val beats: List<BeatEvent>,
        val averageAccuracy: Float      // 0.0-1.0 (1.0 = 完全にジャスト)
    )

    data class BeatEvent(
        val timeMs: Long,               // 検出時刻
        val strength: Float,            // ビートの強さ (0.0-1.0)
        val accuracy: Float             // ジャストタイミングからの精度 (0.0-1.0)
    )

    private val beatEvents = ArrayDeque<BeatEvent>(MAX_BEATS)
    private val energyHistory = ArrayDeque<Float>(ENERGY_HISTORY)
    private var lastOnsetTimeMs = 0L
    private var currentBpm = 0f
    private var bpmConfidence = 0f
    private val intervalHistory = ArrayDeque<Long>(64)

    /**
     * 音声フレームを解析してビート検出
     * @param samples PCM float 配列
     * @return BeatResult（ビートが検出されなくても現在のBPMを返す）
     */
    fun analyze(samples: FloatArray): BeatResult {
        val energy = calculateEnergy(samples)
        val now = System.currentTimeMillis()

        // エネルギー履歴の平均
        val avgEnergy = if (energyHistory.isEmpty()) 0f
        else energyHistory.average().toFloat()

        // エネルギー履歴に追加
        if (energyHistory.size >= ENERGY_HISTORY) energyHistory.removeFirst()
        energyHistory.addLast(energy)

        // オンセット検出: 現在エネルギーが平均を大幅に上回る
        val diff = energy - avgEnergy
        val isOnset = diff > ONSET_THRESHOLD && (now - lastOnsetTimeMs) > (60_000L / MAX_BPM)

        if (isOnset) {
            val interval = now - lastOnsetTimeMs

            // 妥当な間隔のみ記録
            val minInterval = 60_000L / MAX_BPM  // ~273ms
            val maxInterval = 60_000L / MIN_BPM  // ~1500ms

            if (lastOnsetTimeMs > 0 && interval in minInterval..maxInterval) {
                if (intervalHistory.size >= 64) intervalHistory.removeFirst()
                intervalHistory.addLast(interval)
                updateBpm()
            }

            // ビートの正確度を算出
            val accuracy = if (currentBpm > 0) {
                val expectedInterval = 60_000.0 / currentBpm
                val deviation = abs(interval - expectedInterval) / expectedInterval
                (1.0 - deviation.coerceAtMost(1.0)).toFloat()
            } else 0.5f

            val strength = (diff / (avgEnergy + 0.001f)).coerceIn(0f, 1f)

            val event = BeatEvent(
                timeMs = now,
                strength = strength,
                accuracy = accuracy
            )
            synchronized(beatEvents) {
                if (beatEvents.size >= MAX_BEATS) beatEvents.removeFirst()
                beatEvents.addLast(event)
            }

            lastOnsetTimeMs = now
        }

        val beats = synchronized(beatEvents) { beatEvents.toList() }
        val avgAccuracy = if (beats.isEmpty()) 0f
        else beats.takeLast(16).map { it.accuracy }.average().toFloat()

        return BeatResult(
            bpm = currentBpm,
            confidence = bpmConfidence,
            beats = beats,
            averageAccuracy = avgAccuracy
        )
    }

    private fun updateBpm() {
        if (intervalHistory.size < 4) return

        // 中央値ベースのBPM算出（外れ値に強い）
        val sorted = intervalHistory.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2].toDouble()
        }

        val bpm = (60_000.0 / median).toFloat()
        if (bpm in MIN_BPM.toFloat()..MAX_BPM.toFloat()) {
            // 平滑化
            currentBpm = if (currentBpm == 0f) bpm
            else currentBpm * 0.7f + bpm * 0.3f

            // 信頼度: 間隔のばらつきが小さいほど高い
            val mean = intervalHistory.average()
            val variance = intervalHistory.map { (it - mean) * (it - mean) }.average()
            val cv = sqrt(variance) / (mean + 1.0) // 変動係数
            bpmConfidence = (1.0 - cv.coerceAtMost(1.0)).toFloat()
        }
    }

    private fun calculateEnergy(samples: FloatArray): Float {
        var sum = 0f
        for (s in samples) sum += s * s
        return sum / samples.size
    }

    fun reset() {
        beatEvents.clear()
        energyHistory.clear()
        intervalHistory.clear()
        lastOnsetTimeMs = 0L
        currentBpm = 0f
        bpmConfidence = 0f
    }
}
