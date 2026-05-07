package com.nexus.vision.audio

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/**
 * 音を2D平面図として可視化するカスタムView
 *
 * 3エリア構成:
 *  A) 音高リング   - 12音を円形配置。強い音ほど大きく光る
 *  B) 音場マップ   - X軸=スペクトル重心(音の明るさ)、Y軸=音量。
 *                    軌跡を残して「音が空間を移動」するように見える
 *  C) スペクトル地形 - 周波数×強度を等高線ライクに塗りつぶし
 */
class SoundMap2DView(context: Context) : View(context) {

    // ── データ ───────────────────────────────────────────────
    private var chroma       = FloatArray(12)
    private var mags         = FloatArray(1024)
    private var centroid     = 0f
    private var rmsDb        = -60f
    private var zcr          = 0f
    private var currentNote  = -1   // MIDI note % 12、-1=無音
    private var centsDiff    = 0f

    // 音場マップ用トレイル（過去80点）
    private val trailX   = ArrayDeque<Float>(80)
    private val trailY   = ArrayDeque<Float>(80)

    // ── ペイント ─────────────────────────────────────────────
    private val bgPaint      = Paint().apply { color = Color.parseColor("#0D0D0F") }
    private val gridPaint    = Paint().apply {
        color = Color.parseColor("#1E1E2A")
        style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val notePaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val noteRingPaint= Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val labelPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val activeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 24f
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val trailPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val specPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dividerPaint = Paint().apply {
        color = Color.parseColor("#222233"); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val titlePaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44AAAACC"); textSize = 18f
    }

    private val NOTE_NAMES = arrayOf(
        "C","C#","D","D#","E","F","F#","G","G#","A","A#","B"
    )

    // ── 更新API ──────────────────────────────────────────────

    fun update(
        f: TunerOverlayService.AnalysisResult,
        pitch: PitchDetector.PitchResult?
    ) {
        chroma    = f.chroma.clone()
        mags      = f.mags.clone()
        centroid  = f.spectralCentroid
        rmsDb     = f.rmsDbfs
        zcr       = f.zcr
        currentNote = pitch?.midiNumber?.let { ((it % 12) + 12) % 12 } ?: -1
        centsDiff   = pitch?.centsDiff ?: 0f

        // 音場マップ用座標を積算（centroid→X, rmsDb→Y で正規化）
        val nx = (centroid / 8000f).coerceIn(0f, 1f)
        val ny = ((rmsDb + 60f) / 60f).coerceIn(0f, 1f)
        trailX.addLast(nx)
        trailY.addLast(ny)
        if (trailX.size > 80) { trailX.removeFirst(); trailY.removeFirst() }

        invalidate()
    }

    // ── 描画 ─────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val W = width.toFloat()
        val H = height.toFloat()
        if (W < 10f || H < 10f) return

        // 背景
        canvas.drawRect(0f, 0f, W, H, bgPaint)

        // レイアウト分割
        //  左: 音高リング (0..W*0.42)
        //  中: 仕切り
        //  右上: 音場マップ (W*0.44..W)×(0..H*0.55)
        //  右下: スペクトル地形 (W*0.44..W)×(H*0.57..H)
        val ringRight  = W * 0.44f
        val rightLeft  = W * 0.46f
        val mapBottom  = H * 0.54f
        val specTop    = H * 0.57f

        canvas.drawLine(ringRight, 0f, ringRight, H, dividerPaint)
        canvas.drawLine(rightLeft, mapBottom, W, mapBottom, dividerPaint)

        drawNoteRing(canvas, ringRight / 2f, H / 2f,
            min(ringRight, H) * 0.38f)

        drawSoundMap(canvas,
            rightLeft + 4f, 4f,
            W - 4f, mapBottom - 4f)

        drawSpectrumTerrain(canvas,
            rightLeft + 4f, specTop + 4f,
            W - 4f, H - 4f)

        // エリアタイトル
        canvas.drawText("音高", 12f, 24f, titlePaint)
        canvas.drawText("音場", rightLeft + 8f, 24f, titlePaint)
        canvas.drawText("周波数地形", rightLeft + 8f, specTop + 24f, titlePaint)
    }

    // ─────────────────────────────────────────────────────────
    // A) 音高リング
    //   12音を時計配置。強度に応じて半径・輝度が変化。
    //   現在検出音をハイライト + チューニングメーター
    // ─────────────────────────────────────────────────────────
    private fun drawNoteRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val maxChroma = chroma.maxOrNull()?.coerceAtLeast(0.001f) ?: 0.001f

        for (i in 0..11) {
            // C=上(270°)から時計回り
            val angleDeg = i * 30.0 - 90.0
            val angleRad = Math.toRadians(angleDeg)
            val norm = (chroma[i] / maxChroma).coerceIn(0f, 1f)

            // 外周座標（ラベル用）
            val lx = cx + (r + 18f) * cos(angleRad).toFloat()
            val ly = cy + (r + 18f) * sin(angleRad).toFloat() + 8f

            // ドット半径（音の強さで変化）
            val dotR = 4f + norm * 14f

            // 色: C=赤、D=橙...12色相環。明るさはnormで変動
            val hue = i * 30f
            val brightness = 0.35f + norm * 0.65f
            val sat = if (i == currentNote) 1f else 0.6f + norm * 0.4f
            notePaint.color = hsvColor(hue, sat, brightness)

            // 現在音: 発光エフェクト（外側に拡散円）
            if (i == currentNote) {
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = hsvColor(hue, 0.8f, 0.9f)
                    style = Paint.Style.FILL
                    maskFilter = BlurMaskFilter(dotR * 2.5f, BlurMaskFilter.Blur.NORMAL)
                }
                val nx = cx + r * cos(angleRad).toFloat()
                val ny = cy + r * sin(angleRad).toFloat()
                canvas.drawCircle(nx, ny, dotR * 1.8f, glowPaint)
                canvas.drawCircle(nx, ny, dotR, notePaint)
                canvas.drawText(NOTE_NAMES[i], lx, ly, activeLabelPaint)

                // チューニングアーク（-50〜+50セント → 半円）
                drawTuningArc(canvas, cx, cy, r * 0.55f, centsDiff)
            } else {
                val nx = cx + r * cos(angleRad).toFloat()
                val ny = cy + r * sin(angleRad).toFloat()
                canvas.drawCircle(nx, ny, dotR, notePaint)
                canvas.drawText(NOTE_NAMES[i], lx, ly, labelPaint)
            }

            // 各音から中心へ強度に応じた線
            if (norm > 0.15f) {
                noteRingPaint.color = hsvColor(hue, 0.7f, norm * 0.5f)
                noteRingPaint.alpha = (norm * 120).toInt()
                val ox = cx + r * 0.08f * cos(angleRad).toFloat()
                val oy = cy + r * 0.08f * sin(angleRad).toFloat()
                canvas.drawLine(
                    cx + r * cos(angleRad).toFloat(),
                    cy + r * sin(angleRad).toFloat(),
                    ox, oy, noteRingPaint
                )
            }
        }

        // 中心: 音名テキスト
        val centerLabel = if (currentNote >= 0) NOTE_NAMES[currentNote] else "♪"
        val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (currentNote >= 0) Color.WHITE else Color.parseColor("#555555")
            textSize = r * 0.28f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        canvas.drawText(centerLabel, cx, cy + cp.textSize * 0.35f, cp)
    }

    /** チューニングアーク: 中心下部に小さな半円メーター */
    private fun drawTuningArc(canvas: Canvas, cx: Float, cy: Float, r: Float, cents: Float) {
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
            color = when {
                abs(cents) < 5f  -> Color.parseColor("#4CAF50")
                abs(cents) < 15f -> Color.parseColor("#FFC107")
                else             -> Color.parseColor("#FF5722")
            }
        }
        // -50~+50を -90~+90度にマップ（底部に表示）
        val startAngle = 90f                          // 真下
        val sweepAngle = (cents / 50f * 90f).coerceIn(-90f, 90f)
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, startAngle, sweepAngle, false, arcPaint)

        // 中心バー（基準線）
        val basePaint = Paint().apply {
            color = Color.parseColor("#333344"); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        canvas.drawLine(cx, cy + r * 0.1f, cx, cy + r, basePaint)
    }

    // ─────────────────────────────────────────────────────────
    // B) 音場マップ
    //   X軸=スペクトル重心（音の明るさ 0〜8kHz）
    //   Y軸=音量（RMS dB −60〜0）
    //   軌跡を残して音の動きを可視化
    // ─────────────────────────────────────────────────────────
    private fun drawSoundMap(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        val W = right - left
        val H = bottom - top

        // グリッド
        val gPaint = Paint().apply {
            color = Color.parseColor("#1A1A2E"); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        for (i in 1..3) {
            canvas.drawLine(left + W * i / 4f, top, left + W * i / 4f, bottom, gPaint)
            canvas.drawLine(left, top + H * i / 4f, right, top + H * i / 4f, gPaint)
        }

        // 軸ラベル
        val axPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#336666"); textSize = 16f
        }
        canvas.drawText("暗←", left + 2f, bottom - 2f, axPaint)
        canvas.drawText("→明", right - 36f, bottom - 2f, axPaint)
        canvas.drawText("大", left + 2f, top + 14f, axPaint)
        canvas.drawText("小", left + 2f, bottom - 14f, axPaint)

        // トレイル描画
        if (trailX.size >= 2) {
            val path = Path()
            trailX.forEachIndexed { idx, nx ->
                val px = left + nx * W
                val py = bottom - trailY[idx] * H
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }

            // 古い点ほど透明
            val shader = SweepGradient(
                left + (trailX.lastOrNull() ?: 0.5f) * W,
                bottom - (trailY.lastOrNull() ?: 0.5f) * H,
                intArrayOf(
                    Color.parseColor("#002244"),
                    Color.parseColor("#0066AA"),
                    Color.parseColor("#00AAFF")
                ),
                floatArrayOf(0f, 0.7f, 1f)
            )
            trailPaint.shader = shader
            trailPaint.alpha  = 180
            canvas.drawPath(path, trailPaint)
            trailPaint.shader = null
        }

        // 現在位置ドット（大きく・発光）
        val curX = left + (centroid / 8000f).coerceIn(0f, 1f) * W
        val curY = bottom - ((rmsDb + 60f) / 60f).coerceIn(0f, 1f) * H

        // ZCRで色分け: 高=打楽器系(赤)、低=トーン系(水色)
        val dotColor = if (zcr > 0.2f)
            Color.parseColor("#FF6644")
        else
            Color.parseColor("#44CCFF")

        val glowDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dotColor; style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(curX, curY, 10f, glowDot)
        dotPaint.color = dotColor
        canvas.drawCircle(curX, curY, 5f, dotPaint)
    }

    // ─────────────────────────────────────────────────────────
    // C) スペクトル地形図
    //   周波数を横軸、強度を色の高さとして「等高線」的に塗る
    //   低周波=暖色、高周波=寒色で直感的
    // ─────────────────────────────────────────────────────────
    private fun drawSpectrumTerrain(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        if (mags.isEmpty()) return
        val W = right - left
        val H = bottom - top

        val bins   = 128  // 表示バンド数（軽量化）
        val step   = mags.size / bins
        val maxMag = mags.take(mags.size).maxOrNull()?.coerceAtLeast(0.001f) ?: 0.001f

        val barW = W / bins

        for (i in 0 until bins) {
            val mag  = (0 until step).map { mags[i * step + it] }.average().toFloat()
            val norm = (mag / maxMag).coerceIn(0f, 1f)

            // 色: 低周波(赤)→中域(黄緑)→高周波(青)
            val hue   = (i.toFloat() / bins) * 240f   // 0°赤 → 240°青
            val sat   = 0.8f + norm * 0.2f
            val bri   = 0.15f + norm * 0.85f

            val barH = norm * H

            // グラデーション塗りつぶし（上部→透明、下部→実色）
            val shader = LinearGradient(
                left + i * barW, bottom - barH,
                left + i * barW, bottom,
                intArrayOf(
                    hsvColorAlpha(hue, sat, bri, 20),
                    hsvColorAlpha(hue, sat, bri, 220)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            specPaint.shader = shader
            canvas.drawRect(
                left + i * barW, bottom - barH,
                left + (i + 1) * barW - 1f, bottom,
                specPaint
            )
            specPaint.shader = null

            // 頂点ライン（等高線イメージ）
            if (norm > 0.05f) {
                val linePaint = Paint().apply {
                    color = hsvColorAlpha(hue, 1f, 1f, (norm * 180).toInt())
                    strokeWidth = 1.5f; style = Paint.Style.STROKE
                }
                canvas.drawLine(
                    left + i * barW, bottom - barH,
                    left + (i + 1) * barW, bottom - barH,
                    linePaint
                )
            }
        }

        // 周波数ラベル
        val fLabels = listOf("0" to 0f, "500" to 500f, "2k" to 2000f, "8k" to 8000f, "20k" to 20000f)
        val nyq = 22050f
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#335566"); textSize = 16f; textAlign = Paint.Align.CENTER
        }
        for ((label, freq) in fLabels) {
            val xPos = left + (freq / nyq) * W
            if (xPos in left..right) {
                canvas.drawLine(xPos, top, xPos, bottom, gridPaint)
                canvas.drawText(label, xPos, bottom - 2f, lp)
            }
        }
    }

    // ── ユーティリティ ────────────────────────────────────────

    private fun hsvColor(h: Float, s: Float, v: Float): Int {
        val arr = floatArrayOf(h, s, v)
        return Color.HSVToColor(arr)
    }

    private fun hsvColorAlpha(h: Float, s: Float, v: Float, alpha: Int): Int {
        val arr = floatArrayOf(h, s, v)
        return (Color.HSVToColor(arr) and 0x00FFFFFF) or (alpha.coerceIn(0,255) shl 24)
    }
}
