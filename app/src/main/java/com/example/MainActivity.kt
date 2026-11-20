package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.service.AutomationBridge
import com.example.service.CarromAutomationService
import com.example.ui.screens.CalibrationScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.TestLabScreen
import com.example.ui.screens.TrainingDataScreen
import com.example.ui.theme.CarromPilotTheme
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.MintGlow
import com.example.ui.theme.ObsidianEmerald
import com.example.ui.theme.SageMuted
import com.example.ui.theme.SlateGreenSurface
import com.example.viewmodel.MainViewModel

private enum class AppRoute(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Control", Icons.Rounded.Dashboard),
    CALIBRATION("calibration", "Calibrate", Icons.Rounded.Tune),
    TEST_LAB("test_lab", "Test Lab", Icons.Rounded.Science),
    TRAINING_DATA("training_data", "Training", Icons.Rounded.Storage)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarromPilotTheme {
                val vm: MainViewModel = viewModel()
                CarromPilotRoot(
                    viewModel = vm,
                    shouldPromptCaptureOnOpen = intent?.getBooleanExtra(EXTRA_REQUEST_CAPTURE, false) == true
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_REQUEST_CAPTURE = "extra_request_capture"
    }
}

@Composable
private fun CarromPilotRoot(
    viewModel: MainViewModel,
    shouldPromptCaptureOnOpen: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val phase by viewModel.bridgePhase.collectAsStateWithLifecycle()
    val statusBanner by viewModel.bridgeStatus.collectAsStateWithLifecycle()
    val alertBanner by viewModel.bridgeAlert.collectAsStateWithLifecycle()
    val latestComparison by viewModel.bridgeComparison.collectAsStateWithLifecycle()
    val calibration by viewModel.activeCalibration.collectAsStateWithLifecycle()
    val savedProfiles by viewModel.savedProfiles.collectAsStateWithLifecycle()
    val tuning by viewModel.tuning.collectAsStateWithLifecycle()
    val learningSummary by viewModel.learningSummary.collectAsStateWithLifecycle()
    val mergeResult by viewModel.mergeStatus.collectAsStateWithLifecycle()
    val allShots by viewModel.allShots.collectAsStateWithLifecycle()
    val successfulShots by viewModel.successfulShots.collectAsStateWithLifecycle()
    val failedShots by viewModel.failedShots.collectAsStateWithLifecycle()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsStateWithLifecycle()
    val testLabState by viewModel.testLabState.collectAsStateWithLifecycle()
    val calibrationPreview by viewModel.calibrationPreviewBitmap.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(context, CarromAutomationService::class.java).apply {
                action = CarromAutomationService.ACTION_START_PROJECTION
                putExtra(CarromAutomationService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CarromAutomationService.EXTRA_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
            viewModel.refreshPermissions()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
    }

    fun requestNotificationThenOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            settingsLauncher.launch(intent)
        } else {
            viewModel.startFloatingOverlayService()
        }
    }

    LaunchedEffect(shouldPromptCaptureOnOpen) {
        if (shouldPromptCaptureOnOpen) {
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    LaunchedEffect(uiMessage) {
        uiMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissUiMessage()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: AppRoute.DASHBOARD.route

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = ObsidianEmerald,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = SlateGreenSurface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        AppRoute.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(AppRoute.DASHBOARD.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("nav_${dest.route}"),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MintGlow,
                                    selectedTextColor = MintGlow,
                                    indicatorColor = EmeraldContainer,
                                    unselectedIconColor = SageMuted,
                                    unselectedTextColor = SageMuted
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(containerColor = SlateGreenSurface) {
                        AppRoute.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(AppRoute.DASHBOARD.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) }
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = AppRoute.DASHBOARD.route,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(AppRoute.DASHBOARD.route) {
                        DashboardScreen(
                            permissions = permissions,
                            phase = phase,
                            statusBanner = statusBanner,
                            alertBanner = alertBanner,
                            tuning = tuning,
                            learningSummary = learningSummary,
                            mergeResult = mergeResult,
                            latestComparison = latestComparison,
                            onRequestOverlayPermission = { requestNotificationThenOverlay() },
                            onOpenAccessibilitySettings = {
                                settingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onRequestMediaProjection = {
                                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                            },
                            onStartOverlayHud = { requestNotificationThenOverlay() },
                            onStopOverlayHud = { viewModel.stopFloatingOverlayService() },
                            onTurnModeChange = { viewModel.updateTurnMode(it) },
                            onToggleDebugMarkers = { viewModel.updateShowDebugMarkers(it) },
                            onWaitTimeChange = { viewModel.updatePostShotWaitMs(it) },
                            onNavigateToCalibration = { navController.navigate(AppRoute.CALIBRATION.route) },
                            onNavigateToTestLab = { navController.navigate(AppRoute.TEST_LAB.route) }
                        )
                    }

                    composable(AppRoute.CALIBRATION.route) {
                        CalibrationScreen(
                            activeCalibration = calibration,
                            savedProfiles = savedProfiles,
                            previewBitmap = calibrationPreview,
                            onSaveCalibration = { viewModel.updateCalibration(it) },
                            onSelectProfile = { viewModel.selectCalibrationProfile(it) },
                            onAutoDetectFromPreview = { viewModel.autoDetectCalibrationFromPreview() },
                            onResetDefault = { viewModel.resetCalibrationToDefault() },
                            onLoadScreenshotUri = { viewModel.loadScreenshotForCalibration(it) },
                            onOpenLiveOverlayCalibrator = {
                                AutomationBridge.setCalibrationMode(true)
                                requestNotificationThenOverlay()
                            }
                        )
                    }

                    composable(AppRoute.TEST_LAB.route) {
                        TestLabScreen(
                            testState = testLabState,
                            calibration = calibration,
                            onRunScenario = { viewModel.runTestScenario(it) },
                            onSimulateBeforeAfter = { viewModel.simulateBeforeAfterTest(it) },
                            onPickTestImage = { viewModel.loadScreenshotForTestLab(it) }
                        )
                    }

                    composable(AppRoute.TRAINING_DATA.route) {
                        TrainingDataScreen(
                            allShots = allShots,
                            successfulShots = successfulShots,
                            failedShots = failedShots,
                            diagnosticLogs = diagnosticLogs,
                            learningSummary = learningSummary,
                            onCopyJson = { viewModel.copyShotsToClipboard(it) },
                            onCopyReadableSummary = { viewModel.copyReadableReportToClipboard() },
                            onExportBackupUri = { viewModel.exportBackupToUri(it) },
                            onImportBackupUri = { viewModel.importAndMergeBackupFromUri(it) },
                            onMergePresets = { viewModel.reRunPresetMerge() },
                            onClearUserShots = { viewModel.clearUserShots() }
                        )
                    }
                }
            }
        }
    }
}
