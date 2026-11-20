package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.BoardCalibration
import com.example.ui.components.BoardPreviewCanvas
import com.example.ui.theme.BorderEmerald
import com.example.ui.theme.BurnishedBrass
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.ObsidianEmerald
import com.example.ui.theme.SageMuted
import com.example.ui.theme.SlateGreenSurface
import com.example.ui.theme.TournamentEmerald

@Composable
fun CalibrationScreen(
    activeCalibration: BoardCalibration,
    savedProfiles: List<BoardCalibration>,
    previewBitmap: Bitmap?,
    onSaveCalibration: (BoardCalibration) -> Unit,
    onSelectProfile: (String) -> Unit,
    onAutoDetectFromPreview: () -> Unit,
    onResetDefault: () -> Unit,
    onLoadScreenshotUri: (android.net.Uri) -> Unit,
    onOpenLiveOverlayCalibrator: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draftCalibration by remember(activeCalibration) { mutableStateOf(activeCalibration) }
    var profileNameInput by remember(activeCalibration.profileName) {
        mutableStateOf(activeCalibration.profileName)
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onLoadScreenshotUri(it) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianEmerald),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Board & Screen Calibration", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Drag the cyan & brass handles to align the 4 board corners, 4 corner pockets, striker baseline, and bottom slider bar.",
                style = MaterialTheme.typography.bodyMedium,
                color = SageMuted
            )
        }

        // Action row: Load game screenshot, Auto-Detect, Launch Live Overlay Calibrator
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("calibration_pick_image_button"),
                    border = BorderStroke(1.dp, CyanTelemetry)
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = null, tint = CyanTelemetry)
                    Spacer(Modifier.width(6.dp))
                    Text("Screenshot", color = CyanTelemetry)
                }

                OutlinedButton(
                    onClick = onAutoDetectFromPreview,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("calibration_auto_detect_button"),
                    border = BorderStroke(1.dp, BurnishedBrass)
                ) {
                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = BurnishedBrass)
                    Spacer(Modifier.width(6.dp))
                    Text("Auto-Detect", color = BurnishedBrass)
                }
            }
        }

        item {
            Button(
                onClick = onOpenLiveOverlayCalibrator,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_live_overlay_calibrator"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BurnishedBrass,
                    contentColor = ObsidianEmerald
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.Layers, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Calibrate Directly Over Live Game", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Interactive Visual Board Calibration Canvas
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
                border = BorderStroke(1.dp, BorderEmerald),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    BoardPreviewCanvas(
                        bitmap = previewBitmap,
                        calibration = draftCalibration,
                        detection = null,
                        shotPlan = null,
                        showCalibrationHandles = true,
                        showDebugMarkers = true,
                        onCalibrationChanged = { updated -> draftCalibration = updated }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Board: (${fmt(draftCalibration.boardLeft)}, ${fmt(draftCalibration.boardTop)}) to (${fmt(draftCalibration.boardRight)}, ${fmt(draftCalibration.boardBottom)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanTelemetry
                    )
                    Text(
                        text = "Baseline Y: ${fmt(draftCalibration.strikerBaselineY)} | Slider Y: ${fmt(draftCalibration.sliderY)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TournamentEmerald
                    )
                }
            }
        }

        // Fine Radii & Pull Scale Sliders
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
                border = BorderStroke(1.dp, BorderEmerald),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fine Geometry & Power Range", style = MaterialTheme.typography.titleMedium)

                    Text("Pocket Capture Radius: ${fmt(draftCalibration.pocketRadiusNorm)}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = draftCalibration.pocketRadiusNorm,
                        onValueChange = { draftCalibration = draftCalibration.copy(pocketRadiusNorm = it) },
                        valueRange = 0.025f..0.075f
                    )

                    Text("Puck Size Ratio: ${fmt(draftCalibration.puckRadiusNorm)}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = draftCalibration.puckRadiusNorm,
                        onValueChange = { draftCalibration = draftCalibration.copy(puckRadiusNorm = it) },
                        valueRange = 0.018f..0.045f
                    )

                    Text("Max Pull-Back Drag Distance: ${fmt(draftCalibration.maxPullDistanceNorm)}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = draftCalibration.maxPullDistanceNorm,
                        onValueChange = { draftCalibration = draftCalibration.copy(maxPullDistanceNorm = it) },
                        valueRange = 0.08f..0.24f
                    )
                }
            }
        }

        // Save Profile & Switch Screen Profiles
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
                border = BorderStroke(1.dp, BorderEmerald),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Screen Profiles", style = MaterialTheme.typography.titleMedium)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        savedProfiles.forEach { profile ->
                            FilterChip(
                                selected = profile.profileName == activeCalibration.profileName,
                                onClick = { onSelectProfile(profile.profileName) },
                                label = { Text(profile.profileName, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = profileNameInput,
                        onValueChange = { profileNameInput = it },
                        label = { Text("Profile Name (e.g. Phone 1080p / Tablet)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("calibration_profile_name_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val cleanName = profileNameInput.trim().ifBlank { "Custom Screen" }
                                onSaveCalibration(draftCalibration.copy(profileName = cleanName))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("save_calibration_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TournamentEmerald,
                                contentColor = ObsidianEmerald
                            )
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save Calibration", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onResetDefault,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("reset_calibration_button"),
                            border = BorderStroke(1.dp, Color.Gray)
                        ) {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = "Reset")
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(v: Float): String = String.format("%.3f", v)
