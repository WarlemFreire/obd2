package com.soswf.obd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.soswf.obd.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val esquema = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF9CC3F5), secondary = Color(0xFFF2A541))
            } else {
                lightColorScheme(primary = Color(0xFF1F3A5F), secondary = Color(0xFFB86E00))
            }
            MaterialTheme(colorScheme = esquema) { App() }
        }
    }
}
