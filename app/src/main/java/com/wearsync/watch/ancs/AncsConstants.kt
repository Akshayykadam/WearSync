package com.wearsync.watch.ancs

import java.util.UUID

object AncsConstants {
    // Apple Notification Center Service (ANCS) UUIDs
    val ANCS_SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    // Standard Bluetooth SIG UUIDs
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    val GAP_SERVICE_UUID: UUID = UUID.fromString("00001800-0000-1000-8000-00805F9B34FB")
    val GAP_DEVICE_NAME_CHAR_UUID: UUID = UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB")
    val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    val MANUFACTURER_NAME_CHAR_UUID: UUID = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")
    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    // Apple Company Identifier in BLE Manufacturer Specific Data
    const val APPLE_MANUFACTURER_ID = 0x004C

    // ANCS Command IDs
    const val CMD_GET_NOTIFICATION_ATTRIBUTES: Byte = 0x00
    const val CMD_GET_APP_ATTRIBUTES: Byte = 0x01
    const val CMD_PERFORM_NOTIFICATION_ACTION: Byte = 0x02

    // ANCS Notification Attribute IDs
    const val NOTIF_ATTR_APP_IDENTIFIER: Byte = 0x00
    const val NOTIF_ATTR_TITLE: Byte = 0x01
    const val NOTIF_ATTR_SUBTITLE: Byte = 0x02
    const val NOTIF_ATTR_MESSAGE: Byte = 0x03
    const val NOTIF_ATTR_MESSAGE_SIZE: Byte = 0x04
    const val NOTIF_ATTR_DATE: Byte = 0x05
    const val NOTIF_ATTR_POSITIVE_ACTION_LABEL: Byte = 0x06
    const val NOTIF_ATTR_NEGATIVE_ACTION_LABEL: Byte = 0x07

    // ANCS App Attribute IDs
    const val APP_ATTR_DISPLAY_NAME: Byte = 0x00

    // ANCS Action IDs
    const val ACTION_POSITIVE: Byte = 0x00
    const val ACTION_NEGATIVE: Byte = 0x01

    // ANCS Event Flags
    const val EVENT_FLAG_SILENT = 1 shl 0
    const val EVENT_FLAG_IMPORTANT = 1 shl 1
    const val EVENT_FLAG_PRE_EXISTING = 1 shl 2
    const val EVENT_FLAG_POSITIVE_ACTION = 1 shl 3
    const val EVENT_FLAG_NEGATIVE_ACTION = 1 shl 4

    val KNOWN_APP_NAMES: Map<String, String> = mapOf(
        "com.apple.MobileSMS" to "Messages",
        "com.apple.mobilephone" to "Phone",
        "com.apple.facetime" to "FaceTime",
        "com.apple.mobilemail" to "Mail",
        "com.apple.mobilecal" to "Calendar",
        "com.apple.reminders" to "Reminders",
        "com.apple.Maps" to "Maps",
        "com.apple.news" to "News",
        "com.apple.Health" to "Health",
        "net.whatsapp.WhatsApp" to "WhatsApp",
        "net.whatsapp.WhatsAppSMB" to "WhatsApp Business",
        "com.burbn.instagram" to "Instagram",
        "com.facebook.Messenger" to "Messenger",
        "com.facebook.Facebook" to "Facebook",
        "com.atebits.Tweetie2" to "X",
        "com.hammerandchisel.discord" to "Discord",
        "ph.telegra.Telegraph" to "Telegram",
        "org.whispersystems.signal" to "Signal",
        "com.tinyspeck.chatlyio" to "Slack",
        "com.microsoft.skype.teams" to "Teams",
        "com.google.Gmail" to "Gmail",
        "com.google.hangouts" to "Google Chat",
        "com.ubercab.UberClient" to "Uber",
        "com.spotify.client" to "Spotify"
    )

    fun friendlyAppName(
        appIdentifier: String,
        cachedName: String? = null,
        categoryId: AncsCategoryId = AncsCategoryId.OTHER
    ): String {
        if (!cachedName.isNullOrBlank()) return cachedName
        KNOWN_APP_NAMES[appIdentifier]?.let { return it }
        val lower = appIdentifier.lowercase()
        return when {
            lower.contains("tinyspeck") || lower.contains("slack") -> "Slack"
            lower.contains("instagram") -> "Instagram"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("gmail") -> "Gmail"
            lower.contains("mobilephone") -> "Phone"
            lower.contains("mobilesms") -> "Messages"
            appIdentifier.isBlank() -> when (categoryId) {
                AncsCategoryId.INCOMING_CALL, AncsCategoryId.MISSED_CALL -> "Phone"
                AncsCategoryId.VOICEMAIL -> "Voicemail"
                AncsCategoryId.EMAIL -> "Mail"
                AncsCategoryId.SCHEDULE -> "Calendar"
                else -> "iPhone"
            }
            else -> appIdentifier.substringAfterLast(".")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
