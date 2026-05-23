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
        description = "Dual ESC / motor control"
    ),
    THRUST_VECTOR(
        displayName = "Thrust Vector",
        description = "Single motor with vectored nozzle / servo rudder"
    )
}

/**
 * BoatProfile — each profile stores its own PidConfig independently.
 * Switch profile to instantly recall per-boat tuning.
 */
enum class BoatProfile(val displayName: String, val icon: String, val description: String) {
    CL16(
        displayName = "CL16",
        icon        = "⛵",
        description = "CL 16 — tiller autopilot"
    ),
    MAC25(
        displayName = "Mac25",
        icon        = "🚢",
        description = "Macgregor 25 — main boat"
    ),
    TOY(
        displayName = "Toy",
        icon        = "🛥",
        description = "Test / toy boat — development"
    )
}

// ─────────────────────────────────────────────
// Autopilot BLE Connection State
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
// IMU Sensor — Connection State, Device, Data
// ─────────────────────────────────────────────

/** Connection state for the separate IMU compass/attitude sensor. */
sealed class ImuConnectionState {
    object Disconnected : ImuConnectionState()
    object Scanning : ImuConnectionState()
    data class Connecting(val deviceName: String) : ImuConnectionState()
    data class Connected(val deviceName: String, val rssi: Int) : ImuConnectionState()
    data class Error(val message: String) : ImuConnectionState()
}

/**
 * A discovered IMU device.
 * Matched by BLE advertised name starting with "IMU_" (e.g. "IMU_PWM", "IMU_compass").
 */
data class ImuDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

/**
 * Live telemetry from the IMU sensor.
 *
 * [heading] is injected into autopilotState.currentHeading when the IMU is connected,
 * overriding the autopilot controller's own heading characteristic.
 * [pitch] and [roll] are shown in the dashboard attitude card.
 * [calibrated] reflects the IMU firmware's calibration status (bit 0 of calibration byte).
 */
data class ImuState(
    val heading:     Float   = 0f,       // degrees 0–359, magnetic compass
    val pitch:       Float   = 0f,       // degrees, positive = bow up
    val roll:        Float   = 0f,       // degrees, positive = starboard down
    val temperature: Float   = 0f,       // °C (optional, 0 if not available)
    val calibrated:  Boolean = false     // true when IMU reports good calibration
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
    val rudderAngle:    Float  = 0f,
    val rudderTarget:   Float  = 0f,
    val shaftAngleDeg:  Float? = null,  // from A5 MMC5603 shaft sensor

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
    val kp:                Float = 1.5f,
    val ki:                Float = 0.05f,
    val kd:                Float = 0.3f,

    // Output limit in degrees of rudder / throttle differential
    val outputLimitDeg:    Float = 30f,

    // Rate limit: max change in output per second (°/s).
    // Prevents sudden large commands that could stall a motor or snap a tiller.
    // 0 = disabled.
    val rateLimitDegPerSec: Float = 60f,

    // Heading error smaller than this suppresses PID output and resets integral.
    val deadbandDeg:       Float = 3.0f,

    // Steering bias: constant offset added to output to compensate for
    // hull asymmetry or propeller walk. Positive = port correction.
    val steeringBiasDeg:   Float = 0f,

    // Off-course alarm threshold
    val offCourseAlarmDeg: Float = 15.0f,

    // Speed scaling: above this speed (knots) the P and D gains are scaled down
    // proportionally, reaching minSpeedScale at maxScaleSpeedKt.
    // Set maxScaleSpeedKt = 0 to disable speed scaling.
    val maxScaleSpeedKt:   Float = 6.0f,    // speed at which scaling reaches minimum
    val minSpeedScale:     Float = 0.4f,    // minimum gain multiplier at high speed

    // GPS_Steer steer motor scale: runtimeMs = steerScaleMs * abs(step)
    // step=1 → one small tap, step=5 → larger move
    // Default 200 ms per step unit — tune to your actuator speed.
    val steerScaleMs:      Int     = 200,

    // Use Kalman filter in SensorFusion instead of complementary filter.
    // Kalman is more accurate but heavier — persisted so it survives restarts.
    val useKalmanFilter:   Boolean = false
)

// ─────────────────────────────────────────────
// Pure-Kotlin PID Controller
// ─────────────────────────────────────────────

class PidController(private var config: PidConfig) {

    private var integral: Float = 0f
    private var lastOutput: Float = 0f
    private var lastTime:  Long   = System.currentTimeMillis()

    fun updateConfig(c: PidConfig) { config = c; integral = 0f }

    fun reset() { integral = 0f; lastOutput = 0f; lastTime = System.currentTimeMillis() }

    /**
     * Compute PID output.
     *
     * @param currentHeading  Current vessel heading in degrees (0–359)
     * @param targetHeading   Desired heading in degrees (0–359)
     * @param gyroZDegS       Yaw rate from BLE IMU gyro in °/s.
     *                        Used directly as the D-term input — much cleaner than
     *                        differentiating the heading error, which amplifies GPS noise.
     *                        A positive value means turning to starboard.
     *                        Pass 0f if gyro is unavailable.
     * @param speedKnots      Current vessel speed for gain scaling.
     *                        Pass 0f to disable speed scaling.
     */
    fun compute(
        currentHeading: Float,
        targetHeading:  Float,
        gyroZDegS:      Float = 0f,
        speedKnots:     Float = 0f
    ): PidResult {
        val now = System.currentTimeMillis()
        val dt  = ((now - lastTime) / 1000f).coerceIn(0.01f, 0.5f)
        lastTime = now

        // ── Heading error (shortest arc, −180…+180) ───────────────────────────
        var error = targetHeading - currentHeading
        while (error >  180f) error -= 360f
        while (error < -180f) error += 360f

        // ── Deadband ──────────────────────────────────────────────────────────
        if (kotlin.math.abs(error) <= config.deadbandDeg) {
            integral    = 0f
            return PidResult(
                output     = 0f,
                error      = error,
                inDeadband = true,
                offCourse  = kotlin.math.abs(error) > config.offCourseAlarmDeg
            )
        }

        // ── Speed scaling: reduce P and D aggressiveness at higher speeds ─────
        // At low speed a boat needs full authority; at planing speed small
        // corrections are enough and large ones cause uncomfortable yawing.
        // Scaling does NOT affect Ki — integral keeps correcting steady drift.
        val speedScale: Float = if (config.maxScaleSpeedKt > 0f && speedKnots > 0f) {
            val t = ((speedKnots - 0f) / config.maxScaleSpeedKt).coerceIn(0f, 1f)
            1f - t * (1f - config.minSpeedScale)   // 1.0 at 0 kt → minSpeedScale at maxScaleSpeedKt
        } else 1f

        // ── P term ────────────────────────────────────────────────────────────
        val p = config.kp * speedScale * error

        // ── I term (with anti-windup) ─────────────────────────────────────────
        integral += error * dt
        val maxI  = config.outputLimitDeg / config.ki.coerceAtLeast(0.001f)
        integral  = integral.coerceIn(-maxI, maxI)
        val i     = config.ki * integral

        // ── D term — uses raw gyro yaw rate, not differentiated error ─────────
        // gyroZDegS is the rate of heading change: positive = turning to starboard.
        // We want to damp the rotation toward the target, so:
        //   if error > 0 (need to turn stbd) and gyro already turning stbd → damp
        //   D term opposes rapid rotation regardless of error direction.
        // Negate: a large positive gyro (fast stbd turn) reduces a stbd correction.
        val d = -config.kd * speedScale * gyroZDegS

        // ── Steering bias ─────────────────────────────────────────────────────
        val bias = config.steeringBiasDeg

        // ── Raw output ────────────────────────────────────────────────────────
        val rawOutput = (p + i + d + bias).coerceIn(-config.outputLimitDeg, config.outputLimitDeg)

        // ── Rate limiter ──────────────────────────────────────────────────────
        val output: Float = if (config.rateLimitDegPerSec > 0f) {
            val maxDelta = config.rateLimitDegPerSec * dt
            (rawOutput - lastOutput).coerceIn(-maxDelta, maxDelta) + lastOutput
        } else rawOutput
        lastOutput = output

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

    fun adjustHeading(delta: Int): ByteArray =
        byteArrayOf(CMD_ADJUST_HDG, delta.coerceIn(-10, 10).toByte())

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

    fun setDeadband(degrees: Float): ByteArray {
        val v = (degrees * 10).toInt().coerceIn(0, 900)
        return byteArrayOf(CMD_SET_DEADBAND, (v shr 8).toByte(), v.toByte())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BLE UUIDs
//
// Autopilot device naming (BLE advertised name):
//   "BLE_tiller"           → Tiller autopilot  (linear motor + rudder sensor)
//   "ESC_PWM" / "BLDC_PWM" → Differential thrust autopilot (dual ESC/motor)
//
// IMU sensor naming:
//   "IMU_PWM" or any name starting with "IMU_" → IMU compass/attitude sensor
//   Shared by both autopilot types for heading input.
//
// Scanning strategy:
//   All scans use no service UUID filter — devices are identified by name only.
//   The ESP32 only needs to set its BLE advertised name correctly.
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

    // ── IMU sensor service  (advertised name: "IMU_PWM" or "IMU_*") ──────────
    // Used by both autopilot types as external compass/heading input.
    const val SERVICE_IMU              = "12345678-1234-1234-1234-1234567890cc"
    const val CHAR_IMU_HEADING         = "12345678-1234-1234-1234-1234567890c1"  // Notify: heading × 10 (uint16 BE)
    const val CHAR_IMU_PITCH_ROLL      = "12345678-1234-1234-1234-1234567890c2"  // Notify: pitch × 10, roll × 10 (int16 BE each)
    const val CHAR_IMU_CALIBRATION     = "12345678-1234-1234-1234-1234567890c3"  // Notify: bit0=calibrated, bits1-2=quality
    const val CHAR_IMU_TEMPERATURE     = "12345678-1234-1234-1234-1234567890c4"  // Notify: temp × 10 (int16 BE, optional)

    // ── Standard Battery service (all devices) ────────────────────────────────
    const val SERVICE_BATTERY          = "0000180f-0000-1000-8000-00805f9b34fb"
    const val CHAR_BATTERY_LEVEL       = "00002a19-0000-1000-8000-00805f9b34fb"

    // ── CCCD descriptor (subscribe to notifications) ──────────────────────────
    const val DESCRIPTOR_CCCD          = "00002902-0000-1000-8000-00805f9b34fb"

    // ── Helpers: resolve correct UUID for the connected autopilot type ────────
    fun serviceUuidFor(type: AutopilotType) = when (type) {
        AutopilotType.TILLER       -> SERVICE_TILLER
        AutopilotType.DIFF_THRUST,
        AutopilotType.THRUST_VECTOR -> SERVICE_DIFF_THRUST  // same hardware, vectored servo
    }
    fun commandCharFor(type: AutopilotType) = when (type) {
        AutopilotType.TILLER       -> CHAR_TILLER_COMMAND
        AutopilotType.DIFF_THRUST,
        AutopilotType.THRUST_VECTOR -> CHAR_THRUST_COMMAND
    }
    fun headingCharFor(type: AutopilotType) = when (type) {
        AutopilotType.TILLER       -> CHAR_TILLER_HEADING
        AutopilotType.DIFF_THRUST,
        AutopilotType.THRUST_VECTOR -> CHAR_THRUST_HEADING
    }
    fun stateCharFor(type: AutopilotType) = when (type) {
        AutopilotType.TILLER       -> CHAR_TILLER_STATE
        AutopilotType.DIFF_THRUST,
        AutopilotType.THRUST_VECTOR -> CHAR_THRUST_STATE
    }
}