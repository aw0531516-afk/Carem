package com.example.service

import com.example.model.AutomationPhase
import com.example.model.AutomationTuning
import com.example.model.BeforeAfterComparison
import com.example.model.BoardCalibration
import com.example.model.BoardDetectionResult
import com.example.model.ShotPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AutomationBridge {
    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isOverlayShowing = MutableStateFlow(false)
    val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

    private val _isMediaProjectionActive = MutableStateFlow(false)
    val isMediaProjectionActive: StateFlow<Boolean> = _isMediaProjectionActive.asStateFlow()

    private val _isAutoPlaying = MutableStateFlow(false)
    val isAutoPlaying: StateFlow<Boolean> = _isAutoPlaying.asStateFlow()

    private val _isCalibrationMode = MutableStateFlow(false)
    val isCalibrationMode: StateFlow<Boolean> = _isCalibrationMode.asStateFlow()

    private val _phase = MutableStateFlow(AutomationPhase.IDLE)
    val phase: StateFlow<AutomationPhase> = _phase.asStateFlow()

    private val _statusBanner = MutableStateFlow("Ready — grant permissions & start overlay")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _alertBanner = MutableStateFlow<String?>(null)
    val alertBanner: StateFlow<String?> = _alertBanner.asStateFlow()

    private val _latestDetection = MutableStateFlow<BoardDetectionResult?>(null)
    val latestDetection: StateFlow<BoardDetectionResult?> = _latestDetection.asStateFlow()

    private val _latestShotPlan = MutableStateFlow<ShotPlan?>(null)
    val latestShotPlan: StateFlow<ShotPlan?> = _latestShotPlan.asStateFlow()

    private val _latestComparison = MutableStateFlow<BeforeAfterComparison?>(null)
    val latestComparison: StateFlow<BeforeAfterComparison?> = _latestComparison.asStateFlow()

    private val _activeCalibration = MutableStateFlow(BoardCalibration())
    val activeCalibration: StateFlow<BoardCalibration> = _activeCalibration.asStateFlow()

    private val _tuning = MutableStateFlow(AutomationTuning())
    val tuning: StateFlow<AutomationTuning> = _tuning.asStateFlow()

    @Volatile
    var accessibilityServiceInstance: CarromAccessibilityService? = null

    fun setAccessibilityConnected(connected: Boolean, service: CarromAccessibilityService?) {
        accessibilityServiceInstance = service
        _isAccessibilityConnected.value = connected
    }

    fun setOverlayShowing(showing: Boolean) {
        _isOverlayShowing.value = showing
    }

    fun setMediaProjectionActive(active: Boolean) {
        _isMediaProjectionActive.value = active
    }

    fun setAutoPlaying(playing: Boolean) {
        _isAutoPlaying.value = playing
    }

    fun setCalibrationMode(enabled: Boolean) {
        _isCalibrationMode.value = enabled
    }

    fun updatePhase(phase: AutomationPhase, status: String) {
        _phase.value = phase
        _statusBanner.value = status
    }

    fun setAlert(alert: String?) {
        _alertBanner.value = alert
    }

    fun updateDetection(result: BoardDetectionResult?, plan: ShotPlan? = _latestShotPlan.value) {
        _latestDetection.value = result
        _latestShotPlan.value = plan
    }

    fun updateComparison(comparison: BeforeAfterComparison?) {
        _latestComparison.value = comparison
    }

    fun updateCalibration(calibration: BoardCalibration) {
        _activeCalibration.value = calibration
    }

    fun updateTuning(tuning: AutomationTuning) {
        _tuning.value = tuning
    }
}
