package com.example.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CalibrationStore
import com.example.data.MergeResult
import com.example.data.TrainingDataRepository
import com.example.data.db.DiagnosticLogEntity
import com.example.data.db.ShotRecordEntity
import com.example.engine.BeforeAfterEvaluator
import com.example.engine.LearningSummary
import com.example.engine.ShotLearner
import com.example.engine.ShotPlannerEngine
import com.example.model.AutomationPhase
import com.example.model.AutomationTuning
import com.example.model.BeforeAfterComparison
import com.example.model.BoardCalibration
import com.example.model.BoardDetectionResult
import com.example.model.ShotOutcomeCategory
import com.example.model.ShotPlan
import com.example.model.TurnTargetMode
import com.example.service.AutomationBridge
import com.example.service.CarromAccessibilityService
import com.example.service.CarromAutomationService
import com.example.vision.BoardVisionAnalyzer
import com.example.vision.TestBoardRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionStatus(
    val overlayGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val mediaProjectionActive: Boolean = false,
    val overlayShowing: Boolean = false
)

data class TestLabState(
    val selectedScenario: TestBoardRenderer.TestScenario = TestBoardRenderer.TestScenario.OPEN_BOARD,
    val previewBitmap: Bitmap? = null,
    val isCustomUploadedImage: Boolean = false,
    val detectionResult: BoardDetectionResult? = null,
    val plannedShot: ShotPlan? = null,
    val lastSimulatedComparison: BeforeAfterComparison? = null,
    val feedbackToast: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val calibrationStore = CalibrationStore(application)
    private val trainingRepository = TrainingDataRepository(application)
    private val visionAnalyzer = BoardVisionAnalyzer()
    private val shotLearner = ShotLearner()
    private val shotPlanner = ShotPlannerEngine(shotLearner)
    private val beforeAfterEvaluator = BeforeAfterEvaluator()

    val activeCalibration: StateFlow<BoardCalibration> = calibrationStore.activeCalibrationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BoardCalibration())

    val savedProfiles: StateFlow<List<BoardCalibration>> = calibrationStore.savedProfilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(BoardCalibration()))

    val tuning: StateFlow<AutomationTuning> = calibrationStore.tuningFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutomationTuning())

    val allShots: StateFlow<List<ShotRecordEntity>> = trainingRepository.allShotsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val successfulShots: StateFlow<List<ShotRecordEntity>> = trainingRepository.successfulShotsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val failedShots: StateFlow<List<ShotRecordEntity>> = trainingRepository.failedShotsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val diagnosticLogs: StateFlow<List<DiagnosticLogEntity>> = trainingRepository.diagnosticLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val learningSummary: StateFlow<LearningSummary> = combine(allShots) { (shots) ->
        shotLearner.summarizeLearning(shots)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LearningSummary())

    val bridgePhase: StateFlow<AutomationPhase> = AutomationBridge.phase
    val bridgeStatus: StateFlow<String> = AutomationBridge.statusBanner
    val bridgeAlert: StateFlow<String?> = AutomationBridge.alertBanner
    val bridgeComparison: StateFlow<BeforeAfterComparison?> = AutomationBridge.latestComparison

    private val _permissions = MutableStateFlow(PermissionStatus())
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    private val _mergeStatus = MutableStateFlow<MergeResult?>(null)
    val mergeStatus: StateFlow<MergeResult?> = _mergeStatus.asStateFlow()

    private val _testLabState = MutableStateFlow(TestLabState())
    val testLabState: StateFlow<TestLabState> = _testLabState.asStateFlow()

    private val _calibrationPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val calibrationPreviewBitmap: StateFlow<Bitmap?> = _calibrationPreviewBitmap.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    init {
        viewModelScope.launch {
            val merge = trainingRepository.initializeAndMergeOnStartup()
            _mergeStatus.value = merge
            refreshPermissions()
            runTestScenario(TestBoardRenderer.TestScenario.OPEN_BOARD)
        }
        viewModelScope.launch {
            activeCalibration.collect { cal ->
                AutomationBridge.updateCalibration(cal)
                if (_calibrationPreviewBitmap.value == null) {
                    _calibrationPreviewBitmap.value = withContext(Dispatchers.Default) {
                        TestBoardRenderer.renderScenarioBitmap(TestBoardRenderer.TestScenario.OPEN_BOARD, cal)
                    }
                }
            }
        }
        viewModelScope.launch {
            tuning.collect { t -> AutomationBridge.updateTuning(t) }
        }
    }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        val overlayOk = Settings.canDrawOverlays(ctx)
        val accOk = AutomationBridge.isAccessibilityConnected.value || isAccessibilityEnabledInSettings(ctx)
        _permissions.value = PermissionStatus(
            overlayGranted = overlayOk,
            accessibilityEnabled = accOk,
            mediaProjectionActive = AutomationBridge.isMediaProjectionActive.value,
            overlayShowing = AutomationBridge.isOverlayShowing.value
        )
    }

    fun startFloatingOverlayService() {
        val ctx = getApplication<Application>()
        if (!Settings.canDrawOverlays(ctx)) {
            _uiMessage.value = "Please grant Display Over Other Apps permission first."
            return
        }
        val intent = Intent(ctx, CarromAutomationService::class.java).apply {
            action = CarromAutomationService.ACTION_START_OVERLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
        refreshPermissions()
    }

    fun stopFloatingOverlayService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, CarromAutomationService::class.java).apply {
            action = CarromAutomationService.ACTION_STOP_ALL
        }
        ctx.startService(intent)
        refreshPermissions()
    }

    fun updateCalibration(calibration: BoardCalibration) {
        viewModelScope.launch {
            calibrationStore.saveCalibration(calibration)
            AutomationBridge.updateCalibration(calibration)
            _uiMessage.value = "Saved calibration: ${calibration.profileName}"
        }
    }

    fun selectCalibrationProfile(profileName: String) {
        viewModelScope.launch {
            calibrationStore.selectProfile(profileName)
            _uiMessage.value = "Switched profile to $profileName"
        }
    }

    fun resetCalibrationToDefault() {
        viewModelScope.launch {
            val fresh = BoardCalibration()
            calibrationStore.saveCalibration(fresh)
            _uiMessage.value = "Reset calibration to Yalla Carrom Portrait defaults"
        }
    }

    fun autoDetectCalibrationFromPreview() {
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = _calibrationPreviewBitmap.value ?: _testLabState.value.previewBitmap ?: return@launch
            val current = activeCalibration.value
            val updated = visionAnalyzer.autoDetectCalibration(bmp, current)
            calibrationStore.saveCalibration(updated)
            _uiMessage.value = "Auto-detected Carrom board & slider boundaries"
        }
    }

    fun loadScreenshotForCalibration(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = decodeBitmapFromUri(uri) ?: return@launch
            _calibrationPreviewBitmap.value = bmp
            val updated = visionAnalyzer.autoDetectCalibration(bmp, activeCalibration.value)
            calibrationStore.saveCalibration(updated)
            _uiMessage.value = "Loaded screenshot and auto-fitted board boundaries"
        }
    }

    fun loadScreenshotForTestLab(uri: Uri) {
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = withContext(Dispatchers.IO) { decodeBitmapFromUri(uri) } ?: return@launch
            val cal = activeCalibration.value
            val t = tuning.value
            val det = visionAnalyzer.analyzeFrame(bmp, cal, t)
            val history = trainingRepository.getAllShotsSnapshot()
            val plan = shotPlanner.planBestShot(det, cal, t, history)
            _testLabState.value = TestLabState(
                selectedScenario = _testLabState.value.selectedScenario,
                previewBitmap = bmp,
                isCustomUploadedImage = true,
                detectionResult = det,
                plannedShot = plan,
                lastSimulatedComparison = null
            )
        }
    }

    fun runTestScenario(scenario: TestBoardRenderer.TestScenario) {
        viewModelScope.launch(Dispatchers.Default) {
            val cal = activeCalibration.value
            val t = tuning.value
            val bmp = TestBoardRenderer.renderScenarioBitmap(scenario, cal)
            val det = visionAnalyzer.analyzeFrame(bmp, cal, t)
            val history = trainingRepository.getAllShotsSnapshot()
            val plan = shotPlanner.planBestShot(det, cal, t, history)
            _testLabState.value = _testLabState.value.copy(
                selectedScenario = scenario,
                previewBitmap = bmp,
                isCustomUploadedImage = false,
                detectionResult = det,
                plannedShot = plan
            )
        }
    }

    fun simulateBeforeAfterTest(afterOutcomeScenario: TestBoardRenderer.TestScenario) {
        viewModelScope.launch(Dispatchers.Default) {
            val cal = activeCalibration.value
            val t = tuning.value
            val beforeBmp = TestBoardRenderer.renderScenarioBitmap(
                TestBoardRenderer.TestScenario.OPEN_BOARD,
                cal
            )
            val beforeDet = visionAnalyzer.analyzeFrame(beforeBmp, cal, t)
            val history = trainingRepository.getAllShotsSnapshot()
            val plan = shotPlanner.planBestShot(beforeDet, cal, t, history) ?: return@launch

            val afterBmp = TestBoardRenderer.renderScenarioBitmap(afterOutcomeScenario, cal)
            val afterDet = visionAnalyzer.analyzeFrame(afterBmp, cal, t)
            val comparison = beforeAfterEvaluator.compare(plan, beforeDet, afterDet)

            trainingRepository.recordComparisonShot(comparison, "${cal.profileName} (TestLab)")
            if (comparison.errorNote.isNotBlank()) {
                trainingRepository.logDiagnostic(
                    severity = if (comparison.outcomeDetail.name.contains("ERROR")) "ERROR" else "INFO",
                    code = comparison.outcomeDetail.name,
                    message = comparison.errorNote
                )
            }
            AutomationBridge.updateComparison(comparison)
            _testLabState.value = _testLabState.value.copy(
                selectedScenario = afterOutcomeScenario,
                previewBitmap = afterBmp,
                detectionResult = afterDet,
                plannedShot = plan,
                lastSimulatedComparison = comparison
            )
            _uiMessage.value = "Before/After logged: ${comparison.outcomeDetail.label}"
        }
    }

    fun updateTurnMode(mode: TurnTargetMode) {
        viewModelScope.launch {
            calibrationStore.updateTuning { it.copy(turnTargetMode = mode) }
        }
    }

    fun updateShowDebugMarkers(show: Boolean) {
        viewModelScope.launch {
            calibrationStore.updateTuning {
                it.copy(showDebugMarkers = show, showAimingLines = show, showBeforeAfterGhost = show)
            }
        }
    }

    fun updatePostShotWaitMs(waitMs: Long) {
        viewModelScope.launch {
            calibrationStore.updateTuning { it.copy(postShotWaitMs = waitMs) }
        }
    }

    fun copyShotsToClipboard(category: ShotOutcomeCategory?) {
        val json = trainingRepository.formatShotsAsCleanJson(allShots.value, category)
        copyToClipboard("CarromPilot Shot Data", json)
        val label = category?.label ?: "All Shot Data (JSON)"
        _uiMessage.value = "Copied $label to clipboard"
    }

    fun copyReadableReportToClipboard() {
        val text = trainingRepository.formatShotsAsReadableSummary(allShots.value)
        copyToClipboard("CarromPilot Summary Report", text)
        _uiMessage.value = "Copied readable Successful/Failed report to clipboard"
    }

    fun exportBackupToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = trainingRepository.formatShotsAsCleanJson(allShots.value, null)
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                _uiMessage.value = "Exported ${allShots.value.size} shots to backup file"
            }.onFailure {
                _uiMessage.value = "Export failed: ${it.localizedMessage}"
            }
        }
    }

    fun importAndMergeBackupFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    it.readText()
                }
            }.getOrNull()
            if (content.isNullOrBlank()) {
                _uiMessage.value = "Could not read selected backup file"
                return@launch
            }
            val added = trainingRepository.importAndMergeJson(content)
            _uiMessage.value = "Merged $added new shots from backup without losing existing data"
        }
    }

    fun reRunPresetMerge() {
        viewModelScope.launch {
            val result = trainingRepository.initializeAndMergeOnStartup()
            _mergeStatus.value = result
            _uiMessage.value = "Merge complete: +${result.presetsAdded} presets, ${result.totalRecords} total shots preserved"
        }
    }

    fun clearUserShots() {
        viewModelScope.launch {
            trainingRepository.clearUserShotsOnly()
            _uiMessage.value = "Cleared user-recorded shots (presets preserved)"
        }
    }

    fun clearDiagnosticLogs() {
        viewModelScope.launch {
            trainingRepository.clearDiagnostics()
            _uiMessage.value = "Cleared diagnostic logs"
        }
    }

    fun dismissUiMessage() {
        _uiMessage.value = null
    }

    private fun copyToClipboard(label: String, content: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, content))
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        return runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    private fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val expected = "${context.packageName}/${CarromAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
