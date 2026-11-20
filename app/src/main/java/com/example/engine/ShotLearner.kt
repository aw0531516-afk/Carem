package com.example.engine

import com.example.data.db.ShotRecordEntity
import com.example.model.PocketId
import com.example.model.ShotOutcomeCategory
import com.example.model.ShotOutcomeDetail
import kotlin.math.abs
import kotlin.math.exp

data class LearnedAdjustment(
    val powerDelta: Float,
    val cutBiasDeg: Float,
    val note: String
)

data class LearningSummary(
    val totalShots: Int = 0,
    val successfulCount: Int = 0,
    val failedCount: Int = 0,
    val userRecordedCount: Int = 0,
    val presetCount: Int = 0,
    val winRatePercent: Int = 0,
    val topFailurePattern: String = "No failures recorded yet",
    val powerCalibrationNote: String = "Using baseline physics model"
)

class ShotLearner {

    fun recommendAdjustment(
        history: List<ShotRecordEntity>,
        targetToPocketDist: Float,
        cutAngleDeg: Float,
        pocket: PocketId
    ): LearnedAdjustment {
        if (history.isEmpty()) {
            return LearnedAdjustment(0f, 0f, "Baseline physics (no history)")
        }

        // Find shots in a similar distance & cut-angle neighborhood
        var weightedSuccessPower = 0f
        var successWeightSum = 0f
        var tooLittleCount = 0
        var tooMuchCount = 0
        var missedLeftCount = 0
        var missedRightCount = 0
        var matchedShots = 0

        for (shot in history) {
            val distDiff = abs(shot.distanceToPocketBefore - targetToPocketDist)
            val angleDiff = abs(shot.cutAngleDeg - cutAngleDeg)
            if (distDiff > 0.18f || angleDiff > 24f) continue

            val pocketBonus = if (shot.selectedPocket == pocket.name) 1.35f else 0.85f
            val recencyBonus = if (shot.isPreset) 0.7f else 1.25f
            val weight = exp(-(distDiff * 10f + angleDiff * 0.08f)) * pocketBonus * recencyBonus
            matchedShots++

            if (shot.outcomeCategory == ShotOutcomeCategory.SUCCESS.name) {
                weightedSuccessPower += shot.appliedPower * weight
                successWeightSum += weight
            } else {
                when (shot.outcomeDetail) {
                    ShotOutcomeDetail.MISSED_TOO_LITTLE_FORCE.name -> tooLittleCount++
                    ShotOutcomeDetail.MISSED_TOO_MUCH_FORCE.name -> tooMuchCount++
                    ShotOutcomeDetail.MISSED_LEFT.name -> missedLeftCount++
                    ShotOutcomeDetail.MISSED_RIGHT.name -> missedRightCount++
                }
            }
        }

        if (matchedShots == 0) {
            return LearnedAdjustment(0f, 0f, "Baseline physics (new angle/distance)")
        }

        var powerDelta = 0f
        if (tooLittleCount > tooMuchCount) {
            powerDelta += minOf(0.12f, (tooLittleCount - tooMuchCount) * 0.04f)
        } else if (tooMuchCount > tooLittleCount) {
            powerDelta -= minOf(0.12f, (tooMuchCount - tooLittleCount) * 0.04f)
        }

        var cutBiasDeg = 0f
        if (missedLeftCount > missedRightCount) {
            cutBiasDeg += minOf(3.5f, (missedLeftCount - missedRightCount) * 1.1f)
        } else if (missedRightCount > missedLeftCount) {
            cutBiasDeg -= minOf(3.5f, (missedRightCount - missedLeftCount) * 1.1f)
        }

        val noteParts = mutableListOf<String>()
        if (successWeightSum > 0f) {
            val avgSuccessPow = weightedSuccessPower / successWeightSum
            noteParts.add("Ref ${(avgSuccessPow * 100).toInt()}% from $matchedShots similar shots")
        } else {
            noteParts.add("Adapted from $matchedShots past shots")
        }
        if (abs(powerDelta) >= 0.01f) {
            val sign = if (powerDelta > 0) "+" else ""
            noteParts.add("Power $sign${(powerDelta * 100).toInt()}%")
        }
        if (abs(cutBiasDeg) >= 0.5f) {
            val sign = if (cutBiasDeg > 0) "+" else ""
            noteParts.add("Angle $sign${String.format("%.1f", cutBiasDeg)}°")
        }

        return LearnedAdjustment(
            powerDelta = powerDelta,
            cutBiasDeg = cutBiasDeg,
            note = noteParts.joinToString(" • ")
        )
    }

    fun summarizeLearning(history: List<ShotRecordEntity>): LearningSummary {
        if (history.isEmpty()) return LearningSummary()
        val success = history.count { it.outcomeCategory == ShotOutcomeCategory.SUCCESS.name }
        val failed = history.count { it.outcomeCategory == ShotOutcomeCategory.FAILED.name }
        val userCount = history.count { !it.isPreset }
        val presetCount = history.count { it.isPreset }
        val rate = ((success.toFloat() / history.size) * 100f).toInt()

        val failureCounts = history
            .filter { it.outcomeCategory == ShotOutcomeCategory.FAILED.name }
            .groupingBy { it.outcomeDetail }
            .eachCount()

        val topFailure = failureCounts.maxByOrNull { it.value }?.let { (detail, count) ->
            val label = runCatching { ShotOutcomeDetail.valueOf(detail).label }.getOrDefault(detail)
            "$label ($count)"
        } ?: "No failures recorded yet"

        val tooWeak = failureCounts[ShotOutcomeDetail.MISSED_TOO_LITTLE_FORCE.name] ?: 0
        val tooStrong = failureCounts[ShotOutcomeDetail.MISSED_TOO_MUCH_FORCE.name] ?: 0
        val powerNote = when {
            tooWeak > tooStrong + 1 -> "Auto-boosting power (+${minOf(12, (tooWeak - tooStrong) * 4)}%) for short misses"
            tooStrong > tooWeak + 1 -> "Auto-softening power (-${minOf(12, (tooStrong - tooWeak) * 4)}%) for rebounds"
            success > 0 -> "Calibrated across $success successful & $failed failed shots"
            else -> "Collecting shots to refine power curve"
        }

        return LearningSummary(
            totalShots = history.size,
            successfulCount = success,
            failedCount = failed,
            userRecordedCount = userCount,
            presetCount = presetCount,
            winRatePercent = rate,
            topFailurePattern = topFailure,
            powerCalibrationNote = powerNote
        )
    }
}
