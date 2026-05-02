package com.nexus.vision.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * リアルタイム音階判定エンジン
 * - 自己相関（Autocorrelation）によるピッチ検出
 * - 12平均律による音名マッピング（A4 = 440Hz）
 */
object PitchDetector {

    private const val TAG = "PitchDetector"

    // ── 音名定義 ──
    private val NOTE_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F",
        "F#", "G", "G#", "A", "A#", "B"
    )

    // ── 基準周波数 ──
    private const val A4_FREQ = 440.0
    private const val A4_MIDI = 69  // MIDI番号: A4 = 69

    // ── 検出範囲 (Hz) ──
    private const val MIN_FREQ = 50.0   // ~G1
    private const val MAX_FREQ = 2000.0 // ~B6

    // ── ノイズゲート: RMS がこの値未満なら無音とみなす ──
    private const val RMS_THRESHOLD = 0.02f

    /**
     * ピッチ検出結果
     */
    data class PitchResult(
        val frequency: Float,      // 検出周波数 (Hz)
        val noteName: String,      // 音名 (例: "A", "C#")
        val octave: Int,           // オクターブ番号 (例: 4)
        val centsDiff: Float,      // 最寄りの音からのセント差 (-50 ~ +50)
        val midiNumber: Int,       // MIDI番号
        val amplitude: Float       // RMS振幅
    )

    /**
     * PCM float配列からピッチを検出する
     * @param samples  -1.0〜1.0 に正規化された PCM データ
     * @param sampleRate  サンプリングレート (Hz)
     * @return PitchResult（無音の場合は null）
     */
    fun detect(samples: FloatArray, sampleRate: Int): PitchResult? {
        // ── 1. RMS 計算（ノイズゲート） ──
        val rms = calculateRms(samples)
        if (rms < RMS_THRESHOLD) return null

        // ── 2. 自己相関によるピッチ検出 ──
        val frequency = autocorrelation(samples, sampleRate) ?: return null

        // ── 3. 周波数 → 音名・オクターブ・セント差 ──
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
     * 自己相関アルゴリズム
     * 論文比較で最も精度が高く、オクターブエラーが少ない手法。
     */
    private fun autocorrelation(samples: FloatArray, sampleRate: Int): Double? {
        val n = samples.size

        // 検出周波数範囲をラグ値に変換
        val minLag = (sampleRate / MAX_FREQ).toInt().coerceAtLeast(1)
        val maxLag = (sampleRate / MIN_FREQ).toInt().coerceAtMost(n - 1)

        if (minLag >= maxLag || maxLag >= n) return null

        // ── ゼロラグ（自己相関の最大値）を先に計算 ──
        var zeroLagSum = 0.0
        for (i in 0 until n) {
            zeroLagSum += samples[i] * samples[i]
        }
        if (zeroLagSum < 1e-10) return null

        // ── 各ラグで自己相関値を計算し、最大ピークを探す ──
        var bestLag = -1
        var bestCorr = 0.0

        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until n - lag) {
                sum += samples[i].toDouble() * samples[i + lag].toDouble()
            }
            // 正規化
            val normalized = sum / zeroLagSum

            if (normalized > bestCorr) {
                bestCorr = normalized
                bestLag = lag
            }
        }

        // 相関が弱すぎる場合は信頼できないと判断
        if (bestLag < 0 || bestCorr < 0.2) return null

        // ── 放物線補間（パラボリック・インターポレーション） ──
        // ラグの前後の値を使い、サブサンプル精度で真のピーク位置を推定
        val refinedLag = if (bestLag > minLag && bestLag < maxLag) {
            val corrPrev = autocorrAtLag(samples, bestLag - 1)
            val corrCurr = autocorrAtLag(samples, bestLag)
            val corrNext = autocorrAtLag(samples, bestLag + 1)
            val delta = 0.5 * (corrPrev - corrNext) /
                    (corrPrev - 2.0 * corrCurr + corrNext)
            bestLag.toDouble() + delta
        } else {
            bestLag.toDouble()
        }

        val freq = sampleRate.toDouble() / refinedLag
        return if (freq in MIN_FREQ..MAX_FREQ) freq else null
    }

    private fun autocorrAtLag(samples: FloatArray, lag: Int): Double {
        var sum = 0.0
        for (i in 0 until samples.size - lag) {
            sum += samples[i].toDouble() * samples[i + lag].toDouble()
        }
        return sum
    }

    private fun calculateRms(samples: FloatArray): Float {
        var sumSq = 0.0
        for (s in samples) sumSq += s * s
        return sqrt(sumSq / samples.size).toFloat()
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}
