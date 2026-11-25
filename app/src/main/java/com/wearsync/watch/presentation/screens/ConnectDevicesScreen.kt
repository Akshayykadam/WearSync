package com.wearsync.watch.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.wearsync.watch.ancs.DeviceUiItem
import com.wearsync.watch.presentation.theme.WearSyncColors

@Composable
fun ConnectDevicesScreen(
    devices: List<DeviceUiItem>,
    isScanning: Boolean,
    onDeviceClick: (DeviceUiItem) -> Unit,
    onForgetDevice: (DeviceUiItem) -> Unit,
    onScanClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WearSyncColors.Background)
            .padding(top = 20.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Connect Devices",
            color = WearSyncColors.TextHeader,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
        ) {
            if (devices.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(WearSyncColors.CardSurface)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isScanning) "Searching for iPhone…" else "No iPhones found yet",
                            color = WearSyncColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap Scan or pair from iPhone Bluetooth settings.",
                            color = WearSyncColors.TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(devices, key = { it.address }) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { onDeviceClick(device) },
                        onForgetClick = { onForgetDevice(device) }
                    )
                }
            }

            item(key = "scan_button") {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(WearSyncColors.PillSurfaceSecondary)
                        .clickable(onClick = onScanClick)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isScanning) "Scanning…" else "Scan iPhone",
                        color = WearSyncColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceUiItem,
    onClick: () -> Unit,
    onForgetClick: () -> Unit
) {
    val isConnected = device.status == ConnectionStatus.CONNECTED
    val isConnecting = device.status == ConnectionStatus.CONNECTING ||
        device.status == ConnectionStatus.DISCOVERING_SERVICES ||
        device.status == ConnectionStatus.PAIRING

    val statusText = when {
        isConnected -> "Connected"
        device.status == ConnectionStatus.PAIRING -> "Pairing…"
        isConnecting -> "Connecting…"
        device.isBonded -> "Disconnected"
        else -> "Nearby"
    }

    val statusColor = when {
        isConnected -> WearSyncColors.StatusConnected
        isConnecting -> WearSyncColors.StatusConnecting
        device.isBonded -> WearSyncColors.StatusDisconnected
        else -> WearSyncColors.TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WearSyncColors.CardSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = device.name,
                color = WearSyncColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 12.sp,
                maxLines = 1
            )
        }

        if (isConnected) {
            Icon(
                painter = painterResource(id = R.drawable.ic_apple),
                contentDescription = "Connected",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        } else if (device.isBonded) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(WearSyncColors.PillSurfaceSecondary)
                    .clickable(onClick = onForgetClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = "Delete pairing",
                    tint = WearSyncColors.TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
