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
import com.mikewen.autopilot.ui.theme.*
import com.mikewen.autopilot.viewmodel.AutopilotViewModel

@Composable
fun SettingsScreen(vm: AutopilotViewModel, onBack: () -> Unit) {
    val pid by vm.pidConfig.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var snackMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(snackMessage) {
        snackMessage?.let { snackbarHostState.showSnackbar(it); snackMessage = null }
    }

    Scaffold(
        snackbarHost    = { SnackbarHost(snackbarHostState) },
        containerColor  = NavyDeep,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(SurfaceCard).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TealAccent) }
                Text("SETTINGS", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Deadband section ─────────────────────────────────────────────
            SectionCard(title = "DEADBAND") {
                DeadbandExplainer()
                Spacer(Modifier.height(4.dp))
                ParamSlider(
                    label       = "Deadband width",
                    value       = pid.deadbandDeg,
                    range       = 0f..15f,
                    unit        = "°",
                    description = "Error must exceed ±this before PID activates",
                    onValueChange = vm::updateDeadband
                )
                ParamSlider(
                    label       = "Off-course alarm",
                    value       = pid.offCourseAlarmDeg,
                    range       = 5f..45f,
                    unit        = "°",
                    description = "Alarm triggers when error exceeds this threshold",
                    onValueChange = vm::updateOffCourseAlarm
                )
            }

            // ── PID tuning ───────────────────────────────────────────────────
            SectionCard(title = "PID TUNING") {
                ParamSlider("Kp  Proportional", pid.kp, 0f..5f,  "", "Main correction strength",    vm::updatePidKp)
                ParamSlider("Ki  Integral",     pid.ki, 0f..1f,  "", "Eliminates steady-state drift",vm::updatePidKi)
                ParamSlider("Kd  Derivative",   pid.kd, 0f..2f,  "", "Dampens oscillation",          vm::updatePidKd)
                ParamSlider("Output limit",     pid.outputLimit, 5f..45f, "°", "Max rudder / thrust diff", vm::updateOutputLimit)

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.applyPid()
                        snackMessage = "PID + deadband sent to autopilot"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent, contentColor = NavyDeep),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("APPLY ALL SETTINGS", style = MaterialTheme.typography.labelLarge)
                }
            }

            // ── About ─────────────────────────────────────────────────────────
            SectionCard(title = "ABOUT") {
                InfoRow("App Version",  "1.1.0")
                InfoRow("GitHub",       "github.com/mikewen/AutoPilot_BLE_App")
                InfoRow("Protocol",     "BLE GATT / Custom UUID")
                InfoRow("Min SDK",      "26 (Android 8.0)")
            }
        }
    }
}

// ── Deadband explainer ────────────────────────────────────────────────────────

@Composable
private fun DeadbandExplainer() {
    Surface(
        color = AmberWarn.copy(alpha = 0.08f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AmberWarn.copy(0.3f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("What is deadband?", style = MaterialTheme.typography.labelLarge, color = AmberWarn)
            Text(
                "When the heading error is smaller than the deadband, the PID output is forced to zero " +
                "and the integral is reset. This prevents the motor from hunting (constantly correcting " +
                "tiny errors caused by waves). Typical values: 1°–5°. Increase if the motor is busy " +
                "in calm conditions; decrease if the boat wanders noticeably.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = TealAccent)
            HorizontalDivider(color = NavyLight)
            content()
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    description: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Bottom) {
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Text(description, style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Text(
                "${String.format("%.2f", value)}$unit",
                style = MaterialTheme.typography.titleMedium, color = TealAccent
            )
        }
        Slider(
            value = value, onValueChange = onValueChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = TealAccent, activeTrackColor = TealAccent, inactiveTrackColor = NavyLight)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Text(value,  style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}
