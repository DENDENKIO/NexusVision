package com.nexus.vision.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 2モード対応の音声キャプチャ
 *  - MIC モード: マイクから取得（楽器チューニング用）
 *  - SYSTEM モード: AudioPlaybackCapture で他アプリの音声を取得（YouTube等）
 */
class AudioCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 44100
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SAMPLES = 8192

        // 波形履歴: 直近 N 件の検出結果を保持
        const val HISTORY_SIZE = 80
    }

    enum class CaptureMode { MIC, SYSTEM }

    private var audioRecord: AudioRecord? = null

    private val _pitchResult = MutableStateFlow<PitchDetector.PitchResult?>(null)
    val pitchResult: Flow<PitchDetector.PitchResult?> = _pitchResult.asStateFlow()

    // 波形履歴（音名 + セント差 + 周波数を保持）
    private val _pitchHistory = MutableStateFlow<List<PitchDetector.PitchResult?>>(emptyList())
    val pitchHistory: Flow<List<PitchDetector.PitchResult?>> = _pitchHistory.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: Flow<Boolean> = _isRecording.asStateFlow()

    private val historyBuffer = ArrayDeque<PitchDetector.PitchResult?>(HISTORY_SIZE)

    /**
     * マイクモードで開始
     */
    suspend fun startMicCapture() = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return@withContext
        }

        val bufferBytes = BUFFER_SAMPLES * 2
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val actualBuffer = maxOf(bufferBytes, minBuffer)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            actualBuffer
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord(MIC) init failed")
                return@withContext
            }
        }

        Log.i(TAG, "MIC capture started: ${SAMPLE_RATE}Hz")
        runCaptureLoop()
    }

    /**
     * システム音声モードで開始
     * @param mediaProjection  MediaProjection トークン（Activity から取得）
     */
    suspend fun startSystemCapture(mediaProjection: MediaProjection) = withContext(Dispatchers.IO) {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL)
            .build()

        val bufferBytes = BUFFER_SAMPLES * 2
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(bufferBytes, 4096))
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord(SYSTEM) init failed")
            audioRecord?.release()
            audioRecord = null
            return@withContext
        }

        Log.i(TAG, "SYSTEM audio capture started: ${SAMPLE_RATE}Hz")
        runCaptureLoop()
    }

    private suspend fun runCaptureLoop() = withContext(Dispatchers.IO) {
        val shortBuffer = ShortArray(BUFFER_SAMPLES)
        val floatBuffer = FloatArray(BUFFER_SAMPLES)

        audioRecord!!.startRecording()
        _isRecording.value = true
        historyBuffer.clear()

        try {
            while (isActive) {
                val read = audioRecord!!.read(shortBuffer, 0, BUFFER_SAMPLES)
                if (read <= 0) continue

                for (i in 0 until read) {
                    floatBuffer[i] = shortBuffer[i] / 32768.0f
                }

                val result = PitchDetector.detect(floatBuffer, SAMPLE_RATE)
                _pitchResult.value = result

                // 履歴に追加（null も追加 → 波形に「無音」として反映）
                synchronized(historyBuffer) {
                    if (historyBuffer.size >= HISTORY_SIZE) {
                        historyBuffer.removeFirst()
                    }
                    historyBuffer.addLast(result)
                    _pitchHistory.value = historyBuffer.toList()
                }
            }
        } finally {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            _isRecording.value = false
            Log.i(TAG, "Capture stopped")
        }
    }
}
