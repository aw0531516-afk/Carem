package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.MergeResult
import com.example.engine.LearningSummary
import com.example.model.AutomationPhase
import com.example.model.AutomationTuning
import com.example.model.BeforeAfterComparison
import com.example.model.ShotOutcomeCategory
import com.example.model.TurnTargetMode
import com.example.ui.theme.BorderEmerald
import com.example.ui.theme.BurnishedBrass
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.ElevatedGreenCard
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.MintGlow
import com.example.ui.theme.ObsidianEmerald
import com.example.ui.theme.QueenCrimson
import com.example.ui.theme.SageMuted
import com.example.ui.theme.SlateGreenSurface
import com.example.ui.theme.TournamentEmerald
import com.example.viewmodel.PermissionStatus

@Composable
fun DashboardScreen(
    permissions: PermissionStatus,
    phase: AutomationPhase,
    statusBanner: String,
    alertBanner: String?,
    tuning: AutomationTuning,
    learningSummary: LearningSummary,
    mergeResult: MergeResult?,
    latestComparison: BeforeAfterComparison?,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onStartOverlayHud: () -> Unit,
    onStopOverlayHud: () -> Unit,
    onTurnModeChange: (TurnTargetMode) -> Unit,
    onToggleDebugMarkers: (Boolean) -> Unit,
    onWaitTimeChange: (Long) -> Unit,
    onNavigateToCalibration: () -> Unit,
    onNavigateToTestLab: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianEmerald),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Banner Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, BorderEmerald),
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface)
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_carrom_hero),
                            contentDescription = "CarromPilot tactical board visual",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, ObsidianEmerald.copy(alpha = 0.92f))
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (permissions.mediaProjectionActive) TournamentEmerald else BurnishedBrass
                                        )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${phase.label} • $statusBanner",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MintGlow
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "CarromPilot Automation",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White
                            )
                        }
                    }

                    // Primary Overlay & Capture Action Buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = if (permissions.overlayShowing) onStopOverlayHud else onStartOverlayHud,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("launch_overlay_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (permissions.overlayShowing) QueenCrimson else TournamentEmerald,
                                contentColor = if (permissions.overlayShowing) Color.White else ObsidianEmerald
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = if (permissions.overlayShowing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (permissions.overlayShowing) "Stop HUD" else "Launch HUD",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onRequestMediaProjection,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("start_screen_capture_button"),
                            border = BorderStroke(1.dp, BurnishedBrass),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Cast, contentDescription = null, tint = BurnishedBrass)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (permissions.mediaProjectionActive) "Capture ON" else "Live Capture",
                                color = BurnishedBrass
                            )
                        }
                    }
                }
            }
        }

        // Immediate Alert Banner if any obstruction or error is active
        if (!alertBanner.isNullOrBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF42101B)),
                    border = BorderStroke(1.dp, QueenCrimson),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Warning, contentDescription = "Alert", tint = QueenCrimson)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = alertBanner,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // System Readiness Checklist
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
                border = BorderStroke(1.dp, BorderEmerald),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("System Readiness", style = MaterialTheme.typography.titleLarge)
                    PermissionRow(
                        icon = Icons.Rounded.Layers,
                        title = "Floating Control Overlay",
                        subtitle = "Required for Start, Stop, Calibrate & Debug HUD over game",
                        granted = permissions.overlayGranted,
                        actionLabel = if (permissions.overlayGranted) "Ready" else "Enable",
                        testTag = "perm_overlay_button",
                        onClick = onRequestOverlayPermission
                    )
                    PermissionRow(
                        icon = Icons.Rounded.TouchApp,
                        title = "Automated Touch & Drag Service",
                        subtitle = "Performs slider moves & striker slingshot pulls",
                        granted = permissions.accessibilityEnabled,
                        actionLabel = if (permissions.accessibilityEnabled) "Connected" else "Settings",
                        testTag = "perm_accessibility_button",
                        onClick = onOpenAccessibilitySettings
                    )
                    PermissionRow(
                        icon = Icons.Rounded.Cast,
                        title = "Live Screen Frame Reader",
                        subtitle = "Reads board corners, pucks, Queen & striker in real time",
                        granted = permissions.mediaProjectionActive,
                        actionLabel = if (permissions.mediaProjectionActive) "Active" else "Grant",
                        testTag = "perm_projection_button",
                        onClick = onRequestMediaProjection
                    )
                }
            }
        }

        // Learning & Shot Preservation Summary
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
                border = BorderStroke(1.dp, BorderEmerald),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoGraph, contentDescription = null, tint = TournamentEmerald)
                            Spacer(Modifier.width(8.dp))
                            Text("Training & Self-Improvement", style = MaterialTheme.typography.titleLarge)
                        }
                        Text(
                            text = "${learningSummary.winRatePercent}% Hit",
                            style = MaterialTheme.typography.labelLarge,
                            color = MintGlow
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatTile("Successful", "${learningSummary.successfulCount}", TournamentEmerald, Modifier.weight(1f))
                        StatTile("Failed", "${learningSummary.failedCount}", QueenCrimson, Modifier.weight(1f))
                        StatTile("Presets", "${learningSummary.presetCount}", CyanTelemetry, Modifier.weight(1f))
                    }

                    Text(
                        text = learningSummary.powerCalibrationNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MintGlow
                    )
                    if (mergeResult != null) {
                        Text(
                            text = "Data preservation active: ${mergeResult.totalRecords} total shots retained across updates (+${mergeResult.presetsAdded} presets merged).",
                            style = MaterialTheme.typography.labelSmall,
                            color = SageMuted
                        )
                    }
                }
            }
        }

        // Latest Before / After Check Card
        if (latestComparison != null) {
            item {
                val isGood = latestComparison.outcomeDetail.category == ShotOutcomeCategory.SUCCESS
                Card(
                    colors = CardDefaults.cardColors(containerColor = ElevatedGreenCard),
                    border = BorderStroke(1.dp, if (isGood) TournamentEmerald else BurnishedBrass),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Latest Before / After Verification",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isGood) TournamentEmerald else BurnishedBrass
                        )
                        Text(
                            text = "${latestComparison.outcomeDetail.label} • ${latestComparison.shotPlan.targetPiece.color.label} -> ${latestComparison.shotPlan.selectedPocket.label}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Travel: ${latestComparison.targetDirectionLabel} | Striker Move: ${String.format("%.3f", latestComparison.strikerDisplacementNorm)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SageMuted
                        )
                        if (latestComparison.errorNote.isNotBlank()) {
                            Text(
                                text = latestComparison.errorNote,
                                style = MaterialTheme.typography.bodyMedium,
                                color = QueenCrimson
                            )
                        }
                    }
                }
            }
        }

        // Turn Color & Overlay Debug Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
                border = BorderStroke(1.dp, BorderEmerald),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Target Turn & Overlay Controls", style = MaterialTheme.typography.titleLarge)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TurnTargetMode.entries.forEach { mode ->
                            val selected = tuning.turnTargetMode == mode
                            FilterChip(
                                selected = selected,
                                onClick = { onTurnModeChange(mode) },
                                label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EmeraldContainer,
                                    selectedLabelColor = MintGlow
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Visual Debug Markers & Aim Lines", style = MaterialTheme.typography.titleMedium)
                            Text("Shows detected piece rings, pocket cone & pull vector", style = MaterialTheme.typography.bodyMedium, color = SageMuted)
                        }
                        Switch(
                            checked = tuning.showDebugMarkers,
                            onCheckedChange = onToggleDebugMarkers
                        )
                    }

                    Column {
                        Text(
                            text = "Before / After Settle Wait: ${tuning.postShotWaitMs / 1000f}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = BurnishedBrass
                        )
                        Slider(
                            value = tuning.postShotWaitMs.toFloat(),
                            onValueChange = { onWaitTimeChange(it.toLong()) },
                            valueRange = 1800f..4500f,
                            colors = SliderDefaults.colors(
                                thumbColor = BurnishedBrass,
                                activeTrackColor = TournamentEmerald
                            )
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onNavigateToCalibration,
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, CyanTelemetry)
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, tint = CyanTelemetry)
                            Spacer(Modifier.width(6.dp))
                            Text("Calibrate Board", color = CyanTelemetry)
                        }
                        OutlinedButton(
                            onClick = onNavigateToTestLab,
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, TournamentEmerald)
                        ) {
                            Icon(Icons.Rounded.AdsClick, contentDescription = null, tint = TournamentEmerald)
                            Spacer(Modifier.width(6.dp))
                            Text("Open Test Lab", color = TournamentEmerald)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ElevatedGreenCard)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Rounded.CheckCircle else icon,
            contentDescription = title,
            tint = if (granted) TournamentEmerald else BurnishedBrass,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SageMuted)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.testTag(testTag),
            border = BorderStroke(1.dp, if (granted) TournamentEmerald else BurnishedBrass),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = actionLabel,
                color = if (granted) TournamentEmerald else BurnishedBrass,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ElevatedGreenCard)
            .padding(12.dp)
    ) {
        Text(text = value, style = MaterialTheme.typography.headlineMedium, color = accent)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = SageMuted)
    }
}
