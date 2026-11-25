package com.wearsync.watch.ancs

enum class AncsEventId(val rawValue: Int) {
    ADDED(0),
    MODIFIED(1),
    REMOVED(2);

    companion object {
        fun fromInt(value: Int): AncsEventId = entries.firstOrNull { it.rawValue == value } ?: ADDED
    }
}

enum class AncsCategoryId(val rawValue: Int) {
    OTHER(0),
    INCOMING_CALL(1),
    MISSED_CALL(2),
    VOICEMAIL(3),
    SOCIAL(4),
    SCHEDULE(5),
    EMAIL(6),
    NEWS(7),
    HEALTH_AND_FITNESS(8),
    BUSINESS_AND_FINANCE(9),
    LOCATION(10),
    ENTERTAINMENT(11);

    companion object {
        fun fromInt(value: Int): AncsCategoryId = entries.firstOrNull { it.rawValue == value } ?: OTHER
    }
}

data class AncsEventFlags(val raw: Int) {
    val isSilent: Boolean get() = (raw and AncsConstants.EVENT_FLAG_SILENT) != 0
    val isImportant: Boolean get() = (raw and AncsConstants.EVENT_FLAG_IMPORTANT) != 0
    val isPreExisting: Boolean get() = (raw and AncsConstants.EVENT_FLAG_PRE_EXISTING) != 0
    val hasPositiveAction: Boolean get() = (raw and AncsConstants.EVENT_FLAG_POSITIVE_ACTION) != 0
    val hasNegativeAction: Boolean get() = (raw and AncsConstants.EVENT_FLAG_NEGATIVE_ACTION) != 0
}

data class AncsNotification(
    val uid: Int,
    val eventId: AncsEventId,
    val eventFlags: AncsEventFlags,
    val categoryId: AncsCategoryId,
    val categoryCount: Int,
    var appIdentifier: String = "",
    var appDisplayName: String = "",
    var title: String = "",
    var subtitle: String = "",
    var message: String = "",
    var date: String = "",
    var positiveActionLabel: String = "",
    var negativeActionLabel: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class ConnectionStatus {
    DISCONNECTED,
    SCANNING,
    PAIRING,
    CONNECTING,
    DISCOVERING_SERVICES,
    CONNECTED
}

data class DeviceUiItem(
    val address: String,
    val name: String,
    val isBonded: Boolean,
    val isAppleCandidate: Boolean,
    val rssi: Int? = null,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED
)
