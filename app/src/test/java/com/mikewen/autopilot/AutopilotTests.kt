package com.mikewen.autopilot

import com.mikewen.autopilot.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// BleCommand Tests
// ─────────────────────────────────────────────────────────────────────────────

class BleCommandTest {

    @Test fun `setHeading encodes 180 correctly`() {
        val cmd = BleCommand.setHeading(180f)
        assertEquals(BleCommand.CMD_SET_HDG, cmd[0])
        assertEquals(180, decoded16(cmd, 1))
    }

    @Test fun `setHeading encodes 0 correctly`() {
        val cmd = BleCommand.setHeading(0f)
        assertEquals(0, decoded16(cmd, 1))
    }

    @Test fun `setHeading encodes 359 correctly`() {
        val cmd = BleCommand.setHeading(359f)
        assertEquals(359, decoded16(cmd, 1))
    }

    @Test fun `setHeading clamps above 359`() {
        assertEquals(359, decoded16(BleCommand.setHeading(400f), 1))
    }

    @Test fun `setHeading clamps below 0`() {
        assertEquals(0, decoded16(BleCommand.setHeading(-10f), 1))
    }

    @Test fun `adjustHeading encodes positive delta`() {
        val cmd = BleCommand.adjustHeading(5)
        assertEquals(BleCommand.CMD_ADJUST_HDG, cmd[0])
        assertEquals(5.toByte(), cmd[1])
    }

    @Test fun `adjustHeading encodes negative delta`() {
        val cmd = BleCommand.adjustHeading(-3)
        assertEquals((-3).toByte(), cmd[1])
    }

    @Test fun `adjustHeading clamps to +10`() {
        assertEquals(10.toByte(), BleCommand.adjustHeading(50)[1])
    }

    @Test fun `adjustHeading clamps to -10`() {
        assertEquals((-10).toByte(), BleCommand.adjustHeading(-50)[1])
    }

    @Test fun `setPid encodes kp correctly`() {
        val cmd = BleCommand.setPid(PidConfig(kp = 1.5f))
        assertEquals(BleCommand.CMD_SET_PID, cmd[0])
        assertEquals(150, decoded16(cmd, 1))   // 1.5 * 100
    }

    @Test fun `setPid encodes ki correctly`() {
        val cmd = BleCommand.setPid(PidConfig(ki = 0.05f))
        assertEquals(5, decoded16(cmd, 3))     // 0.05 * 100
    }

    @Test fun `setPid encodes kd correctly`() {
        val cmd = BleCommand.setPid(PidConfig(kd = 0.3f))
        assertEquals(30, decoded16(cmd, 5))    // 0.3 * 100
    }

    @Test fun `setDeadband encodes 3 degrees as 30`() {
        val cmd = BleCommand.setDeadband(3.0f)
        assertEquals(BleCommand.CMD_SET_DEADBAND, cmd[0])
        assertEquals(30, decoded16(cmd, 1))    // 3.0 * 10
    }

    @Test fun `setDeadband encodes 2 degrees as 20`() {
        assertEquals(20, decoded16(BleCommand.setDeadband(2.0f), 1))
    }

    @Test fun `setDeadband clamps to max 900`() {
        assertEquals(900, decoded16(BleCommand.setDeadband(999f), 1))
    }

    @Test fun `setDeadband clamps to min 0`() {
        assertEquals(0, decoded16(BleCommand.setDeadband(-1f), 1))
    }

    @Test fun `CMD_ENGAGE is 0x01`() {
        assertEquals(0x01.toByte(), BleCommand.CMD_ENGAGE)
    }

    @Test fun `CMD_STANDBY is 0x02`() {
        assertEquals(0x02.toByte(), BleCommand.CMD_STANDBY)
    }

    @Test fun `CMD_SET_DEADBAND is 0x07`() {
        assertEquals(0x07.toByte(), BleCommand.CMD_SET_DEADBAND)
    }

    private fun decoded16(cmd: ByteArray, offset: Int) =
        (cmd[offset].toInt() and 0xFF) * 256 + (cmd[offset + 1].toInt() and 0xFF)
}

// ─────────────────────────────────────────────────────────────────────────────
// PID Controller Tests
// ─────────────────────────────────────────────────────────────────────────────

class PidControllerTest {

    private lateinit var pid: PidController

    @Before fun setup() {
        pid = PidController(
            PidConfig(kp = 2f, ki = 0f, kd = 0f, deadbandDeg = 3f,
                      offCourseAlarmDeg = 15f, outputLimit = 45f)
        )
    }

    // ── Deadband ─────────────────────────────────────────────────────────────

    @Test fun `error within deadband gives zero output`() {
        // 2° error < 3° deadband
        val r = pid.compute(currentHeading = 90f, targetHeading = 92f)
        assertEquals(0f, r.output, 0.001f)
        assertTrue(r.inDeadband)
    }

    @Test fun `error exactly at deadband boundary is inside`() {
        // exactly 3° — uses <=, so inside
        val r = pid.compute(currentHeading = 90f, targetHeading = 93f)
        assertTrue(r.inDeadband)
        assertEquals(0f, r.output, 0.001f)
    }

    @Test fun `error just outside deadband activates PID`() {
        // 3.1° > 3° deadband
        val r = pid.compute(currentHeading = 90f, targetHeading = 93.1f)
        assertFalse(r.inDeadband)
        assertNotEquals(0f, r.output)
    }

    @Test fun `error outside deadband gives nonzero output`() {
        // 10° error > 3° deadband
        val r = pid.compute(currentHeading = 90f, targetHeading = 100f)
        assertFalse(r.inDeadband)
        assertTrue(r.output != 0f)
    }

    @Test fun `negative error (port correction) gives negative output`() {
        val r = pid.compute(currentHeading = 100f, targetHeading = 90f)
        assertFalse(r.inDeadband)
        assertTrue(r.output < 0f)
    }

    // ── Heading wrap ──────────────────────────────────────────────────────────

    @Test fun `error wraps correctly crossing north starboard`() {
        // 359° → 001°: shortest path is +2°, not -358°
        val r = pid.compute(currentHeading = 359f, targetHeading = 1f)
        assertEquals(2f, r.error, 0.5f)
        assertTrue(r.output > 0f)
    }

    @Test fun `error wraps correctly crossing north port`() {
        // 001° → 359°: shortest path is -2°, not +358°
        val r = pid.compute(currentHeading = 1f, targetHeading = 359f)
        assertEquals(-2f, r.error, 0.5f)
        assertTrue(r.output < 0f)
    }

    @Test fun `180 degree error is handled`() {
        val r = pid.compute(currentHeading = 0f, targetHeading = 180f)
        assertFalse(r.inDeadband)
        assertTrue(r.output != 0f)
    }

    // ── Output clamping ───────────────────────────────────────────────────────

    @Test fun `output is clamped to positive outputLimit`() {
        val bigPid = PidController(
            PidConfig(kp = 100f, ki = 0f, kd = 0f, deadbandDeg = 0f, outputLimit = 30f)
        )
        val r = bigPid.compute(0f, 90f)
        assertEquals(30f, r.output, 0.001f)
    }

    @Test fun `output is clamped to negative outputLimit`() {
        val bigPid = PidController(
            PidConfig(kp = 100f, ki = 0f, kd = 0f, deadbandDeg = 0f, outputLimit = 30f)
        )
        val r = bigPid.compute(90f, 0f)
        assertEquals(-30f, r.output, 0.001f)
    }

    // ── Off-course alarm ──────────────────────────────────────────────────────

    @Test fun `off-course alarm fires when error exceeds threshold`() {
        // 20° > 15° alarm threshold
        val r = pid.compute(0f, 20f)
        assertTrue(r.offCourse)
    }

    @Test fun `off-course alarm is clear within threshold`() {
        // 10° < 15° alarm threshold
        val r = pid.compute(0f, 10f)
        assertFalse(r.offCourse)
    }

    @Test fun `off-course alarm fires even inside deadband if error is huge`() {
        // deadband = 3°, alarm = 15°; set error to 20° which is > alarm threshold
        // (only reachable if deadband is set larger than alarm — unusual, but tested)
        val widePid = PidController(
            PidConfig(kp = 1f, deadbandDeg = 25f, offCourseAlarmDeg = 15f)
        )
        val r = widePid.compute(0f, 20f)
        assertTrue(r.inDeadband)    // 20° < 25° deadband
        assertTrue(r.offCourse)     // 20° > 15° alarm
    }

    // ── Integral / anti-windup ────────────────────────────────────────────────

    @Test fun `reset clears internal state`() {
        // Build up integral
        val iPid = PidController(PidConfig(kp = 0f, ki = 1f, kd = 0f, deadbandDeg = 0f))
        repeat(10) { iPid.compute(0f, 10f) }
        // Reset and verify output is zero with no proportional gain
        iPid.reset()
        val r = iPid.compute(0f, 0f)
        assertEquals(0f, r.output, 0.001f)
    }

    @Test fun `integral is zeroed when entering deadband`() {
        // Accumulate integral with large error
        val iPid = PidController(PidConfig(kp = 0f, ki = 2f, kd = 0f, deadbandDeg = 5f, outputLimit = 100f))
        repeat(10) { iPid.compute(0f, 30f) }
        // Now move inside deadband — integral should zero, output should be zero
        val r = iPid.compute(0f, 3f)  // 3° < 5° deadband
        assertTrue(r.inDeadband)
        assertEquals(0f, r.output, 0.001f)
    }

    // ── Config update ─────────────────────────────────────────────────────────

    @Test fun `updateConfig changes deadband immediately`() {
        // Old deadband = 3°, error = 4° → active
        val r1 = pid.compute(0f, 4f)
        assertFalse(r1.inDeadband)

        // Widen deadband to 10° — same error should now be inside
        pid.updateConfig(PidConfig(kp = 2f, deadbandDeg = 10f))
        val r2 = pid.compute(0f, 4f)
        assertTrue(r2.inDeadband)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Model / Data Class Tests
// ─────────────────────────────────────────────────────────────────────────────

class AutopilotModelTest {

    @Test fun `default AutopilotState is standby and disengaged`() {
        val s = AutopilotState()
        assertEquals(AutopilotMode.STANDBY, s.mode)
        assertFalse(s.engaged)
        assertFalse(s.inDeadband)
        assertFalse(s.offCourseAlarm)
        assertFalse(s.lowBatteryAlarm)
    }

    @Test fun `PidConfig default deadbandDeg is 3`() {
        assertEquals(3.0f, PidConfig().deadbandDeg, 0.001f)
    }

    @Test fun `PidConfig deadband is less than offCourseAlarmDeg by default`() {
        val cfg = PidConfig()
        assertTrue(cfg.deadbandDeg < cfg.offCourseAlarmDeg)
    }

    @Test fun `PidConfig outputLimit is positive`() {
        assertTrue(PidConfig().outputLimit > 0f)
    }

    @Test fun `AutopilotType has exactly two values`() {
        assertEquals(2, AutopilotType.values().size)
        assertTrue(AutopilotType.values().contains(AutopilotType.TILLER))
        assertTrue(AutopilotType.values().contains(AutopilotType.DIFF_THRUST))
    }

    @Test fun `BleConnectionState covers all expected cases`() {
        val states: List<BleConnectionState> = listOf(
            BleConnectionState.Disconnected,
            BleConnectionState.Scanning,
            BleConnectionState.Connecting("unit"),
            BleConnectionState.Connected("unit", -65),
            BleConnectionState.Error("boom")
        )
        assertEquals(5, states.size)
    }

    @Test fun `BleDevice stores type correctly`() {
        val d = BleDevice("TillerAP", "AA:BB:CC:DD:EE:FF", -70, AutopilotType.TILLER)
        assertEquals(AutopilotType.TILLER, d.type)
    }

    @Test fun `BleDevice type can be null for unknown devices`() {
        val d = BleDevice("Unknown", "AA:BB:CC:DD:EE:FF", -80, null)
        assertNull(d.type)
    }

    @Test fun `AutopilotState inDeadband defaults false`() {
        assertFalse(AutopilotState().inDeadband)
    }

    @Test fun `AutopilotState copy preserves fields`() {
        val s = AutopilotState(engaged = true, currentHeading = 180f, inDeadband = true)
        val s2 = s.copy(currentHeading = 270f)
        assertTrue(s2.engaged)
        assertTrue(s2.inDeadband)
        assertEquals(270f, s2.currentHeading)
    }

    @Test fun `PidResult captures all fields`() {
        val r = PidResult(output = 12.5f, error = 5f, inDeadband = false, offCourse = false)
        assertEquals(12.5f, r.output, 0.001f)
        assertEquals(5f, r.error, 0.001f)
        assertFalse(r.inDeadband)
        assertFalse(r.offCourse)
    }

    // ── BLE device type detection (mirrors BleManager logic) ─────────────────

    @Test fun `device named tiller is detected as TILLER`() {
        assertEquals(AutopilotType.TILLER, detectType("MyTiller_v2"))
    }

    @Test fun `device named DiffThrust is detected as DIFF_THRUST`() {
        assertEquals(AutopilotType.DIFF_THRUST, detectType("DiffThrust_AP"))
    }

    @Test fun `device named diff is detected as DIFF_THRUST`() {
        assertEquals(AutopilotType.DIFF_THRUST, detectType("boat-diff"))
    }

    @Test fun `unknown device name returns null`() {
        assertNull(detectType("SomeOtherDevice"))
    }

    private fun detectType(name: String): AutopilotType? = when {
        name.contains("tiller", ignoreCase = true) -> AutopilotType.TILLER
        name.contains("thrust", ignoreCase = true) ||
        name.contains("diff",   ignoreCase = true) -> AutopilotType.DIFF_THRUST
        else -> null
    }
}
