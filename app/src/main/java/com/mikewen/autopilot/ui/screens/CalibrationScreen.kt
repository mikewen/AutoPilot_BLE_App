package com.mikewen.autopilot.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.mikewen.autopilot.model.BleConnectionState
import com.mikewen.autopilot.model.ImuConnectionState
import com.mikewen.autopilot.ui.theme.*
import com.mikewen.autopilot.viewmodel.AutopilotViewModel
import kotlinx.coroutines.delay
import kotlin.math.*

/**
 * CalibrationScreen
 *
 * PRIMARY display: raw LSB counts from the BLE sensor A1 packet.
 * This lets the user verify gyro scale, axis orientation, and bias directly.
 *
 * Raw data comes from SensorFusion.lastRawGx/Gy/Gz (set each time processA1 is called).
 * Converted values (°/s) shown as secondary for cross-checking:
 *   gyroZDegS = (gz - gyroBiasZ) * gyroScaleDegS * (if flipped -1 else 1)
 *
 * Gyro scale tuning:
 *   Default gyroScaleDegS = 1/256 → ±128°/s range
 *   If raw gz = 256 LSB and you rotate at ~1°/s, scale is correct.
 *   If heading drifts, adjust gyroBiasZ via calibration.
 *
 * Magnetometer display:
 *   Raw mx/my/mz in LSB — sweep across full 360° to see min/max for hard-iron.
 *   rawMagHeadingDeg = tilt-compensated heading before bias/declination.
 */
@Composable
fun CalibrationScreen(
    vm: AutopilotViewModel,
    onBack: () -> Unit
) {
    val fusion = vm.fusion

    // ── BLE connection guard ──────────────────────────────────────────────────
    val bleConn by vm.connectionState.collectAsState()
    val imuConn by vm.imuConnectionState.collectAsState()
    val hasA1Source = bleConn is BleConnectionState.Connected ||
            imuConn is ImuConnectionState.Connected

    // ── Poll SensorFusion at 20 Hz for display ────────────────────────────────
    // Raw LSB values + converted values, refreshed every 50 ms
    var rawGx by remember { mutableIntStateOf(0) }
    var rawGy by remember { mutableIntStateOf(0) }
    var rawGz by remember { mutableIntStateOf(0) }
    var rawAx by remember { mutableIntStateOf(0) }
    var rawAy by remember { mutableIntStateOf(0) }
    var rawAz by remember { mutableIntStateOf(0) }
    var rawMx by remember { mutableIntStateOf(0) }
    var rawMy by remember { mutableIntStateOf(0) }
    var rawMz by remember { mutableIntStateOf(0) }

    // Converted / processed values
    var gyroZDegS     by remember { mutableFloatStateOf(0f) }
    var headingDeg    by remember { mutableFloatStateOf(0f) }
    var rawMagHdg     by remember { mutableFloatStateOf(0f) }
    var tiltDeg       by remember { mutableFloatStateOf(0f) }
    var seaState      by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            rawGx = fusion.lastRawGx.toInt()
            rawGy = fusion.lastRawGy.toInt()
            rawGz = fusion.lastRawGz.toInt()
            rawAx = fusion.lastRawAx.toInt()
            rawAy = fusion.lastRawAy.toInt()
            rawAz = fusion.lastRawAz.toInt()
            rawMx = fusion.lastRawMx.toInt()
            rawMy = fusion.lastRawMy.toInt()
            rawMz = fusion.lastRawMz.toInt()
            gyroZDegS  = fusion.lastGyroZDegS
            val fs     = fusion.getState()
            headingDeg = fs.headingDeg
            rawMagHdg  = fs.rawMagHeadingDeg
            tiltDeg    = fs.tiltDeg
            seaState   = fs.seaState
            delay(50)   // 20 Hz
        }
    }

    // ── Gyro calibration state ────────────────────────────────────────────────
    var gyroPhase  by remember { mutableStateOf(GyroPhase.IDLE) }
    var gyroSecs   by remember { mutableIntStateOf(0) }
    var gyroResult by remember { mutableStateOf("") }
    var gyroOk     by remember { mutableStateOf(false) }

    // ── Mag calibration state ─────────────────────────────────────────────────
    var magPhase          by remember { mutableStateOf(MagPhase.IDLE) }
    var magSamples        by remember { mutableIntStateOf(0) }
    var magResult         by remember { mutableStateOf("") }
    var magOk             by remember { mutableStateOf(false) }
    // Accumulated rotation angle — integrates gyroZ while mag cal is active
    // so the user knows when ~360° has been completed
    var accumulatedRotDeg by remember { mutableFloatStateOf(0f) }
    var lastIntegTimeMs   by remember { mutableLongStateOf(0L) }

    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "r"
    )

    LaunchedEffect(gyroPhase) {
        if (gyroPhase == GyroPhase.SAMPLING) {
            gyroSecs = 0
            fusion.startGyroBiasCal()
            repeat(5) { delay(1_000); gyroSecs++ }
            val ok = fusion.finishGyroBiasCal()
            gyroPhase = GyroPhase.IDLE; gyroOk = ok
            gyroResult = if (ok)
                "Bias — X=%d  Y=%d  Z=%d LSB  (%.4f°/s each)".format(
                    fusion.gyroBiasX.toInt(), fusion.gyroBiasY.toInt(), fusion.gyroBiasZ.toInt(),
                    fusion.gyroBiasZ * fusion.gyroScaleDegS)
            else "Not enough BLE samples — is the device connected and sending A1?"
            if (ok) vm.gpsManager.saveCalibration()
        }
    }

    LaunchedEffect(magPhase) {
        if (magPhase == MagPhase.ROTATING) {
            magSamples        = 0
            accumulatedRotDeg = 0f
            lastIntegTimeMs   = System.currentTimeMillis()
            fusion.startManualMagCal()
            while (magPhase == MagPhase.ROTATING) {
                delay(100)   // 10 Hz integration
                val now = System.currentTimeMillis()
                val dtS = (now - lastIntegTimeMs) / 1000f
                lastIntegTimeMs = now
                // Integrate absolute yaw rate — we want total degrees swept,
                // not net rotation, so use abs() to count both directions.
                // Threshold 0.3°/s to ignore noise when still.
                val gz = fusion.lastGyroZDegS
                if (abs(gz) > 0.3f) {
                    accumulatedRotDeg += abs(gz) * dtS
                }
                magSamples = (magSamples + 5).coerceAtMost(900)
            }
        }
    }

    Scaffold(
        containerColor = NavyDeep,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(SurfaceCard).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = TealAccent)
                }
                Text("SENSOR CALIBRATION",
                    style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Connection warning ────────────────────────────────────────────
            if (!hasA1Source) {
                Surface(color = RedAlarm.copy(0.1f), shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, RedAlarm.copy(0.5f))) {
                    Row(Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = RedAlarm, modifier = Modifier.size(20.dp))
                        Text("Connect autopilot or IMU device first — needs live A1 packets.",
                            style = MaterialTheme.typography.bodyMedium, color = RedAlarm)
                    }
                }
            }

            // ── RAW GYRO (PRIMARY) ────────────────────────────────────────────
            SectionCard("GYRO  — raw LSB  (primary)") {
                // Scale info bar
                Surface(color = NavyMid, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Scale: %.5f °/LSB".format(fusion.gyroScaleDegS),
                            style = MaterialTheme.typography.labelLarge, color = TealAccent,
                            fontFamily = FontFamily.Monospace)
                        Text("Flipped Z: ${fusion.gyroZFlipped}",
                            style = MaterialTheme.typography.labelLarge, color = Muted,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Raw LSB values — large monospace for easy reading
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    RawValueCell("GX", rawGx)
                    RawValueCell("GY", rawGy)
                    RawValueCell("GZ", rawGz, highlight = true)
                }

                Spacer(Modifier.height(6.dp))

                // Converted Z (yaw) + stillness indicator
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("GZ converted", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Text("%.3f °/s".format(gyroZDegS),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                abs(gyroZDegS) < 0.5f -> GreenGo
                                abs(gyroZDegS) < 5f   -> AmberWarn
                                else                   -> RedAlarm
                            })
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("GZ bias (saved)", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Text("%.1f LSB  (%.4f°/s)".format(
                            fusion.gyroBiasZ, fusion.gyroBiasZ * fusion.gyroScaleDegS),
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace, color = TealAccent)
                    }
                }

                // Stillness chip
                val isStill = abs(rawGz) < (0.5f / fusion.gyroScaleDegS)
                Surface(
                    color  = if (isStill) GreenGo.copy(0.15f) else AmberWarn.copy(0.15f),
                    shape  = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, if (isStill) GreenGo.copy(0.5f) else AmberWarn.copy(0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isStill) "✓ STILL — GZ raw < ${(0.5f/fusion.gyroScaleDegS).toInt()} LSB — ready for gyro bias cal"
                        else "MOVING — GZ = $rawGz LSB — wait until still",
                        style    = MaterialTheme.typography.labelMedium,
                        color    = if (isStill) GreenGo else AmberWarn,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ── RAW ACCEL ─────────────────────────────────────────────────────
            SectionCard("ACCEL  — raw LSB") {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    RawValueCell("AX", rawAx)
                    RawValueCell("AY", rawAy)
                    RawValueCell("AZ", rawAz, highlight = true)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Tilt from vertical: ${String.format("%.1f", tiltDeg)}°",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (tiltDeg < 10f) GreenGo else AmberWarn,
                        fontFamily = FontFamily.Monospace)
                    Text("Sea state: ${String.format("%.2f", seaState)}",
                        style = MaterialTheme.typography.labelLarge, color = Muted,
                        fontFamily = FontFamily.Monospace)
                }
            }

            // ── RAW MAG ───────────────────────────────────────────────────────
            SectionCard("MAG  — raw LSB") {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    RawValueCell("MX", rawMx, highlight = true)
                    RawValueCell("MY", rawMy, highlight = true)
                    RawValueCell("MZ", rawMz)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Column {
                        Text("Raw mag heading", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Text("${rawMagHdg.toInt()}°",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace, color = TealAccent)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Hard-iron (saved)", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Text("X=%.0f  Y=%.0f LSB".format(
                            fusion.manualCalHardIronX, fusion.manualCalHardIronY),
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace, color = TealAccent)
                    }
                }
                Text("Fused heading (after bias+decl): ${headingDeg.toInt()}°  " +
                        "  Decl: ${"%.2f".format(fusion.getState().magDeclinationDeg)}°",
                    style = MaterialTheme.typography.labelMedium, color = Muted,
                    fontFamily = FontFamily.Monospace)
            }

            // ── Saved calibration values ──────────────────────────────────────
            SectionCard("SAVED CALIBRATION VALUES") {
                InfoRow("Gyro bias X", "%d LSB  (%.4f °/s)".format(
                    fusion.gyroBiasX.toInt(), fusion.gyroBiasX * fusion.gyroScaleDegS))
                InfoRow("Gyro bias Y", "%d LSB  (%.4f °/s)".format(
                    fusion.gyroBiasY.toInt(), fusion.gyroBiasY * fusion.gyroScaleDegS))
                InfoRow("Gyro bias Z", "%d LSB  (%.4f °/s)".format(
                    fusion.gyroBiasZ.toInt(), fusion.gyroBiasZ * fusion.gyroScaleDegS))
                HorizontalDivider(color = NavyLight)
                InfoRow("Mag hard-iron X", "%.1f LSB".format(fusion.manualCalHardIronX))
                InfoRow("Mag hard-iron Y", "%.1f LSB".format(fusion.manualCalHardIronY))
                HorizontalDivider(color = NavyLight)
                val decl = fusion.getState().magDeclinationDeg
                InfoRow("Mag declination",
                    if (decl != 0f) "%.2f° (auto from GPS)".format(decl)
                    else "0.00° (needs GPS position)")
                HorizontalDivider(color = NavyLight)
                InfoRow("Gyro scale", "%.6f °/LSB  (1/%.0f)".format(
                    fusion.gyroScaleDegS, 1f / fusion.gyroScaleDegS))
                InfoRow("Gyro Z flipped", "${fusion.gyroZFlipped}")
            }

            // ── Gyro Bias Calibration ─────────────────────────────────────────
            SectionCard("GYRO BIAS CALIBRATION  (5 seconds)") {
                InstructionBox("🚢", "Keep BLE sensor perfectly still", listOf(
                    "Watch GZ raw above — wait until it reads < ${(0.5f/fusion.gyroScaleDegS).toInt()} LSB (green)",
                    "Tap START — sensor must not move for 5 seconds",
                    "Collected at 50 Hz → ~250 samples",
                    "Subtracts DC offset so heading doesn't drift at rest"
                ))
                Spacer(Modifier.height(8.dp))
                when (gyroPhase) {
                    GyroPhase.SAMPLING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, null, tint = TealAccent,
                                modifier = Modifier.size(36.dp).rotate(rotation))
                            Spacer(Modifier.height(6.dp))
                            Text("Sampling…  ${gyroSecs} / 5 s",
                                style = MaterialTheme.typography.titleMedium, color = TealAccent)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress   = { gyroSecs / 5f },
                                modifier   = Modifier.fillMaxWidth(),
                                color      = TealAccent,
                                trackColor = NavyMid
                            )
                        }
                    }
                    GyroPhase.IDLE -> {
                        if (gyroResult.isNotEmpty()) {
                            ResultChip(success = gyroOk, message = gyroResult)
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(
                            onClick  = { gyroResult = ""; gyroPhase = GyroPhase.SAMPLING },
                            enabled  = hasA1Source,
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = TealAccent, contentColor = NavyDeep),
                            shape    = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("START GYRO CALIBRATION", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // ── Mag Hard-Iron Calibration ─────────────────────────────────────
            SectionCard("MAG HARD-IRON CALIBRATION") {
                InstructionBox("🧲", "Rotate the BLE sensor 360° horizontally", listOf(
                    "Keep sensor level — watch MX/MY values sweep min→max",
                    "Tap START then slowly rotate full circle (~20–30 s)",
                    "MX and MY should each swing from negative to positive",
                    "Tap FINISH — needs ≥ 36 samples"
                ))
                Spacer(Modifier.height(8.dp))
                when (magPhase) {
                    MagPhase.IDLE -> {
                        if (magResult.isNotEmpty()) {
                            ResultChip(success = magOk, message = magResult)
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(
                            onClick  = { magResult = ""; magSamples = 0; magPhase = MagPhase.ROTATING },
                            enabled  = hasA1Source,
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = TealAccent, contentColor = NavyDeep),
                            shape    = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("START MAG CALIBRATION", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    MagPhase.ROTATING -> {
                        // Live raw MX/MY during rotation
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                            RawValueCell("MX", rawMx, highlight = true)
                            RawValueCell("MY", rawMy, highlight = true)
                        }
                        Spacer(Modifier.height(6.dp))
                        // Rotation progress — integrated from BLE gyro Z
                        val rotPct = (accumulatedRotDeg / 360f).coerceIn(0f, 1f)
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Rotation", style = MaterialTheme.typography.labelMedium, color = Muted)
                                Text(
                                    "${accumulatedRotDeg.toInt()}° / 360°",
                                    fontSize   = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color      = when {
                                        accumulatedRotDeg >= 360f -> GreenGo
                                        accumulatedRotDeg >= 180f -> TealAccent
                                        else                      -> AmberWarn
                                    }
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Raw mag hdg", style = MaterialTheme.typography.labelMedium, color = Muted)
                                Text(
                                    "${rawMagHdg.toInt()}°",
                                    fontSize   = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color      = TealAccent
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress   = { rotPct },
                            modifier   = Modifier.fillMaxWidth(),
                            color      = if (rotPct >= 1f) GreenGo else TealAccent,
                            trackColor = NavyMid
                        )
                        Text(
                            when {
                                accumulatedRotDeg < 90f  -> "Rotate slowly — ${(90f - accumulatedRotDeg).toInt()}° to quarter turn"
                                accumulatedRotDeg < 180f -> "Half way — ${(180f - accumulatedRotDeg).toInt()}° more"
                                accumulatedRotDeg < 360f -> "Almost done — ${(360f - accumulatedRotDeg).toInt()}° more"
                                else                     -> "✓ Full circle complete — tap FINISH"
                            },
                            style = MaterialTheme.typography.bodyMedium, color = Muted,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val ok = fusion.finishManualMagCal()
                                magPhase = MagPhase.IDLE; magOk = ok
                                magResult = if (ok)
                                    "Hard-iron X=%.1f  Y=%.1f LSB".format(
                                        fusion.manualCalHardIronX, fusion.manualCalHardIronY)
                                else "Not enough samples — rotate slower / check BLE"
                                if (ok) vm.gpsManager.saveCalibration()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = GreenGo, contentColor = NavyDeep),
                            shape    = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("FINISH — COMPUTE OFFSETS", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // ── Tips ──────────────────────────────────────────────────────────
            SectionCard("TIPS") {
                TipRow("📐", "Verifying gyro scale",
                    "With the sensor stationary, GZ raw should read ~0 (±a few LSB). " +
                            "Rotate slowly at a known rate and verify: raw × scale ≈ actual °/s. " +
                            "Default scale 1/256 means ±32768 LSB = ±128 °/s.")
                TipRow("🧲", "Verifying mag",
                    "Rotate 360°: MX and MY should each sweep from negative to positive. " +
                            "If one axis barely moves, that axis may be misaligned or faulty.")
                TipRow("💾", "Auto-saved",
                    "Offsets persist in SharedPreferences and reload on next launch.")
            }
        }
    }
}

// ── Enums ─────────────────────────────────────────────────────────────────────

private enum class GyroPhase { IDLE, SAMPLING }
private enum class MagPhase  { IDLE, ROTATING }

// ── Raw value cell — large monospace number ───────────────────────────────────

@Composable
private fun RawValueCell(label: String, value: Int, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (highlight) TealAccent else Muted)
        Text(
            "%6d".format(value),        // fixed width so layout doesn't jump
            fontSize      = 22.sp,
            fontWeight    = FontWeight.Bold,
            fontFamily    = FontFamily.Monospace,
            color         = when {
                highlight && abs(value) < 50  -> GreenGo
                highlight && abs(value) > 2000 -> AmberWarn
                else                           -> Color.White
            }
        )
    }
}

// ── Shared UI helpers ─────────────────────────────────────────────────────────

@Composable
private fun InstructionBox(icon: String, title: String, steps: List<String>) {
    Surface(color = NavyMid, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 20.sp)
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            steps.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i+1}.", style = MaterialTheme.typography.labelMedium,
                        color = TealAccent, modifier = Modifier.width(16.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun ResultChip(success: Boolean, message: String) {
    val color = if (success) GreenGo else AmberWarn
    Surface(color = color.copy(0.12f), shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color)) {
        Row(Modifier.padding(10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (success) Icons.Default.CheckCircle else Icons.Default.Warning,
                null, tint = color, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = color,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun TipRow(icon: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(icon, fontSize = 16.sp, modifier = Modifier.width(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White)
            Text(body,  style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}