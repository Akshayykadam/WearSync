package com.wearsync.watch.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.wearsync.watch.R
import com.wearsync.watch.ancs.AncsCategoryId
import com.wearsync.watch.ancs.AncsConstants
import com.wearsync.watch.ancs.AncsNotification
import com.wearsync.watch.notifications.AppIconFactory
import com.wearsync.watch.presentation.theme.WearSyncColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared individual per-app notification card used by both the incoming alert popup
 * and the in-app Recent Alerts pager.
 */
@Composable
fun NotificationAlertCard(
    notification: AncsNotification,
    headerLabel: String? = null,
    showDismissButton: Boolean = false,
    topPadding: androidx.compose.ui.unit.Dp = 18.dp,
    bottomPadding: androidx.compose.ui.unit.Dp = 18.dp,
    onCardClick: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onPositiveAction: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val appLabel = notification.appDisplayName.ifBlank {
        AncsConstants.friendlyAppName(notification.appIdentifier, categoryId = notification.categoryId)
    }

    val iconBitmap = remember(notification.appIdentifier, appLabel, notification.categoryId) {
        AppIconFactory.createAppIcon(
            context = context,
            appIdentifier = notification.appIdentifier,
            appDisplayName = appLabel,
            categoryId = notification.categoryId
        ).asImageBitmap()
    }

    val timeText = remember(notification.timestamp) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(notification.timestamp))
    }

    val title = notification.title.ifBlank { appLabel }
    val body = buildString {
        if (notification.subtitle.isNotBlank()) {
            append(notification.subtitle)
            if (notification.message.isNotBlank()) append(" • ")
        }
        append(notification.message)
    }.ifBlank { appLabel }

    val baseModifier = Modifier
        .fillMaxSize()
        .background(WearSyncColors.Background)
        .let { mod ->
            if (onCardClick != null) mod.clickable(onClick = onCardClick) else mod
        }
        .padding(start = 24.dp, end = 24.dp, top = topPadding, bottom = bottomPadding)

    val iconBoxSize = if (showDismissButton) 36.dp else 48.dp
    val iconSize = if (showDismissButton) 34.dp else 44.dp
    val badgeSize = if (showDismissButton) 14.dp else 18.dp

    Column(
        modifier = baseModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!headerLabel.isNullOrBlank()) {
            Text(
                text = headerLabel,
                color = WearSyncColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

        // Circular App Icon + Small Grey iPhone Badge
        Box(
            modifier = Modifier.size(iconBoxSize),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = iconBitmap,
                contentDescription = appLabel,
                modifier = Modifier
                    .size(iconSize)
                    .clip(CircleShape)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(Color(0xFF8E8E93)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_iphone_badge),
                    contentDescription = "iPhone",
                    tint = Color.White,
                    modifier = Modifier.size(if (showDismissButton) 9.dp else 11.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(if (showDismissButton) 2.dp else 6.dp))

        Text(
            text = title,
            color = WearSyncColors.TextPrimary,
            fontSize = if (showDismissButton) 14.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Text(
            text = timeText,
            color = WearSyncColors.TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(if (showDismissButton) 3.dp else 6.dp))

        Text(
            text = body,
            color = Color(0xFFF2F2F7),
            fontSize = if (showDismissButton) 12.sp else 14.sp,
            lineHeight = if (showDismissButton) 15.sp else 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        if (notification.categoryId == AncsCategoryId.INCOMING_CALL) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Decline",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34C759))
                        .clickable { onPositiveAction?.invoke() }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Answer",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else if (showDismissButton) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(CircleShape)
                        .background(WearSyncColors.PillSurfaceSecondary)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Dismiss",
                        color = WearSyncColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (notification.eventFlags.hasPositiveAction && onPositiveAction != null) {
                    Box(
                        modifier = Modifier
                            .height(26.dp)
                            .clip(CircleShape)
                            .background(WearSyncColors.PillSurface)
                            .clickable(onClick = onPositiveAction)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = notification.positiveActionLabel.ifBlank { "Open" },
                            color = WearSyncColors.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
