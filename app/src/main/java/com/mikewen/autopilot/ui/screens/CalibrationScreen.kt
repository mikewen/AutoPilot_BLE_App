package com.mikewen.autopilot.ui.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.compose.ui.platform.LocalContext
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
 * Calibration flow:
 *   GpsManager.parseAcPacket() feeds:
 *     fusion.feedGyroBiasSample(gx,gy,gz) when isGyroBiasCalActive
 *     fusion.feedManualMagSample(mx,my)   when isManualCalActive
 *
 * Phone gyro display:
 *   Uses Android SensorManager TYPE_GYROSCOPE to show live rotation rate and
 *   integrated angle while the user holds the sensor still or rotates it.
 *   This is separate from the BLE IMU gyro — it's the phone's own gyro,
 *   useful to verify the sensor is motionless during gyro bias calibration.
 */
@Composable
fun CalibrationScreen(
    vm: AutopilotViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val fusion  = vm.fusion

    // ── Phone gyro via SensorManager ─────────────────────────────────────────
    var phoneGyroDegS  by remember { mutableStateOf(Triple(0f, 0f, 0f)) }   // x,y,z °/s
    var phoneAngleZ    by remember { mutableFloatStateOf(0f) }               // integrated z °
    var phoneGyroActive by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        var lastNs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Convert rad/s → °/s
                val x = Math.toDegrees(event.values[0].toDouble()).toFloat()
                val y = Math.toDegrees(event.values[1].toDouble()).toFloat()
                val z = Math.toDegrees(event.values[2].toDouble()).toFloat()
                phoneGyroDegS = Triple(x, y, z)
                phoneGyroActive = true

                // Integrate z for displayed angle
                if (lastNs != 0L) {
                    val dt = (event.timestamp - lastNs) / 1_000_000_000f
                    phoneAngleZ = ((phoneAngleZ + z * dt + 360f) % 360f)
                }
                lastNs = event.timestamp
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        if (gyro != null) {
            sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm.unregisterListener(listener) }
    }

    // ── BLE connection state ──────────────────────────────────────────────────
    val bleConn by vm.connectionState.collectAsState()
    val imuConn by vm.imuConnectionState.collectAsState()
    val isBleConnected = bleConn is BleConnectionState.Connected
    val isImuConnected = imuConn is ImuConnectionState.Connected
    val hasA1Source    = isBleConnected || isImuConnected

    // ── Gyro calibration state ────────────────────────────────────────────────
    var gyroPhase  by remember { mutableStateOf(GyroPhase.IDLE) }
    var gyroSecs   by remember { mutableIntStateOf(0) }
    var gyroResult by remember { mutableStateOf("") }
    var gyroOk     by remember { mutableStateOf(false) }

    // ── Mag calibration state ─────────────────────────────────────────────────
    var magPhase   by remember { mutableStateOf(MagPhase.IDLE) }
    var magSamples by remember { mutableIntStateOf(0) }
    var magResult  by remember { mutableStateOf("") }
    var magOk      by remember { mutableStateOf(false) }

    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "r"
    )

    // Gyro: sample for 5 s — GpsManager feeds samples automatically
    LaunchedEffect(gyroPhase) {
        if (gyroPhase == GyroPhase.SAMPLING) {
            gyroSecs = 0
            fusion.startGyroBiasCal()
            repeat(5) { delay(1_000); gyroSecs++ }
            val ok = fusion.finishGyroBiasCal()
            gyroPhase = GyroPhase.IDLE
            gyroOk    = ok
            gyroResult = if (ok)
                "BLE gyro bias — X=%.4f  Y=%.4f  Z=%.4f °/s".format(
                    fusion.gyroBiasX, fusion.gyroBiasY, fusion.gyroBiasZ)
            else "Not enough BLE samples. Is the BLE device connected?"
            if (ok) vm.gpsManager.saveCalibration()
        }
    }

    // Mag: poll sample count estimate every 250 ms
    LaunchedEffect(magPhase) {
        if (magPhase == MagPhase.ROTATING) {
            magSamples = 0
            fusion.startManualMagCal()
            while (magPhase == MagPhase.ROTATING) {
                delay(250)
                magSamples = (magSamples + 12).coerceAtMost(720)
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

            // ── Phone Gyro Live Display ───────────────────────────────────────
            SectionCard("PHONE GYRO  (live)") {
                if (!phoneGyroActive) {
                    Text("No gyroscope detected on this device.",
                        style = MaterialTheme.typography.bodyMedium, color = Muted)
                } else {
                    // Large Z angle display — most useful for yaw/rotation
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("YAW (Z)", style = MaterialTheme.typography.labelMedium, color = Muted)
                            Text(
                                "${String.format("%.1f", phoneGyroDegS.third)} °/s",
                                style = MaterialTheme.typography.headlineLarge,
                                color = when {
                                    abs(phoneGyroDegS.third) < 1f  -> GreenGo
                                    abs(phoneGyroDegS.third) < 10f -> AmberWarn
                                    else                           -> RedAlarm
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Integrated", style = MaterialTheme.typography.labelMedium, color = Muted)
                            Text(
                                "${String.format("%.1f", phoneAngleZ)}°",
                                style = MaterialTheme.typography.displayMedium,
                                color = TealAccent
                            )
                        }
                        // Pitch / Roll smaller
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallGyroCell("PITCH (X)", phoneGyroDegS.first)
                            SmallGyroCell("ROLL  (Y)", phoneGyroDegS.second)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Stillness indicator for gyro bias cal
                    val maxRate = maxOf(abs(phoneGyroDegS.first), abs(phoneGyroDegS.second), abs(phoneGyroDegS.third))
                    Surface(
                        color  = when {
                            maxRate < 0.5f -> GreenGo.copy(0.15f)
                            maxRate < 3f   -> AmberWarn.copy(0.15f)
                            else           -> RedAlarm.copy(0.15f)
                        },
                        shape  = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, when {
                            maxRate < 0.5f -> GreenGo.copy(0.5f)
                            maxRate < 3f   -> AmberWarn.copy(0.5f)
                            else           -> RedAlarm.copy(0.5f)
                        })
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (maxRate < 0.5f) Icons.Default.CheckCircle else Icons.Default.Warning,
                                null,
                                tint = if (maxRate < 0.5f) GreenGo else if (maxRate < 3f) AmberWarn else RedAlarm,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                when {
                                    maxRate < 0.5f -> "Phone is still — good for gyro bias calibration"
                                    maxRate < 3f   -> "Moving slowly — wait until steady"
                                    else           -> "Moving too fast — hold still for gyro cal"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (maxRate < 0.5f) GreenGo else if (maxRate < 3f) AmberWarn else RedAlarm
                            )
                        }
                    }
                    // Reset integrated angle
                    TextButton(onClick = { phoneAngleZ = 0f },
                        modifier = Modifier.align(Alignment.End)) {
                        Text("Reset angle", style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                }
            }

            // ── Saved values ──────────────────────────────────────────────────
            SectionCard("SAVED CALIBRATION VALUES") {
                InfoRow("BLE gyro bias X", "%.4f °/s".format(fusion.gyroBiasX))
                InfoRow("BLE gyro bias Y", "%.4f °/s".format(fusion.gyroBiasY))
                InfoRow("BLE gyro bias Z", "%.4f °/s  ← heading drift".format(fusion.gyroBiasZ))
                HorizontalDivider(color = NavyLight)
                InfoRow("Mag hard-iron X", "%.1f LSB".format(fusion.manualCalHardIronX))
                InfoRow("Mag hard-iron Y", "%.1f LSB".format(fusion.manualCalHardIronY))
                HorizontalDivider(color = NavyLight)
                val decl = fusion.getState().magDeclinationDeg
                InfoRow("Mag declination",
                    if (decl != 0f) "%.2f°  (auto from GPS)".format(decl)
                    else "0.00°  (needs GPS fix)")
            }

            // ── BLE connection requirement ─────────────────────────────────────
            if (!hasA1Source) {
                Surface(
                    color  = RedAlarm.copy(0.1f),
                    shape  = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, RedAlarm.copy(0.5f))
                ) {
                    Row(Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = RedAlarm, modifier = Modifier.size(20.dp))
                        Text("Connect autopilot or IMU device first — BLE A1 packets needed for gyro/mag cal.",
                            style = MaterialTheme.typography.bodyMedium, color = RedAlarm)
                    }
                }
            }

            // ── Gyro Bias Calibration ─────────────────────────────────────────
            SectionCard("GYRO BIAS  (5 seconds)") {
                InstructionBox(
                    icon  = "🚢",
                    title = "Keep BLE sensor perfectly still",
                    steps = listOf(
                        "Watch phone gyro above — wait until it shows green (< 0.5°/s)",
                        "Connect BLE device (A1 at 50 Hz must be flowing)",
                        "Tap START — do not move for 5 seconds"
                    )
                )
                Spacer(Modifier.height(8.dp))
                when (gyroPhase) {
                    GyroPhase.SAMPLING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, null, tint = TealAccent,
                                modifier = Modifier.size(36.dp).rotate(rotation))
                            Spacer(Modifier.height(6.dp))
                            Text("Collecting…  ${gyroSecs}/5 s",
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
            SectionCard("MAGNETOMETER HARD-IRON") {
                InstructionBox(
                    icon  = "🧲",
                    title = "Rotate BLE sensor 360° horizontally",
                    steps = listOf(
                        "Keep sensor level — do not tilt",
                        "Tap START then slowly rotate a full circle (~20–30 s)",
                        "Tap FINISH — needs ≥ 36 samples (~10° spacing)"
                    )
                )
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, null, tint = GreenGo,
                                modifier = Modifier.size(36.dp).rotate(rotation))
                            Spacer(Modifier.height(6.dp))
                            Text("Rotating…  ~$magSamples samples",
                                style = MaterialTheme.typography.titleMedium, color = GreenGo)
                            Text(
                                if (magSamples < 36) "Keep rotating — need ≥ 36"
                                else "✓ Enough — tap Finish when circle is complete",
                                style = MaterialTheme.typography.bodyMedium, color = Muted,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val ok = fusion.finishManualMagCal()
                                magPhase = MagPhase.IDLE; magOk = ok
                                magResult = if (ok)
                                    "X=%.1f  Y=%.1f LSB".format(
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
                TipRow("📱", "Phone gyro vs BLE gyro",
                    "The phone gyro display shows the phone's own motion. " +
                            "The BLE gyro bias calibration measures the IMU sensor on your autopilot board. " +
                            "Both must be still for gyro bias cal — use the phone as a proxy.")
                TipRow("⚓", "When to calibrate",
                    "At anchor in calm water. Redo mag cal if heading drifts > 5°.")
                TipRow("💾", "Auto-saved",
                    "Offsets saved to phone and restored on next launch.")
            }
        }
    }
}

// ── Private enums ─────────────────────────────────────────────────────────────

private enum class GyroPhase { IDLE, SAMPLING }
private enum class MagPhase  { IDLE, ROTATING }

// ── Shared UI helpers ─────────────────────────────────────────────────────────

@Composable
private fun SmallGyroCell(label: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(
            "${String.format("%.1f", value)} °/s",
            style = MaterialTheme.typography.titleMedium,
            color = if (abs(value) < 1f) GreenGo else AmberWarn
        )
    }
}

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
            Text(message, style = MaterialTheme.typography.bodyMedium, color = color)
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