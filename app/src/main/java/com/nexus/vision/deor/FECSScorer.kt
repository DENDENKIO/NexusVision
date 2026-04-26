// ファイルパス: app/src/main/java/com/nexus/vision/deor/FECSScorer.kt

package com.nexus.vision.deor

import android.util.Log

/**
 * FECS (Frequency-Entropy Coupled Scoring)
 *
 * F = H × (E_mid + 2·E_high) / (1 + E_low)
 *
 * ルーティング閾値:
 *   F < 1.5  → ルート A（バイキュービック補間のみ）
 *   1.5 - 4.0 → ルート B（蒸留 RRDB + LoRA）
 *   ≥ 4.0    → ルート C（1ステップ拡散 + LoRA）
 *
 * Phase 3: 基本実装
 * Phase 6: EASS パイプラインで各タイルに適用
 */
object FECSScorer {

    private const val TAG = "FECSScorer"

    /** ルーティング先 */
    enum class Route {
        /** バイキュービック補間のみ（低複雑度） */
        A,
        /** 蒸留 RRDB + LoRA（中複雑度） */
        B,
        /** 1ステップ拡散 + LoRA（高複雑度） */
        C
    }

    // ── 閾値定数 ──
    const val THRESHOLD_A_B = 1.5
    const val THRESHOLD_B_C = 4.0

    /**
     * FECS スコアを計算する。
     *
     * @param entropy Shannon エントロピー (H)
     * @param eLow    低周波エネルギー比
     * @param eMid    中周波エネルギー比
     * @param eHigh   高周波エネルギー比
     * @return FECS スコア F
     */
    fun calculateScore(
        entropy: Double,
        eLow: Double,
        eMid: Double,
        eHigh: Double
    ): Double {
        val denominator = 1.0 + eLow
        val numerator = eMid + 2.0 * eHigh
        val score = entropy * numerator / denominator

        Log.d(TAG, "FECS: H=%.3f, E_low=%.3f, E_mid=%.3f, E_high=%.3f → F=%.3f".format(
            entropy, eLow, eMid, eHigh, score
        ))
        return score
    }

    /**
     * FECS スコアからルーティング先を決定する。
     */
    fun determineRoute(score: Double): Route {
        return when {
            score < THRESHOLD_A_B -> Route.A
            score < THRESHOLD_B_C -> Route.B
            else -> Route.C
        }
    }

    /**
     * タイルのピクセルデータから直接 FECS スコアとルートを計算する。
     * EASS パイプラインのメインエントリーポイント。
     *
     * @param pixels ARGB ピクセル配列
     * @param width  タイルの幅
     * @param height タイルの高さ
     * @return Pair(FECS スコア, ルート)
     */
    fun scoreAndRoute(pixels: IntArray, width: Int, height: Int): Pair<Double, Route> {
        // エントロピー計算
        val entropy = EntropyCalculator.calculateFromPixels(pixels)

        // DCT エネルギー比計算
        val (eLow, eMid, eHigh) = PHashCalculator.calculateDctEnergyRatios(pixels, width, height)

        // FECS スコア計算
        val score = calculateScore(entropy, eLow, eMid, eHigh)
        val route = determineRoute(score)

        Log.d(TAG, "Tile ${width}x${height}: F=%.3f → Route $route".format(score))
        return score to route
    }
}
