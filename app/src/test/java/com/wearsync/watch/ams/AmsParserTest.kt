package com.wearsync.watch.ams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmsParserTest {

    private val parser = AmsParser()

    @Test
    fun parseRemoteCommands_mapsSupportedIds() {
        val bytes = byteArrayOf(
            AmsRemoteCommand.TOGGLE_PLAY_PAUSE.id,
            AmsRemoteCommand.NEXT_TRACK.id,
            AmsRemoteCommand.PREVIOUS_TRACK.id,
            AmsRemoteCommand.VOLUME_UP.id,
            AmsRemoteCommand.VOLUME_DOWN.id,
            0x7F // unknown id should be ignored
        )

        val parsed = parser.parseRemoteCommands(bytes)

        assertEquals(
            setOf(
                AmsRemoteCommand.TOGGLE_PLAY_PAUSE,
                AmsRemoteCommand.NEXT_TRACK,
                AmsRemoteCommand.PREVIOUS_TRACK,
                AmsRemoteCommand.VOLUME_UP,
                AmsRemoteCommand.VOLUME_DOWN
            ),
            parsed
        )
    }

    @Test
    fun parseEntityUpdate_extractsHeaderFlagsAndUtf8Value() {
        val title = "Midnight City"
        val packetBytes = byteArrayOf(
            AmsConstants.ENTITY_TRACK,
            AmsConstants.TRACK_ATTR_TITLE,
            AmsConstants.ENTITY_UPDATE_FLAG_TRUNCATED.toByte()
        ) + title.toByteArray(Charsets.UTF_8)

        val packet = parser.parseEntityUpdate(packetBytes)

        assertNotNull(packet)
        assertEquals(AmsConstants.ENTITY_TRACK, packet!!.entityId)
        assertEquals(AmsConstants.TRACK_ATTR_TITLE, packet.attributeId)
        assertTrue(packet.isTruncated)
        assertEquals(title, packet.value)
    }

    @Test
    fun parseEntityUpdate_returnsNullForShortPacket() {
        assertNull(parser.parseEntityUpdate(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun applyEntityUpdate_updatesPlayerPlaybackInfoAndTrackMetadata() {
        var state = MediaPlaybackState()

        state = parser.applyEntityUpdate(
            state,
            AmsConstants.ENTITY_PLAYER,
            AmsConstants.PLAYER_ATTR_NAME,
            "Spotify",
            nowMs = 1000L
        )
        state = parser.applyEntityUpdate(
            state,
            AmsConstants.ENTITY_PLAYER,
            AmsConstants.PLAYER_ATTR_PLAYBACK_INFO,
            "1,1.0,42.5",
            nowMs = 2000L
        )
        state = parser.applyEntityUpdate(
            state,
            AmsConstants.ENTITY_PLAYER,
            AmsConstants.PLAYER_ATTR_VOLUME,
            "0.75",
            nowMs = 2500L
        )
        state = parser.applyEntityUpdate(
            state,
            AmsConstants.ENTITY_TRACK,
            AmsConstants.TRACK_ATTR_TITLE,
            "Starlight",
            nowMs = 3000L
        )
        state = parser.applyEntityUpdate(
            state,
            AmsConstants.ENTITY_TRACK,
            AmsConstants.TRACK_ATTR_ARTIST,
            "Muse",
            nowMs = 3100L
        )
        state = parser.applyEntityUpdate(
            state,
            AmsConstants.ENTITY_TRACK,
            AmsConstants.TRACK_ATTR_DURATION,
            "240.0",
            nowMs = 3200L
        )

        assertTrue(state.isAvailable)
        assertTrue(state.isPlaying)
        assertTrue(state.hasTrackInfo)
        assertEquals("Spotify", state.playerName)
        assertEquals(AmsPlaybackState.PLAYING, state.playbackState)
        assertEquals(1.0f, state.playbackRate, 0.001f)
        assertEquals(42.5f, state.elapsedSeconds, 0.001f)
        assertEquals(0.75f, state.volume!!, 0.001f)
        assertEquals("Starlight", state.title)
        assertEquals("Muse", state.artist)
        assertEquals(240.0f, state.durationSeconds, 0.001f)
        assertEquals(3200L, state.lastUpdatedMs)

        state = parser.applyEntityUpdate(
            state,
            AmsConstants.ENTITY_PLAYER,
            AmsConstants.PLAYER_ATTR_PLAYBACK_INFO,
            "0,0.0,45.0",
            nowMs = 4000L
        )
        assertFalse(state.isPlaying)
        assertEquals(AmsPlaybackState.PAUSED, state.playbackState)
    }

    @Test
    fun buildRegisterEntityUpdateCommand_formatsEntityAndAttributes() {
        val cmd = parser.buildRegisterEntityUpdateCommand(
            AmsConstants.ENTITY_TRACK,
            AmsConstants.TRACK_ATTR_ARTIST,
            AmsConstants.TRACK_ATTR_TITLE
        )
        assertEquals(
            listOf(
                AmsConstants.ENTITY_TRACK,
                AmsConstants.TRACK_ATTR_ARTIST,
                AmsConstants.TRACK_ATTR_TITLE
            ),
            cmd.toList()
        )
    }

    @Test
    fun applyEntityUpdate_updatesQueueShuffleAndRepeatModes() {
        var state = MediaPlaybackState()
        state = parser.applyEntityUpdate(
            state,
            AmsConstants.ENTITY_QUEUE,
            AmsConstants.QUEUE_ATTR_SHUFFLE_MODE,
            "2",
            nowMs = 5000L
        )
        state = parser.applyEntityUpdate(
            state,
            AmsConstants.ENTITY_QUEUE,
            AmsConstants.QUEUE_ATTR_REPEAT_MODE,
            "1",
            nowMs = 5100L
        )

        assertEquals(AmsShuffleMode.ALL, state.shuffleMode)
        assertEquals(AmsRepeatMode.ONE, state.repeatMode)
    }
}
