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
 * Persists PidConfig (Kp, Ki, Kd, deadband, off-course alarm, output limit)
 * using Jetpack DataStore.  Survives app restarts.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_KP              = floatPreferencesKey("pid_kp")
        private val KEY_KI              = floatPreferencesKey("pid_ki")
        private val KEY_KD              = floatPreferencesKey("pid_kd")
        private val KEY_OUTPUT_LIMIT    = floatPreferencesKey("pid_output_limit")
        private val KEY_DEADBAND        = floatPreferencesKey("pid_deadband_deg")
        private val KEY_OFFCOURSE_ALARM = floatPreferencesKey("pid_offcourse_alarm_deg")
    }

    /** Flow of the persisted PidConfig — emits the default if nothing saved yet. */
    val pidConfigFlow: Flow<PidConfig> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            val defaults = PidConfig()
            PidConfig(
                kp               = prefs[KEY_KP]              ?: defaults.kp,
                ki               = prefs[KEY_KI]              ?: defaults.ki,
                kd               = prefs[KEY_KD]              ?: defaults.kd,
                outputLimit      = prefs[KEY_OUTPUT_LIMIT]    ?: defaults.outputLimit,
                deadbandDeg      = prefs[KEY_DEADBAND]        ?: defaults.deadbandDeg,
                offCourseAlarmDeg = prefs[KEY_OFFCOURSE_ALARM] ?: defaults.offCourseAlarmDeg
            )
        }

    /** Save PidConfig to DataStore. Call this after any slider change. */
    suspend fun savePidConfig(config: PidConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KP]              = config.kp
            prefs[KEY_KI]              = config.ki
            prefs[KEY_KD]              = config.kd
            prefs[KEY_OUTPUT_LIMIT]    = config.outputLimit
            prefs[KEY_DEADBAND]        = config.deadbandDeg
            prefs[KEY_OFFCOURSE_ALARM] = config.offCourseAlarmDeg
        }
    }
}
