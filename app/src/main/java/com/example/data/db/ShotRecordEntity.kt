package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shot_records",
    indices = [
        Index(value = ["recordUid"], unique = true),
        Index(value = ["presetKey"]),
        Index(value = ["outcomeCategory"])
    ]
)
data class ShotRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordUid: String,
    val presetKey: String? = null,
    val isPreset: Boolean = false,
    val appVersionCode: Int = 1,
    val timestampMs: Long,
    val outcomeCategory: String, // "SUCCESS" or "FAILED"
    val outcomeDetail: String,   // ShotOutcomeDetail.name
    val targetColor: String,     // PieceColor.name
    val targetBeforeX: Float,
    val targetBeforeY: Float,
    val targetAfterX: Float?,
    val targetAfterY: Float?,
    val selectedPocket: String,  // PocketId.name
    val pocketX: Float,
    val pocketY: Float,
    val strikerStartX: Float,
    val strikerStartY: Float,
    val strikerAfterX: Float?,
    val strikerAfterY: Float?,
    val aimAngleDeg: Float,
    val cutAngleDeg: Float,
    val appliedPower: Float,
    val pullDragDistancePx: Float,
    val targetTravelDistNorm: Float,
    val targetTravelDirection: String,
    val distanceToPocketBefore: Float,
    val distanceToPocketAfter: Float?,
    val strikerTravelDistNorm: Float,
    val wasBlocked: Boolean,
    val errorNote: String,
    val screenProfileName: String
)
