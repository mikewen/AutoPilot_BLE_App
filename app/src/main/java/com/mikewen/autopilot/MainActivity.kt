package com.mikewen.autopilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mikewen.autopilot.ui.AutoPilotApp
import com.mikewen.autopilot.ui.theme.AutoPilotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutoPilotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AutoPilotApp()
                }
            }
        }
    }
}
