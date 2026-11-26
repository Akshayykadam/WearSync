package com.wearsync.watch.ams

enum class AmsPlaybackState {
    PAUSED,
    PLAYING,
    REWINDING,
    FAST_FORWARDING,
    UNKNOWN
}

enum class AmsShuffleMode {
    OFF,
    ONE,
    ALL,
    UNKNOWN
}

enum class AmsRepeatMode {
    OFF,
    ONE,
    ALL,
    UNKNOWN
}

data class MediaPlaybackState(
    val isAvailable: Boolean = false,
    val playerName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playbackState: AmsPlaybackState = AmsPlaybackState.UNKNOWN,
    val playbackRate: Float = 0f,
    val elapsedSeconds: Float = 0f,
    val durationSeconds: Float = 0f,
    val volume: Float? = null,
    val shuffleMode: AmsShuffleMode = AmsShuffleMode.UNKNOWN,
    val repeatMode: AmsRepeatMode = AmsRepeatMode.UNKNOWN,
    val supportedCommands: Set<AmsRemoteCommand> = emptySet(),
    val lastUpdatedMs: Long = 0L
) {
    val isPlaying: Boolean
        get() = playbackState == AmsPlaybackState.PLAYING

    val hasTrackInfo: Boolean
        get() = title.isNotBlank() || artist.isNotBlank()
}

data class EntityUpdatePacket(
    val entityId: Byte,
    val attributeId: Byte,
    val isTruncated: Boolean,
    val value: String
)
