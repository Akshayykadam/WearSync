package com.wearsync.watch.tile

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.wearsync.watch.R
import com.wearsync.watch.ams.AmsRemoteCommand
import com.wearsync.watch.ams.MediaPlaybackState
import com.wearsync.watch.ancs.ConnectionStatus
import com.wearsync.watch.presentation.MainActivity
import com.wearsync.watch.service.WearSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MusicControlTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val TAG = "MusicControlTile"
        private const val RESOURCES_VERSION = "1"

        private const val ID_PREV = "cmd_prev"
        private const val ID_PLAY_PAUSE = "cmd_play_pause"
        private const val ID_NEXT = "cmd_next"
        private const val ID_VOL_DOWN = "cmd_vol_down"
        private const val ID_VOL_UP = "cmd_vol_up"
        private const val ID_OPEN_APP = "open_app"

        private const val RES_PLAY = "ic_play"
        private const val RES_PAUSE = "ic_pause"
        private const val RES_PREV = "ic_prev"
        private const val RES_NEXT = "ic_next"
        private const val RES_MUSIC = "ic_music"

        private const val COLOR_BG = 0xFF000000.toInt()
        private const val COLOR_CARD = 0xFF1C1C1E.toInt()
        private const val COLOR_PILL = 0xFF3A3A3C.toInt()
        private const val COLOR_PILL_SEC = 0xFF2C2C2E.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF9E9EA4.toInt()
        private const val COLOR_GREEN = 0xFF5CE06F.toInt()

        fun requestUpdate(context: Context) {
            try {
                getUpdater(context.applicationContext)
                    .requestUpdate(MusicControlTileService::class.java)
            } catch (e: Exception) {
                Log.d(TAG, "Unable to request tile update: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> {
        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val lastId = requestParams.currentState.lastClickableId
                    handleTileAction(lastId)

                    val bleManager = WearSyncService.activeInstance?.bleManager
                    val isConnected = bleManager?.connectionState?.value == ConnectionStatus.CONNECTED
                    val mediaState = bleManager?.mediaState?.value ?: MediaPlaybackState()

                    val layout = if (isConnected) {
                        buildConnectedLayout(mediaState)
                    } else {
                        buildDisconnectedLayout()
                    }

                    val tile = Tile.Builder()
                        .setResourcesVersion(RESOURCES_VERSION)
                        .setTileTimeline(
                            Timeline.Builder()
                                .addTimelineEntry(
                                    TimelineEntry.Builder()
                                        .setLayout(Layout.Builder().setRoot(layout).build())
                                        .build()
                                )
                                .build()
                        )
                        .build()

                    completer.set(tile)
                } catch (e: Exception) {
                    completer.setException(e)
                }
            }
            "MusicControlTileRequest"
        }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return CallbackToFutureAdapter.getFuture { completer ->
            val resources = ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(
                    RES_PLAY,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_play)
                                .build()
                        )
                        .build()
                )
                .addIdToImageMapping(
                    RES_PAUSE,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_pause)
                                .build()
                        )
                        .build()
                )
                .addIdToImageMapping(
                    RES_PREV,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_skip_previous)
                                .build()
                        )
                        .build()
                )
                .addIdToImageMapping(
                    RES_NEXT,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_skip_next)
                                .build()
                        )
                        .build()
                )
                .addIdToImageMapping(
                    RES_MUSIC,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_music_note)
                                .build()
                        )
                        .build()
                )
                .build()
            completer.set(resources)
            "MusicControlTileResources"
        }
    }

    private suspend fun handleTileAction(clickableId: String?) {
        if (clickableId.isNullOrBlank()) return
        val bleManager = WearSyncService.activeInstance?.bleManager ?: return
        if (bleManager.connectionState.value != ConnectionStatus.CONNECTED) return

        val media = bleManager.mediaState.value
        val supported = media.supportedCommands
        val command = when (clickableId) {
            ID_PREV -> AmsRemoteCommand.PREVIOUS_TRACK
            ID_NEXT -> AmsRemoteCommand.NEXT_TRACK
            ID_VOL_DOWN -> AmsRemoteCommand.VOLUME_DOWN
            ID_VOL_UP -> AmsRemoteCommand.VOLUME_UP
            ID_PLAY_PAUSE -> when {
                AmsRemoteCommand.TOGGLE_PLAY_PAUSE in supported -> AmsRemoteCommand.TOGGLE_PLAY_PAUSE
                media.isPlaying && AmsRemoteCommand.PAUSE in supported -> AmsRemoteCommand.PAUSE
                !media.isPlaying && AmsRemoteCommand.PLAY in supported -> AmsRemoteCommand.PLAY
                else -> AmsRemoteCommand.TOGGLE_PLAY_PAUSE
            }
            else -> null
        } ?: return

        bleManager.sendMediaCommand(command)
        delay(120L)
    }

    private fun buildDisconnectedLayout(): LayoutElementBuilders.LayoutElement {
        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(Background.Builder().setColor(argb(COLOR_BG)).build())
                    .build()
            )
            .addContent(
                Column.Builder()
                    .setWidth(wrap())
                    .setHeight(wrap())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        Image.Builder()
                            .setResourceId(RES_MUSIC)
                            .setWidth(dp(24f))
                            .setHeight(dp(24f))
                            .setColorFilter(
                                LayoutElementBuilders.ColorFilter.Builder()
                                    .setTint(argb(COLOR_TEXT_SECONDARY))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(Spacer.Builder().setHeight(dp(8f)).build())
                    .addContent(
                        Text.Builder()
                            .setText("Music Control")
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(15f))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                                    .setColor(argb(COLOR_TEXT_PRIMARY))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(Spacer.Builder().setHeight(dp(3f)).build())
                    .addContent(
                        Text.Builder()
                            .setText("Connect iPhone")
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(12f))
                                    .setColor(argb(COLOR_TEXT_SECONDARY))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(Spacer.Builder().setHeight(dp(14f)).build())
                    .addContent(
                        Box.Builder()
                            .setWidth(wrap())
                            .setHeight(dp(38f))
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .setModifiers(
                                Modifiers.Builder()
                                    .setBackground(
                                        Background.Builder()
                                            .setColor(argb(COLOR_PILL))
                                            .setCorner(Corner.Builder().setRadius(dp(19f)).build())
                                            .build()
                                    )
                                    .setPadding(
                                        Padding.Builder()
                                            .setStart(dp(20f))
                                            .setEnd(dp(20f))
                                            .build()
                                    )
                                    .setClickable(openAppClickable())
                                    .build()
                            )
                            .addContent(
                                Text.Builder()
                                    .setText("Open WearSync")
                                    .setFontStyle(
                                        FontStyle.Builder()
                                            .setSize(sp(13f))
                                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                            .setColor(argb(COLOR_TEXT_PRIMARY))
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildConnectedLayout(mediaState: MediaPlaybackState): LayoutElementBuilders.LayoutElement {
        val playerLabel = mediaState.playerName.ifBlank { "iPhone" }
        val titleLabel = mediaState.title.ifBlank { "Nothing Playing" }
        val artistLabel = mediaState.artist
        val volumeLabel = mediaState.volume?.let { "${(it * 100).roundToInt()}%" } ?: "Vol"

        val columnBuilder = Column.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                Text.Builder()
                    .setText(playerLabel)
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(11f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                            .setColor(argb(if (mediaState.isPlaying) COLOR_GREEN else COLOR_TEXT_SECONDARY))
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(
                Box.Builder()
                    .setWidth(wrap())
                    .setModifiers(
                        Modifiers.Builder()
                            .setPadding(Padding.Builder().setStart(dp(22f)).setEnd(dp(22f)).build())
                            .setClickable(openAppClickable())
                            .build()
                    )
                    .addContent(
                        Text.Builder()
                            .setText(titleLabel)
                            .setMaxLines(1)
                            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(15f))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                                    .setColor(argb(COLOR_TEXT_PRIMARY))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )

        if (artistLabel.isNotBlank()) {
            columnBuilder
                .addContent(Spacer.Builder().setHeight(dp(2f)).build())
                .addContent(
                    Box.Builder()
                        .setWidth(wrap())
                        .setModifiers(
                            Modifiers.Builder()
                                .setPadding(Padding.Builder().setStart(dp(26f)).setEnd(dp(26f)).build())
                                .build()
                        )
                        .addContent(
                            Text.Builder()
                                .setText(artistLabel)
                                .setMaxLines(1)
                                .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                                .setFontStyle(
                                    FontStyle.Builder()
                                        .setSize(sp(11f))
                                        .setColor(argb(COLOR_TEXT_SECONDARY))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
        }

        columnBuilder
            .addContent(Spacer.Builder().setHeight(dp(12f)).build())
            .addContent(
                Row.Builder()
                    .setWidth(wrap())
                    .setHeight(wrap())
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(
                        circleIconButton(
                            id = ID_PREV,
                            resId = RES_PREV,
                            sizeDp = 36f,
                            iconDp = 18f,
                            bgColor = COLOR_PILL_SEC
                        )
                    )
                    .addContent(Spacer.Builder().setWidth(dp(14f)).build())
                    .addContent(
                        circleIconButton(
                            id = ID_PLAY_PAUSE,
                            resId = if (mediaState.isPlaying) RES_PAUSE else RES_PLAY,
                            sizeDp = 46f,
                            iconDp = 22f,
                            bgColor = COLOR_PILL
                        )
                    )
                    .addContent(Spacer.Builder().setWidth(dp(14f)).build())
                    .addContent(
                        circleIconButton(
                            id = ID_NEXT,
                            resId = RES_NEXT,
                            sizeDp = 36f,
                            iconDp = 18f,
                            bgColor = COLOR_PILL_SEC
                        )
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(
                Row.Builder()
                    .setWidth(wrap())
                    .setHeight(wrap())
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(circleTextButton(id = ID_VOL_DOWN, label = "−"))
                    .addContent(Spacer.Builder().setWidth(dp(10f)).build())
                    .addContent(
                        Text.Builder()
                            .setText(volumeLabel)
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(11f))
                                    .setColor(argb(COLOR_TEXT_SECONDARY))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(Spacer.Builder().setWidth(dp(10f)).build())
                    .addContent(circleTextButton(id = ID_VOL_UP, label = "+"))
                    .build()
            )

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(Background.Builder().setColor(argb(COLOR_BG)).build())
                    .build()
            )
            .addContent(columnBuilder.build())
            .build()
    }

    private fun circleIconButton(
        id: String,
        resId: String,
        sizeDp: Float,
        iconDp: Float,
        bgColor: Int
    ): LayoutElementBuilders.LayoutElement {
        return Box.Builder()
            .setWidth(dp(sizeDp))
            .setHeight(dp(sizeDp))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(Corner.Builder().setRadius(dp(sizeDp / 2f)).build())
                            .build()
                    )
                    .setClickable(loadActionClickable(id))
                    .build()
            )
            .addContent(
                Image.Builder()
                    .setResourceId(resId)
                    .setWidth(dp(iconDp))
                    .setHeight(dp(iconDp))
                    .setColorFilter(
                        LayoutElementBuilders.ColorFilter.Builder()
                            .setTint(argb(COLOR_TEXT_PRIMARY))
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun circleTextButton(id: String, label: String): LayoutElementBuilders.LayoutElement {
        return Box.Builder()
            .setWidth(dp(32f))
            .setHeight(dp(32f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(COLOR_CARD))
                            .setCorner(Corner.Builder().setRadius(dp(16f)).build())
                            .build()
                    )
                    .setClickable(loadActionClickable(id))
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(15f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                            .setColor(argb(COLOR_TEXT_PRIMARY))
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun loadActionClickable(id: String): Clickable {
        return Clickable.Builder()
            .setId(id)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()
    }

    private fun openAppClickable(): Clickable {
        return Clickable.Builder()
            .setId(ID_OPEN_APP)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
