package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AutomationTuning
import com.example.model.BoardCalibration
import com.example.model.NormalizedPoint
import com.example.model.TurnTargetMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carrom_pilot_calibration"
)

class CalibrationStore(private val context: Context) {

    private object Keys {
        val ACTIVE_CALIBRATION_JSON = stringPreferencesKey("active_calibration_json")
        val SAVED_PROFILES_JSON = stringPreferencesKey("saved_profiles_json")
        val TURN_TARGET_MODE = stringPreferencesKey("turn_target_mode")
        val SHOW_DEBUG_MARKERS = booleanPreferencesKey("show_debug_markers")
        val SHOW_AIMING_LINES = booleanPreferencesKey("show_aiming_lines")
        val SHOW_BEFORE_AFTER_GHOST = booleanPreferencesKey("show_before_after_ghost")
        val POST_SHOT_WAIT_MS = longPreferencesKey("post_shot_wait_ms")
        val MIN_POWER_SCALE = floatPreferencesKey("min_power_scale")
        val MAX_POWER_SCALE = floatPreferencesKey("max_power_scale")
        val STRIKER_HUE_MIN = floatPreferencesKey("striker_hue_min")
        val STRIKER_HUE_MAX = floatPreferencesKey("striker_hue_max")
    }

    val activeCalibrationFlow: Flow<BoardCalibration> =
        context.calibrationDataStore.data.map { prefs ->
            val raw = prefs[Keys.ACTIVE_CALIBRATION_JSON]
            if (raw.isNullOrBlank()) BoardCalibration() else decodeCalibration(raw)
        }

    val savedProfilesFlow: Flow<List<BoardCalibration>> =
        context.calibrationDataStore.data.map { prefs ->
            val raw = prefs[Keys.SAVED_PROFILES_JSON]
            if (raw.isNullOrBlank()) listOf(BoardCalibration()) else decodeProfiles(raw)
        }

    val tuningFlow: Flow<AutomationTuning> =
        context.calibrationDataStore.data.map { prefs ->
            val modeName = prefs[Keys.TURN_TARGET_MODE] ?: TurnTargetMode.AUTO.name
            val mode = runCatching { TurnTargetMode.valueOf(modeName) }.getOrDefault(TurnTargetMode.AUTO)
            AutomationTuning(
                turnTargetMode = mode,
                showDebugMarkers = prefs[Keys.SHOW_DEBUG_MARKERS] ?: true,
                showAimingLines = prefs[Keys.SHOW_AIMING_LINES] ?: true,
                showBeforeAfterGhost = prefs[Keys.SHOW_BEFORE_AFTER_GHOST] ?: true,
                postShotWaitMs = prefs[Keys.POST_SHOT_WAIT_MS] ?: 2800L,
                minPowerScale = prefs[Keys.MIN_POWER_SCALE] ?: 0.28f,
                maxPowerScale = prefs[Keys.MAX_POWER_SCALE] ?: 0.96f,
                customStrikerHueMin = prefs[Keys.STRIKER_HUE_MIN] ?: 72f,
                customStrikerHueMax = prefs[Keys.STRIKER_HUE_MAX] ?: 168f
            )
        }

    suspend fun getActiveCalibration(): BoardCalibration = activeCalibrationFlow.first()

    suspend fun saveCalibration(calibration: BoardCalibration) {
        val updated = calibration.copy(updatedAtMs = System.currentTimeMillis())
        context.calibrationDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_CALIBRATION_JSON] = encodeCalibration(updated).toString()
            val existing = prefs[Keys.SAVED_PROFILES_JSON]?.let { decodeProfiles(it) }.orEmpty()
            val merged = existing
                .filterNot { it.profileName == updated.profileName }
                .plus(updated)
            prefs[Keys.SAVED_PROFILES_JSON] = encodeProfiles(merged)
        }
    }

    suspend fun selectProfile(profileName: String) {
        val profiles = savedProfilesFlow.first()
        val match = profiles.firstOrNull { it.profileName == profileName } ?: return
        context.calibrationDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_CALIBRATION_JSON] = encodeCalibration(match).toString()
        }
    }

    suspend fun updateTuning(transform: (AutomationTuning) -> AutomationTuning) {
        val current = tuningFlow.first()
        val next = transform(current)
        context.calibrationDataStore.edit { prefs ->
            prefs[Keys.TURN_TARGET_MODE] = next.turnTargetMode.name
            prefs[Keys.SHOW_DEBUG_MARKERS] = next.showDebugMarkers
            prefs[Keys.SHOW_AIMING_LINES] = next.showAimingLines
            prefs[Keys.SHOW_BEFORE_AFTER_GHOST] = next.showBeforeAfterGhost
            prefs[Keys.POST_SHOT_WAIT_MS] = next.postShotWaitMs
            prefs[Keys.MIN_POWER_SCALE] = next.minPowerScale
            prefs[Keys.MAX_POWER_SCALE] = next.maxPowerScale
            prefs[Keys.STRIKER_HUE_MIN] = next.customStrikerHueMin
            prefs[Keys.STRIKER_HUE_MAX] = next.customStrikerHueMax
        }
    }

    private fun encodeProfiles(list: List<BoardCalibration>): String {
        val arr = JSONArray()
        list.forEach { arr.put(encodeCalibration(it)) }
        return arr.toString()
    }

    private fun decodeProfiles(json: String): List<BoardCalibration> {
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    add(decodeCalibrationObj(arr.getJSONObject(i)))
                }
            }.ifEmpty { listOf(BoardCalibration()) }
        }.getOrDefault(listOf(BoardCalibration()))
    }

    private fun encodeCalibration(c: BoardCalibration): JSONObject {
        return JSONObject().apply {
            put("profileName", c.profileName)
            put("topLeftX", c.topLeft.x.toDouble())
            put("topLeftY", c.topLeft.y.toDouble())
            put("topRightX", c.topRight.x.toDouble())
            put("topRightY", c.topRight.y.toDouble())
            put("bottomLeftX", c.bottomLeft.x.toDouble())
            put("bottomLeftY", c.bottomLeft.y.toDouble())
            put("bottomRightX", c.bottomRight.x.toDouble())
            put("bottomRightY", c.bottomRight.y.toDouble())
            put("pocketTLX", c.pocketTopLeft.x.toDouble())
            put("pocketTLY", c.pocketTopLeft.y.toDouble())
            put("pocketTRX", c.pocketTopRight.x.toDouble())
            put("pocketTRY", c.pocketTopRight.y.toDouble())
            put("pocketBLX", c.pocketBottomLeft.x.toDouble())
            put("pocketBLY", c.pocketBottomLeft.y.toDouble())
            put("pocketBRX", c.pocketBottomRight.x.toDouble())
            put("pocketBRY", c.pocketBottomRight.y.toDouble())
            put("pocketRadiusNorm", c.pocketRadiusNorm.toDouble())
            put("baselineLeftX", c.baselineLeftX.toDouble())
            put("baselineRightX", c.baselineRightX.toDouble())
            put("strikerBaselineY", c.strikerBaselineY.toDouble())
            put("sliderLeftX", c.sliderLeftX.toDouble())
            put("sliderRightX", c.sliderRightX.toDouble())
            put("sliderY", c.sliderY.toDouble())
            put("puckRadiusNorm", c.puckRadiusNorm.toDouble())
            put("strikerRadiusNorm", c.strikerRadiusNorm.toDouble())
            put("screenWidthPx", c.screenWidthPx)
            put("screenHeightPx", c.screenHeightPx)
            put("maxPullDistanceNorm", c.maxPullDistanceNorm.toDouble())
            put("updatedAtMs", c.updatedAtMs)
        }
    }

    private fun decodeCalibration(json: String): BoardCalibration {
        return runCatching { decodeCalibrationObj(JSONObject(json)) }.getOrDefault(BoardCalibration())
    }

    private fun decodeCalibrationObj(obj: JSONObject): BoardCalibration {
        val d = BoardCalibration()
        return BoardCalibration(
            profileName = obj.optString("profileName", d.profileName),
            topLeft = NormalizedPoint(
                obj.optDouble("topLeftX", d.topLeft.x.toDouble()).toFloat(),
                obj.optDouble("topLeftY", d.topLeft.y.toDouble()).toFloat()
            ),
            topRight = NormalizedPoint(
                obj.optDouble("topRightX", d.topRight.x.toDouble()).toFloat(),
                obj.optDouble("topRightY", d.topRight.y.toDouble()).toFloat()
            ),
            bottomLeft = NormalizedPoint(
                obj.optDouble("bottomLeftX", d.bottomLeft.x.toDouble()).toFloat(),
                obj.optDouble("bottomLeftY", d.bottomLeft.y.toDouble()).toFloat()
            ),
            bottomRight = NormalizedPoint(
                obj.optDouble("bottomRightX", d.bottomRight.x.toDouble()).toFloat(),
                obj.optDouble("bottomRightY", d.bottomRight.y.toDouble()).toFloat()
            ),
            pocketTopLeft = NormalizedPoint(
                obj.optDouble("pocketTLX", d.pocketTopLeft.x.toDouble()).toFloat(),
                obj.optDouble("pocketTLY", d.pocketTopLeft.y.toDouble()).toFloat()
            ),
            pocketTopRight = NormalizedPoint(
                obj.optDouble("pocketTRX", d.pocketTopRight.x.toDouble()).toFloat(),
                obj.optDouble("pocketTRY", d.pocketTopRight.y.toDouble()).toFloat()
            ),
            pocketBottomLeft = NormalizedPoint(
                obj.optDouble("pocketBLX", d.pocketBottomLeft.x.toDouble()).toFloat(),
                obj.optDouble("pocketBLY", d.pocketBottomLeft.y.toDouble()).toFloat()
            ),
            pocketBottomRight = NormalizedPoint(
                obj.optDouble("pocketBRX", d.pocketBottomRight.x.toDouble()).toFloat(),
                obj.optDouble("pocketBRY", d.pocketBottomRight.y.toDouble()).toFloat()
            ),
            pocketRadiusNorm = obj.optDouble("pocketRadiusNorm", d.pocketRadiusNorm.toDouble()).toFloat(),
            baselineLeftX = obj.optDouble("baselineLeftX", d.baselineLeftX.toDouble()).toFloat(),
            baselineRightX = obj.optDouble("baselineRightX", d.baselineRightX.toDouble()).toFloat(),
            strikerBaselineY = obj.optDouble("strikerBaselineY", d.strikerBaselineY.toDouble()).toFloat(),
            sliderLeftX = obj.optDouble("sliderLeftX", d.sliderLeftX.toDouble()).toFloat(),
            sliderRightX = obj.optDouble("sliderRightX", d.sliderRightX.toDouble()).toFloat(),
            sliderY = obj.optDouble("sliderY", d.sliderY.toDouble()).toFloat(),
            puckRadiusNorm = obj.optDouble("puckRadiusNorm", d.puckRadiusNorm.toDouble()).toFloat(),
            strikerRadiusNorm = obj.optDouble("strikerRadiusNorm", d.strikerRadiusNorm.toDouble()).toFloat(),
            screenWidthPx = obj.optInt("screenWidthPx", d.screenWidthPx),
            screenHeightPx = obj.optInt("screenHeightPx", d.screenHeightPx),
            maxPullDistanceNorm = obj.optDouble("maxPullDistanceNorm", d.maxPullDistanceNorm.toDouble()).toFloat(),
            updatedAtMs = obj.optLong("updatedAtMs", d.updatedAtMs)
        )
    }
}
