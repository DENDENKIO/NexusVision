package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5f,
    var minSilenceDuration: Float = 0.5f,
    var minSpeechDuration: Float = 0.25f,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 30.0f,
)

data class VadModelConfig(
    var sileroVad: SileroVadModelConfig = SileroVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)

class SpeechSegment(val start: Int, val samples: FloatArray)

class VoiceActivityDetector(
    assetManager: AssetManager? = null,
    val config: VadModelConfig,
    var bufferSizeInSeconds: Float = 60.0f,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config, bufferSizeInSeconds)
        } else {
            newFromFile(config, bufferSizeInSeconds)
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun acceptWaveform(samples: FloatArray) = acceptWaveform(ptr, samples)
    fun empty(): Boolean = empty(ptr)
    fun pop(): SpeechSegment = front(ptr)
    fun isSpeechDetected(): Boolean = isSpeechDetected(ptr)
    fun reset() = reset(ptr)
    fun flush() = flush(ptr)

    private external fun delete(ptr: Long)
    private external fun newFromAsset(
        assetManager: AssetManager,
        config: VadModelConfig,
        bufferSizeInSeconds: Float,
    ): Long
    private external fun newFromFile(
        config: VadModelConfig,
        bufferSizeInSeconds: Float,
    ): Long
    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun empty(ptr: Long): Boolean
    private external fun front(ptr: Long): SpeechSegment
    private external fun isSpeechDetected(ptr: Long): Boolean
    private external fun reset(ptr: Long)
    private external fun flush(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
