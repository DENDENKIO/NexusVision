package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineSenseVoiceModelConfig(
    var model: String = "",
    var language: String = "auto",
    var useItn: Boolean = false,
)

data class OfflineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
)

data class OfflineParaformerModelConfig(
    var model: String = "",
)

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "",
    var task: String = "transcribe",
    var tailPaddings: Int = -1,
)

data class OfflineModelConfig(
    var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    var paraformer: OfflineParaformerModelConfig = OfflineParaformerModelConfig(),
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

class OfflineStream(var ptr: Long = 0) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String,
    val emotion: String,
    val event: String,
)

class OfflineRecognizer(
    assetManager: AssetManager? = null,
    val config: OfflineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(): OfflineStream {
        val p = createStream(ptr)
        return OfflineStream(p)
    }

    fun decode(stream: OfflineStream) = decode(ptr, stream.ptr)

    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        return getResult(ptr, stream.ptr)
    }

    private external fun delete(ptr: Long)
    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineRecognizerConfig,
    ): Long
    private external fun newFromFile(config: OfflineRecognizerConfig): Long
    private external fun createStream(ptr: Long): Long
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): OfflineRecognizerResult

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
