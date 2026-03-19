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
private const val SCAN_PERIOD_MS = 15_000L
private const val CONNECT_TIMEOUT_MS = 10_000L
private const val GATT_MTU = 64

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedType: AutopilotType = AutopilotType.TILLER  // set on connect
    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public Flows ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _autopilotState = MutableStateFlow(AutopilotState())
    val autopilotState: StateFlow<AutopilotState> = _autopilotState.asStateFlow()

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

        val scanner = bluetoothAdapter!!.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // No service UUID filter — scan all BLE devices and identify autopilots
        // by device name in the scan callback. This works regardless of whether
        // the ESP32 firmware advertises our custom service UUIDs.
        // Known names:  "BLE_tiller" → Tiller,  "ESC_PWM" / "BLDC_PWM" → Diff thrust

        scanJob?.cancel()
        scanJob = scope.launch {
            scanner.startScan(emptyList(), settings, scanCallback)
            delay(SCAN_PERIOD_MS)
            scanner.stopScan(scanCallback)
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            val address = device.address
            val rssi = result.rssi

            // Identify autopilot type from advertised BLE device name:
            //   "BLE_tiller"            -> Tiller (linear motor + rudder sensor)
            //   "ESC_PWM" / "BLDC_PWM"  -> Differential thrust (dual ESC/motor)
            // Only these known names are shown — other BLE devices are ignored.
            val type = when {
                name.equals("BLE_tiller", ignoreCase = true)  -> AutopilotType.TILLER
                name.equals("ESC_PWM",    ignoreCase = true)  -> AutopilotType.DIFF_THRUST
                name.equals("BLDC_PWM",   ignoreCase = true)  -> AutopilotType.DIFF_THRUST
                // Substring fallback for firmware variants e.g. "ESC_PWM_v2"
                name.contains("tiller",   ignoreCase = true)  -> AutopilotType.TILLER
                name.contains("ESC_PWM",  ignoreCase = true)  -> AutopilotType.DIFF_THRUST
                name.contains("BLDC_PWM", ignoreCase = true)  -> AutopilotType.DIFF_THRUST
                else -> null
            }

            // Skip devices that are not autopilots
            if (type == null) return

            val bleDevice = BleDevice(name, address, rssi, type)
            _scannedDevices.update { list ->
                val updated = list.toMutableList()
                val idx = updated.indexOfFirst { it.address == address }
                if (idx >= 0) updated[idx] = bleDevice else updated.add(bleDevice)
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
        connectedType = device.type ?: AutopilotType.TILLER  // remember type for UUID routing
        _connectionState.value = BleConnectionState.Connecting(device.name)
        val btDevice = bluetoothAdapter?.getRemoteDevice(device.address) ?: return

        scope.launch {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                bluetoothGatt = btDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } ?: run {
                _connectionState.value = BleConnectionState.Error("Connection timed out")
            }
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = BleConnectionState.Disconnected
        _autopilotState.value = AutopilotState()
    }

    // ── GATT Callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, discovering services…")
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNotifications(gatt)
                val name = gatt.device.name ?: "AutoPilot"
                _connectionState.value = BleConnectionState.Connected(name, -70)
                Log.d(TAG, "Services discovered OK")
            } else {
                _connectionState.value = BleConnectionState.Error("Service discovery failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            scope.launch { _incomingData.emit(value) }
            parseNotification(characteristic.uuid.toString(), value)
        }

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
            if (current is BleConnectionState.Connected) {
                _connectionState.value = current.copy(rssi = rssi)
            }
        }
    }

    // ── Notification setup ────────────────────────────────────────────────────

    private fun enableNotifications(gatt: BluetoothGatt) {
        // Build notify list based on connected device type
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
        val allNotifyUuids = typeNotifyUuids + BleUuids.CHAR_BATTERY_LEVEL

        allNotifyUuids.forEach { uuid ->
            // Battery is in its own standard service; all others in the autopilot service
            val svc = if (uuid == BleUuids.CHAR_BATTERY_LEVEL)
                gatt.getService(UUID.fromString(BleUuids.SERVICE_BATTERY))
            else
                gatt.getService(UUID.fromString(serviceUuid))

            svc?.getCharacteristic(UUID.fromString(uuid))?.let { char ->
                gatt.setCharacteristicNotification(char, true)
                char.getDescriptor(UUID.fromString(BleUuids.DESCRIPTOR_CCCD))?.let { desc ->
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(desc)
                }
            }
        }
    }

    // ── Parse incoming BLE data ───────────────────────────────────────────────

    private fun parseNotification(uuid: String, data: ByteArray) {
        val u = uuid.lowercase()
        _autopilotState.update { state ->
            when (u) {
                // ── Heading + speed (both device types) ──────────────────────
                BleUuids.CHAR_TILLER_HEADING.lowercase(),
                BleUuids.CHAR_THRUST_HEADING.lowercase() -> {
                    if (data.size >= 4) {
                        val heading = ((data[0].toInt() and 0xFF) * 256 + (data[1].toInt() and 0xFF)) / 10f
                        val speed   = ((data[2].toInt() and 0xFF) * 256 + (data[3].toInt() and 0xFF)) / 10f
                        state.copy(currentHeading = heading, speedKnots = speed)
                    } else state
                }
                // ── Engaged / alarm flags (both device types) ────────────────
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
                // ── Tiller: rudder angle ──────────────────────────────────────
                BleUuids.CHAR_RUDDER_ANGLE.lowercase() -> {
                    if (data.size >= 2) {
                        val raw = (data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)
                        state.copy(rudderAngle = raw / 10f - 45f)
                    } else state
                }
                // ── Diff-thrust: throttle channels ────────────────────────────
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
                // ── Battery (standard BLE battery service) ────────────────────
                BleUuids.CHAR_BATTERY_LEVEL.lowercase() -> {
                    if (data.isNotEmpty()) {
                        val pct = data[0].toInt() and 0xFF
                        state.copy(
                            batteryVoltage = 10.5f + (pct / 100f) * 3.9f,
                            lowBatteryAlarm = pct < 20
                        )
                    } else state
                }
                else -> state
            }
        }
    }

    // ── Send Commands ─────────────────────────────────────────────────────────

    fun sendCommand(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(UUID.fromString(BleUuids.serviceUuidFor(connectedType))) ?: return
        val char = service.getCharacteristic(UUID.fromString(BleUuids.commandCharFor(connectedType))) ?: return

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    fun engage()   = sendCommand(byteArrayOf(BleCommand.CMD_ENGAGE))
    fun standby()  = sendCommand(byteArrayOf(BleCommand.CMD_STANDBY))
    fun portOne()  = sendCommand(byteArrayOf(BleCommand.CMD_ADJUST_HDG, (-1).toByte()))
    fun portTen()  = sendCommand(byteArrayOf(BleCommand.CMD_ADJUST_HDG, (-10).toByte()))
    fun stbdOne()  = sendCommand(byteArrayOf(BleCommand.CMD_ADJUST_HDG, 1))
    fun stbdTen()  = sendCommand(byteArrayOf(BleCommand.CMD_ADJUST_HDG, 10))

    fun setHeading(degrees: Float) = sendCommand(BleCommand.setHeading(degrees))
    fun setPid(config: PidConfig)  = sendCommand(BleCommand.setPid(config))

    fun readRssi() { bluetoothGatt?.readRemoteRssi() }

    fun dispose() {
        scope.cancel()
        disconnect()
    }
}