package com.wearsync.watch.notifications

import com.wearsync.watch.ancs.AncsCategoryId
import com.wearsync.watch.ancs.AncsConstants
import com.wearsync.watch.ancs.AncsEventFlags
import com.wearsync.watch.ancs.AncsEventId
import com.wearsync.watch.ancs.AncsNotification
import com.wearsync.watch.ancs.AncsParser
import com.wearsync.watch.ancs.DataSourceParseResult
import com.wearsync.watch.data.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNotificationDispatcherTest {

    @Test
    fun friendlyAppName_resolvesMajorIosBundleIdentifiers() {
        assertEquals("WhatsApp", AncsConstants.friendlyAppName("net.whatsapp.WhatsApp"))
        assertEquals("Instagram", AncsConstants.friendlyAppName("com.burbn.instagram"))
        assertEquals("Slack", AncsConstants.friendlyAppName("com.tinyspeck.chatlyio"))
        assertEquals("Gmail", AncsConstants.friendlyAppName("com.google.Gmail"))
        assertEquals("Phone", AncsConstants.friendlyAppName("com.apple.mobilephone"))
        assertEquals("Messages", AncsConstants.friendlyAppName("com.apple.MobileSMS"))
        assertEquals("Phone", AncsConstants.friendlyAppName("", categoryId = AncsCategoryId.MISSED_CALL))
        assertEquals("Phone", AncsConstants.friendlyAppName("", categoryId = AncsCategoryId.INCOMING_CALL))
    }

    @Test
    fun syncSettings_defaultsToMultiDrawerCards() {
        assertFalse(SyncSettings().singleDrawerCard)
    }

    @Test
    fun uidSystemIds_areDistinctAcrossMultipleMessagesFromSameApp() {
        val uids = listOf(101, 102, 103, 104)
        val systemIds = uids.map { WatchNotificationDispatcher.computeUidSystemId(it) }
        assertEquals(uids.size, systemIds.toSet().size)
        systemIds.forEach { id ->
            assertNotEquals(WatchNotificationDispatcher.SERVICE_NOTIFICATION_ID, id)
        }
    }

    @Test
    fun appTagSystemId_isDeterministicAndDistinctFromServiceId() {
        val apps = listOf(
            "com.google.Gmail",
            "net.whatsapp.WhatsApp",
            "com.burbn.instagram",
            "com.tinyspeck.chatlyio",
            "com.apple.mobilephone",
            "com.apple.MobileSMS"
        )
        apps.forEach { appTag ->
            val id = WatchNotificationDispatcher.computeAppSystemId(appTag)
            assertNotEquals(WatchNotificationDispatcher.SERVICE_NOTIFICATION_ID, id)
            assertEquals(id, WatchNotificationDispatcher.computeAppSystemId(appTag))
        }
    }

    @Test
    fun ancsParser_resetsOversizedBufferAndRecoversForNextNotification() {
        val parser = AncsParser()
        val target = AncsNotification(
            uid = 42,
            eventId = AncsEventId.ADDED,
            eventFlags = AncsEventFlags(0),
            categoryId = AncsCategoryId.SOCIAL,
            categoryCount = 1
        )
        parser.startNotificationAssembly(setOf(AncsConstants.NOTIF_ATTR_APP_IDENTIFIER))
        val oversized = ByteArray(17_000) { 0x01 }
        assertEquals(DataSourceParseResult.Incomplete, parser.appendDataSourceChunk(oversized, target))

        // Valid minimal response should parse cleanly after overflow reset
        val appId = "net.whatsapp.WhatsApp".toByteArray(Charsets.UTF_8)
        val validPacket = java.io.ByteArrayOutputStream().apply {
            write(AncsConstants.CMD_GET_NOTIFICATION_ATTRIBUTES.toInt())
            write(byteArrayOf(42, 0, 0, 0))
            write(AncsConstants.NOTIF_ATTR_APP_IDENTIFIER.toInt())
            write(appId.size and 0xFF)
            write((appId.size ushr 8) and 0xFF)
            write(appId)
        }.toByteArray()

        val result = parser.appendDataSourceChunk(validPacket, target)
        assertTrue(result is DataSourceParseResult.NotificationAttributesReady)
        assertEquals("net.whatsapp.WhatsApp", (result as DataSourceParseResult.NotificationAttributesReady).notification.appIdentifier)
    }
}
