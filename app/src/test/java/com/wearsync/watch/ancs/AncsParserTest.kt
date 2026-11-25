package com.wearsync.watch.ancs

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AncsParserTest {

    @Test
    fun parseNotificationSource_parsesFlagsCategoryAndUid() {
        val parser = AncsParser()
        val packet = byteArrayOf(
            0x00, // EventID: Added
            0x1A, // Flags: Important (2) | PositiveAction (8) | NegativeAction (16)
            0x04, // CategoryID: Social
            0x03, // CategoryCount: 3
            0x78, 0x56, 0x34, 0x12 // UID = 0x12345678
        )

        val notif = parser.parseNotificationSource(packet)
        assertNotNull(notif)
        notif!!
        assertEquals(AncsEventId.ADDED, notif.eventId)
        assertEquals(AncsCategoryId.SOCIAL, notif.categoryId)
        assertEquals(3, notif.categoryCount)
        assertEquals(0x12345678, notif.uid)
        assertFalse(notif.eventFlags.isSilent)
        assertTrue(notif.eventFlags.isImportant)
        assertFalse(notif.eventFlags.isPreExisting)
        assertTrue(notif.eventFlags.hasPositiveAction)
        assertTrue(notif.eventFlags.hasNegativeAction)
    }

    @Test
    fun buildGetNotificationAttributesCommand_hasExactByteLengthWithNoTrailingZeros() {
        val parser = AncsParser()
        val (cmd, expectedAttrs) = parser.buildGetNotificationAttributesCommand(
            uid = 42,
            includePositiveAction = false,
            includeNegativeAction = true
        )

        // 1 (cmd) + 4 (uid) + 1 (appId) + 3 (title) + 3 (subtitle) + 3 (msg) + 1 (date) + 1 (negAction, no max-len per ANCS spec) = 17
        assertEquals(17, cmd.size)
        assertEquals(
            setOf(
                AncsConstants.NOTIF_ATTR_APP_IDENTIFIER,
                AncsConstants.NOTIF_ATTR_TITLE,
                AncsConstants.NOTIF_ATTR_SUBTITLE,
                AncsConstants.NOTIF_ATTR_MESSAGE,
                AncsConstants.NOTIF_ATTR_DATE,
                AncsConstants.NOTIF_ATTR_NEGATIVE_ACTION_LABEL
            ),
            expectedAttrs
        )
    }

    @Test
    fun appendDataSourceChunk_reassemblesFragmentedNotificationAttributes() {
        val parser = AncsParser()
        val (_, expectedAttrs) = parser.buildGetNotificationAttributesCommand(uid = 99)
        parser.startNotificationAssembly(expectedAttrs)

        val notif = AncsNotification(
            uid = 99,
            eventId = AncsEventId.ADDED,
            eventFlags = AncsEventFlags(0),
            categoryId = AncsCategoryId.SOCIAL,
            categoryCount = 1
        )

        val response = ByteArrayOutputStream().apply {
            write(AncsConstants.CMD_GET_NOTIFICATION_ATTRIBUTES.toInt())
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(99).array())
            fun writeAttr(id: Byte, value: String) {
                val b = value.toByteArray(Charsets.UTF_8)
                write(id.toInt())
                write(b.size and 0xFF)
                write((b.size ushr 8) and 0xFF)
                write(b)
            }
            writeAttr(AncsConstants.NOTIF_ATTR_APP_IDENTIFIER, "com.apple.MobileSMS")
            writeAttr(AncsConstants.NOTIF_ATTR_TITLE, "John")
            writeAttr(AncsConstants.NOTIF_ATTR_SUBTITLE, "")
            writeAttr(AncsConstants.NOTIF_ATTR_MESSAGE, "Meet at 5pm?")
            writeAttr(AncsConstants.NOTIF_ATTR_DATE, "20261125T093000")
        }.toByteArray()

        // Split right between Title and Subtitle to verify we don't emit early
        val splitPoint = response.indexOf('J'.code.toByte()) + 4
        val chunk1 = response.copyOfRange(0, splitPoint)
        val chunk2 = response.copyOfRange(splitPoint, response.size)

        assertEquals(DataSourceParseResult.Incomplete, parser.appendDataSourceChunk(chunk1, notif))

        val finalResult = parser.appendDataSourceChunk(chunk2, notif)
        assertTrue(finalResult is DataSourceParseResult.NotificationAttributesReady)
        val ready = (finalResult as DataSourceParseResult.NotificationAttributesReady).notification
        assertEquals("com.apple.MobileSMS", ready.appIdentifier)
        assertEquals("John", ready.title)
        assertEquals("Meet at 5pm?", ready.message)
        assertEquals("20261125T093000", ready.date)
    }

    @Test
    fun appendDataSourceChunk_parsesAppDisplayNameResponse() {
        val parser = AncsParser()
        parser.startAppAttributeAssembly()

        val response = ByteArrayOutputStream().apply {
            write(AncsConstants.CMD_GET_APP_ATTRIBUTES.toInt())
            write("net.whatsapp.WhatsApp".toByteArray(Charsets.UTF_8))
            write(0x00)
            write(AncsConstants.APP_ATTR_DISPLAY_NAME.toInt())
            val nameBytes = "WhatsApp".toByteArray(Charsets.UTF_8)
            write(nameBytes.size and 0xFF)
            write((nameBytes.size ushr 8) and 0xFF)
            write(nameBytes)
        }.toByteArray()

        val result = parser.appendDataSourceChunk(response, null)
        assertEquals(
            DataSourceParseResult.AppAttributesReady("net.whatsapp.WhatsApp", "WhatsApp"),
            result
        )
    }
}
