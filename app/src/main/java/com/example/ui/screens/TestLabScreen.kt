package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.BoardCalibration
import com.example.model.ShotOutcomeCategory
import com.example.ui.components.BoardPreviewCanvas
import com.example.ui.theme.BorderEmerald
import com.example.ui.theme.BurnishedBrass
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.ElevatedGreenCard
import com.example.ui.theme.MintGlow
import com.example.ui.theme.ObsidianEmerald
import com.example.ui.theme.QueenCrimson
import com.example.ui.theme.SageMuted
import com.example.ui.theme.SlateGreenSurface
import com.example.ui.theme.TournamentEmerald
import com.example.viewmodel.TestLabState
import com.example.vision.TestBoardRenderer.TestScenario

@Composable
fun TestLabScreen(
    testState: TestLabState,
    calibration: BoardCalibration,
    onRunScenario: (TestScenario) -> Unit,
    onSimulateBeforeAfter: (TestScenario) -> Unit,
    onPickTestImage: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onPickTestImage(it) }
    }

    val preShotScenarios = listOf(
        TestScenario.OPEN_BOARD,
        TestScenario.STRAIGHT_SHOT,
        TestScenario.QUEEN_BEFORE_LAST,
        TestScenario.POPUP_OBSTRUCTION,
        TestScenario.UNREALISTIC_NO_BOARD
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianEmerald),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Science, contentDescription = null, tint = CyanTelemetry)
                Spacer(Modifier.width(8.dp))
                Text("Step-by-Step Test Lab", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Test screen reading, pop-up blocking, straight/cut shot aiming, Before/After verification, and self-improving data logging step by step.",
                style = MaterialTheme.typography.bodyMedium,
                color = SageMuted
            )
        }

        // Step 1: Choose test frame or pick real screenshot
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
                border = BorderStroke(1.dp, BorderEmerald),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Step 1 • Frame & Vision Reading", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("testlab_pick_screenshot_button"),
                            border = BorderStroke(1.dp, CyanTelemetry)
                        ) {
                            Icon(Icons.Rounded.Image, contentDescription = null, tint = CyanTelemetry)
                            Spacer(Modifier.width(6.dp))
                            Text("Test Photo", color = CyanTelemetry)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        preShotScenarios.forEach { scenario ->
                            FilterChip(
                                selected = !testState.isCustomUploadedImage && testState.selectedScenario == scenario,
                                onClick = { onRunScenario(scenario) },
                                label = { Text(scenario.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }

        // Visual Frame + Debug Markers + Aiming Vector
        item {
            BoardPreviewCanvas(
                bitmap = testState.previewBitmap,
                calibration = calibration,
                detection = testState.detectionResult,
                shotPlan = testState.plannedShot,
                comparison = testState.lastSimulatedComparison,
                showCalibrationHandles = false,
                showDebugMarkers = true
            )
        }

        // Step 2 & 3: Detection Status, Validation Alert & Planned Shot Telemetry
        item {
            val det = testState.detectionResult
            val plan = testState.plannedShot
            val isValid = det?.validity?.isActionable == true

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isValid) ElevatedGreenCard else Color(0xFF42101B)
                ),
                border = BorderStroke(1.dp, if (isValid) TournamentEmerald else QueenCrimson),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isValid) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = if (isValid) TournamentEmerald else QueenCrimson
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Step 2 • ${det?.validity?.label ?: "No Frame"}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                    if (det != null) {
                        Text(
                            text = det.validationDetail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isValid) MintGlow else Color.White
                        )
                        Text(
                            text = "Detected: White=${det.whiteCount}  Black=${det.blackCount}  Queen=${det.queenCount}  Striker=${if (det.striker != null) "Ready" else "Missing"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SageMuted
                        )
                    }

                    if (plan != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Step 3 • Planned Shot: ${plan.targetPiece.color.label} -> ${plan.selectedPocket.label}",
                            style = MaterialTheme.typography.titleMedium,
                            color = BurnishedBrass
                        )
                        Text(
                            text = "Aim=${String.format("%.1f", plan.aimAngleDeg)}° | Cut=${String.format("%.1f", plan.cutAngleDeg)}° | Power=${(plan.appliedPower * 100).toInt()}% | Clearance=${(plan.clearanceScore * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Learning Model: ${plan.learnedAdjustmentNote}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanTelemetry
                        )
                    }
                }
            }
        }

        // Step 4: Before / After Verification & Error Catching Simulator
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
                border = BorderStroke(1.dp, BorderEmerald),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Step 4 • Before / After & Error-Catching Test", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Simulate shot outcomes against the Open Board to verify movement checks, error alerts, and database logging:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SageMuted
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSimulateBeforeAfter(TestScenario.AFTER_CLEAN_POCKET) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sim_clean_pocket_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TournamentEmerald,
                                contentColor = ObsidianEmerald
                            )
                        ) {
                            Text("Clean Pocket", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { onSimulateBeforeAfter(TestScenario.AFTER_TOO_LITTLE_FORCE) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sim_weak_shot_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BurnishedBrass,
                                contentColor = ObsidianEmerald
                            )
                        ) {
                            Text("Too Weak", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { onSimulateBeforeAfter(TestScenario.AFTER_NO_MOVEMENT) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sim_no_move_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = QueenCrimson,
                                contentColor = Color.White
                            )
                        ) {
                            Text("No Move Err", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    testState.lastSimulatedComparison?.let { cmp ->
                        val isGood = cmp.outcomeDetail.category == ShotOutcomeCategory.SUCCESS
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ElevatedGreenCard, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Result: ${cmp.outcomeDetail.label}",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isGood) TournamentEmerald else QueenCrimson
                            )
                            Text(
                                text = "Target Travel: ${String.format("%.3f", cmp.targetDisplacementNorm)} (${cmp.targetDirectionLabel})",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "Striker Travel: ${String.format("%.3f", cmp.strikerDisplacementNorm)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = SageMuted
                            )
                            if (cmp.errorNote.isNotBlank()) {
                                Text(cmp.errorNote, style = MaterialTheme.typography.bodyMedium, color = BurnishedBrass)
                            }
                        }
                    }
                }
            }
        }
    }
}
