package com.mikewen.autopilot.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikewen.autopilot.ble.BleManager
import com.mikewen.autopilot.ble.ImuManager
import com.mikewen.autopilot.model.*
import com.mikewen.autopilot.data.SettingsRepository
import com.mikewen.autopilot.ble.RemoteManager
import com.mikewen.autopilot.sensor.GpsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AutopilotViewModel(application: Application) : AndroidViewModel(application) {

    // ── Managers & repositories ───────────────────────────────────────────────
    val bleManager   = BleManager(application)
    val imuManager   = ImuManager(application)
    val gpsManager   = GpsManager(application)
    val remoteManager = RemoteManager(application)
    private val settingsRepo = SettingsRepository(application)

    // ── Autopilot type ────────────────────────────────────────────────────────
    private val _selectedType = MutableStateFlow<AutopilotType?>(null)
    val selectedType: StateFlow<AutopilotType?> = _selectedType.asStateFlow()

    fun selectAutopilotType(type: AutopilotType) { _selectedType.value = type }

    // ── Autopilot BLE ─────────────────────────────────────────────────────────
    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val scannedDevices:  StateFlow<List<BleDevice>>    = bleManager.scannedDevices

    // ── IMU ───────────────────────────────────────────────────────────────────
    val imuConnectionState: StateFlow<ImuConnectionState> = imuManager.connectionState
    val scannedImuDevices:  StateFlow<List<ImuDevice>>    = imuManager.scannedDevices
    val imuState:           StateFlow<ImuState>           = imuManager.imuState

    // ── GPS / SensorFusion ────────────────────────────────────────────────────
    private val _gpsData = MutableStateFlow(GpsManager.GpsData())
    val gpsData: StateFlow<GpsManager.GpsData> = _gpsData.asStateFlow()

    /**
     * Merged autopilot state — priority order for currentHeading:
     *   1. IMU sensor (when connected) — direct BLE compass heading
     *   2. GpsManager / SensorFusion — IMU+Mag+GPS fusion via ae02 stream
     *   3. BLE autopilot controller's own heading characteristic
     *
     * Speed and position always come from GpsManager when available.
     */
    val autopilotState: StateFlow<AutopilotState> = combine(
        bleManager.autopilotState,
        _gpsData,
        imuManager.connectionState,
        imuManager.imuState
    ) { apState, gps, imuConn, imu ->
        val heading = when {
            imuConn is ImuConnectionState.Connected -> imu.heading
            gps.hasHeading                          -> gps.headingDeg
            else                                    -> apState.currentHeading
        }
        if (gps.hasHeading || imuConn is ImuConnectionState.Connected) {
            apState.copy(
                currentHeading = heading,
                speedKnots     = if (gps.hasHeading) gps.speedKnots else apState.speedKnots,
                latitude       = if (gps.hasFix)     gps.latDeg     else apState.latitude,
                longitude      = if (gps.hasFix)     gps.lonDeg     else apState.longitude
            )
        } else apState
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AutopilotState())

    // ── PID + Deadband Config (persisted via DataStore) ──────────────────────
    private val _pidConfig = MutableStateFlow(PidConfig())
    val pidConfig: StateFlow<PidConfig> = _pidConfig.asStateFlow()

    private fun updateAndSave(newConfig: PidConfig) {
        _pidConfig.value = newConfig
        viewModelScope.launch { settingsRepo.savePidConfig(newConfig) }
    }

    fun updatePidKp(v: Float)             { updateAndSave(_pidConfig.value.copy(kp = v)) }
    fun updatePidKi(v: Float)             { updateAndSave(_pidConfig.value.copy(ki = v)) }
    fun updatePidKd(v: Float)             { updateAndSave(_pidConfig.value.copy(kd = v)) }
    fun updateDeadband(v: Float)          { updateAndSave(_pidConfig.value.copy(deadbandDeg = v)) }
    fun updateOffCourseAlarm(v: Float)    { updateAndSave(_pidConfig.value.copy(offCourseAlarmDeg = v)) }
    fun updateOutputLimit(v: Float)       { updateAndSave(_pidConfig.value.copy(outputLimitDeg = v)) }
    fun updateRateLimit(v: Float)         { updateAndSave(_pidConfig.value.copy(rateLimitDegPerSec = v)) }
    fun updateSteeringBias(v: Float)      { updateAndSave(_pidConfig.value.copy(steeringBiasDeg = v)) }
    fun updateMaxScaleSpeed(v: Float)     { updateAndSave(_pidConfig.value.copy(maxScaleSpeedKt = v)) }
    fun updateMinSpeedScale(v: Float)     { updateAndSave(_pidConfig.value.copy(minSpeedScale = v)) }

    fun applyPid() {
        val config = _pidConfig.value
        bleManager.setPid(config)
        bleManager.sendCommand(BleCommand.setDeadband(config.deadbandDeg))
    }

    // ── Target Heading ────────────────────────────────────────────────────────
    private val _targetHeading = MutableStateFlow(0f)
    val targetHeading: StateFlow<Float> = _targetHeading.asStateFlow()

    // ── Target Waypoint (set from map) ────────────────────────────────────────
    private val _targetWaypoint = MutableStateFlow<Waypoint?>(null)
    val targetWaypoint: StateFlow<Waypoint?> = _targetWaypoint.asStateFlow()

    fun setTargetWaypoint(wp: Waypoint) {
        _targetWaypoint.value = wp
        // Use SensorFusion state directly — it always has lat/lon from phone GPS
        // even if hasFix is not yet true (position updates before fix quality improves).
        val fs = gpsManager.fusion.getState()
        val lat = fs.latDeg.takeIf { it != 0.0 } ?: _gpsData.value.latDeg
        val lon = fs.lonDeg.takeIf { it != 0.0 } ?: _gpsData.value.lonDeg
        if (lat != 0.0) {
            val bearing = gpsManager.fusion.bearingTo(lat, lon, wp.latitude, wp.longitude)
            setTargetHeading(bearing)
        }
    }

    fun clearTargetWaypoint() { _targetWaypoint.value = null }

    fun setTargetHeading(deg: Float) {
        val norm = ((deg % 360f) + 360f) % 360f
        _targetHeading.value = norm
        bleManager.setHeading(norm)
    }

    // ── Heading history (last 2 min at 1 Hz) ─────────────────────────────────
    private val _headingHistory = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    val headingHistory: StateFlow<List<Pair<Float, Float>>> = _headingHistory.asStateFlow()

    init {
        // Load persisted settings on startup
        viewModelScope.launch {
            settingsRepo.pidConfigFlow.first().let { saved ->
                _pidConfig.value = saved
            }
        }

        gpsManager.onUpdate = { data -> _gpsData.value = data }
        gpsManager.startPhoneGps()

        // ── Wire LookbonRemote → ViewModel actions ────────────────────────────
        remoteManager.apply {
            onEngage   = ::engage
            onStandby  = ::standby
            onPortOne  = ::portOne
            onPortTen  = ::portTen
            onStbdOne  = ::stbdOne
            onStbdTen  = ::stbdTen
            onHardStop = ::hardStop
            onEscPwm   = { port, stbd -> sendEscPwm(port, stbd) }
            onBldcDuty = { port, stbd -> sendBldcDuty(port, stbd) }
        }

        // ── Route A1/A2/A3 packets from ALL connected BLE devices to SensorFusion ──
        // Any device (autopilot controller, IMU sensor, or secondary sensor) may
        // stream A1/A2/A3 binary packets on its ae02-equivalent characteristic.
        // All streams are merged into the same GpsManager.feedAe02Bytes() call so
        // SensorFusion always gets the freshest data regardless of which device sent it.
        viewModelScope.launch {
            bleManager.incomingData.collect { bytes ->
                gpsManager.feedAe02Bytes(bytes)
            }
        }

        // IMU device may also send A1/A2/A3 packets alongside its own c1–c4 characteristics
        imuManager.onRawBytes = { bytes -> gpsManager.feedAe02Bytes(bytes) }

        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val s = autopilotState.value
                _headingHistory.update { h ->
                    (h + Pair(s.currentHeading, s.targetHeading)).takeLast(120)
                }
                computeShadowPid()   // keep shadow PID fresh at 1 Hz for display
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (connectionState.value is BleConnectionState.Connected) bleManager.readRssi()
                if (imuConnectionState.value is ImuConnectionState.Connected) imuManager.readRssi()
            }
        }
    }

    // ── SensorFusion tuning ───────────────────────────────────────────────────
    val fusion get() = gpsManager.fusion
    fun setUseKalman(v: Boolean) { gpsManager.fusion.useKalman = v }

    // ── Phone-side PID shadow (for display / future soft-PID) ────────────────
    // Computes what the PID would output — useful for tuning even when the
    // hardware MCU is running its own PID loop.
    // gyroZDegS = live BLE gyro yaw rate from SensorFusion (50 Hz via A1 packets).
    // speedKnots = from GpsManager (BLE A3 or phone GPS fallback).
    private val _shadowPid = MutableStateFlow(PidResult(0f, 0f, false, false))
    val shadowPid: StateFlow<PidResult> = _shadowPid.asStateFlow()

    private val pidController = PidController(PidConfig())

    fun computeShadowPid() {
        pidController.updateConfig(_pidConfig.value)
        _shadowPid.value = pidController.compute(
            currentHeading = autopilotState.value.currentHeading,
            targetHeading  = _targetHeading.value,
            gyroZDegS      = gpsManager.fusion.lastGyroZDegS,
            speedKnots     = _gpsData.value.speedKnots
        )
    }

    // ── Autopilot controls ────────────────────────────────────────────────────
    fun engage() {
        bleManager.engage()
        remoteManager.notifyEngaged(true)
    }
    fun standby() {
        bleManager.standby()
        remoteManager.notifyEngaged(false)
    }
    fun hardStop() = bleManager.hardStop()

    // ── Manual throttle for diff-thrust (used in standby mode) ───────────────
    fun sendEscPwm(port: Int, stbd: Int)   = bleManager.sendEscPwm(port, stbd)
    fun sendBldcDuty(port: Int, stbd: Int) = bleManager.sendBldcDuty(port, stbd)

    fun portOne() { bleManager.portOne(); _targetHeading.update { ((it - 1f  + 360f) % 360f) }; remoteManager.notifyCourse(_targetHeading.value) }
    fun portTen() { bleManager.portTen(); _targetHeading.update { ((it - 10f + 360f) % 360f) }; remoteManager.notifyCourse(_targetHeading.value) }
    fun stbdOne() { bleManager.stbdOne(); _targetHeading.update { ((it + 1f)  % 360f) };         remoteManager.notifyCourse(_targetHeading.value) }
    fun stbdTen() { bleManager.stbdTen(); _targetHeading.update { ((it + 10f) % 360f) };         remoteManager.notifyCourse(_targetHeading.value) }

    // ── Autopilot scan / connect / disconnect ─────────────────────────────────
    fun startScan()                = bleManager.startScan()
    fun stopScan()                 = bleManager.stopScan()
    fun connect(device: BleDevice) = bleManager.connect(device)
    fun disconnect() {
        bleManager.disconnect()
        gpsManager.disconnectSensor2()
    }

    // ── Remote scan / connect / disconnect ──────────────────────────────────
    fun startRemoteScan()                                          = remoteManager.startScan()
    fun stopRemoteScan()                                           = remoteManager.stopScan()
    fun connectRemote(d: android.bluetooth.BluetoothDevice)        = remoteManager.connect(d)
    fun disconnectRemote()                                         = remoteManager.disconnect()

    // StateFlows exposed for ScanScreen
    val remoteState:    StateFlow<RemoteManager.RemoteState>       = remoteManager.state
    val scannedRemotes: StateFlow<List<android.bluetooth.BluetoothDevice>> = remoteManager.scannedRemotes
    val manualOverride: StateFlow<Boolean>                         = remoteManager.manualOverride

    // ── IMU scan / connect / disconnect ───────────────────────────────────────────
    fun startImuScan()                = imuManager.startScan()
    fun stopImuScan()                 = imuManager.stopScan()
    fun connectImu(device: ImuDevice) = imuManager.connect(device)
    fun disconnectImu()               = imuManager.disconnect()

    override fun onCleared() {
        super.onCleared()
        bleManager.dispose()
        imuManager.dispose()
        remoteManager.dispose()
        gpsManager.stopPhoneGps()
        gpsManager.disconnectSensor2()
        gpsManager.saveCalibration()
        gpsManager.stopLogging()
    }
}