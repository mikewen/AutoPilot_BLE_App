package com.mikewen.autopilot.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.google.accompanist.permissions.*
import com.mikewen.autopilot.model.*
import com.mikewen.autopilot.ui.theme.*
import com.mikewen.autopilot.viewmodel.AutopilotViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ScanScreen(
    vm: AutopilotViewModel,
    onConnected: () -> Unit,
    onBack: () -> Unit
) {
    val connectionState    by vm.connectionState.collectAsState()
    val devices            by vm.scannedDevices.collectAsState()
    val selectedType       by vm.selectedType.collectAsState()
    val imuConnectionState by vm.imuConnectionState.collectAsState()
    val imuDevices         by vm.scannedImuDevices.collectAsState()

    BackHandler { onBack() }

    LaunchedEffect(connectionState) {
        if (connectionState is BleConnectionState.Connected) onConnected()
    }

    // On Android 12+ we need both BLE permissions AND ACCESS_FINE_LOCATION.
    // ACCESS_FINE_LOCATION is required by FusedLocationProviderClient for phone GPS.
    // Previously we only requested BLE perms on S+, which left GPS permission ungranted
    // and caused startPhoneGps() to silently fail every time.
    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberMultiplePermissionsState(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION   // needed for phone GPS
            )
        )
    } else {
        rememberMultiplePermissionsState(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    // Re-start phone GPS once location permission is granted.
    // ViewModel.init() calls startPhoneGps() but permission may not be granted yet at that point.
    LaunchedEffect(blePermissions.allPermissionsGranted) {
        if (blePermissions.allPermissionsGranted) {
            vm.gpsManager.startPhoneGps()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDeep)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        // Top bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = TealAccent)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("CONNECT DEVICES", style = MaterialTheme.typography.titleLarge, color = Color.White)
                selectedType?.let {
                    Text(it.displayName.uppercase(), style = MaterialTheme.typography.labelMedium, color = TealAccent)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!blePermissions.allPermissionsGranted) {
            PermissionCard { blePermissions.launchMultiplePermissionRequest() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // ── Section 1: Autopilot ──────────────────────────────────────
                SectionHeader("⚓  AUTOPILOT", "BLE_tiller  •  ESC_PWM  •  BLDC_PWM")

                ScanButton(
                    scanning = connectionState is BleConnectionState.Scanning,
                    label    = "SCAN FOR AUTOPILOT",
                    onScan   = vm::startScan,
                    onStop   = vm::stopScan
                )

                val apText = when (connectionState) {
                    is BleConnectionState.Scanning    -> "Scanning…"
                    is BleConnectionState.Connecting  -> "Connecting to ${(connectionState as BleConnectionState.Connecting).deviceName}…"
                    is BleConnectionState.Connected   -> "✓ Connected — ${(connectionState as BleConnectionState.Connected).deviceName}  ${(connectionState as BleConnectionState.Connected).rssi} dBm"
                    is BleConnectionState.Error       -> (connectionState as BleConnectionState.Error).message
                    is BleConnectionState.Disconnected -> "Ready"
                }
                val apColor = when (connectionState) {
                    is BleConnectionState.Connected    -> GreenGo
                    is BleConnectionState.Connecting,
                    is BleConnectionState.Scanning     -> TealAccent
                    is BleConnectionState.Error        -> RedAlarm
                    else                               -> Muted
                }
                Text(apText, style = MaterialTheme.typography.bodyMedium, color = apColor)

                if (devices.isEmpty() && connectionState is BleConnectionState.Scanning) {
                    SearchingHint("Searching for autopilots…")
                }
                devices.forEach { device ->
                    AutopilotDeviceCard(device) { vm.connect(device) }
                }

                HorizontalDivider(color = NavyLight, thickness = 1.dp)

                // ── Section 2: IMU Sensor ─────────────────────────────────────
                SectionHeader("🧭  IMU SENSOR", "IMU_PWM  •  IMU_*  (optional — both autopilot types)")

                ScanButton(
                    scanning = imuConnectionState is ImuConnectionState.Scanning,
                    label    = "SCAN FOR IMU",
                    onScan   = vm::startImuScan,
                    onStop   = vm::stopImuScan
                )

                val imuText = when (imuConnectionState) {
                    is ImuConnectionState.Scanning    -> "Scanning for IMU…"
                    is ImuConnectionState.Connecting  -> "Connecting to ${(imuConnectionState as ImuConnectionState.Connecting).deviceName}…"
                    is ImuConnectionState.Connected   -> "✓ IMU connected — ${(imuConnectionState as ImuConnectionState.Connected).deviceName}"
                    is ImuConnectionState.Error       -> (imuConnectionState as ImuConnectionState.Error).message
                    is ImuConnectionState.Disconnected -> "No IMU — autopilot uses its own sensor"
                }
                val imuColor = when (imuConnectionState) {
                    is ImuConnectionState.Connected    -> GreenGo
                    is ImuConnectionState.Connecting,
                    is ImuConnectionState.Scanning     -> TealAccent
                    is ImuConnectionState.Error        -> RedAlarm
                    else                               -> Muted
                }
                Text(imuText, style = MaterialTheme.typography.bodyMedium, color = imuColor)

                if (imuDevices.isEmpty() && imuConnectionState is ImuConnectionState.Scanning) {
                    SearchingHint("Searching for IMU sensors…")
                }
                imuDevices.forEach { device ->
                    ImuDeviceCard(device, imuConnectionState) { vm.connectImu(device) }
                }

                if (imuConnectionState is ImuConnectionState.Connected) {
                    OutlinedButton(
                        onClick  = vm::disconnectImu,
                        modifier = Modifier.fillMaxWidth(),
                        border   = BorderStroke(1.dp, RedAlarm.copy(alpha = 0.6f)),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = RedAlarm),
                        shape    = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.BluetoothDisabled, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("DISCONNECT IMU", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── Components ────────────────────────────────────────────────────────────────

@Composable
private fun ScanButton(scanning: Boolean, label: String, onScan: () -> Unit, onStop: () -> Unit) {
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "r"
    )
    Button(
        onClick  = { if (scanning) onStop() else onScan() },
        modifier = Modifier.fillMaxWidth(),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (scanning) NavyLight else TealAccent,
            contentColor   = if (scanning) TealAccent else NavyDeep
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (scanning) {
            Icon(Icons.Default.Refresh, null, Modifier.rotate(rotation).size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("SCANNING…", style = MaterialTheme.typography.labelLarge)
        } else {
            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Muted)
    }
}

@Composable
private fun SearchingHint(message: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
private fun AutopilotDeviceCard(device: BleDevice, onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onConnect() },
        colors   = CardDefaults.cardColors(containerColor = SurfaceCard),
        border   = BorderStroke(1.dp, NavyLight),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                when (device.type) {
                    AutopilotType.TILLER      -> "⚓"
                    AutopilotType.DIFF_THRUST -> "⚡"
                    null                      -> "❓"
                },
                fontSize = 24.sp
            )
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.address, style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${device.rssi} dBm", style = MaterialTheme.typography.labelMedium, color = TealAccent)
                device.type?.let {
                    Text(it.displayName, style = MaterialTheme.typography.labelMedium, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun ImuDeviceCard(device: ImuDevice, connectionState: ImuConnectionState, onConnect: () -> Unit) {
    val isConnected = connectionState is ImuConnectionState.Connected &&
            (connectionState as ImuConnectionState.Connected).deviceName == device.name
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isConnected) { onConnect() },
        colors   = CardDefaults.cardColors(
            containerColor = if (isConnected) GreenGo.copy(alpha = 0.1f) else SurfaceCard
        ),
        border = BorderStroke(1.dp, if (isConnected) GreenGo else NavyLight),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("🧭", fontSize = 24.sp)
            Column(Modifier.weight(1f)) {
                Text(device.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isConnected) GreenGo else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.address, style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${device.rssi} dBm", style = MaterialTheme.typography.labelMedium, color = TealAccent)
                if (isConnected) Text("CONNECTED", style = MaterialTheme.typography.labelMedium, color = GreenGo)
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = SurfaceCard),
        border   = BorderStroke(1.dp, AmberWarn),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Bluetooth, null, tint = AmberWarn, modifier = Modifier.size(40.dp))
            Text("Bluetooth Permission Required",
                style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("Grant Bluetooth access to scan for autopilot and IMU devices.",
                style = MaterialTheme.typography.bodyMedium, color = Muted)
            Button(
                onClick = onRequest,
                colors  = ButtonDefaults.buttonColors(containerColor = AmberWarn, contentColor = NavyDeep)
            ) { Text("GRANT PERMISSION", style = MaterialTheme.typography.labelLarge) }
        }
    }
}