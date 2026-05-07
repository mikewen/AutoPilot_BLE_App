package com.mikewen.autopilot.ble

/**
 * RemoteCommand — shared command namespace for BLE remote controllers.
 *
 * A RemoteCommand is a (group, action) pair.  The ViewModel interprets these
 * into actual autopilot actions so the remote implementation is decoupled from
 * the navigation/motor layer.
 *
 * Used by LookbonRemote (and any future remote) to describe user intent without
 * needing direct access to BleManager or GpsManager.
 */
object RemoteBleManager {

    data class RemoteCommand(val group: Int, val action: Int)

    // ── Groups ────────────────────────────────────────────────────────────────
    const val GRP_AUTOPILOT = 1   // engage / disengage
    const val GRP_COURSE    = 2   // course adjust
    const val GRP_SPEED     = 3   // throttle / speed
    const val GRP_SYNC      = 4   // sync both motors to same speed
    const val GRP_PORT      = 5   // port motor only
    const val GRP_STBD      = 6   // starboard motor only

    // ── GRP_AUTOPILOT actions ─────────────────────────────────────────────────
    const val AP_ENGAGE    = 1
    const val AP_DISENGAGE = 2

    // ── GRP_COURSE actions ────────────────────────────────────────────────────
    const val CRS_LEFT_1   = 1
    const val CRS_LEFT_10  = 2
    const val CRS_RIGHT_1  = 3
    const val CRS_RIGHT_10 = 4

    // ── GRP_SPEED actions ─────────────────────────────────────────────────────
    const val SPD_UP       = 1   // +5%
    const val SPD_UP_1     = 2   // +1%
    const val SPD_DOWN     = 3   // −5%
    const val SPD_DOWN_1   = 4   // −1%
    const val SPD_STOP     = 5   // zero all

    // ── GRP_SYNC actions ──────────────────────────────────────────────────────
    const val SYNC_ON  = 1
    const val SYNC_OFF = 2

    // ── Misc ──────────────────────────────────────────────────────────────────
    const val R = 6   // R modifier button index (used in LookbonRemote internally)
}
