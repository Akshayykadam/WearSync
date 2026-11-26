package com.wearsync.watch.ams

/**
 * Stateless parser and command builder for Apple Media Service (AMS) BLE packets.
 */
class AmsParser {

    fun parseRemoteCommands(data: ByteArray): Set<AmsRemoteCommand> {
        if (data.isEmpty()) return emptySet()
        return buildSet {
            for (b in data) {
                AmsRemoteCommand.fromId(b)?.let(::add)
            }
        }
    }

    fun parseEntityUpdate(data: ByteArray): EntityUpdatePacket? {
        if (data.size < 3) return null
        val entityId = data[0]
        val attributeId = data[1]
        val flags = data[2].toInt() and 0xFF
        val isTruncated = (flags and AmsConstants.ENTITY_UPDATE_FLAG_TRUNCATED) != 0
        val value = if (data.size > 3) {
            data.copyOfRange(3, data.size).toString(Charsets.UTF_8)
        } else {
            ""
        }
        return EntityUpdatePacket(
            entityId = entityId,
            attributeId = attributeId,
            isTruncated = isTruncated,
            value = value
        )
    }

    fun applyEntityUpdate(
        current: MediaPlaybackState,
        entityId: Byte,
        attributeId: Byte,
        value: String,
        nowMs: Long = System.currentTimeMillis()
    ): MediaPlaybackState {
        return when (entityId) {
            AmsConstants.ENTITY_PLAYER -> when (attributeId) {
                AmsConstants.PLAYER_ATTR_NAME -> current.copy(
                    isAvailable = true,
                    playerName = value.trim(),
                    lastUpdatedMs = nowMs
                )

                AmsConstants.PLAYER_ATTR_PLAYBACK_INFO -> {
                    val parts = value.split(",")
                    val stateCode = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val playbackState = when (stateCode) {
                        0 -> AmsPlaybackState.PAUSED
                        1 -> AmsPlaybackState.PLAYING
                        2 -> AmsPlaybackState.REWINDING
                        3 -> AmsPlaybackState.FAST_FORWARDING
                        else -> AmsPlaybackState.UNKNOWN
                    }
                    val rate = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: current.playbackRate
                    val elapsed = parts.getOrNull(2)?.trim()?.toFloatOrNull() ?: current.elapsedSeconds
                    current.copy(
                        isAvailable = true,
                        playbackState = playbackState,
                        playbackRate = rate,
                        elapsedSeconds = elapsed.coerceAtLeast(0f),
                        lastUpdatedMs = nowMs
                    )
                }

                AmsConstants.PLAYER_ATTR_VOLUME -> {
                    val vol = value.trim().toFloatOrNull()?.coerceIn(0f, 1f)
                    current.copy(
                        isAvailable = true,
                        volume = vol ?: current.volume,
                        lastUpdatedMs = nowMs
                    )
                }

                else -> current
            }

            AmsConstants.ENTITY_QUEUE -> when (attributeId) {
                AmsConstants.QUEUE_ATTR_SHUFFLE_MODE -> {
                    val mode = when (value.trim().toIntOrNull()) {
                        0 -> AmsShuffleMode.OFF
                        1 -> AmsShuffleMode.ONE
                        2 -> AmsShuffleMode.ALL
                        else -> AmsShuffleMode.UNKNOWN
                    }
                    current.copy(
                        isAvailable = true,
                        shuffleMode = mode,
                        lastUpdatedMs = nowMs
                    )
                }

                AmsConstants.QUEUE_ATTR_REPEAT_MODE -> {
                    val mode = when (value.trim().toIntOrNull()) {
                        0 -> AmsRepeatMode.OFF
                        1 -> AmsRepeatMode.ONE
                        2 -> AmsRepeatMode.ALL
                        else -> AmsRepeatMode.UNKNOWN
                    }
                    current.copy(
                        isAvailable = true,
                        repeatMode = mode,
                        lastUpdatedMs = nowMs
                    )
                }

                else -> current
            }

            AmsConstants.ENTITY_TRACK -> when (attributeId) {
                AmsConstants.TRACK_ATTR_ARTIST -> current.copy(
                    isAvailable = true,
                    artist = value.trim(),
                    lastUpdatedMs = nowMs
                )

                AmsConstants.TRACK_ATTR_ALBUM -> current.copy(
                    isAvailable = true,
                    album = value.trim(),
                    lastUpdatedMs = nowMs
                )

                AmsConstants.TRACK_ATTR_TITLE -> current.copy(
                    isAvailable = true,
                    title = value.trim(),
                    lastUpdatedMs = nowMs
                )

                AmsConstants.TRACK_ATTR_DURATION -> {
                    val duration = value.trim().toFloatOrNull()?.coerceAtLeast(0f) ?: current.durationSeconds
                    current.copy(
                        isAvailable = true,
                        durationSeconds = duration,
                        lastUpdatedMs = nowMs
                    )
                }

                else -> current
            }

            else -> current
        }
    }

    fun buildRegisterEntityUpdateCommand(entityId: Byte, vararg attributeIds: Byte): ByteArray {
        return ByteArray(1 + attributeIds.size).apply {
            this[0] = entityId
            attributeIds.forEachIndexed { index, attr ->
                this[index + 1] = attr
            }
        }
    }

    fun buildEntityAttributeReadCommand(entityId: Byte, attributeId: Byte): ByteArray {
        return byteArrayOf(entityId, attributeId)
    }
}
