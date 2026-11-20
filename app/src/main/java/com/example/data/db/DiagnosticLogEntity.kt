package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val severity: String, // "INFO", "WARNING", "ERROR"
    val code: String,     // e.g. "NO_MOVEMENT_ERROR", "POPUP_OBSTRUCTION", "INVALID_FRAME"
    val message: String,
    val detail: String
)
