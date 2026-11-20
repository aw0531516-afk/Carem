package com.example.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.data.db.CarromDatabase
import com.example.data.db.DiagnosticLogEntity
import com.example.data.db.ShotRecordEntity
import com.example.model.BeforeAfterComparison
import com.example.model.ShotOutcomeCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TrainingDataRepository(private val context: Context) {

    private val dao = CarromDatabase.getInstance(context).shotDao()
    private val archiveFile: File = File(context.filesDir, "carrom_training_archive.json")

    val allShotsFlow: Flow<List<ShotRecordEntity>> = dao.observeAllShots()
    val successfulShotsFlow: Flow<List<ShotRecordEntity>> = dao.observeSuccessfulShots()
    val failedShotsFlow: Flow<List<ShotRecordEntity>> = dao.observeFailedShots()
    val diagnosticLogsFlow: Flow<List<DiagnosticLogEntity>> = dao.observeDiagnosticLogs()

    suspend fun getAllShotsSnapshot(): List<ShotRecordEntity> = withContext(Dispatchers.IO) {
        dao.getAllShotsSnapshot()
    }

    /**
     * Preserves all previously saved user shots and merges any newly added presets
     * from the current app build, plus recovers from the JSON archive if needed.
     */
    suspend fun initializeAndMergeOnStartup(): MergeResult = withContext(Dispatchers.IO) {
        val versionCode = resolveAppVersionCode()
        var recoveredCount = 0

        // 1. Recover any records from internal JSON archive if they are missing in Room
        if (archiveFile.exists()) {
            val archiveContent = runCatching { archiveFile.readText() }.getOrNull()
            if (!archiveContent.isNullOrBlank()) {
                recoveredCount = mergeFromJsonInternal(archiveContent)
            }
        }

        // 2. Merge bundled preset shots that do not already exist by presetKey
        val existingPresets = dao.getExistingPresetKeys().toSet()
        val presetsToInsert = PresetTrainingData.bundledPresets(versionCode)
            .filter { it.presetKey != null && it.presetKey !in existingPresets }

        if (presetsToInsert.isNotEmpty()) {
            dao.insertShotsIfNew(presetsToInsert)
        }

        // 3. Refresh durable archive snapshot
        syncArchiveSnapshot()

        MergeResult(
            presetsAdded = presetsToInsert.size,
            recoveredFromArchive = recoveredCount,
            totalRecords = dao.getAllShotsSnapshot().size
        )
    }

    suspend fun recordComparisonShot(
        comparison: BeforeAfterComparison,
        profileName: String
    ): ShotRecordEntity = withContext(Dispatchers.IO) {
        val plan = comparison.shotPlan
        val pullPx = plan.strikerTargetPos.distanceTo(plan.pullEndPos) * 1000f
        val entity = ShotRecordEntity(
            recordUid = "shot_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            presetKey = null,
            isPreset = false,
            appVersionCode = resolveAppVersionCode(),
            timestampMs = System.currentTimeMillis(),
            outcomeCategory = comparison.outcomeDetail.category.name,
            outcomeDetail = comparison.outcomeDetail.name,
            targetColor = plan.targetPiece.color.name,
            targetBeforeX = comparison.targetBefore.x,
            targetBeforeY = comparison.targetBefore.y,
            targetAfterX = comparison.targetAfter?.x,
            targetAfterY = comparison.targetAfter?.y,
            selectedPocket = plan.selectedPocket.name,
            pocketX = plan.pocketPoint.x,
            pocketY = plan.pocketPoint.y,
            strikerStartX = comparison.strikerStart.x,
            strikerStartY = comparison.strikerStart.y,
            strikerAfterX = comparison.strikerAfter?.x,
            strikerAfterY = comparison.strikerAfter?.y,
            aimAngleDeg = plan.aimAngleDeg,
            cutAngleDeg = plan.cutAngleDeg,
            appliedPower = plan.appliedPower,
            pullDragDistancePx = pullPx,
            targetTravelDistNorm = comparison.targetDisplacementNorm,
            targetTravelDirection = comparison.targetDirectionLabel,
            distanceToPocketBefore = comparison.distanceToPocketBefore,
            distanceToPocketAfter = comparison.distanceToPocketAfter,
            strikerTravelDistNorm = comparison.strikerDisplacementNorm,
            wasBlocked = comparison.outcomeDetail.name == "BLOCKED_COLLISION",
            errorNote = comparison.errorNote,
            screenProfileName = profileName
        )
        dao.insertShotIfNew(entity)
        syncArchiveSnapshot()
        entity
    }

    suspend fun logDiagnostic(
        severity: String,
        code: String,
        message: String,
        detail: String = ""
    ) = withContext(Dispatchers.IO) {
        dao.insertDiagnosticLog(
            DiagnosticLogEntity(
                timestampMs = System.currentTimeMillis(),
                severity = severity,
                code = code,
                message = message,
                detail = detail
            )
        )
    }

    suspend fun clearUserShotsOnly() = withContext(Dispatchers.IO) {
        dao.clearUserShotsOnly()
        syncArchiveSnapshot()
    }

    suspend fun clearDiagnostics() = withContext(Dispatchers.IO) {
        dao.clearDiagnosticLogs()
    }

    suspend fun importAndMergeJson(jsonContent: String): Int = withContext(Dispatchers.IO) {
        val merged = mergeFromJsonInternal(jsonContent)
        syncArchiveSnapshot()
        merged
    }

    fun formatShotsAsCleanJson(
        shots: List<ShotRecordEntity>,
        filterCategory: ShotOutcomeCategory? = null
    ): String {
        val filtered = if (filterCategory == null) shots else {
            shots.filter { it.outcomeCategory == filterCategory.name }
        }
        val successful = filtered.filter { it.outcomeCategory == ShotOutcomeCategory.SUCCESS.name }
        val failed = filtered.filter { it.outcomeCategory == ShotOutcomeCategory.FAILED.name }

        val root = JSONObject().apply {
            put("exportFormat", "carrom_pilot_training_v1")
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("appVersionCode", resolveAppVersionCode())
            put("summary", JSONObject().apply {
                put("totalShots", filtered.size)
                put("successfulShotsCount", successful.size)
                put("failedShotsCount", failed.size)
            })
            put("successfulShots", JSONArray().apply {
                successful.forEach { put(shotToJson(it)) }
            })
            put("failedShots", JSONArray().apply {
                failed.forEach { put(shotToJson(it)) }
            })
        }
        return root.toString(2)
    }

    fun formatShotsAsReadableSummary(shots: List<ShotRecordEntity>): String {
        val successful = shots.filter { it.outcomeCategory == ShotOutcomeCategory.SUCCESS.name }
        val failed = shots.filter { it.outcomeCategory == ShotOutcomeCategory.FAILED.name }
        val sb = StringBuilder()
        sb.appendLine("=== CARROM PILOT TRAINING REPORT ===")
        sb.appendLine("Total: ${shots.size} | Successful: ${successful.size} | Failed: ${failed.size}")
        sb.appendLine()
        sb.appendLine("--- SUCCESSFUL SHOTS (${successful.size}) ---")
        successful.forEachIndexed { idx, s ->
            sb.appendLine(
                "${idx + 1}. [${s.targetColor} -> ${s.selectedPocket}] " +
                    "Striker=(${fmt(s.strikerStartX)},${fmt(s.strikerStartY)}) " +
                    "Target=(${fmt(s.targetBeforeX)},${fmt(s.targetBeforeY)}) " +
                    "Angle=${fmt1(s.aimAngleDeg)}deg Cut=${fmt1(s.cutAngleDeg)}deg " +
                    "Power=${(s.appliedPower * 100).toInt()}% " +
                    "Travel=${fmt(s.targetTravelDistNorm)} (${s.targetTravelDirection})"
            )
        }
        sb.appendLine()
        sb.appendLine("--- FAILED SHOTS (${failed.size}) ---")
        failed.forEachIndexed { idx, s ->
            sb.appendLine(
                "${idx + 1}. [${s.outcomeDetail}] ${s.targetColor} -> ${s.selectedPocket} | " +
                    "Angle=${fmt1(s.aimAngleDeg)}deg Cut=${fmt1(s.cutAngleDeg)}deg " +
                    "Power=${(s.appliedPower * 100).toInt()}% | " +
                    "TargetMove=${fmt(s.targetTravelDistNorm)} (${s.targetTravelDirection}) | " +
                    "StrikerMove=${fmt(s.strikerTravelDistNorm)} | Note=${s.errorNote}"
            )
        }
        return sb.toString()
    }

    private suspend fun syncArchiveSnapshot() {
        val allShots = dao.getAllShotsSnapshot()
        val json = formatShotsAsCleanJson(allShots, filterCategory = null)
        runCatching { archiveFile.writeText(json) }
    }

    private suspend fun mergeFromJsonInternal(jsonContent: String): Int {
        val existingUids = dao.getExistingRecordUids().toMutableSet()
        val existingPresetKeys = dao.getExistingPresetKeys().toMutableSet()
        val toInsert = mutableListOf<ShotRecordEntity>()

        runCatching {
            val root = JSONObject(jsonContent)
            val arrays = listOfNotNull(
                root.optJSONArray("successfulShots"),
                root.optJSONArray("failedShots"),
                root.optJSONArray("shots")
            )
            arrays.forEach { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val entity = jsonToShot(obj)
                    val presetConflict = entity.presetKey != null && entity.presetKey in existingPresetKeys
                    if (entity.recordUid !in existingUids && !presetConflict) {
                        toInsert.add(entity)
                        existingUids.add(entity.recordUid)
                        entity.presetKey?.let { existingPresetKeys.add(it) }
                    }
                }
            }
        }

        if (toInsert.isNotEmpty()) {
            dao.insertShotsIfNew(toInsert)
        }
        return toInsert.size
    }

    private fun shotToJson(s: ShotRecordEntity): JSONObject = JSONObject().apply {
        put("recordUid", s.recordUid)
        if (s.presetKey != null) put("presetKey", s.presetKey)
        put("isPreset", s.isPreset)
        put("appVersionCode", s.appVersionCode)
        put("timestampMs", s.timestampMs)
        put("outcomeCategory", s.outcomeCategory)
        put("outcomeDetail", s.outcomeDetail)
        put("targetColor", s.targetColor)
        put("targetBefore", JSONObject().apply {
            put("x", s.targetBeforeX.toDouble())
            put("y", s.targetBeforeY.toDouble())
        })
        if (s.targetAfterX != null && s.targetAfterY != null) {
            put("targetAfter", JSONObject().apply {
                put("x", s.targetAfterX.toDouble())
                put("y", s.targetAfterY.toDouble())
            })
        } else {
            put("targetAfter", JSONObject.NULL)
        }
        put("selectedPocket", s.selectedPocket)
        put("pocketPos", JSONObject().apply {
            put("x", s.pocketX.toDouble())
            put("y", s.pocketY.toDouble())
        })
        put("strikerStart", JSONObject().apply {
            put("x", s.strikerStartX.toDouble())
            put("y", s.strikerStartY.toDouble())
        })
        if (s.strikerAfterX != null && s.strikerAfterY != null) {
            put("strikerAfter", JSONObject().apply {
                put("x", s.strikerAfterX.toDouble())
                put("y", s.strikerAfterY.toDouble())
            })
        } else {
            put("strikerAfter", JSONObject.NULL)
        }
        put("aimAngleDeg", s.aimAngleDeg.toDouble())
        put("cutAngleDeg", s.cutAngleDeg.toDouble())
        put("appliedPower", s.appliedPower.toDouble())
        put("pullDragDistancePx", s.pullDragDistancePx.toDouble())
        put("targetTravelDistNorm", s.targetTravelDistNorm.toDouble())
        put("targetTravelDirection", s.targetTravelDirection)
        put("distanceToPocketBefore", s.distanceToPocketBefore.toDouble())
        if (s.distanceToPocketAfter != null) {
            put("distanceToPocketAfter", s.distanceToPocketAfter.toDouble())
        } else {
            put("distanceToPocketAfter", JSONObject.NULL)
        }
        put("strikerTravelDistNorm", s.strikerTravelDistNorm.toDouble())
        put("wasBlocked", s.wasBlocked)
        put("errorNote", s.errorNote)
        put("screenProfileName", s.screenProfileName)
    }

    private fun jsonToShot(obj: JSONObject): ShotRecordEntity {
        val tb = obj.optJSONObject("targetBefore")
        val ta = obj.optJSONObject("targetAfter")
        val p = obj.optJSONObject("pocketPos")
        val ss = obj.optJSONObject("strikerStart")
        val sa = obj.optJSONObject("strikerAfter")
        val uid = obj.optString("recordUid", "").ifBlank {
            "imported_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        }
        val presetKey = obj.optString("presetKey", "").ifBlank { null }
        return ShotRecordEntity(
            recordUid = uid,
            presetKey = presetKey,
            isPreset = obj.optBoolean("isPreset", false),
            appVersionCode = obj.optInt("appVersionCode", 1),
            timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
            outcomeCategory = obj.optString("outcomeCategory", "FAILED"),
            outcomeDetail = obj.optString("outcomeDetail", "MISSED_LEFT"),
            targetColor = obj.optString("targetColor", "BLACK"),
            targetBeforeX = (tb?.optDouble("x", 0.5) ?: obj.optDouble("targetBeforeX", 0.5)).toFloat(),
            targetBeforeY = (tb?.optDouble("y", 0.5) ?: obj.optDouble("targetBeforeY", 0.5)).toFloat(),
            targetAfterX = ta?.let { it.optDouble("x").toFloat() },
            targetAfterY = ta?.let { it.optDouble("y").toFloat() },
            selectedPocket = obj.optString("selectedPocket", "TOP_RIGHT"),
            pocketX = (p?.optDouble("x", 0.9) ?: obj.optDouble("pocketX", 0.9)).toFloat(),
            pocketY = (p?.optDouble("y", 0.3) ?: obj.optDouble("pocketY", 0.3)).toFloat(),
            strikerStartX = (ss?.optDouble("x", 0.5) ?: obj.optDouble("strikerStartX", 0.5)).toFloat(),
            strikerStartY = (ss?.optDouble("y", 0.665) ?: obj.optDouble("strikerStartY", 0.665)).toFloat(),
            strikerAfterX = sa?.let { it.optDouble("x").toFloat() },
            strikerAfterY = sa?.let { it.optDouble("y").toFloat() },
            aimAngleDeg = obj.optDouble("aimAngleDeg", -45.0).toFloat(),
            cutAngleDeg = obj.optDouble("cutAngleDeg", 10.0).toFloat(),
            appliedPower = obj.optDouble("appliedPower", 0.65).toFloat(),
            pullDragDistancePx = obj.optDouble("pullDragDistancePx", 200.0).toFloat(),
            targetTravelDistNorm = obj.optDouble("targetTravelDistNorm", 0.0).toFloat(),
            targetTravelDirection = obj.optString("targetTravelDirection", "Unknown"),
            distanceToPocketBefore = obj.optDouble("distanceToPocketBefore", 0.2).toFloat(),
            distanceToPocketAfter = if (obj.isNull("distanceToPocketAfter")) null else obj.optDouble("distanceToPocketAfter").toFloat(),
            strikerTravelDistNorm = obj.optDouble("strikerTravelDistNorm", 0.0).toFloat(),
            wasBlocked = obj.optBoolean("wasBlocked", false),
            errorNote = obj.optString("errorNote", ""),
            screenProfileName = obj.optString("screenProfileName", "Imported")
        )
    }

    private fun resolveAppVersionCode(): Int {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }.getOrDefault(1)
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.3f", v)
    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)
}

data class MergeResult(
    val presetsAdded: Int,
    val recoveredFromArchive: Int,
    val totalRecords: Int
)
