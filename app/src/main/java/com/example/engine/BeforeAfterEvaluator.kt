package com.example.engine

import com.example.model.BeforeAfterComparison
import com.example.model.BoardDetectionResult
import com.example.model.DetectedPiece
import com.example.model.FrameValidity
import com.example.model.NormalizedPoint
import com.example.model.ShotOutcomeDetail
import com.example.model.ShotPlan
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class BeforeAfterEvaluator {

    fun compare(
        shotPlan: ShotPlan,
        before: BoardDetectionResult,
        after: BoardDetectionResult
    ): BeforeAfterComparison {
        val targetBefore = shotPlan.targetPiece.point
        val pocket = shotPlan.pocketPoint
        val strikerStart = before.striker?.point ?: shotPlan.strikerTargetPos
        val strikerAfter = after.striker?.point
        val strikerDisp = if (strikerAfter != null) strikerStart.distanceTo(strikerAfter) else 0f
        val distToPocketBefore = targetBefore.distanceTo(pocket)

        // Check if after-frame itself became invalid (e.g. popup blocked check)
        if (after.validity == FrameValidity.INVALID_NO_BOARD) {
            return BeforeAfterComparison(
                shotPlan = shotPlan,
                beforeResult = before,
                afterResult = after,
                strikerStart = strikerStart,
                strikerAfter = strikerAfter,
                strikerDisplacementNorm = strikerDisp,
                targetBefore = targetBefore,
                targetAfter = null,
                targetDisplacementNorm = 0f,
                targetDirectionLabel = "Unknown (Invalid After Frame)",
                distanceToPocketBefore = distToPocketBefore,
                distanceToPocketAfter = null,
                targetWasPocketed = false,
                strikerWasPocketed = false,
                outcomeDetail = ShotOutcomeDetail.INVALID_ABORTED,
                errorNote = after.validationDetail
            )
        }

        val sameColorBeforeCount = before.pieces.count { it.color == shotPlan.targetPiece.color }
        val sameColorAfter = after.pieces.filter { it.color == shotPlan.targetPiece.color }
        val sameColorAfterCount = sameColorAfter.size

        val anyPieceMoved = checkAnyBoardDisplacement(before.pieces, after.pieces)
        val strikerWasPocketed = after.striker == null && after.obstructionZones.any {
            it.contains("Foul", ignoreCase = true)
        }

        // 1. Check immediate Zero-Movement Error (touch/drag had no effect!)
        if (!anyPieceMoved && strikerDisp < 0.012f && sameColorAfterCount >= sameColorBeforeCount) {
            return BeforeAfterComparison(
                shotPlan = shotPlan,
                beforeResult = before,
                afterResult = after,
                strikerStart = strikerStart,
                strikerAfter = strikerAfter,
                strikerDisplacementNorm = strikerDisp,
                targetBefore = targetBefore,
                targetAfter = targetBefore,
                targetDisplacementNorm = 0f,
                targetDirectionLabel = "No movement",
                distanceToPocketBefore = distToPocketBefore,
                distanceToPocketAfter = distToPocketBefore,
                targetWasPocketed = false,
                strikerWasPocketed = false,
                outcomeDetail = ShotOutcomeDetail.NO_MOVEMENT_ERROR,
                errorNote = "Touch/drag had no effect — striker and all pucks remained stationary."
            )
        }

        // 2. Did the target puck disappear (count of its color dropped)?
        val matchedTargetAfter = findNearestMovedPiece(targetBefore, sameColorAfter, before.pieces)
        val targetPocketed = sameColorAfterCount < sameColorBeforeCount &&
            (matchedTargetAfter == null || matchedTargetAfter.point.distanceTo(targetBefore) > 0.03f)

        if (targetPocketed) {
            val outcome = if (strikerWasPocketed) {
                ShotOutcomeDetail.POCKETED_WITH_FOUL
            } else {
                ShotOutcomeDetail.POCKETED_CLEAN
            }
            return BeforeAfterComparison(
                shotPlan = shotPlan,
                beforeResult = before,
                afterResult = after,
                strikerStart = strikerStart,
                strikerAfter = strikerAfter,
                strikerDisplacementNorm = strikerDisp,
                targetBefore = targetBefore,
                targetAfter = null,
                targetDisplacementNorm = distToPocketBefore,
                targetDirectionLabel = "Pocketed into ${shotPlan.selectedPocket.label}",
                distanceToPocketBefore = distToPocketBefore,
                distanceToPocketAfter = 0f,
                targetWasPocketed = true,
                strikerWasPocketed = strikerWasPocketed,
                outcomeDetail = outcome,
                errorNote = if (strikerWasPocketed) "Foul: striker pocketed with target" else ""
            )
        }

        // 3. Target is still on the board — measure displacement & classify miss reason
        val afterPoint = matchedTargetAfter?.point ?: targetBefore
        val targetDisp = targetBefore.distanceTo(afterPoint)
        val distToPocketAfter = afterPoint.distanceTo(pocket)
        val directionLabel = describeTravelDirection(targetBefore, afterPoint, pocket)

        val outcomeDetail: ShotOutcomeDetail
        val note: String

        if (targetDisp < 0.012f) {
            // Striker moved, target didn't -> blocked or missed target completely
            outcomeDetail = ShotOutcomeDetail.BLOCKED_COLLISION
            note = "Target puck barely moved (${String.format("%.3f", targetDisp)}); striker deflected or missed impact."
        } else {
            // Compute lateral deviation vs pocket line
            val lineX = pocket.x - targetBefore.x
            val lineY = pocket.y - targetBefore.y
            val lineLen = hypot(lineX, lineY).coerceAtLeast(0.001f)
            val ux = lineX / lineLen
            val uy = lineY / lineLen

            val moveX = afterPoint.x - targetBefore.x
            val moveY = afterPoint.y - targetBefore.y
            val forwardProj = moveX * ux + moveY * uy
            val lateralCross = moveX * uy - moveY * ux // + = right of pocket line, - = left

            when {
                abs(lateralCross) > 0.045f && lateralCross < 0 -> {
                    outcomeDetail = ShotOutcomeDetail.MISSED_LEFT
                    note = "Target drifted left of ${shotPlan.selectedPocket.label} pocket line."
                }
                abs(lateralCross) > 0.045f && lateralCross > 0 -> {
                    outcomeDetail = ShotOutcomeDetail.MISSED_RIGHT
                    note = "Target drifted right of ${shotPlan.selectedPocket.label} pocket line."
                }
                forwardProj > 0 && distToPocketAfter < distToPocketBefore && distToPocketAfter < 0.14f -> {
                    outcomeDetail = ShotOutcomeDetail.MISSED_TOO_LITTLE_FORCE
                    note = "Stopped short near pocket (remaining dist ${String.format("%.3f", distToPocketAfter)})."
                }
                forwardProj > lineLen * 1.15f || distToPocketAfter > distToPocketBefore -> {
                    outcomeDetail = ShotOutcomeDetail.MISSED_TOO_MUCH_FORCE
                    note = "Overshot or rebounded away from pocket."
                }
                else -> {
                    outcomeDetail = ShotOutcomeDetail.MISSED_TOO_LITTLE_FORCE
                    note = "Short of pocket on path (${directionLabel})."
                }
            }
        }

        return BeforeAfterComparison(
            shotPlan = shotPlan,
            beforeResult = before,
            afterResult = after,
            strikerStart = strikerStart,
            strikerAfter = strikerAfter,
            strikerDisplacementNorm = strikerDisp,
            targetBefore = targetBefore,
            targetAfter = afterPoint,
            targetDisplacementNorm = targetDisp,
            targetDirectionLabel = directionLabel,
            distanceToPocketBefore = distToPocketBefore,
            distanceToPocketAfter = distToPocketAfter,
            targetWasPocketed = false,
            strikerWasPocketed = strikerWasPocketed,
            outcomeDetail = outcomeDetail,
            errorNote = note
        )
    }

    private fun checkAnyBoardDisplacement(
        before: List<DetectedPiece>,
        after: List<DetectedPiece>
    ): Boolean {
        if (before.size != after.size) return true
        for (b in before) {
            val nearest = after
                .filter { it.color == b.color }
                .minOfOrNull { it.point.distanceTo(b.point) } ?: 1f
            if (nearest > 0.015f) return true
        }
        return false
    }

    private fun findNearestMovedPiece(
        targetBefore: NormalizedPoint,
        sameColorAfter: List<DetectedPiece>,
        allBefore: List<DetectedPiece>
    ): DetectedPiece? {
        if (sameColorAfter.isEmpty()) return null
        // Prefer piece closest to targetBefore that isn't clearly an unmoved twin elsewhere
        return sameColorAfter.minByOrNull { candidate ->
            candidate.point.distanceTo(targetBefore)
        }
    }

    private fun describeTravelDirection(
        from: NormalizedPoint,
        to: NormalizedPoint,
        pocket: NormalizedPoint
    ): String {
        val dist = from.distanceTo(to)
        if (dist < 0.01f) return "Stationary"
        val dx = to.x - from.x
        val dy = to.y - from.y
        val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))

        val cardinal = when {
            angleDeg in -22.5..22.5 -> "Right"
            angleDeg in 22.5..67.5 -> "Back-Right"
            angleDeg in 67.5..112.5 -> "Backward"
            angleDeg in 112.5..157.5 -> "Back-Left"
            angleDeg > 157.5 || angleDeg < -157.5 -> "Left"
            angleDeg in -157.5..-112.5 -> "Forward-Left"
            angleDeg in -112.5..-67.5 -> "Forward"
            else -> "Forward-Right"
        }

        val nearPocket = to.distanceTo(pocket) < 0.12f
        return if (nearPocket) "$cardinal (Near Pocket)" else cardinal
    }
}
