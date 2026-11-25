package com.wearsync.watch.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme

object WearSyncColors {
    val Background = Color(0xFF000000)
    val CardSurface = Color(0xFF1C1C1E)
    val PillSurface = Color(0xFF3A3A3C)
    val PillSurfaceSecondary = Color(0xFF2C2C2E)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextHeader = Color(0xFFE5E5EA)
    val TextSecondary = Color(0xFF9E9EA4)
    val StatusConnected = Color(0xFF5CE06F)
    val StatusDisconnected = Color(0xFFFFA726)
    val StatusConnecting = Color(0xFF64B5F6)
    val DotActive = Color(0xFFFFFFFF)
    val DotInactive = Color(0xFF555558)
}

@Composable
fun WearSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
