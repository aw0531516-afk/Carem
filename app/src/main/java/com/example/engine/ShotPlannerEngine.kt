package com.example.engine

import com.example.data.db.ShotRecordEntity
import com.example.model.AutomationTuning
import com.example.model.BoardCalibration
import com.example.model.BoardDetectionResult
import com.example.model.DetectedPiece
import com.example.model.NormalizedPoint
import com.example.model.PieceColor
import com.example.model.PocketId
import com.example.model.ShotPlan
import com.example.model.TurnTargetMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ShotPlannerEngine(
    private val shotLearner: ShotLearner = ShotLearner()
) {

    fun planBestShot(
        detection: BoardDetectionResult,
        calibration: BoardCalibration,
        tuning: AutomationTuning,
        history: List<ShotRecordEntity>,
        forcedPieceId: String? = null
    ): ShotPlan? {
        if (!detection.validity.isActionable) return null
        val candidatePieces = selectEligibleTargets(detection, tuning, forcedPieceId)
        if (candidatePieces.isEmpty()) return null

        val allObstacles = detection.pieces
        val collisionRadius = (calibration.puckRadiusNorm + calibration.strikerRadiusNorm) * calibration.boardWidth * 0.92f
        val puckClearRadius = calibration.puckRadiusNorm * calibration.boardWidth * 1.88f
        val strikerSteps = 15

        var bestPlan: ShotPlan? = null

        for (piece in candidatePieces) {
            for ((pocketId, pocketPt) in calibration.allPockets()) {
                val pVecX = pocketPt.x - piece.normX
                val pVecY = pocketPt.y - piece.normY
                val targetToPocketDist = hypot(pVecX, pVecY)
                if (targetToPocketDist < 0.015f) continue

                val dirTPx = pVecX / targetToPocketDist
                val dirTPy = pVecY / targetToPocketDist

                // Ghost ball center where striker must hit target piece
                val hitOffset = (calibration.puckRadiusNorm + calibration.strikerRadiusNorm) * calibration.boardWidth * 0.94f
                val ghostX = piece.normX - dirTPx * hitOffset
                val ghostY = piece.normY - dirTPy * hitOffset

                // Check if ghost ball itself is outside board bounds
                if (ghostX < calibration.boardLeft + 0.03f || ghostX > calibration.boardRight - 0.03f ||
                    ghostY < calibration.boardTop + 0.03f || ghostY > calibration.boardBottom - 0.03f
                ) continue

                val ghostPos = NormalizedPoint(ghostX, ghostY)
                val targetToPocketClear = computePathClearance(
                    start = piece.point,
                    end = pocketPt,
                    obstacles = allObstacles,
                    ignoreId = piece.id,
                    clearanceRadius = puckClearRadius
                )
                if (targetToPocketClear < 0.25f) continue

                for (step in 0 until strikerSteps) {
                    val frac = step.toFloat() / (strikerSteps - 1)
                    val sx = calibration.baselineLeftX + (calibration.baselineRightX - calibration.baselineLeftX) * frac
                    val sy = calibration.strikerBaselineY
                    val strikerPos = NormalizedPoint(sx, sy)

                    // Avoid placing striker directly on top of another puck resting on the baseline
                    val strikerSpotBlocked = allObstacles.any { obs ->
                        obs.point.distanceTo(strikerPos) < collisionRadius * 1.05f
                    }
                    if (strikerSpotBlocked) continue

                    val sVecX = ghostX - sx
                    val sVecY = ghostY - sy
                    val strikerToGhostDist = hypot(sVecX, sVecY)
                    if (strikerToGhostDist < 0.03f) continue

                    val dirSGx = sVecX / strikerToGhostDist
                    val dirSGy = sVecY / strikerToGhostDist

                    // Cut angle between striker->ghost and target->pocket
                    val dot = (dirSGx * dirTPx + dirSGy * dirTPy).coerceIn(-1f, 1f)
                    val cutAngleDeg = (acos(dot) * 180.0 / PI).toFloat()
                    if (cutAngleDeg > 66f) continue

                    val strikerPathClear = computePathClearance(
                        start = strikerPos,
                        end = ghostPos,
                        obstacles = allObstacles,
                        ignoreId = piece.id,
                        clearanceRadius = collisionRadius
                    )
                    if (strikerPathClear < 0.30f) continue

                    // Base physics power from total distance & cut angle cosine compensation
                    val totalTravel = strikerToGhostDist + targetToPocketDist * 1.15f
                    val cosComp = (1f / cos(Math.toRadians(cutAngleDeg.toDouble())).toFloat().coerceAtLeast(0.45f))
                    val rawPower = (0.32f + totalTravel * 0.72f) * (0.85f + 0.15f * cosComp)

                    val learned = shotLearner.recommendAdjustment(
                        history = history,
                        targetToPocketDist = targetToPocketDist,
                        cutAngleDeg = cutAngleDeg,
                        pocket = pocketId
                    )

                    val finalPower = (rawPower + learned.powerDelta)
                        .coerceIn(tuning.minPowerScale, tuning.maxPowerScale)

                    val aimAngleDeg = (atan2(dirSGy, dirSGx) * 180.0 / PI).toFloat() + learned.cutBiasDeg
                    val adjustedRad = Math.toRadians(aimAngleDeg.toDouble())
                    val adjDirX = cos(adjustedRad).toFloat()
                    val adjDirY = sin(adjustedRad).toFloat()

                    val pullDist = calibration.maxPullDistanceNorm * finalPower
                    val pullEnd = NormalizedPoint(
                        x = (sx - adjDirX * pullDist).coerceIn(0.03f, 0.97f),
                        y = (sy - adjDirY * pullDist).coerceIn(0.05f, 0.95f)
                    )

                    // Score the shot: reward straight cuts, short pocket distance, clean path, Queen priority
                    val queenBonus = if (piece.color == PieceColor.QUEEN) 18f else 0f
                    val clearanceAvg = (targetToPocketClear + strikerPathClear) * 0.5f
                    val cutPenalty = cutAngleDeg * 0.95f
                    val distPenalty = targetToPocketDist * 45f + strikerToGhostDist * 18f
                    val scratchRiskPenalty = if (
                        pocketId in listOf(PocketId.TOP_LEFT, PocketId.TOP_RIGHT) && cutAngleDeg < 8f && targetToPocketDist < 0.14f
                    ) 4f else 0f

                    val score = 100f + queenBonus + clearanceAvg * 38f - cutPenalty - distPenalty - scratchRiskPenalty

                    if (bestPlan == null || score > bestPlan.planScore) {
                        bestPlan = ShotPlan(
                            targetPiece = piece,
                            selectedPocket = pocketId,
                            pocketPoint = pocketPt,
                            strikerTargetPos = strikerPos,
                            ghostImpactPos = ghostPos,
                            pullEndPos = pullEnd,
                            aimAngleDeg = aimAngleDeg,
                            cutAngleDeg = cutAngleDeg,
                            appliedPower = finalPower,
                            clearanceScore = clearanceAvg,
                            planScore = score,
                            learnedAdjustmentNote = learned.note
                        )
                    }
                }
            }
        }
        return bestPlan
    }

    private fun selectEligibleTargets(
        detection: BoardDetectionResult,
        tuning: AutomationTuning,
        forcedPieceId: String?
    ): List<DetectedPiece> {
        if (forcedPieceId != null) {
            return detection.pieces.filter { it.id == forcedPieceId }
        }

        val myColor = when (tuning.turnTargetMode) {
            TurnTargetMode.BLACK -> PieceColor.BLACK
            TurnTargetMode.WHITE -> PieceColor.WHITE
            TurnTargetMode.QUEEN -> PieceColor.QUEEN
            TurnTargetMode.AUTO -> detection.detectedPlayerColor ?: PieceColor.BLACK
        }

        val myPucks = detection.pieces.filter { it.color == myColor }
        val queens = detection.pieces.filter { it.color == PieceColor.QUEEN }

        return when {
            // Carrom rule observed in game: Queen must be pocketed before the last puck!
            queens.isNotEmpty() && myPucks.size <= 1 -> queens
            tuning.turnTargetMode == TurnTargetMode.QUEEN && queens.isNotEmpty() -> queens + myPucks
            queens.isNotEmpty() && myPucks.size >= 2 -> myPucks + queens
            myPucks.isNotEmpty() -> myPucks
            else -> detection.pieces.filter { it.color != PieceColor.STRIKER }
        }
    }

    private fun computePathClearance(
        start: NormalizedPoint,
        end: NormalizedPoint,
        obstacles: List<DetectedPiece>,
        ignoreId: String,
        clearanceRadius: Float
    ): Float {
        val segX = end.x - start.x
        val segY = end.y - start.y
        val segLenSq = segX * segX + segY * segY
        if (segLenSq < 1e-6f) return 0f

        var minMargin = 1.0f
        for (obs in obstacles) {
            if (obs.id == ignoreId || obs.color == PieceColor.STRIKER) continue
            val t = (((obs.normX - start.x) * segX + (obs.normY - start.y) * segY) / segLenSq)
                .coerceIn(0f, 1f)
            val projX = start.x + t * segX
            val projY = start.y + t * segY
            val dist = hypot(obs.normX - projX, obs.normY - projY)
            if (dist < clearanceRadius) {
                return 0f
            }
            val normalizedMargin = ((dist - clearanceRadius) / clearanceRadius).coerceIn(0f, 1f)
            if (normalizedMargin < minMargin) {
                minMargin = normalizedMargin
            }
        }
        return minMargin
    }
}
