package com.wearsync.watch.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.wearsync.watch.ancs.AncsConstants
import com.wearsync.watch.ancs.AncsNotification
import com.wearsync.watch.presentation.components.NotificationAlertCard
import com.wearsync.watch.presentation.theme.WearSyncColors

/**
 * Full-screen vertical pager displaying each mirrored iPhone notification
 * as its own individual per-app card (avoiding Wear OS drawer stacking).
 */
@Composable
fun RecentAlertsScreen(
    notifications: List<AncsNotification>,
    focusUid: Int? = null,
    onFocusConsumed: () -> Unit = {},
    onDismissNotification: (Int) -> Unit,
    onPositiveAction: (Int) -> Unit,
    onClearAll: () -> Unit,
    onSendTestAlert: () -> Unit
) {
    if (notifications.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WearSyncColors.Background)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Recent Alerts",
                color = WearSyncColors.TextHeader,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Individual per-app cards from your iPhone appear here.",
                color = WearSyncColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(WearSyncColors.PillSurface)
                    .clickable(onClick = onSendTestAlert)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Test Watch Alert",
                    color = WearSyncColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { notifications.size })

    LaunchedEffect(focusUid, notifications) {
        if (focusUid != null) {
            val targetIndex = notifications.indexOfFirst { it.uid == focusUid }
            if (targetIndex >= 0) {
                pagerState.scrollToPage(targetIndex)
            }
            onFocusConsumed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WearSyncColors.Background)
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { index ->
            val notif = notifications.getOrNull(index) ?: return@VerticalPager
            val appLabel = notif.appDisplayName.ifBlank {
                AncsConstants.friendlyAppName(notif.appIdentifier, categoryId = notif.categoryId)
            }
            val header = if (notifications.size > 1) {
                "${index + 1}/${notifications.size} • $appLabel"
            } else {
                appLabel
            }

            NotificationAlertCard(
                notification = notif,
                headerLabel = header,
                showDismissButton = true,
                topPadding = 22.dp,
                bottomPadding = 26.dp,
                onDismiss = { onDismissNotification(notif.uid) },
                onPositiveAction = { onPositiveAction(notif.uid) }
            )
        }

        if (notifications.size > 1) {
            Text(
                text = "Clear all",
                color = WearSyncColors.StatusDisconnected,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClearAll)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            )

            // Vertical card position dots on the right edge
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val visibleCount = notifications.size.coerceAtMost(6)
                repeat(visibleCount) { dotIdx ->
                    val active = pagerState.currentPage.coerceAtMost(visibleCount - 1) == dotIdx
                    Box(
                        modifier = Modifier
                            .size(if (active) 5.dp else 4.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) WearSyncColors.DotActive
                                else WearSyncColors.DotInactive
                            )
                    )
                }
            }
        }
    }
}
