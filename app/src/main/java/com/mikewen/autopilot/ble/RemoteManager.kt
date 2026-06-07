package com.mikewen.autopilot.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.mikewen.autopilot.sensor.VoicePrompt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "RemoteManager"

/**
 * RemoteManager
 *
 * Manages the LOOKBON BLE remote (or any future remote that emits RemoteCommand).
 * Bridges between raw button events and autopilot/motor actions via callbacks
 * that the ViewModel wires up.
 *
 * Responsibilities:
 *  - Scan for LOOKBON devices (name contains "LOOKBON")
 *  - Connect / disconnect / auto-reconnect
 *  - Translate RemoteCommand events into ViewModel actions
 *  - Drive VoicePrompt announcements for each action
 *  - Track manual-override state (L button held = direct motor control)
 *
 * The ViewModel injects action lambdas so RemoteManager has no direct
 * dependency on BleManager, MotorController, or GpsManager.
 */
@SuppressLint("MissingPermission")
class RemoteManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val voice          = VoicePrompt(context)
    private var remote: LookbonRemote? = null
    private var scanJob: Job? = null

    // ── Connection state ──────────────────────────────────────────────────────

    enum class RemoteState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(RemoteState.DISCONNECTED)
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val _scannedRemotes = MutableStateFlow<List<android.bluetooth.BluetoothDevice>>(emptyList())
    val scannedRemotes: StateFlow<List<android.bluetooth.BluetoothDevice>> = _scannedRemotes.asStateFlow()

    // ── Manual override ───────────────────────────────────────────────────────

    private val _manualOverride = MutableStateFlow(false)
    val manualOverride: StateFlow<Boolean> = _manualOverride.asStateFlow()

    // Accumulated steer steps for debounced voice
    private var accumulatedSteerSteps = 0
    private var steerVoiceRunnable: Runnable? = null
    private val steerHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ── Throttle state (for voice feedback) ──────────────────────────────────
    // Maintained locally so voice can announce current value
    private var currentSpeedPct  = 0    // 0–100
    private var portSpeedPct     = 0
    private var stbdSpeedPct     = 0

    // ── Action callbacks — injected by ViewModel ──────────────────────────────

    var onEngage:      (() -> Unit)?      = null
    var onStandby:     (() -> Unit)?      = null
    var onPortOne:     (() -> Unit)?      = null
    var onPortTen:     (() -> Unit)?      = null
    var onStbdOne:     (() -> Unit)?      = null
    var onStbdTen:     (() -> Unit)?      = null
    var onHardStop:    (() -> Unit)?      = null
    /** Called with step: negative=port, positive=stbd, 0=stop — manual steer when not engaged. */
    var onRudderStep:  ((Int) -> Unit)?   = null

    // Track engaged state so course buttons route correctly
    private var isEngaged = false

    /** Send ESC PWM (port 500–1000, stbd 500–1000) */
    var onEscPwm:     ((Int, Int) -> Unit)? = null
    /** Send BLDC duty (port 0–10000, stbd 0–10000) */
    var onBldcDuty:   ((Int, Int) -> Unit)? = null

    /** Called when autopilot engaged state changes (to keep remote in sync) */
    fun notifyEngaged(engaged: Boolean) {
        isEngaged = engaged
        remote?.setApEngaged(engaged)
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    fun startScan() {
        if (bluetoothAdapter?.isEnabled != true) return
        _scannedRemotes.value = emptyList()
        _state.value = RemoteState.SCANNING

        val scanner  = bluetoothAdapter!!.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanJob?.cancel()
        scanJob = scope.launch {
            scanner.startScan(emptyList(), settings, scanCallback)
            delay(15_000)
            scanner.stopScan(scanCallback)
            if (_state.value == RemoteState.SCANNING)
                _state.value = RemoteState.DISCONNECTED
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_state.value == RemoteState.SCANNING)
            _state.value = RemoteState.DISCONNECTED
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (LookbonRemote.REMOTE_NAME_FILTERS.none { name.contains(it, ignoreCase = true) }) return
            _scannedRemotes.update { list ->
                if (list.any { it.address == result.device.address }) list
                else list + result.device
            }
            Log.i(TAG, "Found LOOKBON: $name  ${result.device.address}")
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Remote scan failed: $errorCode")
            _state.value = RemoteState.DISCONNECTED
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect(device: android.bluetooth.BluetoothDevice) {
        stopScan()
        _state.value = RemoteState.CONNECTING

        val r = LookbonRemote(context).apply {
            controlMode = LookbonRemote.ControlMode.AUTOPILOT

            onConnected = {
                Log.i(TAG, "LOOKBON connected")
                _state.value = RemoteState.CONNECTED
                voice.speak("Remote connected")
            }
            onDisconnected = {
                Log.i(TAG, "LOOKBON disconnected")
                _state.value = RemoteState.DISCONNECTED
                voice.resetThrottle()
            }
            onManualOverride = { active ->
                _manualOverride.value = active
                voice.speak(if (active) "Manual override" else "Autopilot mode")
            }
            onRemoteCommand = { cmd -> handleCommand(cmd) }
        }
        remote = r
        r.connectToDevice(device)
    }

    fun disconnect() {
        remote?.close()
        remote?.disconnect()?.enqueue()
        remote = null
        _state.value = RemoteState.DISCONNECTED
        _manualOverride.value = false
        voice.resetThrottle()
    }

    // ── Command handler ───────────────────────────────────────────────────────

    private fun handleCommand(cmd: RemoteBleManager.RemoteCommand) {
        Log.d(TAG, "RemoteCmd: grp=${cmd.group} action=${cmd.action}")
        when (cmd.group) {

            RemoteBleManager.GRP_AUTOPILOT -> when (cmd.action) {
                RemoteBleManager.AP_ENGAGE    -> {
                    onEngage?.invoke()
                    voice.speak("Engaged")
                }
                RemoteBleManager.AP_DISENGAGE -> {
                    onStandby?.invoke()
                    voice.speak("Standby")
                }
            }

            RemoteBleManager.GRP_COURSE -> {
                if (isEngaged) {
                    // Autopilot engaged: adjust course heading
                    when (cmd.action) {
                        RemoteBleManager.CRS_LEFT_1   -> onPortOne?.invoke()
                        RemoteBleManager.CRS_LEFT_10  -> onPortTen?.invoke()
                        RemoteBleManager.CRS_RIGHT_1  -> onStbdOne?.invoke()
                        RemoteBleManager.CRS_RIGHT_10 -> onStbdTen?.invoke()
                    }
                } else {
                    // Standby: drive the steer motor directly
                    when (cmd.action) {
                        RemoteBleManager.CRS_LEFT_1   -> { onRudderStep?.invoke(-1); announceSteer(-1) }
                        RemoteBleManager.CRS_LEFT_10  -> { onRudderStep?.invoke(-5); announceSteer(-5) }
                        RemoteBleManager.CRS_RIGHT_1  -> { onRudderStep?.invoke(1);  announceSteer(1) }
                        RemoteBleManager.CRS_RIGHT_10 -> { onRudderStep?.invoke(5);  announceSteer(5) }
                    }
                }
            }

            RemoteBleManager.GRP_SPEED -> when (cmd.action) {
                RemoteBleManager.SPD_UP     -> { currentSpeedPct = (currentSpeedPct + 5).coerceIn(0,100); sendBothSpeed(); voice.speakSpeed(currentSpeedPct) }
                RemoteBleManager.SPD_UP_1   -> { currentSpeedPct = (currentSpeedPct + 1).coerceIn(0,100); sendBothSpeed(); voice.speakSpeed(currentSpeedPct) }
                RemoteBleManager.SPD_DOWN   -> { currentSpeedPct = (currentSpeedPct - 5).coerceIn(0,100); sendBothSpeed(); voice.speakSpeed(currentSpeedPct) }
                RemoteBleManager.SPD_DOWN_1 -> { currentSpeedPct = (currentSpeedPct - 1).coerceIn(0,100); sendBothSpeed(); voice.speakSpeed(currentSpeedPct) }
                RemoteBleManager.SPD_STOP   -> {
                    currentSpeedPct = 0; portSpeedPct = 0; stbdSpeedPct = 0
                    onHardStop?.invoke()
                    voice.speak("Stop")
                }
            }

            RemoteBleManager.GRP_SYNC -> when (cmd.action) {
                RemoteBleManager.SYNC_ON  -> voice.speak("Sync on")
                RemoteBleManager.SYNC_OFF -> voice.speak("Sync off")
            }

            // Port / stbd individual channel (manual override)
            RemoteBleManager.GRP_PORT -> when (cmd.action) {
                RemoteBleManager.SPD_UP_1 -> {
                    portSpeedPct = (portSpeedPct + 1).coerceIn(0, 100)
                    sendSplitSpeed()
                }
                RemoteBleManager.SPD_DOWN_1 -> {
                    portSpeedPct = (portSpeedPct - 1).coerceIn(0, 100)
                    sendSplitSpeed()
                }
            }
            RemoteBleManager.GRP_STBD -> when (cmd.action) {
                RemoteBleManager.SPD_UP_1 -> {
                    stbdSpeedPct = (stbdSpeedPct + 1).coerceIn(0, 100)
                    sendSplitSpeed()
                }
                RemoteBleManager.SPD_DOWN_1 -> {
                    stbdSpeedPct = (stbdSpeedPct - 1).coerceIn(0, 100)
                    sendSplitSpeed()
                }
            }
        }

        // Voice course feedback — debounced by VoicePrompt
        if (cmd.group == RemoteBleManager.GRP_COURSE) {
            // ViewModel will update heading; we just trigger the voice with a
            // callback from ViewModel after heading updates. See notifyCourse().
        }
    }

    /** Called by ViewModel after heading changes so voice gets the actual new heading. */
    fun notifyCourse(headingDeg: Float) {
        voice.speakCourse(headingDeg)
    }

    // ── Motor helpers ─────────────────────────────────────────────────────────

    private fun pctToEsc(pct: Int): Int  = 500 + pct * 5    // 0%=500, 100%=1000
    private fun pctToBldc(pct: Int): Int = pct * 100         // 0%=0, 100%=10000

    private fun sendBothSpeed() {
        val esc = pctToEsc(currentSpeedPct)
        onEscPwm?.invoke(esc, esc)
    }

    private fun sendSplitSpeed() {
        onEscPwm?.invoke(pctToEsc(portSpeedPct), pctToEsc(stbdSpeedPct))
    }

    /**
     * Debounced voice for manual steer.
     * Accumulates step presses — speaks the net direction after 400 ms silence.
     * e.g. 3× LEFT_1 → "Left" after last press, not three separate announcements.
     */
    private fun announceSteer(step: Int) {
        accumulatedSteerSteps += step
        steerVoiceRunnable?.let { steerHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val net = accumulatedSteerSteps
            accumulatedSteerSteps = 0
            when {
                net < -4 -> voice.speak("Hard Left")
                net < 0  -> voice.speak("Left")
                net > 4  -> voice.speak("Hard Right")
                net > 0  -> voice.speak("Right")
                else     -> voice.speak("Stop")
            }
        }
        steerVoiceRunnable = runnable
        steerHandler.postDelayed(runnable, 400L)
    }

    fun dispose() {
        scope.cancel()
        disconnect()
        voice.shutdown()
    }
}