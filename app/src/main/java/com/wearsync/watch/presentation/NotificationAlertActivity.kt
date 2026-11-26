package com.wearsync.watch.presentation

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.wearsync.watch.ancs.AncsCategoryId
import com.wearsync.watch.presentation.components.NotificationAlertCard
import com.wearsync.watch.presentation.screens.IncomingCallScreen
import com.wearsync.watch.presentation.theme.WearSyncColors
import com.wearsync.watch.presentation.theme.WearSyncTheme
import com.wearsync.watch.service.WearSyncService
import kotlinx.coroutines.delay

class NotificationAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContent {
            WearSyncTheme {
                AlertPopupHost(
                    onFinish = { finish() }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Dismiss stale popup when user navigates away or watch screen sleeps,
        // so an old alert never flashes back on next wake.
        if (!isChangingConfigurations) {
            WearSyncService.activeInstance?.clearPopupAlerts()
            finish()
        }
    }
}

@Composable
private fun AlertPopupHost(onFinish: () -> Unit) {
    val service = WearSyncService.activeInstance
    val alerts by (service?.popupAlerts?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })

    LaunchedEffect(alerts) {
        if (alerts.isEmpty()) {
            onFinish()
        }
    }

    // Auto-dismiss after 8 seconds for regular alerts (keep incoming calls open until answered/ended)
    val topAlert = alerts.firstOrNull()
    LaunchedEffect(topAlert?.uid) {
        if (topAlert != null && topAlert.categoryId != AncsCategoryId.INCOMING_CALL) {
            delay(8_000L)
            service?.clearPopupAlerts()
            onFinish()
        }
    }

    if (alerts.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { alerts.size })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WearSyncColors.Background)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { index ->
            val notif = alerts.getOrNull(index) ?: return@HorizontalPager
            if (notif.categoryId == AncsCategoryId.INCOMING_CALL) {
                IncomingCallScreen(
                    notification = notif,
                    onAccept = {
                        service?.performNotificationAction(notif.uid, true)
                        if (alerts.size <= 1) onFinish()
                    },
                    onDecline = {
                        service?.performNotificationAction(notif.uid, false)
                        if (alerts.size <= 1) onFinish()
                    }
                )
            } else {
                NotificationAlertCard(
                    notification = notif,
                    showDismissButton = false,
                    onCardClick = {
                        service?.hidePopupAlert(notif.uid)
                        if (alerts.size <= 1) onFinish()
                    },
                    onDismiss = {
                        service?.dismissPopupAlert(notif.uid)
                        if (alerts.size <= 1) onFinish()
                    },
                    onPositiveAction = {
                        service?.performNotificationAction(notif.uid, true)
                        if (alerts.size <= 1) onFinish()
                    }
                )
            }
        }

        if (alerts.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(alerts.size.coerceAtMost(5)) { idx ->
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == idx) WearSyncColors.DotActive
                                else WearSyncColors.DotInactive
                            )
                    )
                }
            }
        }
    }
}
