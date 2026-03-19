package com.mikewen.autopilot.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikewen.autopilot.ble.BleManager
import com.mikewen.autopilot.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AutopilotViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)

    // ── BLE state (forwarded directly) ───────────────────────────────────────
    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val scannedDevices:  StateFlow<List<BleDevice>>    = bleManager.scannedDevices
    val autopilotState:  StateFlow<AutopilotState>     = bleManager.autopilotState

    // ── Autopilot type ────────────────────────────────────────────────────────
    private val _selectedType = MutableStateFlow<AutopilotType?>(null)
    val selectedType: StateFlow<AutopilotType?> = _selectedType.asStateFlow()

    fun selectAutopilotType(type: AutopilotType) { _selectedType.value = type }

    // ── PID + Deadband Config ─────────────────────────────────────────────────
    private val _pidConfig = MutableStateFlow(PidConfig())
    val pidConfig: StateFlow<PidConfig> = _pidConfig.asStateFlow()

    fun updatePidKp(v: Float)           { _pidConfig.update { it.copy(kp = v) } }
    fun updatePidKi(v: Float)           { _pidConfig.update { it.copy(ki = v) } }
    fun updatePidKd(v: Float)           { _pidConfig.update { it.copy(kd = v) } }
    fun updateDeadband(v: Float)        { _pidConfig.update { it.copy(deadbandDeg = v) } }
    fun updateOffCourseAlarm(v: Float)  { _pidConfig.update { it.copy(offCourseAlarmDeg = v) } }
    fun updateOutputLimit(v: Float)     { _pidConfig.update { it.copy(outputLimit = v) } }

    fun applyPid() {
        val config = _pidConfig.value
        bleManager.setPid(config)
        bleManager.sendCommand(BleCommand.setDeadband(config.deadbandDeg))
    }

    // ── Target Heading ────────────────────────────────────────────────────────
    private val _targetHeading = MutableStateFlow(0f)
    val targetHeading: StateFlow<Float> = _targetHeading.asStateFlow()

    fun setTargetHeading(deg: Float) {
        val norm = ((deg % 360f) + 360f) % 360f
        _targetHeading.value = norm
        bleManager.setHeading(norm)
    }

    // ── Heading history (last 2 min at 1 Hz, for chart) ──────────────────────
    private val _headingHistory = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    val headingHistory: StateFlow<List<Pair<Float, Float>>> = _headingHistory.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val s = autopilotState.value
                _headingHistory.update { history ->
                    (history + Pair(s.currentHeading, s.targetHeading)).takeLast(120)
                }
            }
        }
        // Refresh RSSI every 5 s when connected
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (connectionState.value is BleConnectionState.Connected) bleManager.readRssi()
            }
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────
    fun engage()  = bleManager.engage()
    fun standby() = bleManager.standby()

    fun portOne() { bleManager.portOne(); _targetHeading.update { ((it - 1f + 360f) % 360f) } }
    fun portTen() { bleManager.portTen(); _targetHeading.update { ((it - 10f + 360f) % 360f) } }
    fun stbdOne() { bleManager.stbdOne(); _targetHeading.update { ((it + 1f) % 360f) } }
    fun stbdTen() { bleManager.stbdTen(); _targetHeading.update { ((it + 10f) % 360f) } }

    // ── Scan / connect / disconnect ───────────────────────────────────────────
    fun startScan()                = bleManager.startScan()
    fun stopScan()                 = bleManager.stopScan()
    fun connect(device: BleDevice) = bleManager.connect(device)
    fun disconnect()               = bleManager.disconnect()

    override fun onCleared() {
        super.onCleared()
        bleManager.dispose()
    }
}
