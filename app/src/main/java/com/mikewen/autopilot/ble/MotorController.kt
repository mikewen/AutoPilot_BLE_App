package com.mikewen.autopilot.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import java.util.UUID

/**
 * MotorController — Generic BLE motor controller for dual-motor autopilot.
 *
 * Supports two drive modes over the same BLE service (ae00 / ae30):
 *
 *   ESC mode  (CMD_ESC_PWM  0x01): standard 50Hz RC PWM, duty 500–1000
 *     500  = 1000µs (stop/arm)
 *     750  = 1500µs (mid / neutral)
 *     1000 = 2000µs (full throttle)
 *
 *   BLDC mode (CMD_BLDC_DUTY 0x02): direct duty cycle 0–10000
 *     0     = 0%   (stop)
 *     10000 = 100% (full)
 *
 * Both port and starboard channels are sent in a single 5-byte packet,
 * allowing differential thrust control with one write.
 *
 * BLE Service: ae00 (alias ae30 on some firmware builds)
 *   ae03  WRITE_WITHOUT_RESPONSE — 5-byte command packet
 *   ae02  NOTIFY                 — echo / CASIC GNSS / IMU stream
 *   ae10  READ | WRITE           — status read / mode switch
 *
 * Packet format (ae03, little-endian):
 *   byte[0]       CMD
 *   byte[1..2]    port  duty  (uint16 LE)
 *   byte[3..4]    stbd  duty  (uint16 LE)
 *
 * ae10 status string: "M<mode>A<vbat_mv>T<uptime_min>"
 */
class MotorController(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "MotorController"

        // Service UUIDs — firmware may advertise either
        val SERVICE_AE00_UUID: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
        val SERVICE_AE30_UUID: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")

        val CHAR_AE03_UUID: UUID = UUID.fromString("0000ae03-0000-1000-8000-00805f9b34fb")
        val CHAR_AE02_UUID: UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
        val CHAR_AE10_UUID: UUID = UUID.fromString("0000ae10-0000-1000-8000-00805f9b34fb")

        // Command bytes
        const val CMD_ESC_PWM:    Byte = 0x01
        const val CMD_BLDC_DUTY:  Byte = 0x02
        const val CMD_STOP:       Byte = 0xFF.toByte()

        // ESC duty range — timer counts, 50Hz period
        // 500 = 1000µs (stop/arm)  750 = 1500µs (neutral)  1000 = 2000µs (full)
        const val ESC_MIN     = 500
        const val ESC_NEUTRAL = 750
        const val ESC_MAX     = 1000

        // BLDC duty range — 0 = stop, 10000 = 100%
        const val BLDC_MIN = 0
        const val BLDC_MAX = 10000
    }

    // ── Drive mode ────────────────────────────────────────────────────────────

    enum class DriveMode { ESC_PWM, BLDC_DUTY }

    /** Current drive mode — set before connecting or call [setEscMode]/[setBldcMode] at runtime. */
    var driveMode: DriveMode = DriveMode.ESC_PWM
        private set

    // ── Characteristics ───────────────────────────────────────────────────────

    private var charAe03: BluetoothGattCharacteristic? = null
    private var charAe02: BluetoothGattCharacteristic? = null
    private var charAe10: BluetoothGattCharacteristic? = null

    // ── Callbacks ─────────────────────────────────────────────────────────────

    /** Raw ae02 bytes forwarded to [GpsManager.feedAe02Bytes] for A1/A2/A3/CASIC parsing. */
    var onAe02Raw:  ((ByteArray)       -> Unit)? = null
    var onFeedback: ((FeedbackData)    -> Unit)? = null
    var onError:    ((String)          -> Unit)? = null

    // ── Nordic BleManager overrides ───────────────────────────────────────────

    override fun getGattCallback(): BleManagerGattCallback = MotorGattCallback()

    private inner class MotorGattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val svc = gatt.getService(SERVICE_AE00_UUID)
                ?: gatt.getService(SERVICE_AE30_UUID)
                ?: return false

            Log.i(TAG, "Motor service found: ${svc.uuid}")
            charAe03 = svc.getCharacteristic(CHAR_AE03_UUID)
            charAe02 = svc.getCharacteristic(CHAR_AE02_UUID)
            charAe10 = svc.getCharacteristic(CHAR_AE10_UUID)
            return charAe03 != null
        }

        override fun initialize() {
            charAe02?.let { c ->
                setNotificationCallback(c).with { _, data ->
                    data.value?.let { bytes ->
                        onAe02Raw?.invoke(bytes)   // forward to GpsManager
                        parseAe02Echo(bytes)
                    }
                }
                enableNotifications(c).enqueue()
            }
        }

        override fun onServicesInvalidated() {
            charAe03 = null; charAe02 = null; charAe10 = null
        }
    }

    // ── Drive commands ────────────────────────────────────────────────────────

    /**
     * Send ESC PWM command.
     * Duty range: [ESC_MIN]=500 (stop/arm) … [ESC_MAX]=1000 (full throttle).
     * Corresponds to 1000µs–2000µs at 50Hz.
     */
    fun sendEscPwm(portDuty: Int, stbdDuty: Int) {
        val p = portDuty.coerceIn(ESC_MIN, ESC_MAX)
        val s = stbdDuty.coerceIn(ESC_MIN, ESC_MAX)
        Log.d(TAG, "ESC duty port=$p stbd=$s  (${dutyToUs(p)}µs / ${dutyToUs(s)}µs)")
        writeCommand(buildPacket(CMD_ESC_PWM, p, s))
        driveMode = DriveMode.ESC_PWM
    }

    /**
     * Send BLDC duty command.
     * Duty range: 0 (stop) … 10000 (100%).
     */
    fun sendBldcDuty(portDuty: Int, stbdDuty: Int) {
        val p = portDuty.coerceIn(BLDC_MIN, BLDC_MAX)
        val s = stbdDuty.coerceIn(BLDC_MIN, BLDC_MAX)
        Log.d(TAG, "BLDC duty port=$p stbd=$s")
        writeCommand(buildPacket(CMD_BLDC_DUTY, p, s))
        driveMode = DriveMode.BLDC_DUTY
    }

    /**
     * Unified send: dispatches to [sendEscPwm] or [sendBldcDuty] based on [driveMode].
     * Values are always 0.0–1.0 (normalised throttle fraction).
     *   ESC:   0.0 → duty 500 (stop), 1.0 → duty 1000 (full)
     *   BLDC:  0.0 → duty 0,          1.0 → duty 10000
     */
    fun sendThrottle(portFraction: Float, stbdFraction: Float) {
        val p = portFraction.coerceIn(0f, 1f)
        val s = stbdFraction.coerceIn(0f, 1f)
        when (driveMode) {
            DriveMode.ESC_PWM   -> sendEscPwm(
                portDuty = (ESC_MIN + p * (ESC_MAX - ESC_MIN)).toInt(),
                stbdDuty = (ESC_MIN + s * (ESC_MAX - ESC_MIN)).toInt()
            )
            DriveMode.BLDC_DUTY -> sendBldcDuty(
                portDuty = (p * BLDC_MAX).toInt(),
                stbdDuty = (s * BLDC_MAX).toInt()
            )
        }
    }

    /** Stop both motors immediately. */
    fun stopMotors() {
        Log.d(TAG, "STOP")
        writeCommand(buildPacket(CMD_STOP, 0, 0))
    }

    /** Arm ESC by sending the minimum duty for both channels. */
    fun armEsc() = sendEscPwm(ESC_MIN, ESC_MIN)

    // ── Mode switching (ae10 write) ───────────────────────────────────────────

    /** Switch firmware to ESC / RC-PWM mode. */
    fun setEscMode() {
        driveMode = DriveMode.ESC_PWM
        charAe10?.let {
            writeCharacteristic(it, byteArrayOf(0x01), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
        }
    }

    /** Switch firmware to BLDC direct-duty mode. */
    fun setBldcMode() {
        driveMode = DriveMode.BLDC_DUTY
        charAe10?.let {
            writeCharacteristic(it, byteArrayOf(0x02), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
        }
    }

    // ── Status read (ae10) ────────────────────────────────────────────────────

    /** Read status string: "M<mode>A<vbat_mv>T<uptime_min>" */
    fun readStatus() {
        charAe10?.let {
            readCharacteristic(it).with { _, data ->
                val raw = data.value?.toString(Charsets.UTF_8) ?: return@with
                onFeedback?.invoke(parseAe10Status(raw))
            }.enqueue()
        }
    }

    // ── Battery helper ────────────────────────────────────────────────────────

    /** Convert battery millivolts to a 0–100% estimate (LiPo curve). */
    fun battMvToPercent(mv: Int): Int = when {
        mv >= 4200 -> 100
        mv >= 3700 -> 60 + (mv - 3700) * 40 / 500
        mv >= 3500 -> 20 + (mv - 3500) * 40 / 200
        mv >= 3300 -> (mv - 3300) * 20 / 200
        else       -> 0
    }.coerceIn(0, 100)

    /** Convert ESC duty unit to microseconds (1 duty unit = 2µs at 50Hz). */
    fun dutyToUs(duty: Int): Int = duty * 2

    // ── Connection helper ─────────────────────────────────────────────────────

    fun connectToDevice(device: BluetoothDevice) {
        connect(device).useAutoConnect(false).retry(3, 200).enqueue()
    }

    // ── Packet builder ────────────────────────────────────────────────────────

    private fun buildPacket(cmd: Byte, port: Int, stbd: Int): ByteArray = byteArrayOf(
        cmd,
        (port and 0xFF).toByte(), ((port shr 8) and 0xFF).toByte(),
        (stbd and 0xFF).toByte(), ((stbd shr 8) and 0xFF).toByte()
    )

    private fun writeCommand(bytes: ByteArray) {
        charAe03?.let {
            writeCharacteristic(it, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue()
        }
    }

    // ── ae02 echo parser ──────────────────────────────────────────────────────

    private fun parseAe02Echo(bytes: ByteArray) {
        if (bytes.size < 5) return
        val cmd     = bytes[0]
        val portVal = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        val stbdVal = (bytes[3].toInt() and 0xFF) or ((bytes[4].toInt() and 0xFF) shl 8)
        onFeedback?.invoke(FeedbackData(
            source   = "ae02-echo",
            echoCmd  = cmd.toInt() and 0xFF,
            echoPort = portVal,
            echoStbd = stbdVal,
            rawAe02  = bytes
        ))
    }

    // ── ae10 status parser ────────────────────────────────────────────────────

    private fun parseAe10Status(raw: String): FeedbackData {
        val mode      = Regex("M(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val battMv    = Regex("A(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val uptimeMin = Regex("T(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return FeedbackData(source = "ae10-read", batteryMv = battMv, uptimeMin = uptimeMin, rawAe10 = raw)
    }

    // ── Feedback data ─────────────────────────────────────────────────────────

    data class FeedbackData(
        val source:    String    = "",
        val batteryMv: Int       = 0,
        val uptimeMin: Int       = -1,
        val rawAe10:   String    = "",
        val echoCmd:   Int       = -1,
        val echoPort:  Int       = -1,
        val echoStbd:  Int       = -1,
        val rawAe02:   ByteArray = ByteArray(0)
    )
}
