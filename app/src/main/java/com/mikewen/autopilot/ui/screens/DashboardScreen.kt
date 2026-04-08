package com.mikewen.autopilot.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.mikewen.autopilot.model.*
import com.mikewen.autopilot.sensor.GpsManager
import com.mikewen.autopilot.ui.theme.*
import com.mikewen.autopilot.viewmodel.AutopilotViewModel
import kotlin.math.*

@Composable
fun DashboardScreen(
    vm: AutopilotViewModel,
    onSettings: () -> Unit,
    onMapTarget: () -> Unit,
    onDisconnect: () -> Unit
) {
    val connection  by vm.connectionState.collectAsState()
    val type        by vm.selectedType.collectAsState()
    val target      by vm.targetHeading.collectAsState()
    val history     by vm.headingHistory.collectAsState()
    val pidConfig   by vm.pidConfig.collectAsState()
    val state       by vm.autopilotState.collectAsState()
    val imuConn     by vm.imuConnectionState.collectAsState()
    val imuState    by vm.imuState.collectAsState()
    val gpsData     by vm.gpsData.collectAsState()
    val targetWp    by vm.targetWaypoint.collectAsState()

    Column(Modifier.fillMaxSize().background(NavyDeep)) {
        TopBar(connection, imuConn, onSettings, onMapTarget, onDisconnect)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // IMU chip when connected
            if (imuConn is ImuConnectionState.Connected)
                ImuStatusChip(imuConn as ImuConnectionState.Connected, imuState)

            // Compass with larger readouts
            CompassCard(state.currentHeading, target, pidConfig.deadbandDeg)

            // Nav row: speed | error | dist/ETA (no battery)
            NavDataRow(state, gpsData, targetWp)

            // Deadband + PID bar
            DeadbandStatusRow(state, pidConfig.deadbandDeg)

            // Engage / Standby — shows data panel when engaged
            EngagePanel(
                state     = state,
                gpsData   = gpsData,
                onEngage  = vm::engage,
                onStandby = vm::standby
            )

            // Larger course adjust buttons
            CourseAdjustRow(vm::portTen, vm::portOne, vm::stbdOne, vm::stbdTen)

            if (history.size > 5) HeadingChart(history)

            if (imuConn is ImuConnectionState.Connected) ImuAttitudeCard(imuState)

            when (type) {
                AutopilotType.TILLER      -> TillerPanel(state)
                AutopilotType.DIFF_THRUST -> DiffThrustPanel(state)
                null                      -> {}
            }

            if (state.offCourseAlarm) AlarmBanner(state)

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    connection: BleConnectionState,
    imuConn: ImuConnectionState,
    onSettings: () -> Unit,
    onMapTarget: () -> Unit,
    onDisconnect: () -> Unit
) {
    val name    = (connection as? BleConnectionState.Connected)?.deviceName ?: "—"
    val rssi    = (connection as? BleConnectionState.Connected)?.let { "${it.rssi} dBm" } ?: ""
    val imuName = (imuConn    as? ImuConnectionState.Connected)?.deviceName

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = TealAccent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rssi, style = MaterialTheme.typography.labelMedium, color = Muted)
                if (imuName != null) {
                    Text("•", style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text("IMU: $imuName", style = MaterialTheme.typography.labelMedium, color = GreenGo)
                }
            }
        }
        IconButton(onClick = onMapTarget) {
            Icon(Icons.Default.Map, "Set Target", tint = TealAccent)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, "Settings", tint = Muted)
        }
        IconButton(onClick = onDisconnect) {
            Icon(Icons.Default.BluetoothDisabled, "Disconnect", tint = RedAlarm)
        }
    }
}

// ── IMU status chip ───────────────────────────────────────────────────────────

@Composable
private fun ImuStatusChip(conn: ImuConnectionState.Connected, imu: ImuState) {
    Surface(
        color  = GreenGo.copy(0.1f),
        shape  = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (imu.calibrated) GreenGo else AmberWarn)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🧭", fontSize = 18.sp)
            Column(Modifier.weight(1f)) {
                Text("IMU: ${conn.deviceName}",
                    style = MaterialTheme.typography.titleMedium, color = GreenGo)
                Text(if (imu.calibrated) "Calibrated  •  Heading source" else "Calibrating…",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (imu.calibrated) Muted else AmberWarn)
            }
            Text("${imu.heading.toInt().toString().padStart(3, '0')}°",
                style = MaterialTheme.typography.headlineMedium, color = GreenGo)
        }
    }
}

// ── IMU attitude card ─────────────────────────────────────────────────────────

@Composable
private fun ImuAttitudeCard(imu: ImuState) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("IMU ATTITUDE", style = MaterialTheme.typography.titleLarge, color = TealAccent)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                AttitudeCell("PITCH", imu.pitch, "°")
                AttitudeCell("ROLL",  imu.roll,  "°")
                if (imu.temperature != 0f) AttitudeCell("TEMP", imu.temperature, "°C")
                CalibrationCell(imu.calibrated)
            }
        }
    }
}

@Composable
private fun AttitudeCell(label: String, value: Float, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text("${String.format("%.1f", value)}$unit",
            style = MaterialTheme.typography.headlineMedium,
            color = when { abs(value) < 5f -> GreenGo; abs(value) < 15f -> AmberWarn; else -> RedAlarm })
    }
}

@Composable
private fun CalibrationCell(calibrated: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("CAL", style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.height(4.dp))
        Surface(color = if (calibrated) GreenGo.copy(0.2f) else AmberWarn.copy(0.2f), shape = CircleShape) {
            Text(if (calibrated) "✓" else "…",
                style = MaterialTheme.typography.headlineMedium,
                color = if (calibrated) GreenGo else AmberWarn,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
        }
    }
}

// ── Compass ───────────────────────────────────────────────────────────────────

@Composable
private fun CompassCard(currentHeading: Float, targetHeading: Float, deadbandDeg: Float) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape  = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("HEADING", style = MaterialTheme.typography.labelLarge, color = Muted)
            Spacer(Modifier.height(12.dp))
            CompassDial(currentHeading, targetHeading, deadbandDeg)
            Spacer(Modifier.height(16.dp))
            // ── Larger readouts ──────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                HeadingReadout("CURRENT", currentHeading, TealAccent)
                HeadingReadout("TARGET",  targetHeading,  AmberWarn)
            }
        }
    }
}

@Composable
private fun CompassDial(currentHeading: Float, targetHeading: Float, deadbandDeg: Float) {
    val animatedHeading by animateFloatAsState(
        targetValue   = currentHeading,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label         = "heading"
    )
    Canvas(modifier = Modifier.size(220.dp)) {
        val cx = size.width / 2; val cy = size.height / 2
        val r  = size.minDimension / 2 - 8.dp.toPx()

        drawCircle(NavyLight, radius = r, style = Stroke(2.dp.toPx()))

        if (deadbandDeg > 0f) {
            val startAngle = targetHeading - deadbandDeg - 90f
            val sweep      = deadbandDeg * 2f
            drawArc(AmberWarn.copy(0.15f), startAngle, sweep, useCenter = true)
            drawArc(AmberWarn.copy(0.4f),  startAngle, sweep, useCenter = false, style = Stroke(2.dp.toPx()))
        }
        for (deg in 0 until 360 step 10) {
            val angle = Math.toRadians(deg.toDouble() - 90.0)
            val inner = if (deg % 90 == 0) 0.80f else if (deg % 30 == 0) 0.85f else 0.90f
            drawLine(
                color = if (deg % 90 == 0) TealAccent else NavyLight,
                start = Offset(cx + cos(angle).toFloat() * r * inner, cy + sin(angle).toFloat() * r * inner),
                end   = Offset(cx + cos(angle).toFloat() * r,         cy + sin(angle).toFloat() * r),
                strokeWidth = if (deg % 90 == 0) 3f else 1.5f
            )
        }
        val txAngle = Math.toRadians(targetHeading.toDouble() - 90.0)
        drawLine(AmberWarn.copy(0.7f), Offset(cx, cy),
            Offset(cx + cos(txAngle).toFloat() * r * 0.65f, cy + sin(txAngle).toFloat() * r * 0.65f),
            3.dp.toPx(), cap = StrokeCap.Round)
        val hdgAngle = Math.toRadians(animatedHeading.toDouble() - 90.0)
        drawLine(TealAccent, Offset(cx, cy),
            Offset(cx + cos(hdgAngle).toFloat() * r * 0.85f, cy + sin(hdgAngle).toFloat() * r * 0.85f),
            4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(TealAccent, radius = 6.dp.toPx(), center = Offset(cx, cy))
    }
}

// ── LARGER heading readout (issue #6) ─────────────────────────────────────────

@Composable
private fun HeadingReadout(label: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Muted)
        Text(
            "${value.toInt().toString().padStart(3, '0')}°",
            fontSize    = 56.sp,           // was displayMedium (~45sp) — now bigger
            fontWeight  = FontWeight.Bold,
            color       = color
        )
    }
}

// ── Nav Data Row — speed | error | dist or ETA (issue #3) ─────────────────────

@Composable
private fun NavDataRow(
    state: AutopilotState,
    gpsData: GpsManager.GpsData,
    targetWp: Waypoint?
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NavDataCard("SPEED", "${String.format("%.1f", state.speedKnots)} kn", Modifier.weight(1f))
        NavDataCard("ERROR", "${String.format("%.1f", state.headingError)}°", Modifier.weight(1f))

        // Distance to target if waypoint set and GPS fix available; otherwise heading confidence
        if (targetWp != null && gpsData.latDeg != 0.0) {
            val distNm = remember(gpsData.latDeg, gpsData.lonDeg, targetWp) {
                // Quick haversine inline — avoid allocating fusion object here
                val R = 3440.065
                val lat1 = Math.toRadians(gpsData.latDeg); val lon1 = Math.toRadians(gpsData.lonDeg)
                val lat2 = Math.toRadians(targetWp.latitude); val lon2 = Math.toRadians(targetWp.longitude)
                val dLat = lat2 - lat1; val dLon = lon2 - lon1
                val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
                R * 2 * asin(sqrt(a))
            }
            val etaMins = if (state.speedKnots > 0.3f) (distNm / state.speedKnots * 60).toInt() else null
            NavDataCard(
                label      = "DIST / ETA",
                value      = if (etaMins != null) "${"%.1f".format(distNm)}nm  ${etaMins}m"
                else "${"%.1f".format(distNm)} nm",
                modifier   = Modifier.weight(1f),
                valueColor = TealAccent
            )
        } else {
            // No waypoint — show GPS source indicator
            NavDataCard(
                label      = "GPS SRC",
                value      = when (gpsData.source) {
                    GpsManager.Source.BLE   -> "BLE"
                    GpsManager.Source.PHONE -> "PHONE"
                    GpsManager.Source.NONE  -> "—"
                },
                modifier   = Modifier.weight(1f),
                valueColor = when (gpsData.source) {
                    GpsManager.Source.BLE   -> GreenGo
                    GpsManager.Source.PHONE -> AmberWarn
                    GpsManager.Source.NONE  -> Muted
                }
            )
        }
    }
}

@Composable
private fun NavDataCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TealAccent) {
    Card(modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape  = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, color = valueColor,
                textAlign = TextAlign.Center)
        }
    }
}

// ── Deadband Status ───────────────────────────────────────────────────────────

@Composable
private fun DeadbandStatusRow(state: AutopilotState, deadbandDeg: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val dbColor = if (state.inDeadband) AmberWarn else Muted
        Surface(color = dbColor.copy(0.15f), shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, dbColor)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(dbColor, CircleShape))
                Text(if (state.inDeadband) "IN DEADBAND" else "ACTIVE",
                    style = MaterialTheme.typography.labelMedium, color = dbColor)
            }
        }
        Text("±${String.format("%.1f", deadbandDeg)}°", style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.weight(1f))
        val pidNorm = (state.pidOutput / 30f).coerceIn(-1f, 1f)
        Text("PID", style = MaterialTheme.typography.labelMedium, color = Muted)
        Box(Modifier.width(80.dp).height(10.dp).background(NavyMid, RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxWidth(abs(pidNorm)).fillMaxHeight()
                .align(if (pidNorm < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .background(if (state.inDeadband) Muted.copy(0.3f) else TealAccent, RoundedCornerShape(5.dp)))
        }
    }
}

// ── Engage Panel (issue #5) ───────────────────────────────────────────────────
// STANDBY state: big ENGAGE button
// ENGAGED state: data panel (heading error, PID output, rudder/throttle) + STANDBY button

@Composable
private fun EngagePanel(
    state: AutopilotState,
    gpsData: GpsManager.GpsData,
    onEngage: () -> Unit,
    onStandby: () -> Unit
) {
    if (state.engaged) {
        // ── Engaged: show data + standby button ──────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = GreenGo.copy(0.08f)),
            border   = BorderStroke(1.dp, GreenGo.copy(0.5f)),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Header
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val pulse by rememberInfiniteTransition(label = "epulse").animateFloat(
                            0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ep")
                        Box(Modifier.size(10.dp).background(GreenGo.copy(alpha = pulse), CircleShape))
                        Text("ENGAGED", style = MaterialTheme.typography.titleLarge,
                            color = GreenGo, fontWeight = FontWeight.Bold)
                    }
                    // STANDBY button — prominent but not full-width
                    Button(
                        onClick = onStandby,
                        colors  = ButtonDefaults.buttonColors(
                            containerColor = RedAlarm, contentColor = Color.White),
                        shape   = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("STANDBY", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = GreenGo.copy(0.2f))
                // Data grid
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    EngageDataCell("ERROR",
                        "${String.format("%+.1f", state.headingError)}°",
                        when {
                            abs(state.headingError) <= 3f  -> GreenGo
                            abs(state.headingError) <= 10f -> AmberWarn
                            else                           -> RedAlarm
                        })
                    EngageDataCell("PID OUT",
                        String.format("%+.1f", state.pidOutput),
                        TealAccent)
                    EngageDataCell("SPEED",
                        "${String.format("%.1f", state.speedKnots)} kn",
                        TealAccent)
                    if (state.rudderAngle != 0f || state.rudderTarget != 0f) {
                        EngageDataCell("RUDDER",
                            "${String.format("%+.1f", state.rudderAngle)}°",
                            AmberWarn)
                    } else {
                        // Diff thrust — show differential
                        EngageDataCell("DIFF",
                            String.format("%+.0f%%",
                                (state.starboardThrottle - state.portThrottle) * 100f),
                            AmberWarn)
                    }
                }
                if (state.inDeadband) {
                    Text("IN DEADBAND — motor idle",
                        style = MaterialTheme.typography.labelMedium, color = AmberWarn)
                }
            }
        }
    } else {
        // ── Standby: big ENGAGE button ───────────────────────────────────────
        Button(
            onClick  = onEngage,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = TealAccent.copy(alpha = 0.9f), contentColor = NavyDeep),
            shape    = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("ENGAGE AUTOPILOT",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EngageDataCell(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(value, style = MaterialTheme.typography.titleLarge, color = color,
            fontWeight = FontWeight.Bold)
    }
}

// ── Course Adjust — larger buttons (issue #6) ─────────────────────────────────

@Composable
private fun CourseAdjustRow(
    onPortTen: () -> Unit, onPortOne: () -> Unit,
    onStbdOne: () -> Unit, onStbdTen: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text("COURSE ADJUST", style = MaterialTheme.typography.labelLarge, color = Muted)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                AdjustButton("◀◀\n10°", onPortTen, Modifier.weight(1f))
                AdjustButton("◀\n1°",   onPortOne, Modifier.weight(1f))
                AdjustButton("1°\n▶",   onStbdOne, Modifier.weight(1f))
                AdjustButton("10°\n▶▶", onStbdTen, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AdjustButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick          = onClick,
        modifier         = modifier.height(64.dp),   // taller buttons
        shape            = RoundedCornerShape(10.dp),
        border           = BorderStroke(1.dp, TealAccent.copy(0.6f)),
        colors           = ButtonDefaults.outlinedButtonColors(contentColor = TealAccent),
        contentPadding   = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

// ── Heading History Chart ─────────────────────────────────────────────────────

@Composable
private fun HeadingChart(history: List<Pair<Float, Float>>) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("HEADING HISTORY", style = MaterialTheme.typography.labelLarge, color = Muted)
                Text("2 min", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                if (history.size < 2) return@Canvas
                val w = size.width; val h = size.height; val n = history.size
                fun yOf(hdg: Float): Float {
                    val t = history.last().second; val span = 60f
                    return h * (1f - ((hdg - (t - span / 2f)) / span).coerceIn(0f, 1f))
                }
                history.forEachIndexed { i, (_, t) ->
                    if (i % 4 == 0) drawCircle(AmberWarn.copy(0.5f), 2.dp.toPx(), Offset(i / (n-1f)*w, yOf(t)))
                }
                for (i in 1 until n) drawLine(TealAccent,
                    Offset((i-1)/(n-1f)*w, yOf(history[i-1].first)),
                    Offset(i/(n-1f)*w,     yOf(history[i].first)), 2.dp.toPx(), cap = StrokeCap.Round)
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("2m ago", style = MaterialTheme.typography.labelMedium, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(TealAccent, "Actual"); LegendDot(AmberWarn, "Target")
                }
                Text("now", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
    }
}

// ── Tiller Panel ──────────────────────────────────────────────────────────────

@Composable
fun TillerPanel(state: AutopilotState) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TILLER", style = MaterialTheme.typography.titleLarge, color = TealAccent)
            RudderBar(state.rudderAngle)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                DataCell("RUDDER",  "${String.format("%.1f", state.rudderAngle)}°")
                DataCell("TARGET",  "${String.format("%.1f", state.rudderTarget)}°")
                DataCell("PID OUT", String.format("%.2f", state.pidOutput))
            }
        }
    }
}

@Composable
private fun RudderBar(angle: Float) {
    val clamped  = angle.coerceIn(-45f, 45f)
    val fraction = (clamped + 45f) / 90f
    val barColor = when { abs(clamped) < 5f -> GreenGo; abs(clamped) < 20f -> AmberWarn; else -> RedAlarm }
    Column {
        Text("RUDDER POSITION", style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(20.dp).background(NavyMid, RoundedCornerShape(10.dp))) {
            Box(Modifier.width(2.dp).fillMaxHeight().align(Alignment.Center).background(Muted))
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(barColor.copy(0.6f), RoundedCornerShape(10.dp)))
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text("PORT 45°", style = MaterialTheme.typography.labelMedium, color = Muted)
            Text("STBD 45°", style = MaterialTheme.typography.labelMedium, color = Muted)
        }
    }
}

// ── Diff Thrust Panel ─────────────────────────────────────────────────────────

@Composable
fun DiffThrustPanel(state: AutopilotState) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("DIFFERENTIAL THRUST", style = MaterialTheme.typography.titleLarge, color = TealAccent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ThrottleGauge("PORT", state.portThrottle,      Modifier.weight(1f))
                ThrottleGauge("STBD", state.starboardThrottle, Modifier.weight(1f))
            }
            DataCell("DIFFERENTIAL", String.format("%.1f%%",
                (state.starboardThrottle - state.portThrottle) * 100f))
        }
    }
}

@Composable
private fun ThrottleGauge(label: String, value: Float, modifier: Modifier = Modifier) {
    val c = value.coerceIn(0f, 1f)
    val color = when { c < 0.3f -> GreenGo; c < 0.7f -> AmberWarn; else -> RedAlarm }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Muted)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(48.dp).height(120.dp).background(NavyMid, RoundedCornerShape(24.dp))) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(c).align(Alignment.BottomCenter)
                .background(color.copy(0.7f), RoundedCornerShape(24.dp)))
        }
        Spacer(Modifier.height(6.dp))
        Text("${(c * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun DataCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

// ── Alarm Banner ──────────────────────────────────────────────────────────────

@Composable
private fun AlarmBanner(state: AutopilotState) {
    val pulse by rememberInfiniteTransition(label = "alarm").animateFloat(
        0.6f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "a")
    Card(colors = CardDefaults.cardColors(containerColor = RedAlarm.copy(alpha = pulse * 0.3f)),
        border = BorderStroke(1.dp, RedAlarm), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Warning, null, tint = RedAlarm)
            Column {
                if (state.offCourseAlarm) Text("⚠ OFF COURSE ALARM",
                    style = MaterialTheme.typography.labelLarge, color = RedAlarm)
            }
        }
    }
}