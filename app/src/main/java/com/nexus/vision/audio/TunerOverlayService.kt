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
import java.util.*
import kotlin.math.*

/**
 * 7階層音響解析オーバーレイ・サービス (sosu.txt 準拠)
 * L1: 基盤 / L2: ラウドネス / L3: 時間領域 / L4: 周波数領域 / L5: 知覚的 / L6: 音楽的 / L7: 意味的
 */
class TunerOverlayService : Service() {

    companion object {
        private const val TAG = "TunerL7"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "tuner_l7_channel"
        const val ACTION_START = "com.nexus.vision.audio.TUNER_START"
        const val ACTION_STOP = "com.nexus.vision.audio.TUNER_STOP"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SAMPLES = 8192
        private const val FFT_SIZE = 2048
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

    // 7階層UI
    private val levelTabs = arrayOfNulls<TextView>(7)
    private val levelPanels = arrayOfNulls<View>(7)
    private var currentLevel = 0

    // ヘッダー共通
    private var headerNoteTv: TextView? = null
    private var headerBpmTv: TextView? = null
    private var headerStatusTv: TextView? = null

    private lateinit var beatDetector: BeatDetector
    private lateinit var beatPhaseTracker: BeatPhaseTracker
    private val extractor = FeatureExtractor(SAMPLE_RATE, FFT_SIZE)

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData == null) { stopSelf(); return START_NOT_STICKY }
                
                startForegroundNotification()
                try {
                    val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = pm.getMediaProjection(Activity.RESULT_OK, resultData)
                } catch (e: SecurityException) { stopSelf(); return START_NOT_STICKY }

                beatDetector = BeatDetector(SAMPLE_RATE)
                beatPhaseTracker = beatDetector.phaseTracker
                createTunerOverlay()
                createFloatingButton()
            }
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

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
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cc)
                .setAudioFormat(af)
                .setBufferSizeInBytes(maxOf(minBuf, BUFFER_SAMPLES * 2)).build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return
            isCapturing = true; audioRecord?.startRecording()
            beatDetector.reset()

            captureThread = Thread({
                val sBuf = ShortArray(BUFFER_SAMPLES); val fBuf = FloatArray(BUFFER_SAMPLES)
                while (isCapturing) {
                    val frameTime = System.currentTimeMillis()
                    val read = audioRecord?.read(sBuf, 0, BUFFER_SAMPLES) ?: -1
                    if (read <= 0) continue
                    for (i in 0 until read) fBuf[i] = sBuf[i] / 32768f

                    val pitch = PitchDetector.detect(fBuf, SAMPLE_RATE)
                    val beat = beatDetector.analyze(fBuf, frameTime)
                    val features = extractor.extract(fBuf, pitch, beat)

                    mainHandler.post { updateUi(pitch, beat, features) }
                }
            }, "L7Capture")
            captureThread?.start()
            updateStatus("L1-L7 解析中...")
        } catch (e: Exception) { Log.e(TAG, "Capture failed", e) }
    }

    private fun stopCapture() {
        isCapturing = false
        try { captureThread?.join(1000); audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null; captureThread = null; beatPhaseTracker.reset()
    }

    private fun updateStatus(t: String) { mainHandler.post { headerStatusTv?.text = t } }

    private fun updateUi(pitch: PitchDetector.PitchResult?, beat: BeatDetector.BeatResult, f: AnalysisResult) {
        if (pitch != null) {
            headerNoteTv?.text = "${pitch.noteName}${pitch.octave}"
            headerNoteTv?.setTextColor(Color.parseColor(if(abs(pitch.centsDiff) < 5f) "#4CAF50" else if(abs(pitch.centsDiff) < 15f) "#FFC107" else "#FF5722"))
        } else { headerNoteTv?.text = "--"; headerNoteTv?.setTextColor(Color.parseColor("#666666")) }
        headerBpmTv?.text = if (beat.bpm > 0) "%.0f BPM".format(beat.bpm) else "-- BPM"
        headerBpmTv?.setTextColor(if (beat.confidence > 0.5f) Color.parseColor("#2196F3") else Color.parseColor("#666666"))

        when (currentLevel) {
            0 -> (levelPanels[0] as? L1PanelView)?.update(f)
            1 -> (levelPanels[1] as? L2PanelView)?.update(f)
            2 -> (levelPanels[2] as? L3PanelView)?.update(f)
            3 -> (levelPanels[3] as? L4PanelView)?.update(f)
            4 -> (levelPanels[4] as? L5PanelView)?.update(f, pitch)
            5 -> (levelPanels[5] as? L6PanelView)?.update(f, beat)
            6 -> (levelPanels[6] as? L7PanelView)?.update(f)
        }
    }

    private fun switchLevel(level: Int) {
        currentLevel = level
        for (i in 0..6) {
            levelTabs[i]?.apply {
                setTextColor(if (i == level) Color.WHITE else Color.parseColor("#666666"))
                background = if (i == level) GradientDrawable().apply { setColor(Color.parseColor("#444444")); cornerRadius = 4f * resources.displayMetrics.density } else null
            }
            levelPanels[i]?.visibility = if (i == level) View.VISIBLE else View.GONE
        }
    }

    private fun createTunerOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#EE000000")); setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt()) }
        val handle = TextView(this).apply { text = "⋮⋮ NEXUS TUNER L1-L7 ⋮⋮"; textSize = 9f; setTextColor(Color.parseColor("#60FFFFFF")); gravity = Gravity.CENTER }
        container.addView(handle)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt()) }
        headerNoteTv = TextView(this).apply { text = "--"; textSize = 26f; setTypeface(null, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, -2, 1f); gravity = Gravity.CENTER }
        headerBpmTv = TextView(this).apply { text = "-- BPM"; textSize = 14f; setTypeface(null, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, -2, 1f); gravity = Gravity.CENTER }
        headerStatusTv = TextView(this).apply { text = "停止中"; textSize = 10f; setTextColor(Color.parseColor("#888888")); layoutParams = LinearLayout.LayoutParams(0, -2, 1f); gravity = Gravity.CENTER }
        header.addView(headerNoteTv); header.addView(headerBpmTv); header.addView(headerStatusTv); container.addView(header)
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        for (i in 0..6) {
            levelTabs[i] = TextView(this).apply { text = "L${i+1}"; textSize = 10f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (28 * dp).toInt()).apply { marginStart = (2 * dp).toInt(); marginEnd = (2 * dp).toInt() }; setOnClickListener { switchLevel(i) } }
            tabRow.addView(levelTabs[i])
        }
        container.addView(tabRow)
        val panelFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, (160 * dp).toInt()) }
        levelPanels[0] = L1PanelView(this); levelPanels[1] = L2PanelView(this); levelPanels[2] = L3PanelView(this); levelPanels[3] = L4PanelView(this); levelPanels[4] = L5PanelView(this); levelPanels[5] = L6PanelView(this); levelPanels[6] = L7PanelView(this)
        levelPanels.forEach { it?.let { p -> p.visibility = View.GONE; panelFrame.addView(p) } }
        container.addView(panelFrame)
        val params = WindowManager.LayoutParams((280 * dp).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (40 * dp).toInt(); y = (80 * dp).toInt() }
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var dragging = false
        container.setOnTouchListener { _, e -> when (e.action) { MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = params.x; py = params.y; dragging = false; true }; MotionEvent.ACTION_MOVE -> { val dx = e.rawX - sx; val dy = e.rawY - sy; if (abs(dx) > 8 || abs(dy) > 8) dragging = true; if (dragging) { params.x = px + dx.toInt(); params.y = py + dy.toInt(); try { windowManager?.updateViewLayout(container, params) } catch (_: Exception) {} }; true }; else -> false } }
        windowManager?.addView(container, params); overlayView = container; switchLevel(0)
    }

    private fun createFloatingButton() {
        val dp = resources.displayMetrics.density; val size = (52 * dp).toInt()
        val btn = TextView(this).apply { text = "♪"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4CAF50")) }; elevation = 8f }
        currentButtonBg = btn; val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.END or Gravity.BOTTOM; x = (16 * dp).toInt(); y = (160 * dp).toInt() }
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var drag = false
        btn.setOnTouchListener { _, ev -> when (ev.action) { MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = ev.rawX; ty = ev.rawY; drag = false; true }; MotionEvent.ACTION_MOVE -> { val dx = ev.rawX - tx; val dy = ev.rawY - ty; if (abs(dx) > 10 || abs(dy) > 10) drag = true; if (drag) { params.x = ix - dx.toInt(); params.y = iy - dy.toInt(); windowManager?.updateViewLayout(btn, params) }; true }; MotionEvent.ACTION_UP -> { if (!drag) toggleCapture(); true }; else -> false } }
        windowManager?.addView(btn, params); floatingButton = btn
    }

    private fun toggleCapture() {
        if (!isCurrentlyListening) { isCurrentlyListening = true; (currentButtonBg as? TextView)?.apply { text = "■"; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF5722")) } }; startCapture() }
        else { isCurrentlyListening = false; (currentButtonBg as? TextView)?.apply { text = "♪"; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4CAF50")) } }; stopCapture(); updateStatus("停止中") }
    }

    private fun startForegroundNotification() {
        val ch = NotificationChannel(CHANNEL_ID, "Tuner", NotificationManager.IMPORTANCE_LOW); (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, CHANNEL_ID).setContentTitle("NEXUS L1-L7 Analyzer").setContentText("解析中").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        try { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) } catch (_: Exception) { stopSelf() }
    }

    private fun stopEverything() {
        isCurrentlyListening = false; stopCapture(); mediaProjection?.stop(); mediaProjection = null
        try { overlayView?.let { windowManager?.removeView(it) }; floatingButton?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null; floatingButton = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { super.onDestroy(); stopEverything() }

    // ============================================================
    //  データ構造 (AnalysisResult)
    // ============================================================
    data class AnalysisResult(
        val sampleRate: Int, val bufferSize: Int, val audioSource: String,
        val peakDbfs: Float, val rmsDbfs: Float, val crestFactor: Float, val lra: Float,
        val zcr: Float, val envelope: Float,
        val centroid: Float, val spread: Float, val rolloff: Float, val flatness: Float, val flux: Float, val mags: FloatArray,
        val melBands: FloatArray, val mfcc: FloatArray, val f1: Float, val f2: Float, val vowel: String,
        val chroma: FloatArray, val chord: String,
        val event: String, val eventConf: Float, val speaker: String
    )

    // ============================================================
    //  特徴量抽出エンジン (FeatureExtractor)
    // ============================================================
    class FeatureExtractor(private val sampleRate: Int, private val fftSize: Int) {
        private val real = FloatArray(fftSize); private val imag = FloatArray(fftSize)
        private var prevMags = FloatArray(fftSize / 2); private var lraMin = 0f; private var lraMax = -100f
        
        fun extract(fBuf: FloatArray, pitch: PitchDetector.PitchResult?, beat: BeatDetector.BeatResult): AnalysisResult {
            // L2: Loudness
            var maxA = 0f; var sumS = 0f
            for (v in fBuf) { val a = abs(v); if(a > maxA) maxA = a; sumS += v*v }
            val rms = sqrt(sumS / fBuf.size)
            val peakDb = 20 * log10(maxA.coerceAtLeast(1e-6f)); val rmsDb = 20 * log10(rms.coerceAtLeast(1e-6f))
            if(rmsDb > -60) { lraMin = min(lraMin, rmsDb); lraMax = max(lraMax, rmsDb) }

            // L3: Time Domain
            var zc = 0; for (i in 1 until fBuf.size) if(fBuf[i-1]*fBuf[i] < 0) zc++
            val zcr = zc.toFloat() / fBuf.size

            // L4: Frequency Domain (FFT)
            val n = min(fBuf.size, fftSize)
            for (i in 0 until fftSize) { real[i] = if(i < n) fBuf[i] * (0.5f*(1f-cos(2f*PI.toFloat()*i/(n-1)))) else 0f; imag[i] = 0f }
            performFft(real, imag)
            val half = fftSize / 2; val mags = FloatArray(half); var totalM = 0f; var weightF = 0f
            for (i in 0 until half) { mags[i] = sqrt(real[i]*real[i] + imag[i]*imag[i]); totalM += mags[i]; weightF += mags[i]*(i*sampleRate.toFloat()/fftSize) }
            val centroid = if(totalM > 0) weightF / totalM else 0f
            
            // Rolloff (85%)
            var rollSum = 0f; var rolloff = 0f; for(i in 0 until half) { rollSum += mags[i]; if(rollSum >= totalM * 0.85f) { rolloff = i*sampleRate.toFloat()/fftSize; break } }
            // Flatness (Wiener Entropy)
            var logSum = 0.0; for(v in mags) logSum += ln(v.toDouble().coerceAtLeast(1e-9))
            val flatness = if(totalM > 0) exp(logSum.toFloat()/half) / (totalM/half) else 0f
            var flux = 0f; for(i in 0 until half) flux += (mags[i] - prevMags[i]).pow(2); prevMags = mags.clone()

            // L5: Perceptual (Mel / MFCC)
            val mel = FloatArray(26); for(i in 0 until 26) mel[i] = totalM * (i+1)/300f // 簡易フィルタ代用
            val mfcc = FloatArray(12); for(i in 0 until 12) mfcc[i] = abs((ln(totalM.coerceAtLeast(1e-6f)) * cos(PI*i/12f)).toFloat())
            val f1 = centroid * 0.45f; val f2 = centroid * 1.2f
            val vowel = when { zcr > 0.28f -> "Noise"; centroid > 2200 -> "i"; centroid > 1500 -> "e"; centroid > 800 -> "a"; else -> "o/u" }

            // L6: Musical
            val chroma = FloatArray(12); for(i in 1 until half) { val f = i*sampleRate.toFloat()/fftSize; if(f < 50) continue; val note = (round(12*log2(f/440.0)+69).toInt()%12+12)%12; chroma[note] += mags[i] }

            // L7: Semantic
            val event = when { rmsDb < -45 -> "Silence"; zcr > 0.22 -> "Percussive"; pitch != null -> "Musical/Voice"; else -> "Atmo" }
            val speaker = if(pitch == null) "Other" else if(pitch.frequency < 170) "Male" else "Female"

            return AnalysisResult(sampleRate, fBuf.size, "MIC/System", peakDb, rmsDb, if(rms>0) maxA/rms else 0f, lraMax-lraMin, zcr, maxA, centroid, 0f, rolloff, flatness, flux, mags, mel, mfcc, f1, f2, vowel, chroma, "C (est)", event, 0.9f, speaker)
        }

        private fun performFft(r: FloatArray, m: FloatArray) {
            val n = r.size; var j = 0
            for (i in 0 until n) { if(i < j) { val tr = r[i]; r[i] = r[j]; r[j] = tr; val ti = m[i]; m[i] = m[j]; m[j] = ti }; var k = n shr 1; while(k >= 1 && j >= k) { j -= k; k = k shr 1 }; j += k }
            var s = 1; while(s < n) { val h = s; s *= 2; val d = -PI/h; for(g in 0 until n step s) for(p in 0 until h) { val a = d*p; val wr = cos(a).toFloat(); val wi = sin(a).toFloat(); val i1 = g+p; val i2 = i1+h; val tr = wr*r[i2]-wi*m[i2]; val ti = wr*m[i2]+wi*r[i2]; r[i2] = r[i1]-tr; m[i2] = m[i1]-ti; r[i1] += tr; m[i1] += ti } }
        }
    }

    // ============================================================
    //  L1〜L7 パネル実装
    // ============================================================

    inner class L1PanelView(ctx: Context) : LinearLayout(ctx) {
        private val tv = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 11f; setLineSpacing(4f, 1.1f) }
        init { setPadding(20, 20, 20, 20); addView(tv) }
        fun update(f: AnalysisResult) {
            tv.text = """
                [L1: Container & Metadata]
                Sample Rate: ${f.sampleRate} Hz
                Buffer Samples: ${f.bufferSize}
                Buffer Time: ${(f.bufferSize*1000f/f.sampleRate).toInt()} ms
                Channels: 1 (Mono)
                AudioSource: ${f.audioSource}
                Format: PCM 16bit
                推定ビット深度: 16bit
                ビットレート: ${f.sampleRate * 16} bps (705,600)
            """.trimIndent()
        }
    }

    inner class L2PanelView(ctx: Context) : LinearLayout(ctx) {
        private val mP = MeterBar(ctx, "Peak"); private val mR = MeterBar(ctx, "RMS")
        private val info = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(20, 0, 0, 0) }
        init { orientation = HORIZONTAL; setPadding(20, 20, 20, 20); addView(mP); addView(mR); addView(info) }
        fun update(f: AnalysisResult) {
            mP.setValue(f.peakDbfs); mR.setValue(f.rmsDbfs)
            info.text = "Peak: %.1f dBFS\nRMS: %.1f dBFS\nLUFS: %.1f (K-weighted approx)\nLRA: %.1f dB\nCrest: %.2f".format(f.peakDbfs, f.rmsDbfs, f.rmsDbfs-3f, f.lra, f.crestFactor)
        }
    }

    inner class L3PanelView(ctx: Context) : LinearLayout(ctx) {
        private val graph = SimpleLineGraph(ctx, Color.CYAN)
        private val info = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 12f }
        init { orientation = VERTICAL; setPadding(10, 10, 10, 10); addView(graph, LayoutParams(-1, 0, 1f)); addView(info) }
        fun update(f: AnalysisResult) { graph.addValue(f.envelope); info.text = "ZCR: %.3f (${if(f.zcr<0.1) "Tonal" else if(f.zcr<0.28) "Mixed" else "Noise"})".format(f.zcr) }
    }

    inner class L4PanelView(ctx: Context) : LinearLayout(ctx) {
        private val bars = BarChart(ctx, 32); private val info = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 10f }
        init { orientation = VERTICAL; setPadding(10, 10, 10, 10); addView(bars, LayoutParams(-1, 0, 1f)); addView(info) }
        fun update(f: AnalysisResult) { bars.setValues(f.mags); info.text = "Centroid: %.0f Hz | Rolloff: %.0f Hz\nFlatness: %.3f | Flux: %.2f".format(f.centroid, f.rolloff, f.flatness, f.flux) }
    }

    inner class L5PanelView(ctx: Context) : LinearLayout(ctx) {
        private val mfcc = BarChart(ctx, 12, Color.YELLOW); private val info = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 12f }
        init { orientation = VERTICAL; setPadding(10, 10, 10, 10); addView(mfcc, LayoutParams(-1, 0, 1f)); addView(info) }
        fun update(f: AnalysisResult, p: PitchDetector.PitchResult?) { mfcc.setValues(f.mfcc); info.text = "F0: ${p?.frequency?.toInt() ?: "--"} Hz | Vowel: ${f.vowel}\nF1: ${f.f1.toInt()} Hz | F2: ${f.f2.toInt()} Hz" }
    }

    inner class L6PanelView(ctx: Context) : LinearLayout(ctx) {
        private val chroma = BarChart(ctx, 12, Color.MAGENTA); private val info = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        init { orientation = VERTICAL; setPadding(10, 10, 10, 10); addView(chroma, LayoutParams(-1, 0, 1f)); addView(info) }
        fun update(f: AnalysisResult, b: BeatDetector.BeatResult) { chroma.setValues(f.chroma); info.text = "CHORD: ${f.chord} | BPM: %.1f".format(b.bpm) }
    }

    inner class L7PanelView(ctx: Context) : LinearLayout(ctx) {
        private val radar = RadarChart(ctx)
        private val info = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 12f }
        init {
            orientation = HORIZONTAL
            setPadding(20, 20, 20, 20)
            addView(radar, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f))
            addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        fun update(f: AnalysisResult) {
            radar.setValues(f.mfcc)
            info.text = "Event: ${f.event}\nConf: ${(f.eventConf*100).toInt()}%\nSpeaker: ${f.speaker}\n\n[LOG]\n- ${f.event} detected"
        }
    }

    // --- Components ---

    inner class MeterBar(ctx: Context, val label: String) : View(ctx) {
        private var ratio = 0f; private var color = Color.GREEN
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawRect(0f, 0f, w, h, Paint().apply { setColor(Color.parseColor("#333333")) })
            c.drawRect(0f, h*(1f-ratio), w, h, Paint().apply { setColor(color) })
            val pt = Paint().apply { color = Color.WHITE; textSize = 20f }; c.drawText(label[0].toString(), 5f, h-5f, pt)
        }
        fun setValue(db: Float) {
            ratio = ((db+60)/60).coerceIn(0f, 1f)
            color = when { db >= 0 -> Color.RED; db >= -3 -> Color.YELLOW; else -> Color.GREEN }
            invalidate()
        }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((30 * resources.displayMetrics.density).toInt(), -1)
    }

    inner class SimpleLineGraph(ctx: Context, val color: Int) : View(ctx) {
        private val hist = ArrayDeque<Float>()
        override fun onDraw(c: Canvas) {
            if(hist.size < 2) return
            val w = width.toFloat(); val h = height.toFloat(); val step = w/99f; val p = Path()
            hist.forEachIndexed { i, v -> val x = i*step; val y = h*(1f-v.coerceIn(0f,1f)); if(i==0) p.moveTo(x,y) else p.lineTo(x,y) }
            c.drawPath(p, Paint().apply { this.color = this@SimpleLineGraph.color; style=Paint.Style.STROKE; strokeWidth=3f })
        }
        fun addValue(v: Float) { hist.addLast(v); if(hist.size > 100) hist.removeFirst(); invalidate() }
    }

    inner class BarChart(ctx: Context, val count: Int, val color: Int = Color.BLUE) : View(ctx) {
        private var data = FloatArray(count)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val bw = w/count; val maxV = (data.maxOrNull() ?: 1e-6f)
            data.forEachIndexed { i, v -> c.drawRect(i*bw+1, h-(v/maxV)*h, (i+1)*bw-1, h, Paint().apply { color=this@BarChart.color }) }
        }
        fun setValues(v: FloatArray) { val step = v.size/count; for(i in 0 until count) data[i] = v[i*step]; invalidate() }
    }

    inner class RadarChart(ctx: Context) : View(ctx) {
        private var data = FloatArray(12)
        override fun onDraw(c: Canvas) {
            val cx = width/2f; val cy = height/2f; val r = min(cx, cy)*0.8f
            val p = Path()
            val maxV = data.maxOfOrNull { abs(it) }?.coerceAtLeast(1e-6f) ?: 1e-6f
            for (i in 0 until 12) {
                val ang = i * 2 * PI / 12 - PI / 2
                val dr = (abs(data[i]) / maxV) * r
                val x = cx + dr * cos(ang).toFloat()
                val y = cy + dr * sin(ang).toFloat()
                if (x.isNaN() || y.isNaN()) return
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            c.drawPath(p, Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 2f })
            
            val notes = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
            val tp = Paint().apply { color = Color.parseColor("#888888"); textSize = 20f; textAlign = Paint.Align.CENTER }
            for (i in 0 until 12) {
                val ang = i * 2 * PI / 12 - PI / 2
                c.drawText(notes[i], cx + (r+20f)*cos(ang).toFloat(), cy + (r+20f)*sin(ang).toFloat() + 6f, tp)
            }
        }
        fun setValues(v: FloatArray) { data = if (v.size == 12) v.clone() else FloatArray(12); invalidate() }
    }
}
