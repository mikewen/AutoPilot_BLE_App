package com.mikewen.autopilot.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalFocusManager
import com.mikewen.autopilot.ui.theme.*
import com.mikewen.autopilot.viewmodel.AutopilotViewModel
import java.util.Locale
import androidx.compose.material.icons.automirrored.filled.*

@Composable
fun SettingsScreen(
    vm: AutopilotViewModel,
    onBack: () -> Unit,
    onCalibration: () -> Unit
) {
    val pid     = vm.pidConfig.collectAsState().value
    val profile = vm.activeProfile.collectAsState().value
    val snackbarHostState = remember { SnackbarHostState() }
    var snackMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(snackMessage) {
        snackMessage?.let { snackbarHostState.showSnackbar(it); snackMessage = null }
    }

    Scaffold(
        snackbarHost   = { SnackbarHost(snackbarHostState) },
        containerColor = NavyDeep,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TealAccent)
                }
                Column(Modifier.weight(1f)) {
                    Text("SETTINGS", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text("${profile.icon} ${profile.displayName}",
                        style = MaterialTheme.typography.labelMedium, color = TealAccent)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Calibration nav ──────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onCalibration() },
                colors   = CardDefaults.cardColors(containerColor = SurfaceCard),
                border   = BorderStroke(1.dp, TealAccent.copy(0.4f)),
                shape    = RoundedCornerShape(14.dp)
            ) {
                Row(
                    Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(Icons.Default.Tune, null, tint = TealAccent, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SENSOR CALIBRATION",
                            style = MaterialTheme.typography.titleLarge, color = TealAccent)
                        Text("Gyro bias  •  Magnetometer hard-iron",
                            style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TealAccent)
                }
            }

            // ── Profile banner ──────────────────────────────────────────────────
            Surface(
                color  = TealAccent.copy(0.1f),
                shape  = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, TealAccent.copy(0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.icon, fontSize = 28.sp)
                    Column {
                        Text("${profile.displayName} — ${profile.description}",
                            style = MaterialTheme.typography.titleMedium, color = TealAccent)
                        Text("Settings below are saved for this boat only.",
                            style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                }
            }

            // ── Deadband & Alarms ──────────────────────────────────────────────
            SectionCard(title = "DEADBAND & ALARMS") {
                DeadbandExplainer()
                Spacer(Modifier.height(4.dp))
                ParamSlider("Deadband width", pid.deadbandDeg, 1f..9f, "°",
                    "Error must exceed ±this before PID activates", vm::updateDeadband)
                ParamSlider("Off-course alarm", pid.offCourseAlarmDeg, 5f..30f, "°",
                    "Alarm triggers when error exceeds this threshold", vm::updateOffCourseAlarm)
            }

            // ── PID Gains ────────────────────────────────────────────────────
            SectionCard(title = "PID GAINS") {
                ParamSlider("Kp  Proportional", pid.kp, 0f..5f,  "",
                    "Main correction strength — larger = more aggressive", vm::updatePidKp)
                ParamSlider("Ki  Integral",     pid.ki, 0f..1f,  "",
                    "Eliminates steady-state drift (e.g. current, wind)", vm::updatePidKi)
                ParamSlider("Kd  Derivative",   pid.kd, 0f..5f,  "",
                    "Uses live BLE gyro yaw rate — damps oscillation without GPS noise", vm::updatePidKd)
            }

            // ── Output Limits ────────────────────────────────────────────────
            SectionCard(title = "OUTPUT LIMITS") {
                ParamSlider("Output limit", pid.outputLimitDeg, 5f..60f, "°",
                    "Maximum rudder angle / throttle differential", vm::updateOutputLimit)
                ParamSlider("Rate limit", pid.rateLimitDegPerSec, 10f..90f, "°/s",
                    "Max change in output per second — 0 = disabled", vm::updateRateLimit)
            }

            // ── Steering Correction ──────────────────────────────────────────
            SectionCard(title = "STEERING CORRECTION") {
                ParamSlider("Steering bias", pid.steeringBiasDeg, -10f..10f, "°",
                    "Compensate hull asymmetry or prop walk. + = port, - = stbd",
                    vm::updateSteeringBias)
            }

            // ── Speed Scaling ────────────────────────────────────────────────
            SectionCard(title = "SPEED SCALING") {
                SpeedScalingExplainer()
                Spacer(Modifier.height(4.dp))
                ParamSlider("Full-scale speed", pid.maxScaleSpeedKt, 0f..15f, " kt",
                    "Speed at which gains are reduced to minimum (0 = disable scaling)",
                    vm::updateMaxScaleSpeed)
                ParamSlider("Min gain multiplier", pid.minSpeedScale, 0.1f..1f, "×",
                    "Gain multiplier applied at full-scale speed (1.0 = no reduction)",
                    vm::updateMinSpeedScale)
                // Live preview of current scaling
                val speedKt = pid.maxScaleSpeedKt
                val minScale = pid.minSpeedScale
                if (speedKt > 0f) {
                    Spacer(Modifier.height(4.dp))
                    Surface(color = NavyMid, shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Scale preview:", style = MaterialTheme.typography.labelMedium, color = Muted)
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                listOf(0f, speedKt * 0.25f, speedKt * 0.5f, speedKt * 0.75f, speedKt).forEach { spd ->
                                    val t = (spd / speedKt).coerceIn(0f, 1f)
                                    val scale = 1f - t * (1f - minScale)
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${"%.0f".format(Locale.US, spd)}kt",
                                            style = MaterialTheme.typography.labelMedium, color = Muted)
                                        Text("${"%.0f".format(Locale.US, scale * 100)}%",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (scale > 0.7f) TealAccent else AmberWarn)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── GPS_Steer Steer Motor ─────────────────────────────────────────
            SectionCard(title = "STEER MOTOR  (GPS_Steer)") {
                Surface(
                    color  = TealAccent.copy(alpha = 0.08f),
                    shape  = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, TealAccent.copy(0.3f))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Steer scale", style = MaterialTheme.typography.labelLarge, color = TealAccent)
                        Text(
                            "runtimeMs = steerScaleMs * abs(step). " +
                                    "L1/R1 buttons -> 1 step, L5/R5 -> 5 steps. " +
                                    "Tune so 1 step moves the rudder ~1\u00b0.",
                            style = MaterialTheme.typography.bodyMedium, color = Muted
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // Integer slider 20..500 ms — use float internally, cast to Int
                ParamSlider(
                    label       = "Steer scale",
                    value       = pid.steerScaleMs.toFloat(),
                    range       = 20f..500f,
                    unit        = " ms",
                    description = "Motor run time per step unit (L1/R1 = 1 step, L5/R5 = 5 steps)",
                    onValueChange = { vm.updateSteerScale(it.toInt()) }
                )
                // Live preview: show runtimeMs for each button
                Surface(color = NavyMid, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp),
                        Arrangement.SpaceEvenly) {
                        listOf(1, 5).forEach { step ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("L$step / R$step",
                                    style = MaterialTheme.typography.labelMedium, color = Muted)
                                Text("${pid.steerScaleMs * step} ms",
                                    style = MaterialTheme.typography.titleMedium, color = TealAccent)
                            }
                        }
                    }
                }
            }

            // ── Steer Sensor Supervision ──────────────────────────────────────
            SectionCard(title = "STEER SENSOR SUPERVISION") {
                // Enable/disable switch
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use steer sensor in PID loop",
                            style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        Text("Limit enforcement, anti-windup, lag detection",
                            style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                    Switch(
                        checked         = pid.useSteerSensor,
                        onCheckedChange = { vm.updateUseSteerSensor(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = TealAccent,
                            checkedTrackColor   = TealAccent.copy(0.4f),
                            uncheckedThumbColor = Muted,
                            uncheckedTrackColor = NavyMid
                        )
                    )
                }
                if (pid.useSteerSensor) {
                    Spacer(Modifier.height(8.dp))
                    // Requires shaft sensor calibration
                    Surface(color = AmberWarn.copy(0.08f), shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AmberWarn.copy(0.3f))) {
                        Row(Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null,
                                tint = AmberWarn, modifier = Modifier.size(16.dp))
                            Text("Requires shaft sensor (A5) to be calibrated in Calibration screen.",
                                style = MaterialTheme.typography.labelMedium, color = AmberWarn)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ParamSlider("Port limit", pid.shaftLimitPortDeg, 10f..60f, "°",
                        "Motor stops when shaft reaches this port angle",
                        vm::updateShaftLimitPort)
                    ParamSlider("Stbd limit", pid.shaftLimitStbdDeg, 10f..60f, "°",
                        "Motor stops when shaft reaches this stbd angle",
                        vm::updateShaftLimitStbd)
                    ParamSlider("Lag threshold", pid.shaftLagThresholdDeg, 0.5f..10f, "°",
                        "Min shaft movement expected within lag window",
                        vm::updateShaftLagThreshold)
                    ParamSlider("Lag window", pid.shaftLagWindowMs.toFloat(), 500f..5000f, " ms",
                        "Time allowed before declaring actuator lag/failure",
                        vm::updateShaftLagWindow)
                }
            }

            // ── Apply button ─────────────────────────────────────────────────
            Button(
                onClick  = { vm.applyPid(); snackMessage = "All settings sent to autopilot" },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = TealAccent, contentColor = NavyDeep),
                shape    = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("APPLY ALL SETTINGS", style = MaterialTheme.typography.labelLarge)
            }

            // ── About ────────────────────────────────────────────────────────
            SectionCard(title = "ABOUT") {
                InfoRow("App Version", "1.1.0")
                InfoRow("GitHub",      "github.com/mikewen/AutoPilot_BLE_App")
                InfoRow("Protocol",    "BLE GATT / Custom UUID")
                InfoRow("Min SDK",     "26 (Android 8.0)")
            }
        }
    }
}

// ── Deadband explainer ────────────────────────────────────────────────────────

@Composable
private fun SpeedScalingExplainer() {
    Surface(
        color  = TealAccent.copy(alpha = 0.08f),
        shape  = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, TealAccent.copy(0.3f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("What is speed scaling?", style = MaterialTheme.typography.labelLarge, color = TealAccent)
            Text(
                "At low speed a boat needs full rudder authority. At higher speeds the same " +
                        "correction causes uncomfortable yawing or overshoot. Speed scaling reduces " +
                        "P and D gains proportionally as speed increases, while Ki is unaffected so " +
                        "steady-state drift is still corrected. Set Full-scale speed = 0 to disable.",
                style = MaterialTheme.typography.bodyMedium, color = Muted
            )
        }
    }
}

@Composable
private fun DeadbandExplainer() {
    Surface(
        color  = AmberWarn.copy(alpha = 0.08f),
        shape  = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AmberWarn.copy(0.3f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("What is deadband?", style = MaterialTheme.typography.labelLarge, color = AmberWarn)
            Text(
                "When the heading error is smaller than the deadband, the PID output is forced to zero " +
                        "and the integral is reset. This prevents the motor from hunting (constantly correcting " +
                        "tiny errors caused by waves). Typical values: 1°–5°.",
                style = MaterialTheme.typography.bodyMedium, color = Muted
            )
        }
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = TealAccent)
            HorizontalDivider(color = NavyLight)
            content()
        }
    }
}

@Composable
internal fun ParamSlider(
    label: String, value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String, description: String,
    onValueChange: (Float) -> Unit
) {
    var editing       by remember { mutableStateOf(false) }
    var textValue     by remember(value) {
        mutableStateOf(TextFieldValue(
            text      = if (unit == " ms") value.toInt().toString()
            else String.format("%.2f", value),
            selection = TextRange(0, 99)   // select all on open
        ))
    }

    // Update textValue from external 'value' ONLY when NOT currently editing
    LaunchedEffect(value) {
        if (!editing) {
            textValue = TextFieldValue(
                text = if (unit == " ms") value.toInt().toString()
                else String.format(Locale.US, "%.2f", value)
            )
        }
    }

    val focusRequester = remember { FocusRequester() }
    val focusManager   = LocalFocusManager.current
    var hasFocused     by remember { mutableStateOf(false) }

    fun commitText() {
        if (!editing) return
        val sanitized = textValue.text.trim().replace(',', '.')
        val parsed = sanitized.toFloatOrNull()
        if (parsed != null) {
            onValueChange(parsed.coerceIn(range.start, range.endInclusive))
        }
        editing = false
        hasFocused = false
    }

    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Text(description, style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Spacer(Modifier.width(8.dp))
            if (editing) {
                OutlinedTextField(
                    value           = textValue,
                    onValueChange   = { textValue = it },
                    modifier        = Modifier
                        .width(110.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                hasFocused = true
                            } else if (hasFocused) {
                                // Focus lost (e.g. user tapped elsewhere) -> commit and close
                                commitText()
                            }
                        },
                    singleLine      = true,
                    suffix          = { Text(unit, color = Muted) },
                    textStyle       = MaterialTheme.typography.titleMedium.copy(color = TealAccent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction    = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        // Clear focus triggers onFocusChanged -> commitText()
                        focusManager.clearFocus()
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = TealAccent,
                        unfocusedBorderColor = NavyLight,
                        cursorColor          = TealAccent,
                        focusedTextColor     = TealAccent
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                LaunchedEffect(Unit) {
                    // Select all text on open for easy replacement
                    textValue = textValue.copy(selection = TextRange(0, textValue.text.length))
                    focusRequester.requestFocus()
                }
            } else {
                Surface(
                    onClick = { editing = true },
                    color   = NavyMid,
                    shape   = RoundedCornerShape(8.dp),
                    border  = BorderStroke(1.dp, TealAccent.copy(0.4f))
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            if (unit == " ms") value.toInt().toString()
                            else String.format(Locale.US, "%.2f", value),
                            style = MaterialTheme.typography.titleMedium, color = TealAccent
                        )
                        Text(unit, style = MaterialTheme.typography.labelMedium, color = Muted)
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(12.dp), tint = Muted)
                    }
                }
            }
        }
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = range,
            colors        = SliderDefaults.colors(
                thumbColor         = TealAccent,
                activeTrackColor   = TealAccent,
                inactiveTrackColor = NavyLight
            )
        )
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Text(value,  style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}
