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

private const val TAG = "ImuManager"

// AC6329C hardware service UUIDs — same as on ESC_PWM / GPS_PWM / BLE_tiller
private val UUID_SVC_AE00 = java.util.UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
private val UUID_SVC_AE30 = java.util.UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
private val UUID_AE02     = java.util.UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
private val UUID_CCCD_IMU = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val SCAN_PERIOD_MS    = 15_000L
private const val CONNECT_TIMEOUT_MS = 10_000L

/**
 * ImuManager
 *
 * Manages the BLE connection to an IMU compass/attitude sensor.
 * Identified by BLE advertised name starting with "IMU_" (e.g. "IMU_PWM").
 *
 * Provides heading, pitch, roll, calibration status and temperature to both
 * Tiller and Differential Thrust autopilot types.
 *
 * When connected, the heading from [imuState] is injected into
 * AutopilotViewModel.autopilotState as the currentHeading, overriding the
 * autopilot controller's own heading characteristic.
 *
 * IMU BLE protocol (all big-endian):
 *   CHAR_IMU_HEADING     — uint16 × 0.1°   e.g. 0x0B04 = 2820 = 282.0°
 *   CHAR_IMU_PITCH_ROLL  — int16 pitch × 0.1°, int16 roll × 0.1° (4 bytes)
 *   CHAR_IMU_CALIBRATION — uint8 bit0=calibrated, bits1-2=quality (0–3)
 *   CHAR_IMU_TEMPERATURE — int16 × 0.1°C (optional, 2 bytes)
 */
@SuppressLint("MissingPermission")
class ImuManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var gatt: BluetoothGatt? = null
    private var scanJob: Job? = null

    /**
     * True when the IMU device uses the real ae00/ae30 service (AC6329C firmware).
     * IMU_PWM / GPS_PWM / ESC_PWM all use this service and stream A1/A2/A3 on ae02.
     * False = custom UUID firmware (12345678-...-90cc) with individual c1-c4 characteristics.
     */
    private var isHardwareProtocol = false

    // GATT write queue — same as BleManager, needed for descriptor sequencing
    private val pendingDescWrites = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    private var descWriteBusy     = false

    private fun queueDescWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, value: ByteArray) {
        pendingDescWrites.addLast(desc to value)
        if (!descWriteBusy) drainDescQueue(g)
    }

    private fun drainDescQueue(g: BluetoothGatt) {
        val next = pendingDescWrites.removeFirstOrNull() ?: run { descWriteBusy = false; return }
        descWriteBusy = true
        val (desc, value) = next
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(desc, value)
        } else {
            @Suppress("DEPRECATION")
            desc.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(desc)
        }
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public flows ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ImuConnectionState>(ImuConnectionState.Disconnected)
    val connectionState: StateFlow<ImuConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ImuDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ImuDevice>> = _scannedDevices.asStateFlow()

    private val _imuState = MutableStateFlow(ImuState())
    val imuState: StateFlow<ImuState> = _imuState.asStateFlow()

    /**
     * Called with every raw notification byte array from the IMU device.
     * Wire to [com.mikewen.autopilot.sensor.GpsManager.feedAe02Bytes] in the ViewModel
     * so A1/A2/A3 packets streamed by the IMU board are also fed into SensorFusion.
     */
    var onRawBytes: ((ByteArray) -> Unit)? = null

    // ── Scanning ──────────────────────────────────────────────────────────────

    fun startScan() {
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = ImuConnectionState.Error("Bluetooth is disabled")
            return
        }
        _scannedDevices.value = emptyList()
        _connectionState.value = ImuConnectionState.Scanning

        val scanner  = bluetoothAdapter!!.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // No UUID filter — identify by name starting with "IMU_"
        scanJob?.cancel()
        scanJob = scope.launch {
            scanner.startScan(emptyList(), settings, scanCallback)
            delay(SCAN_PERIOD_MS)
            scanner.stopScan(scanCallback)
            if (_connectionState.value is ImuConnectionState.Scanning) {
                _connectionState.value = ImuConnectionState.Disconnected
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value is ImuConnectionState.Scanning) {
            _connectionState.value = ImuConnectionState.Disconnected
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            // Accept "IMU_PWM", "IMU_compass", "IMU_v2", etc.
            if (!name.startsWith("IMU_", ignoreCase = true)) return

            val device = ImuDevice(
                name    = name,
                address = result.device.address,
                rssi    = result.rssi
            )
            _scannedDevices.update { list ->
                val updated = list.toMutableList()
                val idx = updated.indexOfFirst { it.address == device.address }
                if (idx >= 0) updated[idx] = device else updated.add(device)
                updated.sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = ImuConnectionState.Error("IMU scan failed: code $errorCode")
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect(device: ImuDevice) {
        stopScan()
        _connectionState.value = ImuConnectionState.Connecting(device.name)
        val btDevice = bluetoothAdapter?.getRemoteDevice(device.address) ?: return

        scope.launch {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                gatt = btDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } ?: run {
                _connectionState.value = ImuConnectionState.Error("IMU connection timed out")
            }
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isHardwareProtocol = false
        pendingDescWrites.clear()
        descWriteBusy = false
        _connectionState.value = ImuConnectionState.Disconnected
        _imuState.value = ImuState()
    }

    // ── GATT Callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "IMU connected, requesting MTU…")
                    g.requestMtu(64)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ImuConnectionState.Disconnected
                    gatt?.close()
                    gatt = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNotifications(g)
                val name = g.device.name ?: "IMU"
                _connectionState.value = ImuConnectionState.Connected(name, -70)
                Log.d(TAG, "IMU services discovered OK")
            } else {
                _connectionState.value = ImuConnectionState.Error("IMU service discovery failed")
            }
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onRawBytes?.invoke(value)
            parseNotification(characteristic.uuid.toString(), value)
        }

        // API < 33 — must use block body; expression body with return is not allowed
        @Deprecated("Kept for API < 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            onRawBytes?.invoke(value)
            parseNotification(characteristic.uuid.toString(), value)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            val s = _connectionState.value
            if (s is ImuConnectionState.Connected) {
                _connectionState.value = s.copy(rssi = rssi)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) { drainDescQueue(g) }
    }

    // ── Enable notifications ──────────────────────────────────────────────────

    private fun enableNotifications(g: BluetoothGatt) {
        // Detect hardware protocol: ae00/ae30 = AC6329C chip (IMU_PWM, GPS_PWM, ESC_PWM)
        val hwSvc = g.getService(UUID_SVC_AE00) ?: g.getService(UUID_SVC_AE30)
        isHardwareProtocol = hwSvc != null

        Log.d(TAG, "IMU protocol: ${if (isHardwareProtocol) "ae00/ae30 hardware" else "custom UUID"}")

        if (isHardwareProtocol) {
            // ae02 streams A1/A2/A3 packets — subscribe to it.
            // All raw bytes are forwarded via onRawBytes → GpsManager.feedAe02Bytes().
            val ae02 = hwSvc!!.getCharacteristic(UUID_AE02) ?: run {
                Log.w(TAG, "ae02 not found on IMU device")
                return
            }
            g.setCharacteristicNotification(ae02, true)
            ae02.getDescriptor(UUID_CCCD_IMU)?.let { desc ->
                queueDescWrite(g, desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            Log.d(TAG, "Subscribed to ae02 on IMU_PWM — A1 packets will flow")
        } else {
            // Custom UUID firmware: subscribe to individual c1-c4 characteristics
            val svc = g.getService(java.util.UUID.fromString(BleUuids.SERVICE_IMU)) ?: run {
                Log.w(TAG, "IMU service ${BleUuids.SERVICE_IMU} not found")
                return
            }
            listOf(
                BleUuids.CHAR_IMU_HEADING,
                BleUuids.CHAR_IMU_PITCH_ROLL,
                BleUuids.CHAR_IMU_CALIBRATION,
                BleUuids.CHAR_IMU_TEMPERATURE
            ).forEach { uuid ->
                svc.getCharacteristic(java.util.UUID.fromString(uuid))?.let { char ->
                    g.setCharacteristicNotification(char, true)
                    char.getDescriptor(java.util.UUID.fromString(BleUuids.DESCRIPTOR_CCCD))
                        ?.let { desc -> queueDescWrite(g, desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                }
            }
        }
    }

    // ── Parse IMU notifications ───────────────────────────────────────────────

    private fun parseNotification(uuid: String, data: ByteArray) {
        _imuState.update { state ->
            when (uuid.lowercase()) {

                // Heading: uint16 big-endian, value = degrees × 10
                // e.g. 0x0B 0x04 = 2820 = 282.0°
                BleUuids.CHAR_IMU_HEADING.lowercase() -> {
                    if (data.size >= 2) {
                        val raw = (data[0].toInt() and 0xFF) * 256 + (data[1].toInt() and 0xFF)
                        state.copy(heading = raw / 10f)
                    } else state
                }

                // Pitch + Roll: two int16 big-endian, each × 0.1°
                // bytes[0..1] = pitch, bytes[2..3] = roll
                BleUuids.CHAR_IMU_PITCH_ROLL.lowercase() -> {
                    if (data.size >= 4) {
                        val pitchRaw = (data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)
                        val rollRaw  = (data[2].toInt() shl 8) or (data[3].toInt() and 0xFF)
                        state.copy(pitch = pitchRaw / 10f, roll = rollRaw / 10f)
                    } else state
                }

                // Calibration status: uint8
                // bit 0 = system calibrated, bits 1-2 = quality 0–3
                BleUuids.CHAR_IMU_CALIBRATION.lowercase() -> {
                    if (data.isNotEmpty()) {
                        val cal = data[0].toInt() and 0xFF
                        state.copy(calibrated = (cal and 0x01) != 0)
                    } else state
                }

                // Temperature: int16 big-endian, value = °C × 10
                BleUuids.CHAR_IMU_TEMPERATURE.lowercase() -> {
                    if (data.size >= 2) {
                        val raw = (data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)
                        state.copy(temperature = raw / 10f)
                    } else state
                }

                else -> state
            }
        }
    }

    fun readRssi() { gatt?.readRemoteRssi() }

    fun dispose() {
        scope.cancel()
        disconnect()
    }
}