package com.mikewen.autopilot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.mikewen.autopilot.model.PidConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "autopilot_settings")

/**
 * Persists all PidConfig fields to Jetpack DataStore.
 * Survives app restarts and process death.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_KP                  = floatPreferencesKey("pid_kp")
        private val KEY_KI                  = floatPreferencesKey("pid_ki")
        private val KEY_KD                  = floatPreferencesKey("pid_kd")
        private val KEY_OUTPUT_LIMIT        = floatPreferencesKey("pid_output_limit_deg")
        private val KEY_RATE_LIMIT          = floatPreferencesKey("pid_rate_limit_deg_per_sec")
        private val KEY_DEADBAND            = floatPreferencesKey("pid_deadband_deg")
        private val KEY_STEERING_BIAS       = floatPreferencesKey("pid_steering_bias_deg")
        private val KEY_OFFCOURSE_ALARM     = floatPreferencesKey("pid_offcourse_alarm_deg")
        private val KEY_MAX_SCALE_SPEED     = floatPreferencesKey("pid_max_scale_speed_kt")
        private val KEY_MIN_SPEED_SCALE     = floatPreferencesKey("pid_min_speed_scale")
        private val KEY_STEER_SCALE_MS      = intPreferencesKey("steer_scale_ms")
    }

    val pidConfigFlow: Flow<PidConfig> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            val d = PidConfig()
            PidConfig(
                kp                 = prefs[KEY_KP]              ?: d.kp,
                ki                 = prefs[KEY_KI]              ?: d.ki,
                kd                 = prefs[KEY_KD]              ?: d.kd,
                outputLimitDeg     = prefs[KEY_OUTPUT_LIMIT]    ?: d.outputLimitDeg,
                rateLimitDegPerSec = prefs[KEY_RATE_LIMIT]      ?: d.rateLimitDegPerSec,
                deadbandDeg        = prefs[KEY_DEADBAND]        ?: d.deadbandDeg,
                steeringBiasDeg    = prefs[KEY_STEERING_BIAS]   ?: d.steeringBiasDeg,
                offCourseAlarmDeg  = prefs[KEY_OFFCOURSE_ALARM] ?: d.offCourseAlarmDeg,
                maxScaleSpeedKt    = prefs[KEY_MAX_SCALE_SPEED] ?: d.maxScaleSpeedKt,
                minSpeedScale      = prefs[KEY_MIN_SPEED_SCALE] ?: d.minSpeedScale,
                steerScaleMs       = prefs[KEY_STEER_SCALE_MS]  ?: d.steerScaleMs
            )
        }

    suspend fun savePidConfig(config: PidConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KP]              = config.kp
            prefs[KEY_KI]              = config.ki
            prefs[KEY_KD]              = config.kd
            prefs[KEY_OUTPUT_LIMIT]    = config.outputLimitDeg
            prefs[KEY_RATE_LIMIT]      = config.rateLimitDegPerSec
            prefs[KEY_DEADBAND]        = config.deadbandDeg
            prefs[KEY_STEERING_BIAS]   = config.steeringBiasDeg
            prefs[KEY_OFFCOURSE_ALARM] = config.offCourseAlarmDeg
            prefs[KEY_MAX_SCALE_SPEED] = config.maxScaleSpeedKt
            prefs[KEY_MIN_SPEED_SCALE] = config.minSpeedScale
            prefs[KEY_STEER_SCALE_MS]  = config.steerScaleMs
        }
    }
}