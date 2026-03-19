package com.mikewen.autopilot.model

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────
// Autopilot type selection
// ─────────────────────────────────────────────

enum class AutopilotType(val displayName: String, val description: String) {
    TILLER(
        displayName = "Tiller",
        description = "Linear motor + rudder position sensor"
    ),
    DIFF_THRUST(
        displayName = "Differential Thrust",
        description = "Dual motor / throttle control"
    )
}

// ─────────────────────────────────────────────
// BLE Connection State
// ─────────────────────────────────────────────

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceName: String) : BleConnectionState()
    data class Connected(val deviceName: String, val rssi: Int) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val type: AutopilotType?
)

// ─────────────────────────────────────────────
// Autopilot Operating State
// ─────────────────────────────────────────────

enum class AutopilotMode {
    STANDBY,
    HEADING_HOLD,
    WAYPOINT,
    TRACK
}

data class AutopilotState(
    val mode: AutopilotMode = AutopilotMode.STANDBY,
    val engaged: Boolean = false,

    val currentHeading: Float = 0f,
    val targetHeading: Float = 0f,

    val speedKnots: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,

    // Tiller
    val rudderAngle: Float = 0f,
    val rudderTarget: Float = 0f,

    // Diff-thrust
    val portThrottle: Float = 0f,
    val starboardThrottle: Float = 0f,

    // PID / deadband
    val headingError: Float = 0f,
    val pidOutput: Float = 0f,
    val inDeadband: Boolean = false,

    // Alarms
    val offCourseAlarm: Boolean = false,
    val lowBatteryAlarm: Boolean = false,
    val batteryVoltage: Float = 12.6f
)

// ─────────────────────────────────────────────
// PID + Deadband Configuration
// ─────────────────────────────────────────────

@Serializable
data class PidConfig(
    val kp: Float = 1.5f,
    val ki: Float = 0.05f,
    val kd: Float = 0.3f,
    val outputLimit: Float = 30f,
    // Within ±deadbandDeg the controller outputs zero —
    // prevents motor hunting on a boat that naturally yaws in waves.
    val deadbandDeg: Float = 3.0f,
    // Off-course alarm threshold (separate from deadband, typically larger)
    val offCourseAlarmDeg: Float = 15.0f
)

// ─────────────────────────────────────────────
// Pure-Kotlin PID controller (runs on phone for sim & standalone testing)
// ─────────────────────────────────────────────

class PidController(private var config: PidConfig) {

    private var integral: Float = 0f
    private var lastError: Float = 0f
    private var lastTime: Long = System.currentTimeMillis()

    fun updateConfig(c: PidConfig) {
        config = c
        integral = 0f   // reset on config change to avoid wind-up spike
    }

    fun reset() {
        integral = 0f
        lastError = 0f
        lastTime = System.currentTimeMillis()
    }

    /**
     * Compute one PID step.
     * Heading error is wrapped to [-180, +180] so crossing north (e.g. 359°→1°) works correctly.
     */
    fun compute(currentHeading: Float, targetHeading: Float): PidResult {
        val now = System.currentTimeMillis()
        val dt = ((now - lastTime) / 1000f).coerceIn(0.01f, 0.5f)
        lastTime = now

        // Wrap error to [-180, +180]
        var error = targetHeading - currentHeading
        while (error > 180f)  error -= 360f
        while (error < -180f) error += 360f

        // ── Deadband check ───────────────────────────────────────────────────
        if (kotlin.math.abs(error) <= config.deadbandDeg) {
            integral = 0f       // anti-windup: zero integral inside deadband
            lastError = error
            return PidResult(
                output     = 0f,
                error      = error,
                inDeadband = true,
                offCourse  = kotlin.math.abs(error) > config.offCourseAlarmDeg
            )
        }

        // ── Proportional ─────────────────────────────────────────────────────
        val p = config.kp * error

        // ── Integral with anti-windup clamp ──────────────────────────────────
        integral += error * dt
        val maxIntegral = config.outputLimit / config.ki.coerceAtLeast(0.001f)
        integral = integral.coerceIn(-maxIntegral, maxIntegral)
        val i = config.ki * integral

        // ── Derivative ───────────────────────────────────────────────────────
        val d = config.kd * (error - lastError) / dt
        lastError = error

        val output = (p + i + d).coerceIn(-config.outputLimit, config.outputLimit)

        return PidResult(
            output     = output,
            error      = error,
            inDeadband = false,
            offCourse  = kotlin.math.abs(error) > config.offCourseAlarmDeg
        )
    }
}

data class PidResult(
    val output: Float,
    val error: Float,
    val inDeadband: Boolean,
    val offCourse: Boolean
)

// ─────────────────────────────────────────────
// Waypoint
// ─────────────────────────────────────────────

@Serializable
data class Waypoint(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double
)

// ─────────────────────────────────────────────
// BLE Protocol Commands
// ─────────────────────────────────────────────

object BleCommand {
    const val CMD_ENGAGE       = 0x01.toByte()
    const val CMD_STANDBY      = 0x02.toByte()
    const val CMD_SET_HDG      = 0x03.toByte()
    const val CMD_ADJUST_HDG   = 0x04.toByte()
    const val CMD_SET_PID      = 0x05.toByte()
    const val CMD_SET_MODE     = 0x06.toByte()
    const val CMD_SET_DEADBAND = 0x07.toByte()
    const val CMD_PORT_MINUS   = 0x10.toByte()
    const val CMD_PORT_PLUS    = 0x11.toByte()
    const val CMD_STBD_MINUS   = 0x12.toByte()
    const val CMD_STBD_PLUS    = 0x13.toByte()

    fun setHeading(degrees: Float): ByteArray {
        val hdg = degrees.toInt().coerceIn(0, 359)
        return byteArrayOf(CMD_SET_HDG, (hdg shr 8).toByte(), hdg.toByte())
    }

    fun adjustHeading(delta: Int): ByteArray {
        return byteArrayOf(CMD_ADJUST_HDG, delta.coerceIn(-10, 10).toByte())
    }

    fun setPid(config: PidConfig): ByteArray {
        fun f(v: Float) = (v * 100).toInt().coerceIn(0, 9999)
        val kp = f(config.kp); val ki = f(config.ki); val kd = f(config.kd)
        return byteArrayOf(
            CMD_SET_PID,
            (kp shr 8).toByte(), kp.toByte(),
            (ki shr 8).toByte(), ki.toByte(),
            (kd shr 8).toByte(), kd.toByte()
        )
    }

    /** Deadband encoded as tenths of a degree (e.g. 2.0° → 20) */
    fun setDeadband(degrees: Float): ByteArray {
        val v = (degrees * 10).toInt().coerceIn(0, 900)
        return byteArrayOf(CMD_SET_DEADBAND, (v shr 8).toByte(), v.toByte())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BLE UUIDs
//
// Device naming convention (BLE advertised name):
//   "BLE_tiller"           → Tiller autopilot  (linear motor + rudder sensor)
//   "ESC_PWM" / "BLDC_PWM" → Differential thrust autopilot (dual motor / ESC)
//
// Each device type has its own service UUID so both can be scanned
// simultaneously without ambiguity.
// ─────────────────────────────────────────────────────────────────────────────

object BleUuids {

    // ── Tiller service  (advertised name: "BLE_tiller") ──────────────────────
    const val SERVICE_TILLER           = "12345678-1234-1234-1234-1234567890aa"
    const val CHAR_TILLER_COMMAND      = "12345678-1234-1234-1234-1234567890a1"  // Write
    const val CHAR_TILLER_STATE        = "12345678-1234-1234-1234-1234567890a2"  // Notify: flags
    const val CHAR_TILLER_HEADING      = "12345678-1234-1234-1234-1234567890a3"  // Notify: heading+speed
    const val CHAR_TILLER_PID         = "12345678-1234-1234-1234-1234567890a4"  // Read/Write
    const val CHAR_RUDDER_ANGLE        = "12345678-1234-1234-1234-1234567890a5"  // Notify: rudder pos
    const val CHAR_LINEAR_MOTOR_STATUS = "12345678-1234-1234-1234-1234567890a6"  // Notify: motor state

    // ── Differential thrust service  (advertised name: "ESC_PWM" / "BLDC_PWM") ──
    const val SERVICE_DIFF_THRUST      = "12345678-1234-1234-1234-1234567890bb"
    const val CHAR_THRUST_COMMAND      = "12345678-1234-1234-1234-1234567890b1"  // Write
    const val CHAR_THRUST_STATE        = "12345678-1234-1234-1234-1234567890b2"  // Notify: flags
    const val CHAR_THRUST_HEADING      = "12345678-1234-1234-1234-1234567890b3"  // Notify: heading+speed
    const val CHAR_THRUST_PID         = "12345678-1234-1234-1234-1234567890b4"  // Read/Write
    const val CHAR_PORT_THROTTLE       = "12345678-1234-1234-1234-1234567890b5"  // Notify: port %
    const val CHAR_STBD_THROTTLE       = "12345678-1234-1234-1234-1234567890b6"  // Notify: stbd %

    // ── Standard Battery service (both devices) ───────────────────────────
    const val SERVICE_BATTERY          = "0000180f-0000-1000-8000-00805f9b34fb"
    const val CHAR_BATTERY_LEVEL       = "00002a19-0000-1000-8000-00805f9b34fb"

    // ── CCCD descriptor (subscribe to notifications) ────────────────────────
    const val DESCRIPTOR_CCCD          = "00002902-0000-1000-8000-00805f9b34fb"

    // ── Helpers: resolve correct UUID for the connected device type ───────────
    fun serviceUuidFor(type: AutopilotType) = when (type) {
        AutopilotType.TILLER      -> SERVICE_TILLER
        AutopilotType.DIFF_THRUST -> SERVICE_DIFF_THRUST
    }
    fun commandCharFor(type: AutopilotType) = when (type) {
        AutopilotType.TILLER      -> CHAR_TILLER_COMMAND
        AutopilotType.DIFF_THRUST -> CHAR_THRUST_COMMAND
    }
    fun headingCharFor(type: AutopilotType) = when (type) {
        AutopilotType.TILLER      -> CHAR_TILLER_HEADING
        AutopilotType.DIFF_THRUST -> CHAR_THRUST_HEADING
    }
    fun stateCharFor(type: AutopilotType) = when (type) {
        AutopilotType.TILLER      -> CHAR_TILLER_STATE
        AutopilotType.DIFF_THRUST -> CHAR_THRUST_STATE
    }
}