package com.nexus.vision.audio

import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TunerViewModel(application: Application) : AndroidViewModel(application) {

    private val captureManager = AudioCaptureManager(application)

    private val _pitchResult = MutableStateFlow<PitchDetector.PitchResult?>(null)
    val pitchResult: StateFlow<PitchDetector.PitchResult?> = _pitchResult.asStateFlow()

    private val _pitchHistory = MutableStateFlow<List<PitchDetector.PitchResult?>>(emptyList())
    val pitchHistory: StateFlow<List<PitchDetector.PitchResult?>> = _pitchHistory.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _captureMode = MutableStateFlow(AudioCaptureManager.CaptureMode.MIC)
    val captureMode: StateFlow<AudioCaptureManager.CaptureMode> = _captureMode.asStateFlow()

    private var captureJob: Job? = null
    private var collectJob: Job? = null

    fun setCaptureMode(mode: AudioCaptureManager.CaptureMode) {
        if (_isActive.value) stopTuner()
        _captureMode.value = mode
    }

    /**
     * マイクモードで開始
     */
    fun startMicTuner() {
        stopTuner()
        _captureMode.value = AudioCaptureManager.CaptureMode.MIC
        collectJob = viewModelScope.launch {
            launch { captureManager.pitchResult.collect { _pitchResult.value = it } }
            launch { captureManager.pitchHistory.collect { _pitchHistory.value = it } }
        }
        captureJob = viewModelScope.launch {
            _isActive.value = true
            captureManager.startMicCapture()
        }
    }

    /**
     * システム音声モードで開始（MediaProjection 必要）
     */
    fun startSystemTuner(mediaProjection: MediaProjection) {
        stopTuner()
        _captureMode.value = AudioCaptureManager.CaptureMode.SYSTEM
        collectJob = viewModelScope.launch {
            launch { captureManager.pitchResult.collect { _pitchResult.value = it } }
            launch { captureManager.pitchHistory.collect { _pitchHistory.value = it } }
        }
        captureJob = viewModelScope.launch {
            _isActive.value = true
            captureManager.startSystemCapture(mediaProjection)
        }
    }

    fun stopTuner() {
        captureJob?.cancel()
        captureJob = null
        collectJob?.cancel()
        collectJob = null
        _isActive.value = false
        _pitchResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopTuner()
    }
}
