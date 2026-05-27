package com.mikewen.autopilot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.mikewen.autopilot.model.BoatProfile
import com.mikewen.autopilot.model.PidConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "autopilot_settings")

/**
 * Persists PidConfig per BoatProfile and the active profile selection.
 * Keys are prefixed with the profile name so each boat has independent settings.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        // Active profile key
        private val KEY_ACTIVE_PROFILE = stringPreferencesKey("active_profile")

        // Returns prefixed keys for a given profile
        private fun key(profile: BoatProfile, name: String) =
            floatPreferencesKey("${profile.name}_$name")
        private fun keyInt(profile: BoatProfile, name: String) =
            intPreferencesKey("${profile.name}_$name")
        private fun keyBool(profile: BoatProfile, name: String) =
            booleanPreferencesKey("${profile.name}_$name")
    }

    /** Flow of the currently selected profile (default: first profile). */
    val activeProfileFlow: Flow<BoatProfile> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val name = prefs[KEY_ACTIVE_PROFILE] ?: BoatProfile.CL16.name
            BoatProfile.entries.firstOrNull { it.name == name } ?: BoatProfile.CL16
        }

    suspend fun saveActiveProfile(profile: BoatProfile) {
        context.dataStore.edit { it[KEY_ACTIVE_PROFILE] = profile.name }
    }

    /** Flow of PidConfig for the given profile. Emits default if nothing saved. */
    fun pidConfigFlow(profile: BoatProfile): Flow<PidConfig> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> loadConfig(prefs, profile) }

    private fun loadConfig(prefs: Preferences, p: BoatProfile): PidConfig {
        val d = PidConfig()
        return PidConfig(
            kp                 = prefs[key(p, "kp")]              ?: d.kp,
            ki                 = prefs[key(p, "ki")]              ?: d.ki,
            kd                 = prefs[key(p, "kd")]              ?: d.kd,
            outputLimitDeg     = prefs[key(p, "output_limit")]    ?: d.outputLimitDeg,
            rateLimitDegPerSec = prefs[key(p, "rate_limit")]      ?: d.rateLimitDegPerSec,
            deadbandDeg        = prefs[key(p, "deadband")]        ?: d.deadbandDeg,
            steeringBiasDeg    = prefs[key(p, "steering_bias")]   ?: d.steeringBiasDeg,
            offCourseAlarmDeg  = prefs[key(p, "offcourse_alarm")] ?: d.offCourseAlarmDeg,
            maxScaleSpeedKt    = prefs[key(p, "max_scale_speed")] ?: d.maxScaleSpeedKt,
            minSpeedScale      = prefs[key(p, "min_speed_scale")] ?: d.minSpeedScale,
            steerScaleMs       = prefs[keyInt(p,  "steer_scale_ms")]   ?: d.steerScaleMs,
            useKalmanFilter    = prefs[keyBool(p, "use_kalman")]        ?: d.useKalmanFilter,
            useSteerSensor     = prefs[keyBool(p, "use_steer_sensor")] ?: d.useSteerSensor,
            shaftLimitPortDeg  = prefs[key(p, "shaft_limit_port")]    ?: d.shaftLimitPortDeg,
            shaftLimitStbdDeg  = prefs[key(p, "shaft_limit_stbd")]    ?: d.shaftLimitStbdDeg,
            shaftLagThresholdDeg = prefs[key(p, "shaft_lag_thresh")] ?: d.shaftLagThresholdDeg,
            shaftLagWindowMs   = prefs[key(p, "shaft_lag_window")]?.toLong() ?: d.shaftLagWindowMs
        )
    }

    suspend fun savePidConfig(profile: BoatProfile, config: PidConfig) {
        context.dataStore.edit { prefs ->
            prefs[key(profile,  "kp")]              = config.kp
            prefs[key(profile,  "ki")]              = config.ki
            prefs[key(profile,  "kd")]              = config.kd
            prefs[key(profile,  "output_limit")]    = config.outputLimitDeg
            prefs[key(profile,  "rate_limit")]      = config.rateLimitDegPerSec
            prefs[key(profile,  "deadband")]        = config.deadbandDeg
            prefs[key(profile,  "steering_bias")]   = config.steeringBiasDeg
            prefs[key(profile,  "offcourse_alarm")] = config.offCourseAlarmDeg
            prefs[key(profile,  "max_scale_speed")] = config.maxScaleSpeedKt
            prefs[key(profile,  "min_speed_scale")] = config.minSpeedScale
            prefs[keyInt(profile,  "steer_scale_ms")]  = config.steerScaleMs
            prefs[keyBool(profile, "use_kalman")]       = config.useKalmanFilter
            prefs[keyBool(profile, "use_steer_sensor")] = config.useSteerSensor
            prefs[key(profile, "shaft_limit_port")]    = config.shaftLimitPortDeg
            prefs[key(profile, "shaft_limit_stbd")]    = config.shaftLimitStbdDeg
            prefs[key(profile, "shaft_lag_thresh")]    = config.shaftLagThresholdDeg
            prefs[key(profile, "shaft_lag_window")]    = config.shaftLagWindowMs.toFloat()
        }
    }
}
