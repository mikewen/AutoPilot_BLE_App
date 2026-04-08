package com.mikewen.autopilot.ui.screens

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.mikewen.autopilot.model.Waypoint
import com.mikewen.autopilot.ui.theme.*
import com.mikewen.autopilot.viewmodel.AutopilotViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.UUID
import kotlin.math.*

/**
 * MapTargetScreen
 *
 * Offline-capable map using OSMDroid (OpenStreetMap).
 * Tiles are downloaded on first use and cached on-device — works offline after that.
 *
 * Features:
 *   - Shows current boat position (blue dot)
 *   - Tap anywhere to place a target marker (red pin)
 *   - Shows bearing and distance from current position to target
 *   - "SET AS TARGET" button sends the bearing to the autopilot via ViewModel
 *   - Saved waypoints list (last 10 taps)
 */
@Composable
fun MapTargetScreen(
    vm: AutopilotViewModel,
    onBack: () -> Unit
) {
    val context     = LocalContext.current
    val gpsData     by vm.gpsData.collectAsState()
    val currentWp   by vm.targetWaypoint.collectAsState()

    var tappedPoint  by remember { mutableStateOf<GeoPoint?>(null) }
    var bearing      by remember { mutableStateOf<Float?>(null) }
    var distanceNm   by remember { mutableStateOf<Double?>(null) }

    // Saved waypoints (session only — persisted via Waypoint in ViewModel if needed)
    var savedWaypoints by remember { mutableStateOf<List<Waypoint>>(emptyList()) }

    // Init OSMDroid config once
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
    }

    // Recalculate bearing when tap or GPS changes
    LaunchedEffect(tappedPoint, gpsData.latDeg, gpsData.lonDeg) {
        val tp = tappedPoint ?: return@LaunchedEffect
        if (gpsData.hasFix && gpsData.latDeg != 0.0) {
            bearing    = vm.gpsManager.fusion.bearingTo(
                gpsData.latDeg, gpsData.lonDeg, tp.latitude, tp.longitude)
            distanceNm = vm.gpsManager.fusion.haversineNm(
                gpsData.latDeg, gpsData.lonDeg, tp.latitude, tp.longitude)
        }
    }

    // MapView reference for imperative updates
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    Scaffold(
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
                    Icon(Icons.Default.ArrowBack, "Back", tint = TealAccent)
                }
                Column(Modifier.weight(1f)) {
                    Text("SET TARGET", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text("Tap map to place target", style = MaterialTheme.typography.labelMedium, color = Muted)
                }
                // Centre on boat
                IconButton(onClick = {
                    mapViewRef?.let { mv ->
                        if (gpsData.hasFix && gpsData.latDeg != 0.0) {
                            mv.controller.animateTo(GeoPoint(gpsData.latDeg, gpsData.lonDeg))
                        }
                    }
                }) {
                    Icon(Icons.Default.MyLocation, "Centre", tint = TealAccent)
                }
            }
        },
        bottomBar = {
            // Target info + Set button
            if (tappedPoint != null) {
                Surface(color = SurfaceCard, shadowElevation = 8.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Column {
                                Text("TARGET", style = MaterialTheme.typography.labelMedium, color = Muted)
                                Text(
                                    "${"%.5f".format(tappedPoint!!.latitude)}°  ${"%.5f".format(tappedPoint!!.longitude)}°",
                                    style = MaterialTheme.typography.bodyMedium, color = Color.White
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                bearing?.let {
                                    Text("BEARING", style = MaterialTheme.typography.labelMedium, color = Muted)
                                    Text("${it.toInt()}°", style = MaterialTheme.typography.headlineMedium, color = TealAccent)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                distanceNm?.let {
                                    Text("DISTANCE", style = MaterialTheme.typography.labelMedium, color = Muted)
                                    Text("${"%.2f".format(it)} nm", style = MaterialTheme.typography.headlineMedium, color = AmberWarn)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Save waypoint
                            OutlinedButton(
                                onClick = {
                                    val tp = tappedPoint ?: return@OutlinedButton
                                    val wp = Waypoint(
                                        id        = UUID.randomUUID().toString(),
                                        name      = "WP ${savedWaypoints.size + 1}",
                                        latitude  = tp.latitude,
                                        longitude = tp.longitude
                                    )
                                    savedWaypoints = (listOf(wp) + savedWaypoints).take(10)
                                },
                                modifier = Modifier.weight(1f),
                                border   = BorderStroke(1.dp, Muted.copy(0.5f)),
                                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Muted),
                                shape    = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.BookmarkAdd, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("SAVE", style = MaterialTheme.typography.labelLarge)
                            }
                            // Set as autopilot target
                            Button(
                                onClick = {
                                    val tp = tappedPoint ?: return@Button
                                    val wp = Waypoint(
                                        id        = UUID.randomUUID().toString(),
                                        name      = "Map Target",
                                        latitude  = tp.latitude,
                                        longitude = tp.longitude
                                    )
                                    vm.setTargetWaypoint(wp)
                                    onBack()
                                },
                                modifier = Modifier.weight(2f),
                                colors   = ButtonDefaults.buttonColors(
                                    containerColor = TealAccent, contentColor = NavyDeep),
                                shape    = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Navigation, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("SET AS TARGET", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // ── OSMDroid MapView ──────────────────────────────────────────────
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory  = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(14.0)

                        // Centre on boat or default (Atlantic Ocean start)
                        val startLat = if (gpsData.hasFix && gpsData.latDeg != 0.0) gpsData.latDeg else 45.0
                        val startLon = if (gpsData.hasFix && gpsData.lonDeg != 0.0) gpsData.lonDeg else -63.0
                        controller.setCenter(GeoPoint(startLat, startLon))

                        // My location overlay (blue dot = boat)
                        val myLocation = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                        myLocation.enableMyLocation()
                        overlays.add(myLocation)

                        // Tap to place target marker
                        val tapReceiver = object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                tappedPoint = p

                                // Remove existing target markers
                                overlays.removeAll { it is Marker && it.title == "Target" }

                                // Add new marker
                                val marker = Marker(this@apply).apply {
                                    position = p
                                    title    = "Target"
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                }
                                overlays.add(marker)
                                invalidate()
                                return true
                            }
                            override fun longPressHelper(p: GeoPoint) = false
                        }
                        overlays.add(0, MapEventsOverlay(tapReceiver))

                        mapViewRef = this
                    }
                },
                update = { mv ->
                    // Update boat position marker if GPS changes
                    mapViewRef = mv
                }
            )

            // ── Saved waypoints panel (slide up from left) ────────────────────
            if (savedWaypoints.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .width(200.dp),
                    color  = SurfaceCard.copy(alpha = 0.92f),
                    shape  = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, NavyLight)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("SAVED", style = MaterialTheme.typography.labelLarge, color = TealAccent)
                        savedWaypoints.forEach { wp ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val gp = GeoPoint(wp.latitude, wp.longitude)
                                        tappedPoint = gp
                                        mapViewRef?.let { mv ->
                                            mv.overlays.removeAll { it is Marker && it.title == "Target" }
                                            val marker = Marker(mv).apply {
                                                position = gp
                                                title    = "Target"
                                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                            }
                                            mv.overlays.add(marker)
                                            mv.controller.animateTo(gp)
                                            mv.invalidate()
                                        }
                                    }
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Bookmark, null,
                                    tint = AmberWarn, modifier = Modifier.size(14.dp))
                                Column {
                                    Text(wp.name, style = MaterialTheme.typography.labelLarge, color = Color.White)
                                    Text("${"%.4f".format(wp.latitude)}, ${"%.4f".format(wp.longitude)}",
                                        style = MaterialTheme.typography.labelMedium, color = Muted)
                                }
                            }
                        }
                    }
                }
            }

            // ── No GPS warning ────────────────────────────────────────────────
            if (!gpsData.hasFix) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (tappedPoint != null) 160.dp else 16.dp)
                        .padding(horizontal = 16.dp),
                    color  = NavyMid.copy(alpha = 0.9f),
                    shape  = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AmberWarn.copy(0.5f))
                ) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GpsOff, null, tint = AmberWarn, modifier = Modifier.size(16.dp))
                        Text("No GPS fix — bearing calculation unavailable",
                            style = MaterialTheme.typography.bodyMedium, color = AmberWarn)
                    }
                }
            }
        }
    }
}
