package com.nexus.vision.audio

import kotlin.math.*

/**
 * Gemma不使用・音楽理論特化エンジン
 *
 * すべてインメモリ計算。推論待ちゼロ。
 *
 * 機能:
 *  - Krumhansl-Schmuckler アルゴリズムによる調性判定
 *  - クロマ累積による和音名推定
 *  - 調性+音名から次に来る音の提案
 *  - BPM/エネルギー/スペクトルからムード文生成
 *  - ギタースケール表示（現在の調性）
 */
object MusicTheoryEngine {

    // ────────────────────────────────────────────────
    // 定数テーブル
    // ────────────────────────────────────────────────

    private val NOTE_NAMES = arrayOf(
        "C","C#","D","D#","E","F","F#","G","G#","A","A#","B"
    )
    private val NOTE_NAMES_JP = arrayOf(
        "ド","ド#","レ","レ#","ミ","ファ","ファ#","ソ","ソ#","ラ","ラ#","シ"
    )

    // Krumhansl-Schmuckler プロファイル（メジャー）
    private val KS_MAJOR = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09,
        2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    )
    // Krumhansl-Schmuckler プロファイル（マイナー）
    private val KS_MINOR = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53,
        2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    )

    // コードパターン [ルート音からの半音間隔]
    private val CHORD_PATTERNS = mapOf(
        "maj"   to intArrayOf(0, 4, 7),
        "min"   to intArrayOf(0, 3, 7),
        "7"     to intArrayOf(0, 4, 7, 10),
        "maj7"  to intArrayOf(0, 4, 7, 11),
        "min7"  to intArrayOf(0, 3, 7, 10),
        "dim"   to intArrayOf(0, 3, 6),
        "aug"   to intArrayOf(0, 4, 8),
        "sus4"  to intArrayOf(0, 5, 7),
        "sus2"  to intArrayOf(0, 2, 7),
        "add9"  to intArrayOf(0, 4, 7, 14)
    )

    // 各調のダイアトニックコード（度数→コード種）
    // index: 0=I, 1=II, 2=III, 3=IV, 4=V, 5=VI, 6=VII
    private val MAJOR_DIATONIC = arrayOf("maj","min","min","maj","7","min","dim")
    private val MINOR_DIATONIC = arrayOf("min","dim","maj","min","min","maj","7")

    // スケール音程テーブル
    private val SCALES = mapOf(
        "major"          to intArrayOf(0,2,4,5,7,9,11),
        "natural_minor"  to intArrayOf(0,2,3,5,7,8,10),
        "harmonic_minor" to intArrayOf(0,2,3,5,7,8,11),
        "dorian"         to intArrayOf(0,2,3,5,7,9,10),
        "mixolydian"     to intArrayOf(0,2,4,5,7,9,10),
        "pentatonic_maj" to intArrayOf(0,2,4,7,9),
        "pentatonic_min" to intArrayOf(0,3,5,7,10),
        "blues"          to intArrayOf(0,3,5,6,7,10)
    )

    // ────────────────────────────────────────────────
    // データクラス
    // ────────────────────────────────────────────────

    data class KeyResult(
        val keyIndex: Int,          // 0=C … 11=B
        val keyName: String,        // "C", "G#" など
        val mode: String,           // "major" / "minor"
        val confidence: Float,      // 0.0〜1.0
        val keyNameJp: String       // "ハ長調" など
    )

    data class ChordResult(
        val rootName: String,
        val chordType: String,
        val fullName: String,       // "Cmaj7" など
        val score: Float,
        val roman: String           // "I", "IVmaj7" など
    )

    data class ScaleResult(
        val scaleName: String,
        val scaleNameJp: String,
        val notes: List<String>,    // スケール構成音
        val nextNotes: List<String> // 現在音の次に来やすい音
    )

    data class MoodResult(
        val mood: String,           // "明るく活発" など
        val moodEmoji: String,
        val genreHint: String,
        val description: String     // 1行コメント
    )

    data class MusicAnalysis(
        val key: KeyResult?,
        val chord: ChordResult?,
        val scale: ScaleResult?,
        val mood: MoodResult?,
        val diatonicChords: List<String>  // I〜VII のコード名
    )

    // ────────────────────────────────────────────────
    // クロマ累積バッファ（フレーム間平滑化）
    // ────────────────────────────────────────────────

    private val chromaAccum = FloatArray(12)
    private const val CHROMA_SMOOTHING = 0.85f   // 低いほど反応が速い
    private var frameCount = 0

    // ────────────────────────────────────────────────
    // メインAPI
    // ────────────────────────────────────────────────

    /**
     * フレームごとに呼ぶ。クロマを平滑化しながら全解析を実行。
     * 完全同期・スレッドセーフ（capture thread から直接呼び可）
     *
     * @param chromaVector  FeatureExtractor が出力した12次元クロマ
     * @param pitch         PitchDetector の結果（null=無音）
     * @param bpm           BeatDetector の bpm
     * @param rms           振幅（エネルギー代替）
     * @param spectralCentroid  スペクトル重心 Hz
     */
    fun analyze(
        chromaVector: FloatArray,
        pitch: PitchDetector.PitchResult?,
        bpm: Float,
        rms: Float,
        spectralCentroid: Float
    ): MusicAnalysis {
        // クロマ平滑化（指数移動平均）
        for (i in 0..11) {
            chromaAccum[i] = CHROMA_SMOOTHING * chromaAccum[i] +
                             (1f - CHROMA_SMOOTHING) * chromaVector[i]
        }
        frameCount++

        val key   = detectKey(chromaAccum)
        val chord = if (pitch != null) detectChord(chromaAccum, key) else null
        val scale = if (key != null) buildScale(key, pitch) else null
        val mood  = buildMood(bpm, rms, spectralCentroid, key)
        val diatonic = if (key != null) buildDiatonicChords(key) else emptyList()

        return MusicAnalysis(key, chord, scale, mood, diatonic)
    }

    /** クロマリセット（曲変わり検知後などに呼ぶ） */
    fun reset() {
        chromaAccum.fill(0f)
        frameCount = 0
    }

    // ────────────────────────────────────────────────
    // 調性判定: Krumhansl-Schmuckler
    // ────────────────────────────────────────────────

    fun detectKey(chroma: FloatArray): KeyResult? {
        if (frameCount < 5) return null  // バッファ不足

        val total = chroma.sum()
        if (total < 0.01f) return null

        // 正規化
        val norm = DoubleArray(12) { chroma[it].toDouble() / total }

        var bestScore = Double.MIN_VALUE
        var bestKey = 0
        var bestMode = "major"

        for (root in 0..11) {
            val scoreMaj = pearsonCorrelation(norm, KS_MAJOR, root)
            val scoreMin = pearsonCorrelation(norm, KS_MINOR, root)

            if (scoreMaj > bestScore) { bestScore = scoreMaj; bestKey = root; bestMode = "major" }
            if (scoreMin > bestScore) { bestScore = scoreMin; bestKey = root; bestMode = "minor" }
        }

        // 信頼度: pearson相関を0〜1にスケール
        val confidence = ((bestScore + 1.0) / 2.0).toFloat().coerceIn(0f, 1f)

        val jp = buildJpKeyName(bestKey, bestMode)
        return KeyResult(bestKey, NOTE_NAMES[bestKey], bestMode, confidence, jp)
    }

    private fun pearsonCorrelation(x: DoubleArray, profile: DoubleArray, shift: Int): Double {
        val n = 12
        val xMean = x.average()
        val pMean = profile.average()

        var num = 0.0; var dx2 = 0.0; var dp2 = 0.0
        for (i in 0 until n) {
            val xi = x[i] - xMean
            val pi = profile[(i - shift + n) % n] - pMean
            num += xi * pi; dx2 += xi * xi; dp2 += pi * pi
        }
        val denom = sqrt(dx2 * dp2)
        return if (denom < 1e-10) 0.0 else num / denom
    }

    private fun buildJpKeyName(keyIndex: Int, mode: String): String {
        val majorNames = arrayOf(
            "ハ長調","嬰ハ長調","ニ長調","嬰ニ長調","ホ長調","ヘ長調",
            "嬰ヘ長調","ト長調","嬰ト長調","イ長調","嬰イ長調","ロ長調"
        )
        val minorNames = arrayOf(
            "イ短調","嬰イ短調","ロ短調","嬰ロ短調","嬰ハ短調","ニ短調",
            "嬰ニ短調","ホ短調","嬰ホ短調","嬰ヘ短調","ト短調","嬰ト短調"
        )
        // 短調のrootはルートから3度下がったところが主調
        return if (mode == "major") majorNames[keyIndex] else minorNames[keyIndex]
    }

    // ────────────────────────────────────────────────
    // 和音名推定
    // ────────────────────────────────────────────────

    fun detectChord(chroma: FloatArray, key: KeyResult?): ChordResult? {
        val total = chroma.sum()
        if (total < 0.01f) return null

        val norm = FloatArray(12) { chroma[it] / total }

        var bestScore = -1f
        var bestRoot = 0
        var bestType = "maj"

        for (root in 0..11) {
            for ((type, intervals) in CHORD_PATTERNS) {
                var score = 0f
                for (interval in intervals) {
                    val noteIdx = (root + interval) % 12
                    score += norm[noteIdx]
                }
                // ルート音に重みづけ
                score += norm[root] * 0.3f
                if (score > bestScore) {
                    bestScore = score; bestRoot = root; bestType = type
                }
            }
        }

        val rootName = NOTE_NAMES[bestRoot]
        val fullName = if (bestType == "maj") rootName else "$rootName$bestType"
        val roman = key?.let { buildRoman(bestRoot, bestType, it) } ?: ""

        return ChordResult(rootName, bestType, fullName, bestScore, roman)
    }

    private fun buildRoman(root: Int, type: String, key: KeyResult): String {
        val degree = ((root - key.keyIndex + 12) % 12)
        val diatonicSteps = if (key.mode == "major")
            intArrayOf(0,2,4,5,7,9,11)
        else
            intArrayOf(0,2,3,5,7,8,10)

        val romanNumerals = arrayOf("I","II","III","IV","V","VI","VII")
        val stepIdx = diatonicSteps.indexOfFirst { it == degree }
        if (stepIdx < 0) return "?"

        val numeral = if (type.contains("min") || type == "dim") 
            romanNumerals[stepIdx].lowercase()
        else 
            romanNumerals[stepIdx]

        val suffix = when (type) {
            "maj"  -> ""
            "min"  -> ""
            "7"    -> "7"
            "maj7" -> "△7"
            "min7" -> "m7"
            "dim"  -> "°"
            "aug"  -> "+"
            else   -> type
        }
        return "$numeral$suffix"
    }

    // ────────────────────────────────────────────────
    // スケール＆次の音提案
    // ────────────────────────────────────────────────

    fun buildScale(key: KeyResult, pitch: PitchDetector.PitchResult?): ScaleResult {
        val scaleName = if (key.mode == "major") "major" else "natural_minor"
        val scaleNameJp = if (key.mode == "major") "メジャースケール" else "ナチュラルマイナースケール"
        val intervals = SCALES[scaleName]!!

        val notes = intervals.map { interval ->
            NOTE_NAMES[(key.keyIndex + interval) % 12]
        }

        // 次に来やすい音: 現在音がスケール内ならその次と2つ先
        val nextNotes = if (pitch != null) {
            val currentIdx = ((pitch.midiNumber % 12) + 12) % 12
            val posInScale = intervals.indexOfFirst { 
                (key.keyIndex + it) % 12 == currentIdx 
            }
            if (posInScale >= 0) {
                // スケール内→隣接音2つ
                val next1 = intervals[(posInScale + 1) % intervals.size]
                val next2 = intervals[(posInScale + 2) % intervals.size]
                val prev1 = intervals[(posInScale - 1 + intervals.size) % intervals.size]
                listOf(
                    NOTE_NAMES[(key.keyIndex + next1) % 12],
                    NOTE_NAMES[(key.keyIndex + next2) % 12],
                    NOTE_NAMES[(key.keyIndex + prev1) % 12]
                ).distinct()
            } else {
                // スケール外→最近傍スケール音
                intervals.map { (key.keyIndex + it) % 12 }
                    .sortedBy { abs(it - currentIdx) }
                    .take(3)
                    .map { NOTE_NAMES[it] }
            }
        } else {
            // 無音→主音・属音・導音
            listOf(
                notes[0],                          // 主音
                notes.getOrNull(4) ?: notes[0],    // 属音
                notes.lastOrNull() ?: notes[0]     // 導音
            )
        }

        return ScaleResult(scaleName, scaleNameJp, notes, nextNotes)
    }

    // ────────────────────────────────────────────────
    // ダイアトニックコード表
    // ────────────────────────────────────────────────

    fun buildDiatonicChords(key: KeyResult): List<String> {
        val pattern = if (key.mode == "major") MAJOR_DIATONIC else MINOR_DIATONIC
        val steps = if (key.mode == "major")
            intArrayOf(0,2,4,5,7,9,11)
        else
            intArrayOf(0,2,3,5,7,8,10)

        return steps.mapIndexed { i, step ->
            val root = (key.keyIndex + step) % 12
            val type = pattern[i]
            val name = NOTE_NAMES[root]
            if (type == "maj") name else "$name$type"
        }
    }

    // ────────────────────────────────────────────────
    // ムード判定（スペクトル特徴ベース）
    // ────────────────────────────────────────────────

    fun buildMood(
        bpm: Float,
        rms: Float,
        spectralCentroid: Float,
        key: KeyResult?
    ): MoodResult {
        // valence: メジャー=明るい、マイナー=暗い
        val valence = when (key?.mode) {
            "major" -> 0.7f
            "minor" -> 0.3f
            else    -> 0.5f
        }

        // energy: BPM + RMS から算出
        val energyBpm   = ((bpm - 60f) / 120f).coerceIn(0f, 1f)
        val energyRms   = (rms * 10f).coerceIn(0f, 1f)
        val energy      = (energyBpm * 0.6f + energyRms * 0.4f)

        // brightness: スペクトル重心が高いほど明るい
        val brightness  = ((spectralCentroid - 200f) / 3800f).coerceIn(0f, 1f)

        val mood = when {
            valence > 0.6f && energy > 0.6f  -> "明るく活発"
            valence > 0.6f && energy > 0.3f  -> "爽やかで軽快"
            valence > 0.6f                   -> "穏やかで明るい"
            valence < 0.4f && energy > 0.7f  -> "激しく緊張感がある"
            valence < 0.4f && energy > 0.4f  -> "憂鬱でドラマチック"
            valence < 0.4f                   -> "静かで内省的"
            energy > 0.7f                    -> "力強くエネルギッシュ"
            else                             -> "中性的でバランスの良い"
        }

        val emoji = when {
            valence > 0.6f && energy > 0.6f  -> "🎉"
            valence > 0.6f && energy > 0.3f  -> "🌟"
            valence > 0.6f                   -> "🌸"
            valence < 0.4f && energy > 0.7f  -> "⚡"
            valence < 0.4f && energy > 0.4f  -> "🌙"
            valence < 0.4f                   -> "🍂"
            energy > 0.7f                    -> "🔥"
            else                             -> "🎵"
        }

        val genre = buildGenreHint(bpm, energy, brightness, key?.mode ?: "")
        val desc  = buildDescription(bpm, key, mood)

        return MoodResult(mood, emoji, genre, desc)
    }

    private fun buildGenreHint(bpm: Float, energy: Float, brightness: Float, mode: String): String {
        return when {
            bpm > 160f && energy > 0.7f                   -> "テクノ/ドラムンベース"
            bpm in 128f..160f && energy > 0.6f            -> "EDM/ハウス"
            bpm in 120f..135f && mode == "minor"          -> "トランス/ダークEDM"
            bpm in 100f..130f && brightness > 0.6f        -> "ポップ/ロック"
            bpm in 100f..130f && mode == "minor"          -> "Jロック/アニソン"
            bpm in 80f..110f && energy < 0.4f             -> "バラード/R&B"
            bpm in 85f..115f && mode == "minor"           -> "ジャズ/ソウル"
            bpm in 60f..90f  && energy < 0.3f             -> "アンビエント/チル"
            bpm > 140f && mode == "major"                 -> "ユーロビート/アニソン"
            bpm < 70f                                     -> "スローバラード"
            else                                          -> "J-POP/一般"
        }
    }

    private fun buildDescription(bpm: Float, key: KeyResult?, mood: String): String {
        if (key == null) return "解析中..."
        val bpmLabel = when {
            bpm > 160f -> "超高速"
            bpm > 130f -> "アップテンポ"
            bpm > 100f -> "ミディアムテンポ"
            bpm > 70f  -> "スローテンポ"
            bpm > 0f   -> "ゆったり"
            else       -> "テンポ不明"
        }
        return "${key.keyNameJp} / $bpmLabel ${bpm.roundToInt()}BPM / $mood"
    }

    // ────────────────────────────────────────────────
    // ユーティリティ
    // ────────────────────────────────────────────────

    /** MIDI番号→音名 */
    fun midiToNoteName(midi: Int): String = NOTE_NAMES[((midi % 12) + 12) % 12]

    /** セント差→チューニング状態文字列 */
    fun tuningStatus(centsDiff: Float): String = when {
        abs(centsDiff) < 5f  -> "✓ イン"
        centsDiff > 0         -> "+${centsDiff.roundToInt()}¢ 高い"
        else                  -> "${centsDiff.roundToInt()}¢ 低い"
    }

    /** スケール音かどうか判定 */
    fun isInScale(midiNote: Int, key: KeyResult, scaleName: String = "major"): Boolean {
        val intervals = SCALES[if (key.mode == "major") "major" else "natural_minor"] ?: return false
        val degree = ((midiNote % 12) - key.keyIndex + 12) % 12
        return intervals.contains(degree)
    }
}
