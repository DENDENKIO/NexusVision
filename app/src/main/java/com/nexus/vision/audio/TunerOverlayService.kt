package com.nexus.vision.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class TunerOverlayService : Service() {
    companion object {
        private const val TAG = "TunerOverlay"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "tuner_overlay_channel"
        const val ACTION_START = "com.nexus.vision.audio.TUNER_START"
        const val ACTION_STOP = "com.nexus.vision.audio.TUNER_STOP"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SAMPLES = 8192
        private const val PITCH_HISTORY_SIZE = 60
        private const val VOLUME_HISTORY_SIZE = 80
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var floatingButton: View? = null
    private var currentButtonBg: View? = null
    private var isCurrentlyListening = false

    // 共通ヘッダー
    private var noteTextView: TextView? = null
    private var freqTextView: TextView? = null
    private var bpmTextView: TextView? = null
    private var statusTextView: TextView? = null

    // タブ
    private var tabPitch: TextView? = null
    private var tabRhythm: TextView? = null
    private var tabVolume: TextView? = null
    private var currentTab = 0  // 0=音程, 1=リズム, 2=音量

    // グラフ
    private var pitchGraphView: PitchGraphView? = null
    private var rhythmGraphView: RhythmGraphView? = null
    private var volumeGraphView: VolumeGraphView? = null
    private var graphContainer: FrameLayout? = null

    // セントメーター
    private var meterLeftView: View? = null
    private var meterRightView: View? = null
    private var centsTextView: TextView? = null
    private var meterContainer: LinearLayout? = null

    // リズム情報
    private var accuracyTextView: TextView? = null
    private var rhythmInfoContainer: LinearLayout? = null

    // データ
    private val pitchHistory = ArrayDeque<PitchDetector.PitchResult?>(PITCH_HISTORY_SIZE)
    private val volumeHistory = ArrayDeque<Float>(VOLUME_HISTORY_SIZE)
    private val volumePeakHistory = ArrayDeque<Float>(VOLUME_HISTORY_SIZE)
    private lateinit var beatDetector: BeatDetector

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else { intent.getParcelableExtra(EXTRA_RESULT_DATA) }
                if (resultData == null) { stopSelf(); return START_NOT_STICKY }
                startForegroundNotification()
                try {
                    val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = pm.getMediaProjection(Activity.RESULT_OK, resultData)
                } catch (e: SecurityException) { stopSelf(); return START_NOT_STICKY }

                beatDetector = BeatDetector(SAMPLE_RATE)
                createTunerOverlay()
                createFloatingButton()
            }
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    // ============================================================
    //  AudioPlaybackCapture → 3つの解析を同時実行
    // ============================================================
    private fun startCapture() {
        if (mediaProjection == null) return
        try {
            val cc = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val af = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord.Builder().setAudioPlaybackCaptureConfig(cc)
                .setAudioFormat(af).setBufferSizeInBytes(maxOf(minBuf, BUFFER_SAMPLES * 2)).build()
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return

            isCapturing = true; audioRecord?.startRecording()
            pitchHistory.clear(); volumeHistory.clear(); volumePeakHistory.clear()
            beatDetector.reset()

            captureThread = Thread({
                val sBuf = ShortArray(BUFFER_SAMPLES); val fBuf = FloatArray(BUFFER_SAMPLES)
                while (isCapturing) {
                    val read = audioRecord?.read(sBuf, 0, BUFFER_SAMPLES) ?: -1
                    if (read <= 0) continue
                    for (i in 0 until read) fBuf[i] = sBuf[i] / 32768.0f

                    // 1. ピッチ検出
                    val pitch = PitchDetector.detect(fBuf, SAMPLE_RATE)
                    synchronized(pitchHistory) {
                        if (pitchHistory.size >= PITCH_HISTORY_SIZE) pitchHistory.removeFirst()
                        pitchHistory.addLast(pitch)
                    }

                    // 2. ビート検出
                    val beat = beatDetector.analyze(fBuf)

                    // 3. 音量 (RMS + ピーク)
                    var sumSq = 0f; var peak = 0f
                    for (i in 0 until read) {
                        val v = abs(fBuf[i]); sumSq += v * v; if (v > peak) peak = v
                    }
                    val rms = kotlin.math.sqrt(sumSq / read)
                    synchronized(volumeHistory) {
                        if (volumeHistory.size >= VOLUME_HISTORY_SIZE) volumeHistory.removeFirst()
                        volumeHistory.addLast(rms)
                        if (volumePeakHistory.size >= VOLUME_HISTORY_SIZE) volumePeakHistory.removeFirst()
                        volumePeakHistory.addLast(peak)
                    }

                    mainHandler.post { updateAll(pitch, beat, rms, peak) }
                }
            }, "TunerCaptureAll"); captureThread?.start()
            updateStatus("解析中...")
        } catch (e: Exception) { Log.e(TAG, "Capture failed", e) }
    }

    private fun stopCapture() {
        isCapturing = false
        try { captureThread?.join(3000); audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null; captureThread = null
    }

    // ============================================================
    //  統合更新
    // ============================================================
    private fun updateAll(pitch: PitchDetector.PitchResult?, beat: BeatDetector.BeatResult, rms: Float, peak: Float) {
        // 共通ヘッダー
        if (pitch != null) {
            noteTextView?.text = "${pitch.noteName}${pitch.octave}"
            freqTextView?.text = "%.1f Hz".format(pitch.frequency)
            val c = when { abs(pitch.centsDiff) < 5f -> "#4CAF50"; abs(pitch.centsDiff) < 15f -> "#FFC107"; else -> "#FF5722" }
            noteTextView?.setTextColor(Color.parseColor(c))
        } else {
            noteTextView?.text = "--"; noteTextView?.setTextColor(Color.parseColor("#666666"))
            freqTextView?.text = "-- Hz"
        }
        bpmTextView?.text = if (beat.bpm > 0) "%.0f BPM".format(beat.bpm) else "-- BPM"
        bpmTextView?.setTextColor(if (beat.confidence > 0.5f) Color.parseColor("#2196F3") else Color.parseColor("#666666"))

        // タブごとの更新
        when (currentTab) {
            0 -> {
                // 音程タブ
                if (pitch != null) {
                    centsTextView?.text = "%+.0f ¢".format(pitch.centsDiff)
                    val cc = when { abs(pitch.centsDiff) < 5f -> "#4CAF50"; abs(pitch.centsDiff) < 15f -> "#FFC107"; else -> "#FF5722" }
                    centsTextView?.setTextColor(Color.parseColor(cc))
                    updateMeter(((pitch.centsDiff + 50f) / 100f).coerceIn(0f, 1f), Color.parseColor(cc))
                } else {
                    centsTextView?.text = "±0 ¢"; centsTextView?.setTextColor(Color.parseColor("#666666"))
                    updateMeter(0.5f, Color.parseColor("#333333"))
                }
                pitchGraphView?.updateData(synchronized(pitchHistory) { pitchHistory.toList() })
            }
            1 -> {
                // リズムタブ
                val accPct = (beat.averageAccuracy * 100).roundToInt()
                val accColor = when { accPct >= 85 -> "#4CAF50"; accPct >= 60 -> "#FFC107"; else -> "#FF5722" }
                val bandText = if (beat.beats.isNotEmpty()) {
                    val last = beat.beats.last()
                    when (last.band) {
                        BeatDetector.Band.LOW -> "⬤ キック"
                        BeatDetector.Band.MID -> "⬤ スネア"
                        BeatDetector.Band.HIGH -> "⬤ ハイハット"
                        BeatDetector.Band.COMBINED -> "⬤ 複合"
                    }
                } else ""
                accuracyTextView?.text = "正確度: ${accPct}%  $bandText"
                accuracyTextView?.setTextColor(Color.parseColor(accColor))
                rhythmGraphView?.updateData(beat)
            }
            2 -> {
                // 音量タブ
                volumeGraphView?.updateData(
                    synchronized(volumeHistory) { volumeHistory.toList() },
                    synchronized(volumePeakHistory) { volumePeakHistory.toList() }
                )
            }
        }
    }

    private fun updateMeter(ratio: Float, color: Int) {
        meterLeftView?.let { l -> meterRightView?.let { r ->
            (l.layoutParams as LinearLayout.LayoutParams).weight = ratio
            (r.layoutParams as LinearLayout.LayoutParams).weight = 1f - ratio
            l.requestLayout(); r.requestLayout()
            (l.background as? GradientDrawable)?.setColor(color)
        }}
    }

    // ============================================================
    //  タブ切り替え
    // ============================================================
    private fun switchTab(tab: Int) {
        currentTab = tab
        val sel = Color.parseColor("#FFFFFF"); val unsel = Color.parseColor("#666666")
        val selBg = Color.parseColor("#333333"); val unselBg = Color.TRANSPARENT
        listOf(tabPitch, tabRhythm, tabVolume).forEachIndexed { i, tv ->
            tv?.setTextColor(if (i == tab) sel else unsel)
            tv?.setBackgroundColor(if (i == tab) selBg else unselBg)
        }
        // 表示切り替え
        meterContainer?.visibility = if (tab == 0) View.VISIBLE else View.GONE
        centsTextView?.visibility = if (tab == 0) View.VISIBLE else View.GONE
        rhythmInfoContainer?.visibility = if (tab == 1) View.VISIBLE else View.GONE

        pitchGraphView?.visibility = if (tab == 0) View.VISIBLE else View.GONE
        rhythmGraphView?.visibility = if (tab == 1) View.VISIBLE else View.GONE
        volumeGraphView?.visibility = if (tab == 2) View.VISIBLE else View.GONE
    }

    // ============================================================
    //  カスタムView: ピッチグラフ
    // ============================================================
    inner class PitchGraphView(ctx: Context) : View(ctx) {
        private var data: List<PitchDetector.PitchResult?> = emptyList()
        private val lnP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#804CAF50"); style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gridP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333333"); strokeWidth = 1f }
        private val gridPC = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#555555"); strokeWidth = 1.5f }
        private val lblP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#999999"); textSize = 20f }
        private val lblPD = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#555555"); textSize = 18f }
        private val bandP = Paint().apply { color = Color.parseColor("#1E1E1E") }
        private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val badgeBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC4CAF50") }
        private val notes = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
        private val whites = setOf(0,2,4,5,7,9,11)

        fun updateData(d: List<PitchDetector.PitchResult?>) { data = d; invalidate() }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#1A1A1A"))
            val valid = data.filterNotNull(); if (valid.isEmpty()) return
            val w = width.toFloat(); val h = height.toFloat()
            val lp = 64f; val rp = 12f; val tp = 12f; val bp = 12f
            val dw = w - lp - rp; val dh = h - tp - bp
            val mn = valid.minOf { it.midiNumber }; val mx = valid.maxOf { it.midiNumber }
            val ctr = (mn + mx) / 2f; val hr = ((mx - mn) / 2f).coerceAtLeast(6f) + 2f
            val minM = (ctr - hr).toInt(); val maxM = (ctr + hr).toInt(); val mr = (maxM - minM).toFloat()
            fun y(midi: Float) = tp + dh * (1f - (midi - minM) / mr)

            for (m in minM..maxM) {
                val ni = ((m % 12) + 12) % 12; val isW = ni in whites; val isC = ni == 0
                val yy = y(m.toFloat())
                if (!isW) c.drawRect(lp, yy - dh / mr / 2, w - rp, yy + dh / mr / 2, bandP)
                c.drawLine(lp, yy, w - rp, yy, if (isC) gridPC else gridP)
                if (isW) c.drawText(if (isC) "C${m / 12 - 1}" else notes[ni], 4f, yy + 5f, if (isC) lblP else lblPD)
            }

            val step = dw / (PITCH_HISTORY_SIZE - 1).toFloat()
            val path = Path(); var started = false
            for (i in data.indices) {
                val r = data[i] ?: continue
                val x = lp + i * step; val yy = y(r.midiNumber + r.centsDiff / 100f)
                val a = (0.3f + 0.7f * i / data.size * 255).toInt().coerceIn(0, 255)
                dotP.color = when { abs(r.centsDiff) < 5f -> Color.parseColor("#4CAF50"); abs(r.centsDiff) < 15f -> Color.parseColor("#FFC107"); else -> Color.parseColor("#FF5722") }
                dotP.alpha = a; c.drawCircle(x, yy, 4f, dotP)
                if (!started) { path.moveTo(x, yy); started = true } else path.lineTo(x, yy)
            }
            if (started) c.drawPath(path, lnP)

            val latest = data.lastOrNull { it != null } ?: return
            val li = data.indexOfLast { it != null }
            val lx = lp + li * step; val ly = y(latest.midiNumber + latest.centsDiff / 100f)
            glowP.color = Color.parseColor("#4CAF50")
            glowP.alpha = 50; c.drawCircle(lx, ly, 20f, glowP)
            glowP.alpha = 120; c.drawCircle(lx, ly, 12f, glowP)
            glowP.alpha = 255; c.drawCircle(lx, ly, 7f, glowP)
            val badge = "${latest.noteName}${latest.octave}"
            val tw = badgeP.measureText(badge)
            val bx = (lx + 12f).coerceAtMost(w - tw - 16f)
            val by = (ly - 16f).coerceAtLeast(tp + 24f)
            c.drawRoundRect(bx - 4f, by - 20f, bx + tw + 4f, by + 4f, 8f, 8f, badgeBgP)
            c.drawText(badge, bx, by, badgeP)
        }
    }

    // ============================================================
    //  カスタムView: リズムグラフ（スペクトラルフラックス版）
    // ============================================================
    inner class RhythmGraphView(ctx: Context) : View(ctx) {
        private var beatResult: BeatDetector.BeatResult? = null

        // ペイント
        private val bgColor = Color.parseColor("#1A1A1A")
        private val bpmBigP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 44f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        private val confBarBgP = Paint().apply { color = Color.parseColor("#333333") }
        private val confBarP = Paint().apply { color = Color.parseColor("#2196F3") }
        private val confTextP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888888"); textSize = 16f; textAlign = Paint.Align.CENTER }
        private val beatDotP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val beatLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        private val gridLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#334CAF50"); strokeWidth = 1.5f }
        private val nowLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f }
        private val fluxBarP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fluxBgP = Paint().apply { color = Color.parseColor("#222222") }
        private val threshLineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80FFFFFF"); strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f) }
        private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888888"); textSize = 16f }
        private val bandLabelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; textAlign = Paint.Align.CENTER }

        private val lowColor = Color.parseColor("#E91E63")   // ピンク（キック）
        private val midColor = Color.parseColor("#FF9800")   // オレンジ（スネア）
        private val highColor = Color.parseColor("#00BCD4")  // シアン（ハイハット）

        fun updateData(b: BeatDetector.BeatResult) { beatResult = b; invalidate() }

        override fun onDraw(c: Canvas) {
            c.drawColor(bgColor)
            val b = beatResult ?: return
            val w = width.toFloat(); val h = height.toFloat()
            val pad = 10f; val dp = resources.displayMetrics.density

            // ====== 上段: BPM + 信頼度 (高さ 22%) ======
            val topH = h * 0.22f
            if (b.bpm > 0) {
                bpmBigP.color = if (b.confidence > 0.5f) Color.parseColor("#2196F3") else Color.parseColor("#666666")
                c.drawText("%.0f BPM".format(b.bpm), w / 2f, topH * 0.55f, bpmBigP)
                // 信頼度バー
                val barW = w * 0.5f; val barH = 5f; val barX = (w - barW) / 2f; val barY = topH * 0.7f
                c.drawRoundRect(barX, barY, barX + barW, barY + barH, 3f, 3f, confBarBgP)
                c.drawRoundRect(barX, barY, barX + barW * b.confidence, barY + barH, 3f, 3f, confBarP)
                confTextP.textSize = 14f
                c.drawText("信頼度 ${(b.confidence * 100).roundToInt()}%", w / 2f, barY + 18f, confTextP)
            } else {
                bpmBigP.color = Color.parseColor("#444444")
                c.drawText("--- BPM", w / 2f, topH * 0.55f, bpmBigP)
            }

            // ====== 中段: スペクトラルフラックスバー (高さ 18%) ======
            val midTop = topH + 4f; val midH = h * 0.18f; val midBot = midTop + midH
            val barCount = 3; val gap = 6f * dp
            val barTotalW = w - 2 * pad - gap * (barCount - 1)
            val singleBarW = barTotalW / barCount

            val maxFlux = maxOf(b.lowFlux, b.midFlux, b.highFlux, b.fluxThreshold, 0.001f) * 1.3f
            val fluxes = listOf(
                Triple(b.lowFlux, lowColor, "LOW"),
                Triple(b.midFlux, midColor, "MID"),
                Triple(b.highFlux, highColor, "HI")
            )
            for ((idx, triple) in fluxes.withIndex()) {
                val (flux, color, label) = triple
                val bx = pad + idx * (singleBarW + gap)
                val fillH = (flux / maxFlux * midH).coerceIn(0f, midH)
                // 背景
                c.drawRoundRect(bx, midTop, bx + singleBarW, midBot, 4f, 4f, fluxBgP)
                // 値
                fluxBarP.color = color; fluxBarP.alpha = 200
                c.drawRoundRect(bx, midBot - fillH, bx + singleBarW, midBot, 4f, 4f, fluxBarP)
                // ラベル
                bandLabelP.color = color
                c.drawText(label, bx + singleBarW / 2f, midTop - 3f, bandLabelP)
            }
            // 閾値ライン
            val threshY = midBot - (b.fluxThreshold / maxFlux * midH).coerceIn(0f, midH)
            c.drawLine(pad, threshY, w - pad, threshY, threshLineP)

            // ====== 下段: ビートタイムライン (残り) ======
            val tlTop = midBot + 8f; val tlBot = h - pad; val tlH = tlBot - tlTop
            if (tlH < 10f) return

            val now = System.currentTimeMillis()
            val windowMs = 6000L

            // 予測グリッド（BPMから等間隔の縦線）
            if (b.bpm > 0 && b.predictedBeatIntervalMs > 0) {
                val interval = b.predictedBeatIntervalMs
                val gridStart = now - windowMs
                // グリッドアンカーから逆算して最初のグリッド位置を求める
                var t = now
                while (t > gridStart) t -= interval
                t += interval
                while (t <= now) {
                    val age = (now - t).toFloat() / windowMs
                    val x = w - pad - (w - 2 * pad) * age
                    if (x >= pad) {
                        gridLineP.alpha = ((1f - age * 0.3f) * 100).toInt().coerceIn(30, 100)
                        c.drawLine(x, tlTop, x, tlBot, gridLineP)
                    }
                    t += interval
                }
            }

            // ビートドット
            val recentBeats = b.beats.filter { now - it.timeMs < windowMs }
            for (beat in recentBeats) {
                val age = (now - beat.timeMs).toFloat() / windowMs
                val x = w - pad - (w - 2 * pad) * age
                if (x < pad) continue

                val baseColor = when (beat.band) {
                    BeatDetector.Band.LOW -> lowColor
                    BeatDetector.Band.MID -> midColor
                    BeatDetector.Band.HIGH -> highColor
                    BeatDetector.Band.COMBINED -> Color.WHITE
                }

                val alpha = ((1f - age * 0.6f) * 255).toInt().coerceIn(40, 255)
                val radius = 5f + beat.strength * 12f

                // 縦線（薄く）
                beatLineP.color = baseColor; beatLineP.alpha = alpha / 3
                c.drawLine(x, tlTop, x, tlBot, beatLineP)

                // 正確度で縦位置を変える（正確=中央、不正確=上下にズレ）
                val yCenter = tlTop + tlH / 2f
                val yOffset = (1f - beat.accuracy) * tlH * 0.35f
                val y = yCenter + if (beat.accuracy < 0.5f) yOffset else -yOffset * 0.2f

                // グロー
                beatDotP.color = baseColor; beatDotP.alpha = alpha / 3
                c.drawCircle(x, y, radius * 1.8f, beatDotP)

                // ドット
                beatDotP.alpha = alpha
                c.drawCircle(x, y, radius, beatDotP)

                // 正確度色リング
                val accColor = when {
                    beat.accuracy >= 0.85f -> Color.parseColor("#4CAF50")
                    beat.accuracy >= 0.6f -> Color.parseColor("#FFC107")
                    else -> Color.parseColor("#FF5722")
                }
                val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accColor; style = Paint.Style.STROKE; strokeWidth = 2f; this.alpha = alpha
                }
                c.drawCircle(x, y, radius + 3f, ringP)
            }

            // Now マーカー
            c.drawLine(w - pad, tlTop, w - pad, tlBot, nowLineP)

            // 帯域凡例（下部右寄せ）
            val legendY = tlBot - 2f
            val legendP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
            var lx = w - pad
            for ((label, color) in listOf("HI" to highColor, "MID" to midColor, "LOW" to lowColor)) {
                legendP.color = color
                val tw = legendP.measureText(label)
                lx -= tw + 8f
                c.drawCircle(lx - 6f, legendY - 4f, 3f, Paint().apply { this.color = color })
                c.drawText(label, lx, legendY, legendP)
                lx -= 12f
            }
        }
    }

    // ============================================================
    //  カスタムView: 音量グラフ
    // ============================================================
    inner class VolumeGraphView(ctx: Context) : View(ctx) {
        private var rmsData: List<Float> = emptyList()
        private var peakData: List<Float> = emptyList()
        private val rmsP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#804CAF50"); style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND }
        private val peakP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#40FF5722"); style = Paint.Style.STROKE; strokeWidth = 1.5f }
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#204CAF50"); style = Paint.Style.FILL }
        private val gridP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A2A"); strokeWidth = 1f }
        private val lblP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#666666"); textSize = 18f }
        private val valP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        private val unitP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888888"); textSize = 18f; textAlign = Paint.Align.CENTER }

        fun updateData(rms: List<Float>, peak: List<Float>) { rmsData = rms; peakData = peak; invalidate() }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#1A1A1A"))
            val w = width.toFloat(); val h = height.toFloat()
            val pad = 12f; val topArea = h * 0.25f

            // 上部: 現在の RMS + Peak 数値
            val curRms = rmsData.lastOrNull() ?: 0f
            val curPeak = peakData.lastOrNull() ?: 0f
            val dbRms = if (curRms > 0) (20 * kotlin.math.log10(curRms.toDouble())).toFloat() else -60f
            val dbPeak = if (curPeak > 0) (20 * kotlin.math.log10(curPeak.toDouble())).toFloat() else -60f

            valP.color = Color.parseColor("#4CAF50")
            c.drawText("%.1f".format(dbRms), w * 0.3f, topArea * 0.6f, valP)
            unitP.color = Color.parseColor("#4CAF50")
            c.drawText("dB RMS", w * 0.3f, topArea * 0.9f, unitP)

            valP.color = Color.parseColor("#FF5722")
            c.drawText("%.1f".format(dbPeak), w * 0.7f, topArea * 0.6f, valP)
            unitP.color = Color.parseColor("#FF5722")
            c.drawText("dB Peak", w * 0.7f, topArea * 0.9f, unitP)

            // 下部: 波形グラフ
            val gTop = topArea + 8f; val gBot = h - pad; val gH = gBot - gTop
            if (rmsData.isEmpty()) return

            // -60dB〜0dB 範囲
            fun dbToY(db: Float): Float {
                val norm = ((db + 60f) / 60f).coerceIn(0f, 1f)
                return gBot - gH * norm
            }

            // グリッド
            for (db in listOf(-48f, -36f, -24f, -12f, 0f)) {
                val y = dbToY(db); c.drawLine(pad, y, w - pad, y, gridP)
                c.drawText("${db.toInt()}", pad, y - 3f, lblP)
            }

            val stepR = (w - 2 * pad) / (VOLUME_HISTORY_SIZE - 1).toFloat()

            // Peak 波形
            if (peakData.size > 1) {
                val pp = Path()
                for (i in peakData.indices) {
                    val x = pad + i * stepR; val db = if (peakData[i] > 0) (20 * kotlin.math.log10(peakData[i].toDouble())).toFloat() else -60f
                    val y = dbToY(db)
                    if (i == 0) pp.moveTo(x, y) else pp.lineTo(x, y)
                }
                c.drawPath(pp, peakP)
            }

            // RMS 波形 + 塗りつぶし
            if (rmsData.size > 1) {
                val rp = Path(); val fp = Path()
                for (i in rmsData.indices) {
                    val x = pad + i * stepR; val db = if (rmsData[i] > 0) (20 * kotlin.math.log10(rmsData[i].toDouble())).toFloat() else -60f
                    val y = dbToY(db)
                    if (i == 0) { rp.moveTo(x, y); fp.moveTo(x, gBot); fp.lineTo(x, y) }
                    else { rp.lineTo(x, y); fp.lineTo(x, y) }
                }
                fp.lineTo(pad + (rmsData.size - 1) * stepR, gBot); fp.close()
                c.drawPath(fp, fillP); c.drawPath(rp, rmsP)
            }
        }
    }

    // ============================================================
    //  フローティングボタン
    // ============================================================
    private fun createFloatingButton() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density; val size = (52 * dp).toInt()
        val btn = TextView(this).apply {
            text = "♪"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4CAF50")) }
            elevation = 8f
        }
        currentButtonBg = btn
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.BOTTOM; x = (16 * dp).toInt(); y = (160 * dp).toInt() }

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var drag = false
        btn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = ev.rawX; ty = ev.rawY; drag = false; true }
                MotionEvent.ACTION_MOVE -> { val dx = ev.rawX - tx; val dy = ev.rawY - ty; if (abs(dx) > 10 || abs(dy) > 10) drag = true; if (drag) { params.x = ix - dx.toInt(); params.y = iy - dy.toInt(); windowManager?.updateViewLayout(btn, params) }; true }
                MotionEvent.ACTION_UP -> { if (!drag) toggleCapture(); true }
                else -> false
            }
        }
        windowManager?.addView(btn, params); floatingButton = btn
    }

    private fun toggleCapture() {
        if (!isCurrentlyListening) {
            isCurrentlyListening = true
            (currentButtonBg as? TextView)?.apply { text = "■"; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF5722")) } }
            startCapture()
        } else {
            isCurrentlyListening = false
            (currentButtonBg as? TextView)?.apply { text = "♪"; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4CAF50")) } }
            stopCapture(); updateStatus("停止中")
        }
    }

    // ============================================================
    //  オーバーレイ構築
    // ============================================================
    private fun createTunerOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (windowManager == null) windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE000000"))
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        }

        // ドラッグハンドル
        val handle = TextView(this).apply { text = "⋮⋮ チューナー ⋮⋮"; textSize = 10f; setTextColor(Color.parseColor("#60FFFFFF")); gravity = Gravity.CENTER }

        // 共通ヘッダー行: [音名] [周波数] [BPM]
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt()) }
        noteTextView = TextView(this).apply { text = "--"; textSize = 28f; setTextColor(Color.parseColor("#666666")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        freqTextView = TextView(this).apply { text = "-- Hz"; textSize = 12f; setTextColor(Color.parseColor("#999999")); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        bpmTextView = TextView(this).apply { text = "-- BPM"; textSize = 14f; setTextColor(Color.parseColor("#666666")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        headerRow.addView(noteTextView); headerRow.addView(freqTextView); headerRow.addView(bpmTextView)

        // タブ行
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
        }
        fun makeTab(label: String, idx: Int): TextView {
            return TextView(this).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { switchTab(idx) }
            }
        }
        tabPitch = makeTab("音程", 0); tabRhythm = makeTab("リズム", 1); tabVolume = makeTab("音量", 2)
        tabRow.addView(tabPitch); tabRow.addView(tabRhythm); tabRow.addView(tabVolume)

        // セントメーター（音程タブ用）
        meterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, (3 * dp).toInt(), 0, (1 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt())
        }
        meterLeftView = View(this).apply { background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 3 * dp }; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.5f) }
        val centerMk = View(this).apply { setBackgroundColor(Color.parseColor("#4CAF50")); layoutParams = LinearLayout.LayoutParams((2 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT) }
        meterRightView = View(this).apply { background = GradientDrawable().apply { setColor(Color.parseColor("#222222")); cornerRadius = 3 * dp }; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.5f) }
        meterContainer!!.addView(meterLeftView); meterContainer!!.addView(centerMk); meterContainer!!.addView(meterRightView)

        centsTextView = TextView(this).apply { text = "±0 ¢"; textSize = 14f; setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER }

        // リズム情報（リズムタブ用）
        rhythmInfoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE
        }
        accuracyTextView = TextView(this).apply { text = "正確度: --%"; textSize = 14f; setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER }
        rhythmInfoContainer!!.addView(accuracyTextView)

        // グラフコンテナ
        graphContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (140 * dp).toInt())
        }
        pitchGraphView = PitchGraphView(this)
        rhythmGraphView = RhythmGraphView(this).apply { visibility = View.GONE }
        volumeGraphView = VolumeGraphView(this).apply { visibility = View.GONE }
        graphContainer!!.addView(pitchGraphView); graphContainer!!.addView(rhythmGraphView); graphContainer!!.addView(volumeGraphView)

        statusTextView = TextView(this).apply { text = "「♪」ボタンで開始"; textSize = 10f; setTextColor(Color.parseColor("#80FFFFFF")); gravity = Gravity.CENTER }

        container.addView(handle); container.addView(headerRow); container.addView(tabRow)
        container.addView(meterContainer); container.addView(centsTextView)
        container.addView(rhythmInfoContainer); container.addView(graphContainer); container.addView(statusTextView)

        val params = WindowManager.LayoutParams((280 * dp).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (40 * dp).toInt(); y = (80 * dp).toInt() }

        var stx = 0f; var sty = 0f; var spx = 0; var spy = 0; var drg = false
        container.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { stx = ev.rawX; sty = ev.rawY; spx = params.x; spy = params.y; drg = false; true }
                MotionEvent.ACTION_MOVE -> { val dx = ev.rawX - stx; val dy = ev.rawY - sty; if (abs(dx) > 8 || abs(dy) > 8) drg = true; if (drg) { params.x = spx + dx.toInt(); params.y = spy + dy.toInt(); try { windowManager?.updateViewLayout(container, params) } catch (_: Exception) {} }; true }
                MotionEvent.ACTION_UP -> { drg = false; true }
                else -> false
            }
        }
        windowManager?.addView(container, params); overlayView = container
        switchTab(0)  // 初期タブ
    }

    private fun updateStatus(t: String) { mainHandler.post { statusTextView?.text = t } }

    // ============================================================
    //  通知 & クリーンアップ
    // ============================================================
    private fun startForegroundNotification() {
        val ch = NotificationChannel(CHANNEL_ID, "Tuner", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, CHANNEL_ID).setContentTitle("NEXUS チューナー").setContentText("解析中...").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        try { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) } catch (e: SecurityException) { stopSelf() }
    }

    private fun stopEverything() {
        isCurrentlyListening = false; stopCapture()
        mediaProjection?.stop(); mediaProjection = null
        try { overlayView?.let { windowManager?.removeView(it) }; floatingButton?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null; floatingButton = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { super.onDestroy(); stopEverything() }
}
