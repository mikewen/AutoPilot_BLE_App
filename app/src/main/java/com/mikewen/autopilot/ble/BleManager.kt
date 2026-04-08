package com.mikewen.autopilot.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import com.mikewen.autopilot.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

private const val TAG = "BleManager"
private const val SCAN_PERIOD_MS     = 15_000L
private const val CONNECT_TIMEOUT_MS = 10_000L
private const val GATT_MTU           = 64

// ── Real hardware service / characteristic UUIDs (AC6329C / ESC_PWM / BLE_tiller) ──
private val UUID_SVC_AE00 = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
private val UUID_SVC_AE30 = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
private val UUID_AE02     = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb") // NOTIFY  — A1/A2/A3 stream
private val UUID_AE03     = UUID.fromString("0000ae03-0000-1000-8000-00805f9b34fb") // WRITE   — motor commands
private val UUID_CCCD     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private val UUID_BAT_SVC  = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
private val UUID_BAT_CHAR = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedType: AutopilotType = AutopilotType.TILLER

    /**
     * True when the connected device uses the real ae00/ae30 service
     * (hardware running AC6329C firmware — ESC_PWM, BLDC_PWM, BLE_tiller).
     * False when using our custom ESP32 UUID scheme.
     */
    private var isHardwareProtocol: Boolean = false

    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public Flows ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _autopilotState = MutableStateFlow(AutopilotState())
    val autopilotState: StateFlow<AutopilotState> = _autopilotState.asStateFlow()

    /**
     * Every raw notification byte array — forwarded to GpsManager.feedAe02Bytes()
     * in AutopilotViewModel so A1/A2/A3 packets reach SensorFusion regardless of
     * which characteristic they arrive on.
     */
    private val _incomingData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingData: SharedFlow<ByteArray> = _incomingData.asSharedFlow()

    // ── Scanning ──────────────────────────────────────────────────────────────

    fun startScan() {
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is disabled")
            return
        }
        _scannedDevices.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning

        val scanner  = bluetoothAdapter!!.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanJob?.cancel()
        scanJob = scope.launch {
            scanner.startScan(emptyList(), settings, scanCallback)
            delay(SCAN_PERIOD_MS)
            scanner.stopScan(scanCallback)
            if (_connectionState.value is BleConnectionState.Scanning)
                _connectionState.value = BleConnectionState.Disconnected
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value is BleConnectionState.Scanning)
            _connectionState.value = BleConnectionState.Disconnected
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name    = result.device.name ?: return
            val address = result.device.address
            val rssi    = result.rssi

            // Identify autopilot type from advertised BLE name:
            //   "BLE_tiller"           → Tiller
            //   "ESC_PWM" / "BLDC_PWM" → Differential Thrust
            val type = when {
                name.equals("BLE_tiller", ignoreCase = true)  -> AutopilotType.TILLER
                name.equals("ESC_PWM",    ignoreCase = true)  -> AutopilotType.DIFF_THRUST
                name.equals("BLDC_PWM",   ignoreCase = true)  -> AutopilotType.DIFF_THRUST
                name.contains("GPS_PWM",   ignoreCase = true)  -> AutopilotType.TILLER
                name.contains("ESC_PWM",  ignoreCase = true)  -> AutopilotType.DIFF_THRUST
                name.contains("BLDC_PWM", ignoreCase = true)  -> AutopilotType.DIFF_THRUST
                name.contains("GPS_PWM", ignoreCase = true)  -> AutopilotType.DIFF_THRUST
                else -> null
            } ?: return   // ignore non-autopilot devices

            val device = BleDevice(name, address, rssi, type)
            _scannedDevices.update { list ->
                val updated = list.toMutableList()
                val idx = updated.indexOfFirst { it.address == address }
                if (idx >= 0) updated[idx] = device else updated.add(device)
                updated.sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = BleConnectionState.Error("Scan failed: code $errorCode")
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect(device: BleDevice) {
        stopScan()
        connectedType = device.type ?: AutopilotType.TILLER
        _connectionState.value = BleConnectionState.Connecting(device.name)
        val btDevice = bluetoothAdapter?.getRemoteDevice(device.address) ?: return

        scope.launch {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                bluetoothGatt = btDevice.connectGatt(
                    context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                )
            } ?: run {
                _connectionState.value = BleConnectionState.Error("Connection timed out")
            }
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isHardwareProtocol = false
        _connectionState.value = BleConnectionState.Disconnected
        _autopilotState.value  = AutopilotState()
    }

    // ── GATT Callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, requesting MTU…")
                    gatt.requestMtu(GATT_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState.Disconnected
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Error("Service discovery failed")
                return
            }

            // Detect which protocol the hardware is using:
            //   ae00 / ae30 → real AC6329C hardware (ESC_PWM, BLE_tiller, etc.)
            //   custom UUIDs → future ESP32 firmware
            val hwService = gatt.getService(UUID_SVC_AE00) ?: gatt.getService(UUID_SVC_AE30)
            isHardwareProtocol = hwService != null

            Log.d(TAG, "Services discovered. Hardware protocol: $isHardwareProtocol " +
                    "(${if (isHardwareProtocol) "ae00/ae30" else "custom UUIDs"})")

            enableNotifications(gatt)
            val name = gatt.device.name ?: "AutoPilot"
            _connectionState.value = BleConnectionState.Connected(name, -70)
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            scope.launch { _incomingData.emit(value) }
            parseNotification(characteristic.uuid.toString(), value)
        }

        // API < 33
        @Deprecated("Kept for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            scope.launch { _incomingData.emit(value) }
            parseNotification(characteristic.uuid.toString(), value)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val current = _connectionState.value
            if (current is BleConnectionState.Connected)
                _connectionState.value = current.copy(rssi = rssi)
        }
    }

    // ── Notification setup ────────────────────────────────────────────────────

    private fun enableNotifications(gatt: BluetoothGatt) {
        if (isHardwareProtocol) {
            enableHardwareNotifications(gatt)
        } else {
            enableCustomNotifications(gatt)
        }
        // Battery service is standard on both protocols
        enableNotifyChar(gatt,
            gatt.getService(UUID_BAT_SVC)?.getCharacteristic(UUID_BAT_CHAR))
    }

    /**
     * Real hardware (ae00/ae30):
     *   Subscribe to ae02 — this is the notification characteristic that streams
     *   all A1 (IMU+Mag 50Hz), A2 (GNSS heading 1Hz), A3 (position 0.2Hz) packets.
     *   All autopilot state parsing happens via GpsManager/SensorFusion downstream.
     */
    private fun enableHardwareNotifications(gatt: BluetoothGatt) {
        val svc = gatt.getService(UUID_SVC_AE00) ?: gatt.getService(UUID_SVC_AE30) ?: run {
            Log.e(TAG, "ae00/ae30 service not found after detection — should not happen")
            return
        }
        val ae02 = svc.getCharacteristic(UUID_AE02) ?: run {
            Log.w(TAG, "ae02 characteristic not found")
            return
        }
        enableNotifyChar(gatt, ae02)
        Log.d(TAG, "Subscribed to ae02 (A1/A2/A3 stream)")
    }

    /**
     * Custom UUID firmware (future ESP32):
     *   Subscribe to type-specific state/heading/throttle/rudder characteristics.
     */
    private fun enableCustomNotifications(gatt: BluetoothGatt) {
        val serviceUuid = BleUuids.serviceUuidFor(connectedType)
        val typeNotifyUuids = when (connectedType) {
            AutopilotType.TILLER -> listOf(
                BleUuids.CHAR_TILLER_STATE,
                BleUuids.CHAR_TILLER_HEADING,
                BleUuids.CHAR_RUDDER_ANGLE,
                BleUuids.CHAR_LINEAR_MOTOR_STATUS
            )
            AutopilotType.DIFF_THRUST -> listOf(
                BleUuids.CHAR_THRUST_STATE,
                BleUuids.CHAR_THRUST_HEADING,
                BleUuids.CHAR_PORT_THROTTLE,
                BleUuids.CHAR_STBD_THROTTLE
            )
        }
        typeNotifyUuids.forEach { uuid ->
            enableNotifyChar(gatt,
                gatt.getService(UUID.fromString(serviceUuid))
                    ?.getCharacteristic(UUID.fromString(uuid)))
        }
    }

    private fun enableNotifyChar(gatt: BluetoothGatt, char: BluetoothGattCharacteristic?) {
        char ?: return
        gatt.setCharacteristicNotification(char, true)
        char.getDescriptor(UUID_CCCD)?.let { desc ->
            @Suppress("DEPRECATION")
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(desc)
        }
    }

    // ── Parse incoming notifications ──────────────────────────────────────────
    //
    // Hardware protocol: ae02 carries raw A1/A2/A3 binary packets.
    // These are forwarded via incomingData → ViewModel → GpsManager.feedAe02Bytes()
    // so SensorFusion processes them. We also parse the A2 state flags here so the
    // UI can show engaged / off-course / in-deadband without needing SensorFusion.
    //
    // Custom UUID protocol: parse individual characteristics directly.

    private fun parseNotification(uuid: String, data: ByteArray) {
        val u = uuid.lowercase()

        if (isHardwareProtocol) {
            // ae02: forward to SensorFusion (via incomingData → ViewModel)
            // Also quick-parse A2 state flags for the UI state machine
            if (u == UUID_AE02.toString().lowercase()) {
                parseHardwareAe02(data)
            }
            return
        }

        // Custom UUID protocol parsing
        _autopilotState.update { state ->
            when (u) {
                BleUuids.CHAR_TILLER_HEADING.lowercase(),
                BleUuids.CHAR_THRUST_HEADING.lowercase() -> {
                    if (data.size >= 4) {
                        val heading = ((data[0].toInt() and 0xFF) * 256 + (data[1].toInt() and 0xFF)) / 10f
                        val speed   = ((data[2].toInt() and 0xFF) * 256 + (data[3].toInt() and 0xFF)) / 10f
                        state.copy(currentHeading = heading, speedKnots = speed)
                    } else state
                }
                BleUuids.CHAR_TILLER_STATE.lowercase(),
                BleUuids.CHAR_THRUST_STATE.lowercase() -> {
                    if (data.isNotEmpty()) {
                        val flags = data[0].toInt()
                        state.copy(
                            engaged        = flags and 0x01 != 0,
                            offCourseAlarm = flags and 0x02 != 0,
                            inDeadband     = flags and 0x04 != 0
                        )
                    } else state
                }
                BleUuids.CHAR_RUDDER_ANGLE.lowercase() -> {
                    if (data.size >= 2) {
                        val raw = (data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)
                        state.copy(rudderAngle = raw / 10f - 45f)
                    } else state
                }
                BleUuids.CHAR_PORT_THROTTLE.lowercase() -> {
                    if (data.isNotEmpty())
                        state.copy(portThrottle = (data[0].toInt() and 0xFF) / 100f)
                    else state
                }
                BleUuids.CHAR_STBD_THROTTLE.lowercase() -> {
                    if (data.isNotEmpty())
                        state.copy(starboardThrottle = (data[0].toInt() and 0xFF) / 100f)
                    else state
                }
                UUID_BAT_CHAR.toString().lowercase() -> {
                    if (data.isNotEmpty()) {
                        val pct = data[0].toInt() and 0xFF
                        state.copy(
                            batteryVoltage  = 10.5f + (pct / 100f) * 3.9f,
                            lowBatteryAlarm = pct < 20
                        )
                    } else state
                }
                else -> state
            }
        }
    }

    /**
     * Quick-parse ae02 for UI state flags only.
     * Full A1/A2/A3 parsing is handled by GpsManager.feedAe02Bytes() downstream.
     *
     * A2 packet byte[0] = 0xA2, byte[5] = gnssQuality, byte[16] = usedSV
     * We extract the engage/alarm state from the A2 quality byte as a proxy.
     */
    private fun parseHardwareAe02(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0]) {
            // A2: GNSS + heading — extract battery from ae10 feedback if available
            // For now just keep existing autopilot state (SensorFusion provides heading)
            0xA2.toByte() -> { /* heading comes from SensorFusion via GpsManager */ }
            // All raw packets forwarded via incomingData to SensorFusion
            else -> { /* no-op — forwarded via incomingData */ }
        }
    }

    // ── Send Commands ─────────────────────────────────────────────────────────

    fun sendCommand(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: return

        if (isHardwareProtocol) {
            // Real hardware: write to ae03 (WRITE_WITHOUT_RESPONSE)
            val svc  = gatt.getService(UUID_SVC_AE00) ?: gatt.getService(UUID_SVC_AE30) ?: return
            val char = svc.getCharacteristic(UUID_AE03) ?: return
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } else {
            // Custom UUID firmware
            val svc  = gatt.getService(UUID.fromString(BleUuids.serviceUuidFor(connectedType))) ?: return
            val char = svc.getCharacteristic(UUID.fromString(BleUuids.commandCharFor(connectedType))) ?: return
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }
    }

    fun engage()  = sendCommand(byteArrayOf(BleCommand.CMD_ENGAGE))
    fun standby() = sendCommand(byteArrayOf(BleCommand.CMD_STANDBY))
    fun portOne() = sendCommand(byteArrayOf(BleCommand.CMD_ADJUST_HDG, (-1).toByte()))
    fun portTen() = sendCommand(byteArrayOf(BleCommand.CMD_ADJUST_HDG, (-10).toByte()))
    fun stbdOne() = sendCommand(byteArrayOf(BleCommand.CMD_ADJUST_HDG, 1))
    fun stbdTen() = sendCommand(byteArrayOf(BleCommand.CMD_ADJUST_HDG, 10))

    fun setHeading(degrees: Float) = sendCommand(BleCommand.setHeading(degrees))
    fun setPid(config: PidConfig)  = sendCommand(BleCommand.setPid(config))

    fun readRssi() { bluetoothGatt?.readRemoteRssi() }

    fun dispose() {
        scope.cancel()
        disconnect()
    }
}