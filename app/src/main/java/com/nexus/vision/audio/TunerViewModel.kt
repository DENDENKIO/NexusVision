package com.nexus.vision.audio

import android.app.Application
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

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var captureJob: Job? = null

    fun toggleTuner() {
        if (captureJob?.isActive == true) {
            stopTuner()
        } else {
            startTuner()
        }
    }

    private fun startTuner() {
        captureJob = viewModelScope.launch {
            _isActive.value = true
            captureManager.pitchResult.collect { result ->
                _pitchResult.value = result
            }
        }
        viewModelScope.launch {
            captureManager.startCapture()
        }
    }

    private fun stopTuner() {
        captureJob?.cancel()
        captureJob = null
        _isActive.value = false
        _pitchResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopTuner()
    }
}
