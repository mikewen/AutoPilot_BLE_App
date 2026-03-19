package com.mikewen.autopilot.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.mikewen.autopilot.model.*
import com.mikewen.autopilot.ui.theme.*
import com.mikewen.autopilot.viewmodel.AutopilotViewModel
import kotlin.math.*

@Composable
fun DashboardScreen(
    vm: AutopilotViewModel,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    val connection by vm.connectionState.collectAsState()
    val type       by vm.selectedType.collectAsState()
    val target     by vm.targetHeading.collectAsState()
    val history    by vm.headingHistory.collectAsState()
    val pidConfig  by vm.pidConfig.collectAsState()
    val state      by vm.autopilotState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDeep)
    ) {
        TopBar(connection, onSettings, onDisconnect)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Compass
            CompassCard(currentHeading = state.currentHeading, targetHeading = target,
                deadbandDeg = pidConfig.deadbandDeg)

            // Nav data row
            NavDataRow(state)

            // Deadband status chip
            DeadbandStatusRow(state, pidConfig.deadbandDeg)

            // Engage button
            EngageButton(engaged = state.engaged, onEngage = vm::engage, onStandby = vm::standby)

            // Course adjust
            CourseAdjustRow(vm::portTen, vm::portOne, vm::stbdOne, vm::stbdTen)

            // Heading history chart
            if (history.size > 5) {
                HeadingChart(history)
            }

            // Type-specific panel
            when (type) {
                AutopilotType.TILLER      -> TillerPanel(state)
                AutopilotType.DIFF_THRUST -> DiffThrustPanel(state)
                null                      -> {}
            }

            // Alarms
            if (state.offCourseAlarm || state.lowBatteryAlarm) AlarmBanner(state)

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    connection: BleConnectionState,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    val name = when (connection) {
        is BleConnectionState.Connected -> connection.deviceName
        else -> "—"
    }
    val rssiText = when (connection) {
        is BleConnectionState.Connected -> "${connection.rssi} dBm"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = TealAccent)
            Text(rssiText, style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, "Settings", tint = Muted)
        }
        IconButton(onClick = onDisconnect) {
            Icon(Icons.Default.BluetoothDisabled, "Disconnect", tint = RedAlarm)
        }
    }
}

// ── Compass ───────────────────────────────────────────────────────────────────

@Composable
private fun CompassCard(currentHeading: Float, targetHeading: Float, deadbandDeg: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("HEADING", style = MaterialTheme.typography.labelLarge, color = Muted)
            Spacer(Modifier.height(12.dp))
            CompassDial(currentHeading, targetHeading, deadbandDeg)
            Spacer(Modifier.height(12.dp))
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
        targetValue = currentHeading,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "heading"
    )
    Canvas(modifier = Modifier.size(200.dp)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r  = size.minDimension / 2 - 8.dp.toPx()

        // Outer ring
        drawCircle(NavyLight, radius = r, style = Stroke(2.dp.toPx()))

        // Deadband arc (shaded sector around target)
        if (deadbandDeg > 0f) {
            val startAngle = targetHeading - deadbandDeg - 90f
            val sweep      = deadbandDeg * 2f
            drawArc(
                color      = AmberWarn.copy(alpha = 0.15f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter  = true
            )
            drawArc(
                color      = AmberWarn.copy(alpha = 0.4f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter  = false,
                style      = Stroke(2.dp.toPx())
            )
        }

        // Cardinal tick marks
        for (deg in 0 until 360 step 10) {
            val angle = Math.toRadians(deg.toDouble() - 90.0)
            val inner = if (deg % 90 == 0) 0.80f else if (deg % 30 == 0) 0.85f else 0.90f
            val x1 = cx + cos(angle).toFloat() * r * inner
            val y1 = cy + sin(angle).toFloat() * r * inner
            val x2 = cx + cos(angle).toFloat() * r
            val y2 = cy + sin(angle).toFloat() * r
            drawLine(
                color = if (deg % 90 == 0) TealAccent else NavyLight,
                start = Offset(x1, y1), end = Offset(x2, y2),
                strokeWidth = if (deg % 90 == 0) 3f else 1.5f
            )
        }

        // Target heading needle (amber)
        val txAngle = Math.toRadians(targetHeading.toDouble() - 90.0)
        drawLine(
            AmberWarn.copy(alpha = 0.7f),
            Offset(cx, cy),
            Offset(cx + cos(txAngle).toFloat() * r * 0.65f, cy + sin(txAngle).toFloat() * r * 0.65f),
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
        )

        // Current heading needle (teal)
        val hdgAngle = Math.toRadians(animatedHeading.toDouble() - 90.0)
        drawLine(
            TealAccent,
            Offset(cx, cy),
            Offset(cx + cos(hdgAngle).toFloat() * r * 0.85f, cy + sin(hdgAngle).toFloat() * r * 0.85f),
            strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round
        )

        // Centre dot
        drawCircle(TealAccent, radius = 6.dp.toPx(), center = Offset(cx, cy))
    }
}

@Composable
private fun HeadingReadout(label: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(
            "${value.toInt().toString().padStart(3, '0')}°",
            style = MaterialTheme.typography.displayMedium, color = color
        )
    }
}

// ── Nav Data Row ──────────────────────────────────────────────────────────────

@Composable
private fun NavDataRow(state: AutopilotState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NavDataCard("SPEED",   "${String.format("%.1f", state.speedKnots)} kn",  Modifier.weight(1f))
        NavDataCard("ERROR",   "${String.format("%.1f", state.headingError)}°",  Modifier.weight(1f))
        NavDataCard("BATTERY", "${String.format("%.1f", state.batteryVoltage)}V", Modifier.weight(1f),
            if (state.lowBatteryAlarm) RedAlarm else TealAccent)
    }
}

@Composable
private fun NavDataCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TealAccent) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = SurfaceCard), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, color = valueColor)
        }
    }
}

// ── Deadband Status ───────────────────────────────────────────────────────────

@Composable
private fun DeadbandStatusRow(state: AutopilotState, deadbandDeg: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Deadband chip
        val dbColor  = if (state.inDeadband) AmberWarn else Muted
        val dbLabel  = if (state.inDeadband) "IN DEADBAND" else "ACTIVE"
        Surface(
            color = dbColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, dbColor)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(dbColor, CircleShape)
                )
                Text(dbLabel, style = MaterialTheme.typography.labelMedium, color = dbColor)
            }
        }

        Text(
            "±${String.format("%.1f", deadbandDeg)}° band",
            style = MaterialTheme.typography.labelMedium,
            color = Muted
        )

        Spacer(Modifier.weight(1f))

        // PID output bar
        val pidNorm = (state.pidOutput / 30f).coerceIn(-1f, 1f)
        Text("PID", style = MaterialTheme.typography.labelMedium, color = Muted)
        Box(
            Modifier
                .width(80.dp)
                .height(10.dp)
                .background(NavyMid, RoundedCornerShape(5.dp))
        ) {
            val barWidth = kotlin.math.abs(pidNorm)
            val isPort   = pidNorm < 0
            Box(
                Modifier
                    .fillMaxWidth(barWidth)
                    .fillMaxHeight()
                    .align(if (isPort) Alignment.CenterStart else Alignment.CenterEnd)
                    .background(
                        if (state.inDeadband) Muted.copy(alpha = 0.3f) else TealAccent,
                        RoundedCornerShape(5.dp)
                    )
            )
        }
    }
}

// ── Heading History Chart ─────────────────────────────────────────────────────

@Composable
private fun HeadingChart(history: List<Pair<Float, Float>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("HEADING HISTORY", style = MaterialTheme.typography.labelLarge, color = Muted)
                Text("2 min", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                if (history.size < 2) return@Canvas
                val w = size.width
                val h = size.height
                val n = history.size

                fun yOf(hdg: Float): Float {
                    // Map 0-360 into the canvas, centred on the most recent target
                    val target = history.last().second
                    val span   = 60f  // show ±30° around target
                    return h * (1f - ((hdg - (target - span / 2f)) / span).coerceIn(0f, 1f))
                }

                // Target line (amber dashed via dots)
                history.forEachIndexed { i, (_, t) ->
                    if (i % 4 == 0) {
                        val x = i / (n - 1f) * w
                        drawCircle(AmberWarn.copy(alpha = 0.5f), radius = 2.dp.toPx(), center = Offset(x, yOf(t)))
                    }
                }

                // Heading line (teal)
                for (i in 1 until n) {
                    val x0 = (i - 1) / (n - 1f) * w
                    val x1 = i       / (n - 1f) * w
                    drawLine(
                        TealAccent,
                        Offset(x0, yOf(history[i - 1].first)),
                        Offset(x1, yOf(history[i].first)),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("2m ago", style = MaterialTheme.typography.labelMedium, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(TealAccent, "Actual")
                    LegendDot(AmberWarn, "Target")
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

// ── Engage Button ─────────────────────────────────────────────────────────────

@Composable
private fun EngageButton(engaged: Boolean, onEngage: () -> Unit, onStandby: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p"
    )
    Button(
        onClick = { if (engaged) onStandby() else onEngage() },
        modifier = Modifier.fillMaxWidth().height(60.dp).scale(if (engaged) pulse else 1f),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (engaged) GreenGo else RedAlarm.copy(alpha = 0.8f),
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            if (engaged) Icons.Default.CheckCircle else Icons.Default.PowerSettingsNew,
            null, Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (engaged) "ENGAGED — TAP TO STANDBY" else "STANDBY — TAP TO ENGAGE",
            style = MaterialTheme.typography.labelLarge
        )
    }
}

// ── Course Adjust ─────────────────────────────────────────────────────────────

@Composable
private fun CourseAdjustRow(
    onPortTen: () -> Unit, onPortOne: () -> Unit,
    onStbdOne: () -> Unit, onStbdTen: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("COURSE ADJUST", style = MaterialTheme.typography.labelLarge, color = Muted)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                AdjustButton("◀◀ 10°", onPortTen)
                AdjustButton("◀ 1°",  onPortOne)
                AdjustButton("1° ▶",  onStbdOne)
                AdjustButton("10° ▶▶", onStbdTen)
            }
        }
    }
}

@Composable
private fun AdjustButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, TealAccent.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TealAccent),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

// ── Tiller Panel ──────────────────────────────────────────────────────────────

@Composable
fun TillerPanel(state: AutopilotState) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
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
    val barColor = when {
        abs(clamped) < 5f  -> GreenGo
        abs(clamped) < 20f -> AmberWarn
        else               -> RedAlarm
    }
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
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("DIFFERENTIAL THRUST", style = MaterialTheme.typography.titleLarge, color = TealAccent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ThrottleGauge("PORT", state.portThrottle,      Modifier.weight(1f))
                ThrottleGauge("STBD", state.starboardThrottle, Modifier.weight(1f))
            }
            DataCell("DIFFERENTIAL", String.format("%.1f%%", (state.starboardThrottle - state.portThrottle) * 100f))
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
            Box(Modifier.fillMaxWidth().fillMaxHeight(c).align(Alignment.BottomCenter).background(color.copy(0.7f), RoundedCornerShape(24.dp)))
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
        0.6f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "a"
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = RedAlarm.copy(alpha = pulse * 0.3f)),
        border = BorderStroke(1.dp, RedAlarm),
        shape  = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Warning, null, tint = RedAlarm)
            Column {
                if (state.offCourseAlarm)  Text("⚠ OFF COURSE ALARM", style = MaterialTheme.typography.labelLarge, color = RedAlarm)
                if (state.lowBatteryAlarm) Text("⚠ LOW BATTERY",      style = MaterialTheme.typography.labelLarge, color = AmberWarn)
            }
        }
    }
}
