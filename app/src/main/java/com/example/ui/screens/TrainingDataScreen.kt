package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.UploadFile
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.db.DiagnosticLogEntity
import com.example.data.db.ShotRecordEntity
import com.example.engine.LearningSummary
import com.example.model.ShotOutcomeCategory
import com.example.model.ShotOutcomeDetail
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TrainingTab(val label: String) {
    ALL("All Shots"),
    SUCCESS("Successful"),
    FAILED("Failed"),
    ERRORS("Error Logs")
}

@Composable
fun TrainingDataScreen(
    allShots: List<ShotRecordEntity>,
    successfulShots: List<ShotRecordEntity>,
    failedShots: List<ShotRecordEntity>,
    diagnosticLogs: List<DiagnosticLogEntity>,
    learningSummary: LearningSummary,
    onCopyJson: (ShotOutcomeCategory?) -> Unit,
    onCopyReadableSummary: () -> Unit,
    onExportBackupUri: (android.net.Uri) -> Unit,
    onImportBackupUri: (android.net.Uri) -> Unit,
    onMergePresets: () -> Unit,
    onClearUserShots: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(TrainingTab.ALL) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { onExportBackupUri(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onImportBackupUri(it) } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianEmerald),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Shot Training & Data Archive", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Successful and failed shots are saved separately, merged with bundled presets across updates, and ready to copy or back up.",
                style = MaterialTheme.typography.bodyMedium,
                color = SageMuted
            )
        }

        // Copy & Export Actions Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
                border = BorderStroke(1.dp, BorderEmerald),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Copy & Backup (${learningSummary.successfulCount} Good • ${learningSummary.failedCount} Failed • ${learningSummary.presetCount} Presets)",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onCopyJson(null) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("copy_all_json_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TournamentEmerald,
                                contentColor = ObsidianEmerald
                            )
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy JSON", style = MaterialTheme.typography.labelLarge)
                        }

                        OutlinedButton(
                            onClick = onCopyReadableSummary,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("copy_readable_report_button"),
                            border = BorderStroke(1.dp, CyanTelemetry)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = CyanTelemetry)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Text", color = CyanTelemetry)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onCopyJson(ShotOutcomeCategory.SUCCESS) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("copy_success_json_button"),
                            border = BorderStroke(1.dp, TournamentEmerald)
                        ) {
                            Text("Copy Good Only", color = TournamentEmerald, style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { onCopyJson(ShotOutcomeCategory.FAILED) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("copy_failed_json_button"),
                            border = BorderStroke(1.dp, QueenCrimson)
                        ) {
                            Text("Copy Failed Only", color = QueenCrimson, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch("carrom_pilot_training_backup.json") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_backup_button"),
                            border = BorderStroke(1.dp, BurnishedBrass)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, tint = BurnishedBrass)
                            Spacer(Modifier.width(4.dp))
                            Text("Export File", color = BurnishedBrass, style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_backup_button"),
                            border = BorderStroke(1.dp, CyanTelemetry)
                        ) {
                            Icon(Icons.Rounded.UploadFile, contentDescription = null, tint = CyanTelemetry)
                            Spacer(Modifier.width(4.dp))
                            Text("Restore & Merge", color = CyanTelemetry, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onMergePresets,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("merge_presets_button"),
                            border = BorderStroke(1.dp, BorderEmerald)
                        ) {
                            Icon(Icons.Rounded.MergeType, contentDescription = null, tint = MintGlow)
                            Spacer(Modifier.width(4.dp))
                            Text("Merge Presets", color = MintGlow, style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = onClearUserShots,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("clear_user_shots_button"),
                            border = BorderStroke(1.dp, Color.Gray)
                        ) {
                            Text("Clear User Shots", color = SageMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Filter tabs
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrainingTab.entries.forEach { tab ->
                    val count = when (tab) {
                        TrainingTab.ALL -> allShots.size
                        TrainingTab.SUCCESS -> successfulShots.size
                        TrainingTab.FAILED -> failedShots.size
                        TrainingTab.ERRORS -> diagnosticLogs.size
                    }
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text("${tab.label} ($count)", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        if (selectedTab == TrainingTab.ERRORS) {
            if (diagnosticLogs.isEmpty()) {
                item {
                    EmptyStateCard("No error or obstruction alerts logged yet.")
                }
            } else {
                items(diagnosticLogs, key = { it.id }) { log ->
                    DiagnosticLogCard(log)
                }
            }
        } else {
            val displayed = when (selectedTab) {
                TrainingTab.ALL -> allShots
                TrainingTab.SUCCESS -> successfulShots
                TrainingTab.FAILED -> failedShots
                TrainingTab.ERRORS -> emptyList()
            }
            if (displayed.isEmpty()) {
                item {
                    EmptyStateCard("No shots recorded in this category yet. Use the Test Lab or live overlay to record shots.")
                }
            } else {
                items(displayed, key = { it.id }) { shot ->
                    ShotRecordCard(shot)
                }
            }
        }
    }
}

@Composable
private fun ShotRecordCard(shot: ShotRecordEntity) {
    val isSuccess = shot.outcomeCategory == ShotOutcomeCategory.SUCCESS.name
    val detailLabel = runCatching { ShotOutcomeDetail.valueOf(shot.outcomeDetail).label }
        .getOrDefault(shot.outcomeDetail)
    val accent = if (isSuccess) TournamentEmerald else QueenCrimson

    Card(
        colors = CardDefaults.cardColors(containerColor = ElevatedGreenCard),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = detailLabel + if (shot.isPreset) " (Preset)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
                Text(
                    text = formatTime(shot.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = SageMuted
                )
            }
            Text(
                text = "Target: ${shot.targetColor} -> Pocket: ${shot.selectedPocket}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Text(
                text = "Striker=(${fmt(shot.strikerStartX)}, ${fmt(shot.strikerStartY)}) | Target=(${fmt(shot.targetBeforeX)}, ${fmt(shot.targetBeforeY)})",
                style = MaterialTheme.typography.labelSmall,
                color = SageMuted
            )
            Text(
                text = "Aim=${String.format("%.1f", shot.aimAngleDeg)}° | Cut=${String.format("%.1f", shot.cutAngleDeg)}° | Force=${(shot.appliedPower * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = BurnishedBrass
            )
            Text(
                text = "Travel: ${fmt(shot.targetTravelDistNorm)} (${shot.targetTravelDirection}) | Striker Move: ${fmt(shot.strikerTravelDistNorm)}",
                style = MaterialTheme.typography.labelSmall,
                color = CyanTelemetry
            )
            if (shot.errorNote.isNotBlank()) {
                Text(
                    text = shot.errorNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = QueenCrimson
                )
            }
        }
    }
}

@Composable
private fun DiagnosticLogCard(log: DiagnosticLogEntity) {
    val accent = when (log.severity) {
        "ERROR" -> QueenCrimson
        "WARNING" -> BurnishedBrass
        else -> CyanTelemetry
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = ElevatedGreenCard),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(log.code, style = MaterialTheme.typography.labelLarge, color = accent)
                Text(formatTime(log.timestampMs), style = MaterialTheme.typography.labelSmall, color = SageMuted)
            }
            Text(log.message, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            if (log.detail.isNotBlank()) {
                Text(log.detail, style = MaterialTheme.typography.labelSmall, color = SageMuted)
            }
        }
    }
}

@Composable
private fun EmptyStateCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateGreenSurface),
        border = BorderStroke(1.dp, BorderEmerald),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = SageMuted,
            modifier = Modifier.padding(18.dp)
        )
    }
}

private fun fmt(v: Float): String = String.format(Locale.US, "%.3f", v)

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(Date(ts))
