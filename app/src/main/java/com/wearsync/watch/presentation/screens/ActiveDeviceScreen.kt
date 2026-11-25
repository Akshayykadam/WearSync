package com.wearsync.watch.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.wearsync.watch.R
import com.wearsync.watch.ancs.ConnectionStatus
import com.wearsync.watch.presentation.theme.WearSyncColors

@Composable
fun ActiveDeviceScreen(
    deviceName: String?,
    connectionStatus: ConnectionStatus,
    statusHint: String?,
    onDisconnectClick: () -> Unit,
    onReconnectClick: () -> Unit,
    onForgetClick: () -> Unit,
    onFindIphoneClick: () -> Unit
) {
    val hasDevice = !deviceName.isNullOrBlank()
    val displayTitle = deviceName ?: "No iPhone Paired"

    val (statusLabel, statusColor) = when {
        !hasDevice -> "Swipe to connect" to WearSyncColors.TextSecondary
        connectionStatus == ConnectionStatus.CONNECTED -> "Connected" to WearSyncColors.StatusConnected
        connectionStatus == ConnectionStatus.CONNECTING -> "Connecting…" to WearSyncColors.StatusConnecting
        connectionStatus == ConnectionStatus.DISCOVERING_SERVICES -> "Syncing ANCS…" to WearSyncColors.StatusConnecting
        connectionStatus == ConnectionStatus.PAIRING -> "Pairing…" to WearSyncColors.StatusConnecting
        connectionStatus == ConnectionStatus.SCANNING -> "Scanning…" to WearSyncColors.StatusConnecting
        else -> "Disconnected" to WearSyncColors.StatusDisconnected
    }

    val isBusyOrConnected = connectionStatus == ConnectionStatus.CONNECTED ||
        connectionStatus == ConnectionStatus.CONNECTING ||
        connectionStatus == ConnectionStatus.DISCOVERING_SERVICES ||
        connectionStatus == ConnectionStatus.PAIRING

    val buttonLabel = when {
        isBusyOrConnected -> "Disconnect"
        hasDevice -> "Connect"
        else -> "Find iPhone"
    }

    val buttonAction = when {
        isBusyOrConnected -> onDisconnectClick
        hasDevice -> onReconnectClick
        else -> onFindIphoneClick
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WearSyncColors.Background)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_apple),
            contentDescription = "Apple iPhone",
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = displayTitle,
            color = WearSyncColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = statusLabel,
            color = statusColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )

        if (!statusHint.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusHint,
                color = WearSyncColors.TextSecondary,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .widthIn(min = 122.dp)
                .height(42.dp)
                .clip(CircleShape)
                .background(WearSyncColors.PillSurface)
                .clickable(onClick = buttonAction)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = buttonLabel,
                color = WearSyncColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        if (hasDevice && !isBusyOrConnected) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Forget",
                color = WearSyncColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onForgetClick)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}
