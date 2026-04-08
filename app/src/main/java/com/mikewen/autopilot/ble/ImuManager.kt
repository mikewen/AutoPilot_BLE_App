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
    }

    // ── Enable notifications ──────────────────────────────────────────────────

    private fun enableNotifications(g: BluetoothGatt) {
        val service = g.getService(UUID.fromString(BleUuids.SERVICE_IMU)) ?: run {
            Log.w(TAG, "IMU service ${BleUuids.SERVICE_IMU} not found — check UUID in firmware")
            return
        }
        listOf(
            BleUuids.CHAR_IMU_HEADING,
            BleUuids.CHAR_IMU_PITCH_ROLL,
            BleUuids.CHAR_IMU_CALIBRATION,
            BleUuids.CHAR_IMU_TEMPERATURE
        ).forEach { uuid ->
            service.getCharacteristic(UUID.fromString(uuid))?.let { char ->
                g.setCharacteristicNotification(char, true)
                char.getDescriptor(UUID.fromString(BleUuids.DESCRIPTOR_CCCD))?.let { desc ->
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
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