package com.nexus.vision.audio

import kotlin.math.*

/**
 * スペクトラルフラックス + 適応的閾値によるビート検出
 * - FFTで低域/中域/高域に分離してオンセット検出
 * - 直近エネルギー平均の倍率で適応的に閾値を算出
 * - BPMから予測ビートグリッドを生成し、正確度を採点
 * - ACFによるBPM算出とPLLによるグリッド追跡
 */
class BeatDetector(private val sampleRate: Int) {

    companion object {
        private const val MIN_BPM = 40
        private const val MAX_BPM = 220
        private const val FFT_SIZE = 2048
        private const val MAX_BEATS = 200
        private const val FLUX_HISTORY = 24          // 適応的閾値に使うフレーム数
        private const val ADAPTIVE_MULTIPLIER = 1.6f // 平均の何倍でオンセット判定
        private const val MIN_ONSET_INTERVAL_MS = 120L // 最短オンセット間隔
        private const val INTERVAL_HISTORY = 48       // BPM算出に使う間隔数
    }

    data class BeatResult(
        val bpm: Float,
        val confidence: Float,
        val beats: List<BeatEvent>,
        val averageAccuracy: Float,
        val predictedBeatIntervalMs: Long,   // 推定周期
        val lowFlux: Float,                  // 現在の低域フラックス（可視化用）
        val midFlux: Float,                  // 現在の中域フラックス
        val highFlux: Float,                 // 現在の高域フラックス
        val fluxThreshold: Float,            // 現在の適応的閾値
        val phaseState: BeatPhaseTracker.PhaseState
    ) {
        val beatPhase get() = phaseState.beatPhase
        val barPhase get() = phaseState.barPhase
        val beatNumber get() = phaseState.beatNumber
        val nextBeatMs get() = phaseState.nextBeatMs
    }

    data class BeatEvent(
        val timeMs: Long,
        val strength: Float,     // 0.0-1.0
        val accuracy: Float,     // 予測グリッドとのズレ（0.0-1.0、1.0=ジャスト）
        val band: Band           // どの帯域で検出されたか
    )

    enum class Band { LOW, MID, HIGH, COMBINED }

    // FFT用バッファ
    private val fftReal = FloatArray(FFT_SIZE)
    private val fftImag = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE).also { w ->
        for (i in w.indices) w[i] = (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    // 前フレームの帯域エネルギー
    private var prevLowEnergy = 0f
    private var prevMidEnergy = 0f
    private var prevHighEnergy = 0f

    // スペクトラルフラックス履歴（適応的閾値用）
    private val fluxHistory = ArrayDeque<Float>(FLUX_HISTORY)

    // ビート履歴
    private val beatEvents = ArrayDeque<BeatEvent>(MAX_BEATS)
    private var lastOnsetTimeMs = 0L

    // BPM算出用
    private val intervalHistory = ArrayDeque<Long>(INTERVAL_HISTORY)
    private var currentBpm = 0f
    private var bpmConfidence = 0f

    // 位相トラッカー
    val phaseTracker = BeatPhaseTracker()

    /**
     * 音声フレームを解析
     */
    fun analyze(samples: FloatArray, frameStartTimeMs: Long): BeatResult {
        val frameDurationMs = (samples.size * 1000L) / sampleRate
        val frameEndTimeMs = frameStartTimeMs + frameDurationMs

        // --- 1. FFT ---
        val n = min(samples.size, FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            fftReal[i] = if (i < n) samples[i] * window[i] else 0f
            fftImag[i] = 0f
        }
        fft(fftReal, fftImag, FFT_SIZE)

        // --- 2. 帯域別エネルギー ---
        val binHz = sampleRate.toFloat() / FFT_SIZE
        val halfN = FFT_SIZE / 2
        var lowE = 0f; var midE = 0f; var highE = 0f

        for (i in 1 until halfN) {
            val freq = i * binHz
            val mag = sqrt(fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i])
            val magSq = mag * mag
            when {
                freq < 200f -> lowE += magSq     // キック、ベース
                freq < 2000f -> midE += magSq    // スネア、ボーカル
                else -> highE += magSq           // ハイハット、シンバル
            }
        }
        lowE = sqrt(lowE / halfN)
        midE = sqrt(midE / halfN)
        highE = sqrt(highE / halfN)

        // --- 3. スペクトラルフラックス（帯域ごとの増分の合計） ---
        val lowFlux = max(0f, lowE - prevLowEnergy)
        val midFlux = max(0f, midE - prevMidEnergy)
        val highFlux = max(0f, highE - prevHighEnergy)
        val combinedFlux = lowFlux * 2.0f + midFlux * 1.0f + highFlux * 0.5f

        prevLowEnergy = lowE
        prevMidEnergy = midE
        prevHighEnergy = highE

        // --- 4. 適応的閾値 ---
        val avgFlux = if (fluxHistory.isEmpty()) 0f else fluxHistory.average().toFloat()
        val threshold = avgFlux * ADAPTIVE_MULTIPLIER + 0.0001f

        if (fluxHistory.size >= FLUX_HISTORY) fluxHistory.removeFirst()
        fluxHistory.addLast(combinedFlux)

        // --- 5. オンセット検出 ---
        val onsetTimeMs = frameStartTimeMs + frameDurationMs / 2L
        val isOnset = combinedFlux > threshold && (onsetTimeMs - lastOnsetTimeMs) > MIN_ONSET_INTERVAL_MS

        if (isOnset) {
            val interval = onsetTimeMs - lastOnsetTimeMs
            val minInterval = 60_000L / MAX_BPM
            val maxInterval = 60_000L / MIN_BPM

            if (lastOnsetTimeMs > 0 && interval in minInterval..maxInterval) {
                if (intervalHistory.size >= INTERVAL_HISTORY) intervalHistory.removeFirst()
                intervalHistory.addLast(interval)
                updateBpmWithACF()
            }

            // どの帯域が主因か判定
            val band = when {
                lowFlux >= midFlux && lowFlux >= highFlux -> Band.LOW
                midFlux >= highFlux -> Band.MID
                else -> Band.HIGH
            }

            val strength = (combinedFlux / (avgFlux * 4f + 0.001f)).coerceIn(0f, 1f)

            // phaseTrackerに通知
            phaseTracker.onBeat(onsetTimeMs, strength)

            // 正確度算出（トラッカーの位相を利用）
            val stateAtOnset = phaseTracker.getState(onsetTimeMs)
            val accuracy = 1f - (abs(stateAtOnset.beatPhase - 0.5f) * 2f).coerceIn(0f, 1f)

            val event = BeatEvent(timeMs = onsetTimeMs, strength = strength, accuracy = accuracy, band = band)
            synchronized(beatEvents) {
                if (beatEvents.size >= MAX_BEATS) beatEvents.removeFirst()
                beatEvents.addLast(event)
            }

            lastOnsetTimeMs = onsetTimeMs
        }

        val beats = synchronized(beatEvents) { beatEvents.toList() }
        val recentBeats = beats.filter { frameEndTimeMs - it.timeMs < 10000 }
        val avgAcc = if (recentBeats.isEmpty()) 0f else recentBeats.takeLast(16).map { it.accuracy }.average().toFloat()

        // 返却時に現在の状態を取得
        val phaseState = phaseTracker.getState(frameEndTimeMs)

        return BeatResult(
            bpm = if (phaseState.confidence > 0.3f) phaseState.bpm else currentBpm,
            confidence = max(phaseState.confidence, bpmConfidence),
            beats = beats,
            averageAccuracy = avgAcc,
            predictedBeatIntervalMs = phaseState.periodMs.toLong(),
            lowFlux = lowFlux,
            midFlux = midFlux,
            highFlux = highFlux,
            fluxThreshold = threshold,
            phaseState = phaseState
        )
    }

    /**
     * ACF（自己相関）ベースのBPM算出
     */
    private fun updateBpmWithACF() {
        if (intervalHistory.size < 8) return
        val intervals = intervalHistory.toList()
        var bestBpm = currentBpm.takeIf { it > 0 } ?: 120f
        var bestScore = -1f
        
        for (bpmCandidate in MIN_BPM..MAX_BPM) {
            val expectedMs = 60_000.0 / bpmCandidate
            var score = 0f
            for (iv in intervals) {
                val multiples = listOf(1.0, 2.0, 0.5, 1.5, 3.0)
                val minErr = multiples.minOf { m ->
                    abs(iv - expectedMs * m) / (expectedMs * m)
                }.toFloat()
                score += (1f - minErr.coerceAtMost(1f))
            }
            score /= intervals.size
            if (score > bestScore) {
                bestScore = score
                bestBpm = bpmCandidate.toFloat()
            }
        }
        currentBpm = if (currentBpm == 0f) bestBpm else currentBpm * 0.6f + bestBpm * 0.4f
        bpmConfidence = bestScore.coerceIn(0f, 1f)
    }

    /**
     * 位相トラッカーのタップテンポ
     */
    fun tapTempo(nowMs: Long) {
        phaseTracker.tapTempo(nowMs)
    }

    fun reset() {
        synchronized(beatEvents) { beatEvents.clear() }
        fluxHistory.clear(); intervalHistory.clear()
        prevLowEnergy = 0f; prevMidEnergy = 0f; prevHighEnergy = 0f
        lastOnsetTimeMs = 0L; currentBpm = 0f; bpmConfidence = 0f
        phaseTracker.reset()
    }

    private fun fft(real: FloatArray, imag: FloatArray, n: Int) {
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
            var m = n shr 1
            while (m >= 1 && j >= m) { j -= m; m = m shr 1 }
            j += m
        }
        // Original FFT implementation from viewed file
        var step = 1
        while (step < n) {
            val halfStep = step
            step = step shl 1
            val angleStep = -PI / halfStep
            for (group in 0 until n step step) {
                for (pair in 0 until halfStep) {
                    val angle = angleStep * pair
                    val wr = cos(angle).toFloat()
                    val wi = sin(angle).toFloat()
                    val i1 = group + pair
                    val i2 = i1 + halfStep
                    val tr = wr * real[i2] - wi * imag[i2]
                    val ti = wr * imag[i2] + wi * real[i2]
                    real[i2] = real[i1] - tr
                    imag[i2] = imag[i1] - ti
                    real[i1] = real[i1] + tr
                    imag[i1] = imag[i1] + ti
                }
            }
        }
    }
}
