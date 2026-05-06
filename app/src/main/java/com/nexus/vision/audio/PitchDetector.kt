package com.nexus.vision.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * リアルタイム音階判定エンジン
 * - YIN アルゴリズム（差分二乗関数 + 累積平均正規化）によるピッチ検出
 * - オクターブエラーを大幅に削減
 * - 12平均律による音名マッピング（A4 = 440Hz）
 */
object PitchDetector {

    private val NOTE_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F",
        "F#", "G", "G#", "A", "A#", "B"
    )

    private const val A4_FREQ = 440.0
    private const val A4_MIDI = 69

    private const val MIN_FREQ = 50.0
    private const val MAX_FREQ = 2000.0

    private const val RMS_THRESHOLD = 0.02f

    // YIN閾値: 小さいほど厳格（0.10〜0.20が一般的）
    private const val YIN_THRESHOLD = 0.15

    data class PitchResult(
        val frequency: Float,
        val noteName: String,
        val octave: Int,
        val centsDiff: Float,
        val midiNumber: Int,
        val amplitude: Float
    )

    fun detect(samples: FloatArray, sampleRate: Int): PitchResult? {
        val rms = calculateRms(samples)
        if (rms < RMS_THRESHOLD) return null

        val frequency = yin(samples, sampleRate) ?: return null

        val midiFloat = 12.0 * log2(frequency / A4_FREQ) + A4_MIDI
        val midiNumber = midiFloat.roundToInt()
        val centsDiff = ((midiFloat - midiNumber) * 100.0).toFloat()

        val noteIndex = ((midiNumber % 12) + 12) % 12
        val noteName = NOTE_NAMES[noteIndex]
        val octave = (midiNumber / 12) - 1

        return PitchResult(
            frequency = frequency.toFloat(),
            noteName = noteName,
            octave = octave,
            centsDiff = centsDiff,
            midiNumber = midiNumber,
            amplitude = rms
        )
    }

    /**
     * YINアルゴリズム
     * Step1: 差分二乗関数 d(tau)
     * Step2: 累積平均正規化差分 d'(tau)
     * Step3: 閾値以下の最初のディップを採用（オクターブエラー防止）
     * Step4: 放物線補間でサブサンプル精度
     */
    private fun yin(samples: FloatArray, sampleRate: Int): Double? {
        val n = samples.size
        val halfN = n / 2

        val minLag = (sampleRate / MAX_FREQ).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / MIN_FREQ).toInt().coerceAtMost(halfN - 1)

        if (minLag >= maxLag) return null

        // Step1 & Step2: 差分二乗関数 + 累積平均正規化
        val diff = DoubleArray(maxLag + 1)
        val cmndf = DoubleArray(maxLag + 1)

        // tau=0 は常に1（定義）
        cmndf[0] = 1.0
        diff[0] = 0.0

        var runningSum = 0.0

        for (tau in 1..maxLag) {
            var sum = 0.0
            for (i in 0 until halfN) {
                val delta = samples[i].toDouble() - samples[i + tau].toDouble()
                sum += delta * delta
            }
            diff[tau] = sum
            runningSum += sum
            // 累積平均正規化
            cmndf[tau] = if (runningSum > 0.0) {
                sum * tau / runningSum
            } else {
                1.0
            }
        }

        // Step3: 閾値以下の最初のローカルミニマムを探す
        var bestTau = -1

        // minLagから探索、閾値以下に入ったあと最初の谷（上昇に転じる直前）を採用
        var tau = minLag
        while (tau < maxLag) {
            if (cmndf[tau] < YIN_THRESHOLD) {
                // 閾値以下に入った → ここから局所最小値を探す
                while (tau + 1 < maxLag && cmndf[tau + 1] < cmndf[tau]) {
                    tau++
                }
                bestTau = tau
                break
            }
            tau++
        }

        // 閾値以下が見つからない場合は最小値を使用
        if (bestTau < 0) {
            var minVal = Double.MAX_VALUE
            for (t in minLag..maxLag) {
                if (cmndf[t] < minVal) {
                    minVal = cmndf[t]
                    bestTau = t
                }
            }
        }

        if (bestTau < 0 || cmndf[bestTau] >= 0.5) return null

        // Step4: 放物線補間でサブサンプル精度
        val refinedTau = if (bestTau in (minLag + 1) until maxLag) {
            val y0 = cmndf[bestTau - 1]
            val y1 = cmndf[bestTau]
            val y2 = cmndf[bestTau + 1]
            val denom = 2.0 * (2.0 * y1 - y0 - y2)
            if (denom != 0.0) {
                bestTau + (y0 - y2) / denom
            } else {
                bestTau.toDouble()
            }
        } else {
            bestTau.toDouble()
        }

        val freq = sampleRate.toDouble() / refinedTau
        return if (freq in MIN_FREQ..MAX_FREQ) freq else null
    }

    private fun calculateRms(samples: FloatArray): Float {
        var sumSq = 0.0
        for (s in samples) sumSq += s * s
        return sqrt(sumSq / samples.size).toFloat()
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}
