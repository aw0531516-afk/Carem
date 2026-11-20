package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.CalibrationStore
import com.example.data.TrainingDataRepository
import com.example.engine.BeforeAfterEvaluator
import com.example.engine.ShotPlannerEngine
import com.example.model.AutomationPhase
import com.example.model.BeforeAfterComparison
import com.example.model.BoardCalibration
import com.example.model.BoardDetectionResult
import com.example.model.FrameValidity
import com.example.model.ShotOutcomeCategory
import com.example.model.ShotOutcomeDetail
import com.example.model.ShotPlan
import com.example.model.TurnTargetMode
import com.example.vision.BoardVisionAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class CarromAutomationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var calibrationStore: CalibrationStore
    private lateinit var trainingRepository: TrainingDataRepository
    private val visionAnalyzer = BoardVisionAnalyzer()
    private val shotPlanner = ShotPlannerEngine()
    private val beforeAfterEvaluator = BeforeAfterEvaluator()

    private lateinit var overlayController: OverlayHudController

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 720
    private var captureHeight = 1600
    private var captureDpi = 320

    private var automationLoopJob: Job? = null

    @Volatile
    private var latestCapturedBitmap: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        calibrationStore = CalibrationStore(applicationContext)
        trainingRepository = TrainingDataRepository(applicationContext)
        createNotificationChannel()

        overlayController = OverlayHudController(
            context = this,
            onStartClick = { toggleAutoPlay() },
            onStopClick = { stopAutoPlay() },
            onAutoCalibrateClick = { performAutoCalibrationFromLiveFrame() },
            onSaveCalibrationClick = { cal ->
                serviceScope.launch {
                    calibrationStore.saveCalibration(cal)
                    AutomationBridge.updateCalibration(cal)
                    trainingRepository.logDiagnostic("INFO", "CALIBRATION_SAVED", "Saved calibration profile ${cal.profileName}")
                }
            },
            onCycleTurnMode = {
                serviceScope.launch {
                    val modes = TurnTargetMode.entries
                    calibrationStore.updateTuning { t ->
                        val nextIdx = (modes.indexOf(t.turnTargetMode) + 1) % modes.size
                        t.copy(turnTargetMode = modes[nextIdx])
                    }
                }
            },
            onToggleDebugMarkers = {
                serviceScope.launch {
                    calibrationStore.updateTuning { t ->
                        val next = !t.showDebugMarkers
                        t.copy(showDebugMarkers = next, showAimingLines = next, showBeforeAfterGhost = next)
                    }
                }
            },
            onCloseOverlayClick = {
                stopAutoPlay()
                overlayController.hide()
                stopSelf()
            }
        )

        serviceScope.launch {
            calibrationStore.activeCalibrationFlow.collectLatest { cal ->
                AutomationBridge.updateCalibration(cal)
                overlayController.refreshState()
            }
        }
        serviceScope.launch {
            calibrationStore.tuningFlow.collectLatest { t ->
                AutomationBridge.updateTuning(t)
                overlayController.refreshState()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_OVERLAY
        val hasProjectionExtras = intent?.hasExtra(EXTRA_RESULT_CODE) == true

        startAsForeground(includeMediaProjection = hasProjectionExtras || mediaProjection != null)

        when (action) {
            ACTION_START_OVERLAY -> {
                overlayController.show()
                AutomationBridge.updatePhase(AutomationPhase.OVERLAY_READY, "Overlay ready — calibrate or tap Start")
                overlayController.refreshState()
            }
            ACTION_START_PROJECTION -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    ?: Activity.RESULT_CANCELED
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    intent?.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
                    setupMediaProjection(resultCode, data)
                    overlayController.show()
                    startAutomationLoop()
                }
            }
            ACTION_STOP_ALL -> {
                stopAutoPlay()
                releaseMediaProjection()
                overlayController.hide()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAutoPlay()
        releaseMediaProjection()
        overlayController.hide()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(includeMediaProjection: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        releaseMediaProjection()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data) ?: return
        mediaProjection = proj

        val dm = resources.displayMetrics
        captureDpi = dm.densityDpi
        // Scale down slightly for fast low-memory capture while preserving aspect ratio
        val scale = 720f / dm.widthPixels.coerceAtLeast(1)
        captureWidth = 720
        captureHeight = (dm.heightPixels * scale).toInt().coerceAtLeast(1000)

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * captureWidth
                val rawBmp = Bitmap.createBitmap(
                    captureWidth + rowPadding / pixelStride,
                    captureHeight,
                    Bitmap.Config.ARGB_8888
                )
                rawBmp.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(rawBmp, 0, 0, captureWidth, captureHeight)
                rawBmp.recycle()
                latestCapturedBitmap?.recycle()
                latestCapturedBitmap = cropped
            } catch (_: Throwable) {
            } finally {
                img.close()
            }
        }, null)

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                AutomationBridge.setMediaProjectionActive(false)
                stopAutoPlay()
            }
        }, null)

        virtualDisplay = proj.createVirtualDisplay(
            "CarromPilotCapture",
            captureWidth,
            captureHeight,
            captureDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
        AutomationBridge.setMediaProjectionActive(true)
    }

    private fun releaseMediaProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        latestCapturedBitmap?.recycle()
        latestCapturedBitmap = null
        AutomationBridge.setMediaProjectionActive(false)
    }

    private fun toggleAutoPlay() {
        if (!AutomationBridge.isMediaProjectionActive.value) {
            // Ask MainActivity to launch MediaProjection permission prompt
            AutomationBridge.setAlert("Grant Screen Capture permission in CarromPilot app to start live reading")
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_REQUEST_CAPTURE, true)
            }
            startActivity(launchIntent)
            overlayController.refreshState()
            return
        }

        if (AutomationBridge.isAutoPlaying.value) {
            AutomationBridge.setAutoPlaying(false)
            AutomationBridge.updatePhase(AutomationPhase.PAUSED, "Paused by user")
            overlayController.refreshState()
        } else {
            startAutomationLoop()
        }
    }

    private fun stopAutoPlay() {
        AutomationBridge.setAutoPlaying(false)
        automationLoopJob?.cancel()
        automationLoopJob = null
        AutomationBridge.updatePhase(AutomationPhase.OVERLAY_READY, "Stopped")
        overlayController.refreshState()
    }

    private fun performAutoCalibrationFromLiveFrame() {
        val bmp = latestCapturedBitmap
        if (bmp == null) {
            AutomationBridge.setAlert("Start Screen Capture first to auto-detect board from live game")
            overlayController.refreshState()
            return
        }
        val current = AutomationBridge.activeCalibration.value
        val detected = visionAnalyzer.autoDetectCalibration(bmp, current)
        AutomationBridge.updateCalibration(detected)
        AutomationBridge.setAlert(null)
        AutomationBridge.updatePhase(AutomationPhase.OVERLAY_READY, "Auto-fitted board boundaries")
        overlayController.refreshState()
    }

    private fun startAutomationLoop() {
        if (automationLoopJob?.isActive == true) {
            AutomationBridge.setAutoPlaying(true)
            overlayController.refreshState()
            return
        }

        AutomationBridge.setAutoPlaying(true)
        AutomationBridge.setAlert(null)

        automationLoopJob = serviceScope.launch {
            while (isActive) {
                if (!AutomationBridge.isAutoPlaying.value || AutomationBridge.isCalibrationMode.value) {
                    delay(350)
                    continue
                }

                val bmp = latestCapturedBitmap
                if (bmp == null) {
                    AutomationBridge.updatePhase(AutomationPhase.SCANNING, "Waiting for live screen frame...")
                    overlayController.refreshState()
                    delay(400)
                    continue
                }

                val calibration = AutomationBridge.activeCalibration.value
                val tuning = AutomationBridge.tuning.value
                val detection1 = visionAnalyzer.analyzeFrame(bmp, calibration, tuning)
                AutomationBridge.updateDetection(detection1, null)

                if (!detection1.validity.isActionable) {
                    AutomationBridge.updatePhase(AutomationPhase.ALERT_HOLD, detection1.validity.label)
                    AutomationBridge.setAlert(detection1.validationDetail)
                    if (detection1.validity == FrameValidity.INVALID_OBSTRUCTION ||
                        detection1.validity == FrameValidity.INVALID_UNREALISTIC_COUNT
                    ) {
                        trainingRepository.logDiagnostic(
                            severity = "WARNING",
                            code = detection1.validity.name,
                            message = detection1.validationDetail
                        )
                    }
                    overlayController.refreshState()
                    delay(750)
                    continue
                }

                // Confirm pieces have settled by comparing with a second frame 350ms later
                AutomationBridge.updatePhase(AutomationPhase.SETTLING, "Checking piece stability...")
                overlayController.refreshState()
                delay(350)
                val bmp2 = latestCapturedBitmap ?: bmp
                val detection2 = visionAnalyzer.analyzeFrame(bmp2, calibration, tuning)
                if (!detection2.validity.isActionable || !hasBoardSettled(detection1, detection2)) {
                    AutomationBridge.updateDetection(detection2, null)
                    AutomationBridge.updatePhase(AutomationPhase.SETTLING, "Pieces still moving or popup appeared")
                    overlayController.refreshState()
                    delay(450)
                    continue
                }

                // Plan best shot with self-improving learner
                val history = trainingRepository.getAllShotsSnapshot()
                val plan = shotPlanner.planBestShot(detection2, calibration, tuning, history)
                if (plan == null) {
                    AutomationBridge.updatePhase(AutomationPhase.ALERT_HOLD, "No clear shot available")
                    AutomationBridge.setAlert("All target pucks blocked or invalid geometry")
                    overlayController.refreshState()
                    delay(800)
                    continue
                }

                AutomationBridge.setAlert(null)
                AutomationBridge.updateDetection(detection2, plan)
                overlayController.refreshState()

                val accService = AutomationBridge.accessibilityServiceInstance
                if (accService == null) {
                    AutomationBridge.updatePhase(AutomationPhase.ALERT_HOLD, "Touch Automation OFF")
                    AutomationBridge.setAlert("Enable CarromPilot Touch Automation in Accessibility Settings")
                    overlayController.refreshState()
                    delay(1200)
                    continue
                }

                // Step A: Slide striker along bottom slider bar
                val screenW = resources.displayMetrics.widthPixels.toFloat()
                val screenH = resources.displayMetrics.heightPixels.toFloat()

                val curStrikerNormX = detection2.striker?.normX ?: ((calibration.baselineLeftX + calibration.baselineRightX) * 0.5f)
                val curFrac = ((curStrikerNormX - calibration.baselineLeftX) /
                    (calibration.baselineRightX - calibration.baselineLeftX).coerceAtLeast(0.05f)).coerceIn(0f, 1f)
                val targetFrac = ((plan.strikerTargetPos.x - calibration.baselineLeftX) /
                    (calibration.baselineRightX - calibration.baselineLeftX).coerceAtLeast(0.05f)).coerceIn(0f, 1f)

                if (abs(targetFrac - curFrac) > 0.04f) {
                    AutomationBridge.updatePhase(AutomationPhase.SLIDING_STRIKER, "Moving striker slider...")
                    overlayController.refreshState()

                    val sliderFromX = (calibration.sliderLeftX + (calibration.sliderRightX - calibration.sliderLeftX) * curFrac) * screenW
                    val sliderToX = (calibration.sliderLeftX + (calibration.sliderRightX - calibration.sliderLeftX) * targetFrac) * screenW
                    val sliderY = calibration.sliderY * screenH
                    accService.performSliderDrag(sliderFromX, sliderY, sliderToX, sliderY)
                    delay(320)

                    // Verify slider move had effect
                    val postSlideBmp = latestCapturedBitmap
                    if (postSlideBmp != null) {
                        val postSlideDet = visionAnalyzer.analyzeFrame(postSlideBmp, calibration, tuning)
                        val newStrikerX = postSlideDet.striker?.normX
                        if (newStrikerX != null && abs(newStrikerX - curStrikerNormX) < 0.01f) {
                            AutomationBridge.setAlert("SLIDER_NO_EFFECT: Striker did not move on baseline")
                            trainingRepository.logDiagnostic(
                                severity = "ERROR",
                                code = ShotOutcomeDetail.SLIDER_NO_EFFECT.name,
                                message = "Slider drag from $curFrac to $targetFrac caused zero striker displacement"
                            )
                            overlayController.refreshState()
                            delay(900)
                            continue
                        }
                    }
                }

                // Step B: Execute pull-and-release shot directly on striker
                AutomationBridge.updatePhase(
                    AutomationPhase.AIMING_AND_SHOOTING,
                    "Shooting ${plan.targetPiece.color.label} -> ${plan.selectedPocket.label} (${(plan.appliedPower * 100).toInt()}%)"
                )
                overlayController.refreshState()

                val startPxX = plan.strikerTargetPos.x * screenW
                val startPxY = plan.strikerTargetPos.y * screenH
                val pullEndPxX = plan.pullEndPos.x * screenW
                val pullEndPxY = plan.pullEndPos.y * screenH

                val shotDispatched = accService.performPullAndReleaseShot(
                    startX = startPxX,
                    startY = startPxY,
                    pullEndX = pullEndPxX,
                    pullEndY = pullEndPxY
                )
                if (!shotDispatched) {
                    AutomationBridge.setAlert("Gesture dispatch failed — check Accessibility Service")
                    trainingRepository.logDiagnostic("ERROR", "GESTURE_FAILED", "AccessibilityService failed to dispatch pull shot")
                    overlayController.refreshState()
                    delay(1000)
                    continue
                }

                // Step C: Wait for shot physics to play out & compare Before / After
                AutomationBridge.updatePhase(AutomationPhase.WAITING_AFTER_SHOT, "Waiting to verify Before / After...")
                overlayController.refreshState()
                delay(tuning.postShotWaitMs)

                val afterBmp = latestCapturedBitmap ?: continue
                val afterDet = visionAnalyzer.analyzeFrame(afterBmp, calibration, tuning)
                val comparison = beforeAfterEvaluator.compare(plan, detection2, afterDet)
                AutomationBridge.updateComparison(comparison)
                trainingRepository.recordComparisonShot(comparison, calibration.profileName)

                if (comparison.outcomeDetail == ShotOutcomeDetail.NO_MOVEMENT_ERROR) {
                    AutomationBridge.setAlert("NO_MOVEMENT_ERROR: Touch/drag had no effect!")
                    trainingRepository.logDiagnostic(
                        severity = "ERROR",
                        code = ShotOutcomeDetail.NO_MOVEMENT_ERROR.name,
                        message = comparison.errorNote
                    )
                } else if (comparison.outcomeDetail.category == ShotOutcomeCategory.SUCCESS) {
                    AutomationBridge.setAlert(null)
                    AutomationBridge.updatePhase(AutomationPhase.SCANNING, "SUCCESS: ${comparison.outcomeDetail.label}")
                } else {
                    AutomationBridge.updatePhase(AutomationPhase.SCANNING, "Logged: ${comparison.outcomeDetail.label}")
                }

                overlayController.refreshState()
                delay(650)
            }
        }
    }

    private fun hasBoardSettled(a: BoardDetectionResult, b: BoardDetectionResult): Boolean {
        if (a.pieces.size != b.pieces.size) return false
        for (pa in a.pieces) {
            val minDist = b.pieces
                .filter { it.color == pa.color }
                .minOfOrNull { it.point.distanceTo(pa.point) } ?: 1f
            if (minDist > 0.015f) return false
        }
        return true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CarromPilot Active")
            .setContentText("Floating HUD & live board analyzer ready")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START_OVERLAY = "com.example.action.START_OVERLAY"
        const val ACTION_START_PROJECTION = "com.example.action.START_PROJECTION"
        const val ACTION_STOP_ALL = "com.example.action.STOP_ALL"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        private const val CHANNEL_ID = "carrom_pilot_automation"
        private const val NOTIFICATION_ID = 4201
    }
}
