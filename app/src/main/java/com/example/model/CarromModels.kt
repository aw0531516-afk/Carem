package com.example.model

import kotlin.math.hypot

data class NormalizedPoint(
    val x: Float,
    val y: Float
) {
    fun distanceTo(other: NormalizedPoint): Float = hypot(x - other.x, y - other.y)

    fun clamped(): NormalizedPoint = NormalizedPoint(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f)
    )
}

enum class PocketId(val label: String) {
    TOP_LEFT("Top-Left"),
    TOP_RIGHT("Top-Right"),
    BOTTOM_LEFT("Bottom-Left"),
    BOTTOM_RIGHT("Bottom-Right")
}

enum class PieceColor(val label: String) {
    WHITE("White"),
    BLACK("Black"),
    QUEEN("Queen (Red)"),
    STRIKER("Striker")
}

enum class TurnTargetMode(val label: String) {
    AUTO("Auto Detect"),
    BLACK("Black"),
    WHITE("White"),
    QUEEN("Queen First")
}

data class BoardCalibration(
    val profileName: String = "Default Yalla Portrait",
    val topLeft: NormalizedPoint = NormalizedPoint(0.028f, 0.278f),
    val topRight: NormalizedPoint = NormalizedPoint(0.972f, 0.278f),
    val bottomLeft: NormalizedPoint = NormalizedPoint(0.028f, 0.728f),
    val bottomRight: NormalizedPoint = NormalizedPoint(0.972f, 0.728f),
    val pocketTopLeft: NormalizedPoint = NormalizedPoint(0.088f, 0.312f),
    val pocketTopRight: NormalizedPoint = NormalizedPoint(0.912f, 0.312f),
    val pocketBottomLeft: NormalizedPoint = NormalizedPoint(0.088f, 0.695f),
    val pocketBottomRight: NormalizedPoint = NormalizedPoint(0.912f, 0.695f),
    val pocketRadiusNorm: Float = 0.044f,
    val baselineLeftX: Float = 0.228f,
    val baselineRightX: Float = 0.772f,
    val strikerBaselineY: Float = 0.665f,
    val sliderLeftX: Float = 0.205f,
    val sliderRightX: Float = 0.795f,
    val sliderY: Float = 0.798f,
    val puckRadiusNorm: Float = 0.027f,
    val strikerRadiusNorm: Float = 0.036f,
    val screenWidthPx: Int = 1080,
    val screenHeightPx: Int = 2400,
    val maxPullDistanceNorm: Float = 0.145f,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val boardLeft: Float get() = minOf(topLeft.x, bottomLeft.x)
    val boardRight: Float get() = maxOf(topRight.x, bottomRight.x)
    val boardTop: Float get() = minOf(topLeft.y, topRight.y)
    val boardBottom: Float get() = maxOf(bottomLeft.y, bottomRight.y)
    val boardWidth: Float get() = (boardRight - boardLeft).coerceAtLeast(0.1f)
    val boardHeight: Float get() = (boardBottom - boardTop).coerceAtLeast(0.1f)

    fun pocketPoint(id: PocketId): NormalizedPoint = when (id) {
        PocketId.TOP_LEFT -> pocketTopLeft
        PocketId.TOP_RIGHT -> pocketTopRight
        PocketId.BOTTOM_LEFT -> pocketBottomLeft
        PocketId.BOTTOM_RIGHT -> pocketBottomRight
    }

    fun allPockets(): List<Pair<PocketId, NormalizedPoint>> = listOf(
        PocketId.TOP_LEFT to pocketTopLeft,
        PocketId.TOP_RIGHT to pocketTopRight,
        PocketId.BOTTOM_LEFT to pocketBottomLeft,
        PocketId.BOTTOM_RIGHT to pocketBottomRight
    )
}

data class DetectedPiece(
    val id: String,
    val color: PieceColor,
    val normX: Float,
    val normY: Float,
    val radiusNorm: Float,
    val confidence: Float
) {
    val point: NormalizedPoint get() = NormalizedPoint(normX, normY)
}

enum class FrameValidity(val label: String, val isActionable: Boolean) {
    VALID("Board Ready", true),
    INVALID_NO_BOARD("No Carrom Board Visible", false),
    INVALID_OBSTRUCTION("Pop-up / Obstruction Detected", false),
    INVALID_UNREALISTIC_COUNT("Unrealistic Piece Count", false),
    INVALID_NO_STRIKER("Striker Not Detected on Baseline", false),
    INVALID_MOVING("Pieces Still Moving", false),
    NOT_YOUR_TURN("Waiting for Opponent Turn", false)
}

data class BoardDetectionResult(
    val timestampMs: Long = System.currentTimeMillis(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val pieces: List<DetectedPiece> = emptyList(),
    val striker: DetectedPiece? = null,
    val whiteCount: Int = 0,
    val blackCount: Int = 0,
    val queenCount: Int = 0,
    val detectedPlayerColor: PieceColor? = null,
    val isPlayerTurnDetected: Boolean = true,
    val validity: FrameValidity = FrameValidity.INVALID_NO_BOARD,
    val validationDetail: String = "Waiting for frame",
    val obstructionZones: List<String> = emptyList(),
    val boardBrightness: Float = 0f
) {
    val totalPucks: Int get() = whiteCount + blackCount + queenCount
}

data class ShotPlan(
    val targetPiece: DetectedPiece,
    val selectedPocket: PocketId,
    val pocketPoint: NormalizedPoint,
    val strikerTargetPos: NormalizedPoint,
    val ghostImpactPos: NormalizedPoint,
    val pullEndPos: NormalizedPoint,
    val aimAngleDeg: Float,
    val cutAngleDeg: Float,
    val appliedPower: Float,
    val clearanceScore: Float,
    val planScore: Float,
    val learnedAdjustmentNote: String = "Baseline physics"
)

enum class ShotOutcomeCategory(val label: String) {
    SUCCESS("Successful Shot"),
    FAILED("Failed Shot")
}

enum class ShotOutcomeDetail(val label: String, val category: ShotOutcomeCategory) {
    POCKETED_CLEAN("Pocketed Cleanly", ShotOutcomeCategory.SUCCESS),
    POCKETED_WITH_FOUL("Pocketed With Foul", ShotOutcomeCategory.FAILED),
    MISSED_TOO_LITTLE_FORCE("Missed - Too Little Force", ShotOutcomeCategory.FAILED),
    MISSED_TOO_MUCH_FORCE("Missed - Too Much Force", ShotOutcomeCategory.FAILED),
    MISSED_LEFT("Missed Left of Pocket", ShotOutcomeCategory.FAILED),
    MISSED_RIGHT("Missed Right of Pocket", ShotOutcomeCategory.FAILED),
    BLOCKED_COLLISION("Blocked by Other Piece", ShotOutcomeCategory.FAILED),
    NO_MOVEMENT_ERROR("No Movement Error (Touch No Effect)", ShotOutcomeCategory.FAILED),
    SLIDER_NO_EFFECT("Slider Move Had No Effect", ShotOutcomeCategory.FAILED),
    INVALID_ABORTED("Aborted Due to Invalid Frame", ShotOutcomeCategory.FAILED)
}

data class BeforeAfterComparison(
    val shotPlan: ShotPlan,
    val beforeResult: BoardDetectionResult,
    val afterResult: BoardDetectionResult,
    val strikerStart: NormalizedPoint,
    val strikerAfter: NormalizedPoint?,
    val strikerDisplacementNorm: Float,
    val targetBefore: NormalizedPoint,
    val targetAfter: NormalizedPoint?,
    val targetDisplacementNorm: Float,
    val targetDirectionLabel: String,
    val distanceToPocketBefore: Float,
    val distanceToPocketAfter: Float?,
    val targetWasPocketed: Boolean,
    val strikerWasPocketed: Boolean,
    val outcomeDetail: ShotOutcomeDetail,
    val errorNote: String = ""
)

enum class AutomationPhase(val label: String) {
    IDLE("Idle"),
    OVERLAY_READY("Overlay Ready"),
    SCANNING("Scanning Live Frame"),
    SETTLING("Waiting for Pieces to Settle"),
    SLIDING_STRIKER("Positioning Striker"),
    AIMING_AND_SHOOTING("Executing Shot"),
    WAITING_AFTER_SHOT("Verifying Before / After"),
    PAUSED("Paused"),
    ALERT_HOLD("Alert: Check Obstruction / Error")
}

data class CalibrationHandleEdit(
    val selectedHandle: CalibrationHandle = CalibrationHandle.NONE
)

enum class CalibrationHandle(val label: String) {
    NONE("None"),
    BOARD_TOP_LEFT("Board Top-Left"),
    BOARD_TOP_RIGHT("Board Top-Right"),
    BOARD_BOTTOM_LEFT("Board Bottom-Left"),
    BOARD_BOTTOM_RIGHT("Board Bottom-Right"),
    POCKET_TOP_LEFT("Top-Left Pocket"),
    POCKET_TOP_RIGHT("Top-Right Pocket"),
    POCKET_BOTTOM_LEFT("Bottom-Left Pocket"),
    POCKET_BOTTOM_RIGHT("Bottom-Right Pocket"),
    BASELINE_LEFT("Baseline Left"),
    BASELINE_RIGHT("Baseline Right"),
    SLIDER_LEFT("Slider Left"),
    SLIDER_RIGHT("Slider Right")
}

data class AutomationTuning(
    val turnTargetMode: TurnTargetMode = TurnTargetMode.AUTO,
    val showDebugMarkers: Boolean = true,
    val showAimingLines: Boolean = true,
    val showBeforeAfterGhost: Boolean = true,
    val autoPlayEnabled: Boolean = false,
    val postShotWaitMs: Long = 2800L,
    val minPowerScale: Float = 0.28f,
    val maxPowerScale: Float = 0.96f,
    val customStrikerHueMin: Float = 72f,
    val customStrikerHueMax: Float = 168f
)
