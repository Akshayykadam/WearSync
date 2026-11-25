package com.wearsync.watch.ancs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class DataSourceParseResult {
    data object Incomplete : DataSourceParseResult()
    data class NotificationAttributesReady(val notification: AncsNotification) : DataSourceParseResult()
    data class AppAttributesReady(val appIdentifier: String, val displayName: String) : DataSourceParseResult()
}

class AncsParser {

    companion object {
        private const val MAX_ASSEMBLY_BUFFER_BYTES = 16_384
    }

    private val incomingBuffer = ByteArrayOutputStream()
    private var expectedAttributeIds: Set<Byte> = emptySet()

    /**
     * Parse 8-byte Notification Source characteristic packet.
     */
    fun parseNotificationSource(data: ByteArray): AncsNotification? {
        if (data.size < 8) return null

        val eventId = AncsEventId.fromInt(data[0].toInt() and 0xFF)
        val eventFlags = AncsEventFlags(data[1].toInt() and 0xFF)
        val categoryId = AncsCategoryId.fromInt(data[2].toInt() and 0xFF)
        val categoryCount = data[3].toInt() and 0xFF

        val uid = (data[4].toInt() and 0xFF) or
            ((data[5].toInt() and 0xFF) shl 8) or
            ((data[6].toInt() and 0xFF) shl 16) or
            ((data[7].toInt() and 0xFF) shl 24)

        return AncsNotification(
            uid = uid,
            eventId = eventId,
            eventFlags = eventFlags,
            categoryId = categoryId,
            categoryCount = categoryCount
        )
    }

    /**
     * Build exact-sized GetNotificationAttributes command for Control Point.
     */
    fun buildGetNotificationAttributesCommand(
        uid: Int,
        includePositiveAction: Boolean = false,
        includeNegativeAction: Boolean = false,
        maxTitleLength: Short = 128,
        maxSubtitleLength: Short = 64,
        maxMessageLength: Short = 512
    ): Pair<ByteArray, Set<Byte>> {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        header.put(AncsConstants.CMD_GET_NOTIFICATION_ATTRIBUTES)
        header.putInt(uid)
        out.write(header.array())

        val requested = linkedSetOf<Byte>()

        fun putAttrNoLen(attrId: Byte) {
            out.write(attrId.toInt())
            requested.add(attrId)
        }

        fun putAttrWithLen(attrId: Byte, maxLen: Short) {
            out.write(attrId.toInt())
            out.write(maxLen.toInt() and 0xFF)
            out.write((maxLen.toInt() ushr 8) and 0xFF)
            requested.add(attrId)
        }

        putAttrNoLen(AncsConstants.NOTIF_ATTR_APP_IDENTIFIER)
        putAttrWithLen(AncsConstants.NOTIF_ATTR_TITLE, maxTitleLength)
        putAttrWithLen(AncsConstants.NOTIF_ATTR_SUBTITLE, maxSubtitleLength)
        putAttrWithLen(AncsConstants.NOTIF_ATTR_MESSAGE, maxMessageLength)
        putAttrNoLen(AncsConstants.NOTIF_ATTR_DATE)

        if (includePositiveAction) {
            putAttrNoLen(AncsConstants.NOTIF_ATTR_POSITIVE_ACTION_LABEL)
        }
        if (includeNegativeAction) {
            putAttrNoLen(AncsConstants.NOTIF_ATTR_NEGATIVE_ACTION_LABEL)
        }

        return out.toByteArray() to requested
    }

    /**
     * Build GetAppAttributes command (CmdID=1 + null-terminated AppIdentifier + DisplayName attr).
     */
    fun buildGetAppAttributesCommand(appIdentifier: String): ByteArray {
        val idBytes = appIdentifier.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(1 + idBytes.size + 2)
        out.write(AncsConstants.CMD_GET_APP_ATTRIBUTES.toInt())
        out.write(idBytes)
        out.write(0x00) // Null terminator required by ANCS spec
        out.write(AncsConstants.APP_ATTR_DISPLAY_NAME.toInt())
        return out.toByteArray()
    }

    /**
     * Build PerformNotificationAction command (CmdID=2 + 4-byte UID + 1-byte ActionID).
     */
    fun buildPerformNotificationActionCommand(uid: Int, isPositive: Boolean): ByteArray {
        return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .put(AncsConstants.CMD_PERFORM_NOTIFICATION_ACTION)
            .putInt(uid)
            .put(if (isPositive) AncsConstants.ACTION_POSITIVE else AncsConstants.ACTION_NEGATIVE)
            .array()
    }

    fun startNotificationAssembly(expectedAttrs: Set<Byte>) {
        incomingBuffer.reset()
        expectedAttributeIds = expectedAttrs
    }

    fun startAppAttributeAssembly() {
        incomingBuffer.reset()
        expectedAttributeIds = setOf(AncsConstants.APP_ATTR_DISPLAY_NAME)
    }

    fun resetAssembly() {
        incomingBuffer.reset()
        expectedAttributeIds = emptySet()
    }

    /**
     * Append a Data Source chunk and parse if the complete response has arrived.
     */
    fun appendDataSourceChunk(
        chunk: ByteArray,
        targetNotification: AncsNotification?
    ): DataSourceParseResult {
        if (incomingBuffer.size() + chunk.size > MAX_ASSEMBLY_BUFFER_BYTES) {
            resetAssembly()
            return DataSourceParseResult.Incomplete
        }
        incomingBuffer.write(chunk)
        val bytes = incomingBuffer.toByteArray()
        if (bytes.isEmpty()) return DataSourceParseResult.Incomplete

        return when (bytes[0]) {
            AncsConstants.CMD_GET_NOTIFICATION_ATTRIBUTES -> {
                val target = targetNotification ?: return DataSourceParseResult.Incomplete
                parseNotificationAttributesResponse(bytes, target)
            }
            AncsConstants.CMD_GET_APP_ATTRIBUTES -> {
                parseAppAttributesResponse(bytes)
            }
            else -> DataSourceParseResult.Incomplete
        }
    }

    private fun parseNotificationAttributesResponse(
        bytes: ByteArray,
        target: AncsNotification
    ): DataSourceParseResult {
        if (bytes.size < 5) return DataSourceParseResult.Incomplete

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // cmdId
        val responseUid = buffer.int
        if (responseUid != target.uid) {
            return DataSourceParseResult.Incomplete
        }

        val parsedIds = hashSetOf<Byte>()

        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 3) {
                return DataSourceParseResult.Incomplete
            }
            val attrId = buffer.get()
            val attrLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < attrLen) {
                return DataSourceParseResult.Incomplete
            }

            val attrBytes = ByteArray(attrLen)
            buffer.get(attrBytes)
            val attrStr = String(attrBytes, Charsets.UTF_8)
            parsedIds.add(attrId)

            when (attrId) {
                AncsConstants.NOTIF_ATTR_APP_IDENTIFIER -> target.appIdentifier = attrStr
                AncsConstants.NOTIF_ATTR_TITLE -> target.title = attrStr
                AncsConstants.NOTIF_ATTR_SUBTITLE -> target.subtitle = attrStr
                AncsConstants.NOTIF_ATTR_MESSAGE -> target.message = attrStr
                AncsConstants.NOTIF_ATTR_DATE -> target.date = attrStr
                AncsConstants.NOTIF_ATTR_POSITIVE_ACTION_LABEL -> target.positiveActionLabel = attrStr
                AncsConstants.NOTIF_ATTR_NEGATIVE_ACTION_LABEL -> target.negativeActionLabel = attrStr
            }
        }

        // Wait until all requested attribute IDs have been delivered in the response
        if (expectedAttributeIds.isNotEmpty() && !parsedIds.containsAll(expectedAttributeIds)) {
            return DataSourceParseResult.Incomplete
        }

        return DataSourceParseResult.NotificationAttributesReady(target)
    }

    private fun parseAppAttributesResponse(bytes: ByteArray): DataSourceParseResult {
        // Format: CmdId(1) + Null-terminated AppIdentifier + [AttrID(1) + Len(2 LE) + Value]...
        var nullIdx = -1
        for (i in 1 until bytes.size) {
            if (bytes[i].toInt() == 0) {
                nullIdx = i
                break
            }
        }
        if (nullIdx == -1) return DataSourceParseResult.Incomplete

        val appId = String(bytes, 1, nullIdx - 1, Charsets.UTF_8)
        val offset = nullIdx + 1
        if (bytes.size - offset < 3) return DataSourceParseResult.Incomplete

        val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.LITTLE_ENDIAN)
        var displayName = ""
        var gotDisplayName = false

        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 3) return DataSourceParseResult.Incomplete
            val attrId = buffer.get()
            val len = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < len) return DataSourceParseResult.Incomplete

            val valueBytes = ByteArray(len)
            buffer.get(valueBytes)
            if (attrId == AncsConstants.APP_ATTR_DISPLAY_NAME) {
                displayName = String(valueBytes, Charsets.UTF_8)
                gotDisplayName = true
            }
        }

        return if (gotDisplayName) {
            DataSourceParseResult.AppAttributesReady(appId, displayName)
        } else {
            DataSourceParseResult.Incomplete
        }
    }
}
