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
    val useKalmanFilter:   Boolean = false,

    // Use shaft/rudder position sensor for PID supervision when enabled:
    //   - Limit enforcement: stop motor at port/stbd cal limits
    //   - Anti-windup: clamp integral when shaft not responding to output
    //   - Centre detection: confirm rudder is centred before engage
    //   - Lag/failure detection: output commanded but shaft not moving
    // Requires A5 shaft sensor (QMC6308/MMC5603) to be calibrated.
    val useSteerSensor:    Boolean = false,

    // Port/stbd hard limits in degrees — motor stops beyond these.
    // Defaults match typical tiller travel; tune to your boat.
    val shaftLimitPortDeg: Float = 40f,
    val shaftLimitStbdDeg: Float = 40f,

    // If shaft moves < this amount (°) within lagWindowMs despite nonzero output
    // → declare actuator lag/failure.
    val shaftLagThresholdDeg: Float = 2f,
    val shaftLagWindowMs:     Long  = 2000L,

    // ── Cascaded PID + Feed-Forward ────────────────────────────────────────
    //
    // Architecture:
    //   Outer loop: heading error (°) → desired yaw rate (°/s)
    //     output_outer = Kp * error + Ki * ∫error·dt
    //     (outer Kp/Ki are the existing config.kp / config.ki)
    //
    //   Feed-Forward: desired yaw rate → rudder pre-position
    //     ff = ffGain * desiredYawRate
    //     Acts immediately — no lag waiting for heading error to build.
    //
    //   Inner loop: (desiredYawRate − gyroZ) → rudder correction
    //     d_inner = kpInner * (desiredYawRate − gyroZ)
    //     kdInner applied to rate of yaw-rate-error (gyro acceleration)
    //
    //   final = ff + d_inner + outer_I + bias
    //
    //   Set ffGain=0 and kpInner=0 to fall back to classic single-loop PID.
    val ffGain:     Float = 0.3f,   // feed-forward gain on desired yaw rate
    val kpInner:    Float = 0.5f,   // inner loop P on yaw rate error
    val kdInner:    Float = 0.05f   // inner loop D on yaw rate acceleration
)

// ─────────────────────────────────────────────
// Pure-Kotlin PID Controller
// ─────────────────────────────────────────────

class PidController(private var config: PidConfig) {

    // ── Outer loop state ──────────────────────────────────────────────────────
    private var integral:    Float = 0f
    private var lastOutput:  Float = 0f
    private var lastTime:    Long  = System.currentTimeMillis()

    // ── Inner loop state ──────────────────────────────────────────────────────
    private var lastGyroZ:   Float = 0f   // previous gyroZ for inner D-term

    // ── Shaft supervision state ───────────────────────────────────────────────
    private var shaftMovingStartMs:       Long  = 0L
    private var shaftAngleAtCommandStart: Float = 0f

    fun updateConfig(c: PidConfig) {
        config = c; integral = 0f; shaftMovingStartMs = 0L; lastGyroZ = 0f
    }

    fun reset() {
        integral = 0f; lastOutput = 0f; lastTime = System.currentTimeMillis()
        lastGyroZ = 0f; shaftMovingStartMs = 0L; shaftAngleAtCommandStart = 0f
    }

    /**
     * Cascaded PID + Feed-Forward compute.
     *
     * Outer loop  (heading → desired yaw rate):
     *   desiredYawRate = Kp·error + Ki·∫error·dt
     *
     * Feed-Forward (desired yaw rate → rudder pre-position):
     *   ff = ffGain · desiredYawRate
     *   Proactively positions the rudder — no lag waiting for error to build.
     *
     * Inner loop  (desired yaw rate − actual gyroZ → rudder correction):
     *   yawRateError = desiredYawRate − gyroZDegS
     *   inner = kpInner·yawRateError − kdInner·(gyroZ - lastGyroZ)/dt
     *   Damps oscillation; also rejects wind gusts (gyro sees gust immediately).
     *
     * Final output = ff + inner + bias  (clamped, rate-limited)
     *
     * Classic single-loop fallback: set ffGain=0, kpInner=0, kdInner=0 —
     * reduces to output = Kp·error + Ki·∫ − Kd·gyroZ (original behaviour).
     */
    fun compute(
        currentHeading: Float,
        targetHeading:  Float,
        gyroZDegS:      Float  = 0f,
        speedKnots:     Float  = 0f,
        shaftAngleDeg:  Float? = null
    ): PidResult {
        val now = System.currentTimeMillis()
        val dt  = ((now - lastTime) / 1000f).coerceIn(0.01f, 0.5f)
        lastTime = now

        // ── Heading error ─────────────────────────────────────────────────────
        var error = targetHeading - currentHeading
        while (error >  180f) error -= 360f
        while (error < -180f) error += 360f

        // ── Deadband ──────────────────────────────────────────────────────────
        if (kotlin.math.abs(error) <= config.deadbandDeg) {
            integral = 0f; lastGyroZ = gyroZDegS
            return PidResult(
                output     = 0f, error = error,
                inDeadband = true,
                offCourse  = kotlin.math.abs(error) > config.offCourseAlarmDeg
            )
        }

        // ── Speed scaling (P and inner loop, not Ki) ──────────────────────────
        val speedScale: Float = if (config.maxScaleSpeedKt > 0f && speedKnots > 0f) {
            val t = (speedKnots / config.maxScaleSpeedKt).coerceIn(0f, 1f)
            1f - t * (1f - config.minSpeedScale)
        } else 1f

        // ── Outer loop ────────────────────────────────────────────────────────
        // Converts heading error to a desired yaw rate setpoint.
        val p = config.kp * speedScale * error

        integral += error * dt
        val maxI  = config.outputLimitDeg / config.ki.coerceAtLeast(0.001f)
        integral  = integral.coerceIn(-maxI, maxI)
        val i     = config.ki * integral

        // desiredYawRate in °/s — how fast we want the heading to change
        val desiredYawRate = (p + i).coerceIn(-config.outputLimitDeg, config.outputLimitDeg)

        // ── Feed-Forward ──────────────────────────────────────────────────────
        // Maps desired yaw rate directly to rudder angle — immediate response.
        // A positive desiredYawRate (turn stbd) maps to positive rudder (stbd).
        val ff = config.ffGain * desiredYawRate

        // ── Inner loop ────────────────────────────────────────────────────────
        // Corrects residual yaw rate error (desired − actual gyroZ).
        // The inner D-term (kdInner) uses gyro acceleration to damp oscillation
        // and react to wind gust onset (rapid change in gyroZ).
        val yawRateError    = desiredYawRate - gyroZDegS
        val gyroAccel       = (gyroZDegS - lastGyroZ) / dt   // °/s²
        val innerCorrection = config.kpInner * speedScale * yawRateError -
                config.kdInner * speedScale * gyroAccel
        lastGyroZ = gyroZDegS

        // ── Steering bias ─────────────────────────────────────────────────────
        val bias = config.steeringBiasDeg

        // ── Combine ───────────────────────────────────────────────────────────
        val rawOutput = (ff + innerCorrection + bias)
            .coerceIn(-config.outputLimitDeg, config.outputLimitDeg)

        // ── Rate limiter ──────────────────────────────────────────────────────
        val output: Float = if (config.rateLimitDegPerSec > 0f) {
            val maxDelta = config.rateLimitDegPerSec * dt
            (rawOutput - lastOutput).coerceIn(-maxDelta, maxDelta) + lastOutput
        } else rawOutput
        lastOutput = output

        // ── Shaft sensor supervision ──────────────────────────────────────────
        val shaftStatus: ShaftStatus
        val finalOutput: Float

        if (!config.useSteerSensor || shaftAngleDeg == null) {
            shaftStatus = ShaftStatus.NO_SENSOR
            finalOutput = output
        } else {
            val shaft = shaftAngleDeg
            val atPortLimit = shaft <= -config.shaftLimitPortDeg && output < 0f
            val atStbdLimit = shaft >=  config.shaftLimitStbdDeg && output > 0f
            if (atPortLimit || atStbdLimit) {
                integral = 0f
                finalOutput = 0f
                shaftStatus = if (atPortLimit) ShaftStatus.AT_PORT_LIMIT
                else             ShaftStatus.AT_STBD_LIMIT
            } else {
                finalOutput = output
                val absOutput = kotlin.math.abs(output)
                if (absOutput > config.outputLimitDeg * 0.1f) {
                    if (shaftMovingStartMs == 0L) {
                        shaftMovingStartMs = System.currentTimeMillis()
                        shaftAngleAtCommandStart = shaft
                    }
                    val elapsed = System.currentTimeMillis() - shaftMovingStartMs
                    val moved   = kotlin.math.abs(shaft - shaftAngleAtCommandStart)
                    shaftStatus = if (elapsed > config.shaftLagWindowMs &&
                        moved < config.shaftLagThresholdDeg)
                        ShaftStatus.LAGGING else ShaftStatus.OK
                } else {
                    shaftMovingStartMs = 0L; shaftAngleAtCommandStart = 0f
                    shaftStatus = ShaftStatus.OK
                }
            }
        }

        return PidResult(
            output      = finalOutput,
            error       = error,
            inDeadband  = false,
            offCourse   = kotlin.math.abs(error) > config.offCourseAlarmDeg,
            shaftStatus = shaftStatus
        )
    }
}

/** Status from shaft sensor supervision in the PID loop. */
enum class ShaftStatus {
    OK,             // normal operation
    AT_PORT_LIMIT,  // shaft at port limit — output clamped to 0
    AT_STBD_LIMIT,  // shaft at stbd limit — output clamped to 0
    LAGGING,        // output commanded but shaft not responding
    NO_SENSOR       // shaft sensor disabled or uncalibrated
}

data class PidResult(
    val output:      Float,
    val error:       Float,
    val inDeadband:  Boolean,
    val offCourse:   Boolean,
    val shaftStatus: ShaftStatus = ShaftStatus.NO_SENSOR
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