package com.mikewen.autopilot.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.mikewen.autopilot.model.AutopilotType
import com.mikewen.autopilot.ui.theme.*

@Composable
fun TypeSelectScreen(onTypeSelected: (AutopilotType) -> Unit) {

    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "shimmer"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDeep)
            .drawBehind { drawMarineGrid(shimmer) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            // Logo / Title
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "AUTO",
                    style = MaterialTheme.typography.displayLarge,
                    color = TealAccent
                )
                Text(
                    "PILOT",
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "BLE CONTROLLER",
                    style = MaterialTheme.typography.labelLarge,
                    color = Muted,
                    letterSpacing = 4.sp
                )
            }

            HorizontalDivider(color = NavyLight, thickness = 1.dp)

            Text(
                "SELECT AUTOPILOT TYPE",
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
                textAlign = TextAlign.Center
            )

            // Tiller Card
            AutopilotTypeCard(
                type = AutopilotType.TILLER,
                icon = "⚓",
                onSelect = onTypeSelected
            )

            // Diff Thrust Card
            AutopilotTypeCard(
                type = AutopilotType.DIFF_THRUST,
                icon = "⚡",
                onSelect = onTypeSelected
            )

            Text(
                "v1.0.0  •  github.com/mikewen/AutoPilot_BLE_App",
                style = MaterialTheme.typography.labelMedium,
                color = Muted.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AutopilotTypeCard(
    type: AutopilotType,
    icon: String,
    onSelect: (AutopilotType) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable { onSelect(type) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, NavyLight)
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(NavyLight, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 28.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    type.displayName.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = TealAccent
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    type.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }
            Text("›", style = MaterialTheme.typography.displayMedium, color = TealAccent)
        }
    }
}

// Animated grid background
private fun DrawScope.drawMarineGrid(phase: Float) {
    val gridSpacing = 48.dp.toPx()
    val lineColor = Color(0xFF1A3A5C).copy(alpha = 0.4f)

    var x = 0f
    while (x < size.width) {
        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += gridSpacing
    }
    var y = 0f
    while (y < size.height) {
        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += gridSpacing
    }

    // Moving highlight dot
    val cx = size.width * phase
    val cy = size.height * 0.3f
    drawCircle(TealAccent.copy(alpha = 0.08f), radius = 120.dp.toPx(), center = Offset(cx, cy))
}
