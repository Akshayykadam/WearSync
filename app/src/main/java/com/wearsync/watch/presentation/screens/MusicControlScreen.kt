package com.wearsync.watch.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.wearsync.watch.R
import com.wearsync.watch.ams.AmsRemoteCommand
import com.wearsync.watch.ams.MediaPlaybackState
import com.wearsync.watch.presentation.theme.WearSyncColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun MusicControlScreen(
    isConnected: Boolean,
    mediaState: MediaPlaybackState,
    onCommand: (AmsRemoteCommand) -> Unit,
    onConnectClick: () -> Unit
) {
    if (!isConnected) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WearSyncColors.Background)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_music_note),
                contentDescription = "Music",
                tint = WearSyncColors.TextSecondary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Music",
                color = WearSyncColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "Connect iPhone to control",
                color = WearSyncColors.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .widthIn(min = 122.dp)
                    .height(42.dp)
                    .clip(CircleShape)
                    .background(WearSyncColors.PillSurface)
                    .clickable(onClick = onConnectClick)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Devices",
                    color = WearSyncColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        return
    }

    var liveElapsed by remember(
        mediaState.elapsedSeconds,
        mediaState.lastUpdatedMs,
        mediaState.isPlaying
    ) {
        mutableFloatStateOf(mediaState.elapsedSeconds)
    }

    LaunchedEffect(mediaState.isPlaying, mediaState.elapsedSeconds, mediaState.lastUpdatedMs) {
        if (mediaState.isPlaying && mediaState.durationSeconds > 0f) {
            while (true) {
                val deltaSec = (System.currentTimeMillis() - mediaState.lastUpdatedMs) / 1000f
                val rate = if (mediaState.playbackRate > 0f) mediaState.playbackRate else 1f
                liveElapsed = (mediaState.elapsedSeconds + deltaSec * rate)
                    .coerceIn(0f, mediaState.durationSeconds)
                delay(1_000L)
            }
        }
    }

    val supported = mediaState.supportedCommands
    val hasCommandList = supported.isNotEmpty()
    val canPrev = !hasCommandList || AmsRemoteCommand.PREVIOUS_TRACK in supported
    val canNext = !hasCommandList || AmsRemoteCommand.NEXT_TRACK in supported
    val canVolDown = !hasCommandList || AmsRemoteCommand.VOLUME_DOWN in supported
    val canVolUp = !hasCommandList || AmsRemoteCommand.VOLUME_UP in supported

    val playPauseCmd = when {
        AmsRemoteCommand.TOGGLE_PLAY_PAUSE in supported -> AmsRemoteCommand.TOGGLE_PLAY_PAUSE
        mediaState.isPlaying && AmsRemoteCommand.PAUSE in supported -> AmsRemoteCommand.PAUSE
        !mediaState.isPlaying && AmsRemoteCommand.PLAY in supported -> AmsRemoteCommand.PLAY
        else -> AmsRemoteCommand.TOGGLE_PLAY_PAUSE
    }

    val sourceLabel = mediaState.playerName.ifBlank { "iPhone" }
    val titleLabel = mediaState.title.ifBlank { "Nothing Playing" }
    val artistLabel = mediaState.artist

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WearSyncColors.Background)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Subtle player name
        Text(
            text = sourceLabel,
            color = if (mediaState.isPlaying) WearSyncColors.StatusConnected else WearSyncColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Track title
        Text(
            text = titleLabel,
            color = WearSyncColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.86f)
        )

        if (artistLabel.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = artistLabel,
                color = WearSyncColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.80f)
            )
        }

        // Hairline progress bar
        if (mediaState.durationSeconds > 0f) {
            val progress = (liveElapsed / mediaState.durationSeconds).coerceIn(0f, 1f)
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.46f)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(WearSyncColors.PillSurfaceSecondary)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(WearSyncColors.TextPrimary)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Clean 3-button transport row matching WearSync pill surfaces
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MinimalCircleButton(
                size = 36.dp,
                iconSize = 18.dp,
                iconRes = R.drawable.ic_skip_previous,
                contentDescription = "Previous",
                backgroundColor = WearSyncColors.PillSurfaceSecondary,
                iconTint = WearSyncColors.TextPrimary,
                enabled = canPrev,
                onClick = { onCommand(AmsRemoteCommand.PREVIOUS_TRACK) }
            )

            MinimalCircleButton(
                size = 46.dp,
                iconSize = 22.dp,
                iconRes = if (mediaState.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                contentDescription = if (mediaState.isPlaying) "Pause" else "Play",
                backgroundColor = WearSyncColors.PillSurface,
                iconTint = WearSyncColors.TextPrimary,
                enabled = true,
                onClick = { onCommand(playPauseCmd) }
            )

            MinimalCircleButton(
                size = 36.dp,
                iconSize = 18.dp,
                iconRes = R.drawable.ic_skip_next,
                contentDescription = "Next",
                backgroundColor = WearSyncColors.PillSurfaceSecondary,
                iconTint = WearSyncColors.TextPrimary,
                enabled = canNext,
                onClick = { onCommand(AmsRemoteCommand.NEXT_TRACK) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Minimal rounded volume row: (-)  72%  (+)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MinimalTextCircleButton(
                size = 32.dp,
                label = "−",
                backgroundColor = WearSyncColors.CardSurface,
                textColor = WearSyncColors.TextPrimary,
                enabled = canVolDown,
                onClick = { onCommand(AmsRemoteCommand.VOLUME_DOWN) }
            )

            Text(
                text = mediaState.volume?.let { "${(it * 100).roundToInt()}%" } ?: "Vol",
                color = WearSyncColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 28.dp)
            )

            MinimalTextCircleButton(
                size = 32.dp,
                label = "+",
                backgroundColor = WearSyncColors.CardSurface,
                textColor = WearSyncColors.TextPrimary,
                enabled = canVolUp,
                onClick = { onCommand(AmsRemoteCommand.VOLUME_UP) }
            )
        }
    }
}

@Composable
private fun MinimalTextCircleButton(
    size: Dp,
    label: String,
    backgroundColor: Color,
    textColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MinimalCircleButton(
    size: Dp,
    iconRes: Int,
    contentDescription: String,
    backgroundColor: Color,
    iconTint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    iconSize: Dp = 18.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
    }
}
