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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.wearsync.watch.ancs.AncsNotification
import com.wearsync.watch.data.SyncSettings
import com.wearsync.watch.presentation.theme.WearSyncColors

@Composable
fun NotificationsSettingsScreen(
    settings: SyncSettings,
    recentNotifications: List<AncsNotification>,
    onUpdateSettings: (SyncSettings) -> Unit,
    onSendTestAlert: () -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WearSyncColors.Background)
            .padding(top = 20.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Notifications",
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
            item(key = "test_btn") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(WearSyncColors.PillSurface)
                        .clickable(onClick = onSendTestAlert),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Test Watch Alert",
                        color = WearSyncColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item(key = "toggle_popup") {
                SettingToggleCard(
                    title = "Alert Popups",
                    subtitle = "Individual card per alert",
                    enabled = settings.popupAlertsEnabled,
                    onToggle = { onUpdateSettings(settings.copy(popupAlertsEnabled = !settings.popupAlertsEnabled)) }
                )
            }

            item(key = "toggle_single_drawer") {
                SettingToggleCard(
                    title = "Single Drawer Card",
                    subtitle = "Keep latest unstacked in drawer",
                    enabled = settings.singleDrawerCard,
                    onToggle = { onUpdateSettings(settings.copy(singleDrawerCard = !settings.singleDrawerCard)) }
                )
            }

            item(key = "toggle_vibrate") {
                SettingToggleCard(
                    title = "Vibration",
                    subtitle = "Vibrate on iPhone alerts",
                    enabled = settings.vibrateEnabled,
                    onToggle = { onUpdateSettings(settings.copy(vibrateEnabled = !settings.vibrateEnabled)) }
                )
            }

            item(key = "toggle_discoverable") {
                SettingToggleCard(
                    title = "Discoverable",
                    subtitle = "Visible in iPhone Bluetooth",
                    enabled = settings.discoverableEnabled,
                    onToggle = { onUpdateSettings(settings.copy(discoverableEnabled = !settings.discoverableEnabled)) }
                )
            }

            item(key = "toggle_pre_existing") {
                SettingToggleCard(
                    title = "Sync Existing",
                    subtitle = "Pull old alerts on connect",
                    enabled = settings.syncPreExisting,
                    onToggle = { onUpdateSettings(settings.copy(syncPreExisting = !settings.syncPreExisting)) }
                )
            }

            item(key = "toggle_dismiss_sync") {
                SettingToggleCard(
                    title = "Dismiss Sync",
                    subtitle = "Clear on iPhone when dismissed",
                    enabled = settings.syncDismissToIphone,
                    onToggle = { onUpdateSettings(settings.copy(syncDismissToIphone = !settings.syncDismissToIphone)) }
                )
            }

            if (recentNotifications.isNotEmpty()) {
                item(key = "recent_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent (${recentNotifications.size})",
                            color = WearSyncColors.TextSecondary,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Clear",
                            color = WearSyncColors.StatusDisconnected,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(onClick = onClearAll)
                        )
                    }
                }

                items(recentNotifications.take(5), key = { it.uid }) { notif ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(WearSyncColors.CardSurface)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = notif.appDisplayName.ifBlank { "iPhone" },
                            color = WearSyncColors.StatusConnected,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = notif.title.ifBlank { "Notification" },
                            color = WearSyncColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (notif.message.isNotBlank()) {
                            Text(
                                text = notif.message,
                                color = WearSyncColors.TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WearSyncColors.CardSurface)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = title,
                color = WearSyncColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = WearSyncColors.TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = if (enabled) "ON" else "OFF",
            color = if (enabled) WearSyncColors.StatusConnected else WearSyncColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
