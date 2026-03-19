package com.mikewen.autopilot.ui.screens

import android.Manifest
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val connectionState by vm.connectionState.collectAsState()
    val devices         by vm.scannedDevices.collectAsState()
    val selectedType    by vm.selectedType.collectAsState()

    LaunchedEffect(connectionState) {
        if (connectionState is BleConnectionState.Connected) onConnected()
    }

    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberMultiplePermissionsState(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        )
    } else {
        rememberMultiplePermissionsState(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDeep)
            .padding(16.dp)
    ) {
        // Top bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = TealAccent)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("SCAN DEVICES", style = MaterialTheme.typography.titleLarge, color = Color.White)
                selectedType?.let {
                    Text(it.displayName.uppercase(), style = MaterialTheme.typography.labelMedium, color = TealAccent)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!blePermissions.allPermissionsGranted) {
            PermissionCard { blePermissions.launchMultiplePermissionRequest() }
        } else {
            ScanButton(connectionState, onScan = vm::startScan, onStop = vm::stopScan)
            Spacer(Modifier.height(10.dp))
            StatusChip(connectionState)
            Spacer(Modifier.height(14.dp))

            if (devices.isEmpty() && connectionState is BleConnectionState.Scanning) {
                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    Text("Searching for autopilots…", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.address }) { device ->
                    DeviceCard(device) { vm.connect(device) }
                }
            }
        }
    }
}

@Composable
private fun ScanButton(state: BleConnectionState, onScan: () -> Unit, onStop: () -> Unit) {
    val scanning = state is BleConnectionState.Scanning
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "r"
    )
    Button(
        onClick = { if (scanning) onStop() else onScan() },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
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
            Text("START SCAN", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusChip(state: BleConnectionState) {
    val (text, color) = when (state) {
        is BleConnectionState.Scanning    -> "Scanning for devices…" to TealAccent
        is BleConnectionState.Connecting  -> "Connecting to ${state.deviceName}…" to AmberWarn
        is BleConnectionState.Connected   -> "Connected to ${state.deviceName}" to GreenGo
        is BleConnectionState.Error       -> state.message to RedAlarm
        is BleConnectionState.Disconnected -> "Ready — tap Scan to start" to Muted
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun DeviceCard(device: BleDevice, onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onConnect() },
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, NavyLight),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
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
            Column(modifier = Modifier.weight(1f)) {
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
private fun PermissionCard(onRequest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, AmberWarn),
        shape  = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Bluetooth, null, tint = AmberWarn, modifier = Modifier.size(40.dp))
            Text("Bluetooth Permission Required",
                style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("Grant Bluetooth access to scan for and connect to your autopilot unit.",
                style = MaterialTheme.typography.bodyMedium, color = Muted)
            Button(
                onClick = onRequest,
                colors  = ButtonDefaults.buttonColors(containerColor = AmberWarn, contentColor = NavyDeep)
            ) { Text("GRANT PERMISSION", style = MaterialTheme.typography.labelLarge) }
        }
    }
}
