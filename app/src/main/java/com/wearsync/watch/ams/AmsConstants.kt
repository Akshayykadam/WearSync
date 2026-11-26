package com.wearsync.watch.ams

import java.util.UUID

/**
 * Apple Media Service (AMS) BLE GATT UUIDs and protocol constants.
 * Exposed natively by iOS alongside ANCS over an encrypted BLE link.
 */
object AmsConstants {
    val AMS_SERVICE_UUID: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
    val REMOTE_COMMAND_UUID: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
    val ENTITY_UPDATE_UUID: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
    val ENTITY_ATTRIBUTE_UUID: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")

    // Entity IDs
    const val ENTITY_PLAYER: Byte = 0x00
    const val ENTITY_QUEUE: Byte = 0x01
    const val ENTITY_TRACK: Byte = 0x02

    // Player Attribute IDs
    const val PLAYER_ATTR_NAME: Byte = 0x00
    const val PLAYER_ATTR_PLAYBACK_INFO: Byte = 0x01
    const val PLAYER_ATTR_VOLUME: Byte = 0x02

    // Queue Attribute IDs
    const val QUEUE_ATTR_INDEX: Byte = 0x00
    const val QUEUE_ATTR_COUNT: Byte = 0x01
    const val QUEUE_ATTR_SHUFFLE_MODE: Byte = 0x02
    const val QUEUE_ATTR_REPEAT_MODE: Byte = 0x03

    // Track Attribute IDs
    const val TRACK_ATTR_ARTIST: Byte = 0x00
    const val TRACK_ATTR_ALBUM: Byte = 0x01
    const val TRACK_ATTR_TITLE: Byte = 0x02
    const val TRACK_ATTR_DURATION: Byte = 0x03

    // Entity Update Flags
    const val ENTITY_UPDATE_FLAG_TRUNCATED: Int = 1 shl 0
}

enum class AmsRemoteCommand(val id: Byte) {
    PLAY(0x00),
    PAUSE(0x01),
    TOGGLE_PLAY_PAUSE(0x02),
    NEXT_TRACK(0x03),
    PREVIOUS_TRACK(0x04),
    VOLUME_UP(0x05),
    VOLUME_DOWN(0x06),
    ADVANCE_REPEAT_MODE(0x07),
    ADVANCE_SHUFFLE_MODE(0x08),
    SKIP_FORWARD(0x09),
    SKIP_BACKWARD(0x0A),
    LIKE_TRACK(0x0B),
    DISLIKE_TRACK(0x0C),
    BOOKMARK_TRACK(0x0D);

    companion object {
        fun fromId(id: Byte): AmsRemoteCommand? = entries.firstOrNull { it.id == id }
    }
}
