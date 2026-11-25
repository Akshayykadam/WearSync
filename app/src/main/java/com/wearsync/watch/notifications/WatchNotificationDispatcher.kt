package com.wearsync.watch.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wearsync.watch.R
import com.wearsync.watch.ancs.*
import com.wearsync.watch.data.SyncSettings
import com.wearsync.watch.presentation.MainActivity
import com.wearsync.watch.presentation.NotificationAlertActivity
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class WatchNotificationDispatcher(private val context: Context) {

    private val TAG = "WatchNotifDispatcher"
    private val testUidCounter = AtomicInteger(900_000)
    private val systemNm: NotificationManager? = context.getSystemService(NotificationManager::class.java)
    private val compatNm: NotificationManagerCompat = NotificationManagerCompat.from(context)

    private data class PostedRecord(
        val appTag: String,
        val systemId: Int,
        val fingerprint: String,
        val postedAtMs: Long
    )

    // Deduplicate ANCS events and keep at most one active drawer card per iOS app
    // so Android 16 doesn't flood or auto-collapse alerts prematurely.
    private val activeByUid = ConcurrentHashMap<Int, PostedRecord>()
    private val activeUidByApp = ConcurrentHashMap<String, Int>()
    private val recentByFingerprint = ConcurrentHashMap<String, PostedRecord>()

    companion object {
        const val CHANNEL_SERVICE = "wearsync_service"
        const val CHANNEL_ALERTS_VIBRATE = "wearsync_alerts_vibrate"
        const val CHANNEL_ALERTS_QUIET = "wearsync_alerts_quiet"
        const val CHANNEL_CALLS = "wearsync_calls"
        const val CHANNEL_PRE_EXISTING = "wearsync_pre_existing"

        const val SERVICE_NOTIFICATION_ID = 1001
        private const val TEST_NOTIF_OFFSET = 500_000
        private const val DUPLICATE_SUPPRESS_WINDOW_MS = 120_000L
        private const val MAX_MULTI_DRAWER_CARDS = 45
        private const val EXTRA_CONTENT_HASH = "wearsync_content_hash"

        internal fun computeAppSystemId(appTag: String): Int {
            val positive = (appTag.hashCode() and 0x7FFFFFFF) % 900_000
            val id = positive + 2000
            return if (id == SERVICE_NOTIFICATION_ID) TEST_NOTIF_OFFSET + 1 else id
        }

        internal fun computeUidSystemId(uid: Int): Int {
            val positive = (uid and 0x7FFFFFFF) % 900_000
            val id = positive + 2000
            return if (id == SERVICE_NOTIFICATION_ID) TEST_NOTIF_OFFSET + 1 else id
        }
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = systemNm ?: return

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "WearSync Background Connection",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps Bluetooth notification sync connected to your iPhone"
            setShowBadge(false)
        }

        val alertsVibrateChannel = NotificationChannel(
            CHANNEL_ALERTS_VIBRATE,
            "iPhone Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Native alerts mirrored from your iPhone"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 90, 180)
        }

        val alertsQuietChannel = NotificationChannel(
            CHANNEL_ALERTS_QUIET,
            "iPhone Notifications (Silent)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Native alerts mirrored without vibration"
            enableVibration(false)
            setSound(null, null)
        }

        val callsChannel = NotificationChannel(
            CHANNEL_CALLS,
            "iPhone Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming call alerts from your iPhone"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 220, 350, 220, 350)
        }

        val preExistingChannel = NotificationChannel(
            CHANNEL_PRE_EXISTING,
            "Previously Existing Alerts",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Existing lock-screen notifications synced on connect"
            enableVibration(false)
            setSound(null, null)
        }

        nm.createNotificationChannels(
            listOf(
                serviceChannel,
                alertsVibrateChannel,
                alertsQuietChannel,
                callsChannel,
                preExistingChannel
            )
        )
    }

    fun buildServiceNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_apple)
            .setContentTitle(context.getString(R.string.service_title))
            .setContentText(statusText)
            .setContentIntent(openAppIntent)
            .setGroup("wearsync_service")
            .setSortKey("zzzz")
            .setLocalOnly(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun updateServiceNotification(statusText: String) {
        try {
            compatNm.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(statusText))
        } catch (_: SecurityException) {
        }
    }

    /**
     * Post or remove a native Wear OS notification corresponding to an ANCS event.
     * Caps drawer notifications to the latest alert per iOS app so the shade stays
     * clean and avoids per-app duplicate flooding.
     */
    fun dispatch(notif: AncsNotification, settings: SyncSettings): Boolean {
        val nm = compatNm

        if (notif.eventId == AncsEventId.REMOVED) {
            cancelByUid(notif.uid)
            Log.d(TAG, "Cancelled watch notification uid=${notif.uid}")
            return false
        }

        if (notif.eventFlags.isPreExisting && !settings.syncPreExisting) {
            Log.d(TAG, "Skipping pre-existing notification uid=${notif.uid}")
            return false
        }

        val appLabel = notif.appDisplayName.ifBlank {
            AncsConstants.friendlyAppName(notif.appIdentifier, categoryId = notif.categoryId)
        }
        val appTag = notif.appIdentifier.ifBlank { appLabel }
        val systemId = if (settings.singleDrawerCard) {
            mapAppToSystemId(appTag)
        } else {
            mapUidToSystemId(notif.uid)
        }

        val title = notif.title.ifBlank { appLabel }
        val body = buildString {
            if (notif.subtitle.isNotBlank()) {
                append(notif.subtitle)
                if (notif.message.isNotBlank()) append("\n")
            }
            append(notif.message)
        }.ifBlank { "New notification from $appLabel" }

        val fingerprint = "${notif.appIdentifier}|$title|$body"
        val contentHash = fingerprint.hashCode()
        val now = System.currentTimeMillis()
        cleanupExpiredFingerprints(now)

        // Only deduplicate rapid duplicate deliveries (within 4s) or identical pre-existing reconnect bursts.
        // Never let a pre-existing alert suppress or silence a brand-new incoming message with the same text (e.g. "Hi").
        val existingFingerprint = recentByFingerprint[fingerprint]
        if (existingFingerprint != null) {
            if (notif.eventFlags.isPreExisting && (now - existingFingerprint.postedAtMs) < DUPLICATE_SUPPRESS_WINDOW_MS) {
                Log.d(TAG, "Skipping duplicate pre-existing alert [$appLabel] $title")
                return false
            }
            if (!notif.eventFlags.isPreExisting &&
                activeByUid[notif.uid] == null &&
                (now - existingFingerprint.postedAtMs) < 4_000L
            ) {
                Log.d(TAG, "Skipping duplicate rapid alert [$appLabel] $title")
                return false
            }
        }

        val activeNotifs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            systemNm?.activeNotifications
        } else {
            null
        }
        val existingHash = findActiveContentHash(appTag, systemId, activeNotifs)

        // In Single-Card Drawer Mode, keep only 1 mirrored card in the system drawer at a time so
        // Android 16 doesn't trigger Aggregate_AlertingSection / SILENT and Samsung shows it unstacked.
        if (settings.singleDrawerCard) {
            clearOtherDrawerNotifications(
                keepTag = appTag,
                keepSystemId = systemId,
                keepUid = if (activeUidByApp[appTag] == notif.uid) notif.uid else null,
                activeNotifs = activeNotifs
            )
        } else {
            enforceMultiCardCap(MAX_MULTI_DRAWER_CARDS, appTag, systemId, activeNotifs)
        }

        val isSilent = notif.eventFlags.isSilent || notif.eventFlags.isPreExisting
        val channelId = when {
            isSilent -> CHANNEL_PRE_EXISTING
            notif.categoryId == AncsCategoryId.INCOMING_CALL -> CHANNEL_CALLS
            settings.vibrateEnabled -> CHANNEL_ALERTS_VIBRATE
            else -> CHANNEL_ALERTS_QUIET
        }

        val openAppIntent = PendingIntent.getActivity(
            context,
            systemId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val alertActivityIntent = Intent(context, NotificationAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            systemId * 10 + 4,
            alertActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIconBitmap = AppIconFactory.createAppIcon(
            context = context,
            appIdentifier = notif.appIdentifier,
            appDisplayName = appLabel,
            categoryId = notif.categoryId
        )
        val badgeColor = AppIconFactory.badgeColorFor(notif.appIdentifier, appLabel, notif.categoryId)

        val extras = android.os.Bundle().apply {
            putInt(EXTRA_CONTENT_HASH, contentHash)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_iphone_badge)
            .setLargeIcon(largeIconBitmap)
            .setColor(badgeColor)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(appLabel)
            .addExtras(extras)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body)
                    .setBigContentTitle(title)
                    .setSummaryText(appLabel)
            )
            .setCategory(androidCategoryFor(notif.categoryId))
            .setPriority(
                if (isSilent) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_HIGH
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setSortKey(String.format(Locale.US, "%016d", (Long.MAX_VALUE - notif.timestamp).coerceAtLeast(0L)))
            .setWhen(notif.timestamp)
            .setShowWhen(true)

        if (!settings.singleDrawerCard) {
            // Unique group per UID avoids broken summary-less groups and prevents Android auto-bundling
            builder
                .setGroup("wearsync:$appTag:${notif.uid}")
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
        }

        if (!isSilent && settings.popupAlertsEnabled) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        }

        val unchangedContent = existingHash != null && existingHash == contentHash
        if (isSilent || (notif.eventId == AncsEventId.MODIFIED && unchangedContent)) {
            builder.setOnlyAlertOnce(true)
        }

        if (!isSilent && settings.vibrateEnabled) {
            if (notif.categoryId == AncsCategoryId.INCOMING_CALL) {
                builder.setVibrate(longArrayOf(0, 350, 220, 350, 220, 350))
            } else {
                builder.setVibrate(longArrayOf(0, 180, 90, 180))
            }
        }

        if (notif.eventFlags.hasPositiveAction) {
            val posLabel = notif.positiveActionLabel.ifBlank {
                if (notif.categoryId == AncsCategoryId.INCOMING_CALL) "Answer" else "Open"
            }
            builder.addAction(
                0,
                posLabel,
                buildActionPendingIntent(notif.uid, appTag, systemId, NotificationActionReceiver.ACTION_POSITIVE, systemId * 10 + 1)
            )
        }

        if (notif.eventFlags.hasNegativeAction) {
            val negLabel = notif.negativeActionLabel.ifBlank {
                if (notif.categoryId == AncsCategoryId.INCOMING_CALL) "Decline" else "Dismiss"
            }
            builder.addAction(
                0,
                negLabel,
                buildActionPendingIntent(notif.uid, appTag, systemId, NotificationActionReceiver.ACTION_NEGATIVE, systemId * 10 + 2)
            )
        }

        if (settings.syncDismissToIphone && notif.eventFlags.hasNegativeAction) {
            builder.setDeleteIntent(
                buildActionPendingIntent(notif.uid, appTag, systemId, NotificationActionReceiver.ACTION_DISMISS, systemId * 10 + 3)
            )
        }

        return try {
            nm.notify(appTag, systemId, builder.build())
            val record = PostedRecord(appTag, systemId, fingerprint, now)
            activeByUid[notif.uid] = record
            activeUidByApp[appTag] = notif.uid
            if (!notif.eventFlags.isPreExisting) {
                recentByFingerprint[fingerprint] = record
            }
            Log.i(TAG, "Posted watch card [$appLabel] $title (uid=${notif.uid}, tag=$appTag, id=$systemId)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "POST_NOTIFICATIONS permission not granted", e)
            false
        }
    }

    fun dispatchTestNotification(settings: SyncSettings): AncsNotification {
        val uid = testUidCounter.incrementAndGet()
        val testNotif = when (uid % 4) {
            1 -> AncsNotification(
                uid = uid,
                eventId = AncsEventId.ADDED,
                eventFlags = AncsEventFlags(
                    AncsConstants.EVENT_FLAG_IMPORTANT or AncsConstants.EVENT_FLAG_NEGATIVE_ACTION
                ),
                categoryId = AncsCategoryId.EMAIL,
                categoryCount = 1,
                appIdentifier = "com.google.Gmail",
                appDisplayName = "Gmail",
                title = "John Matthew",
                message = "This is a notification from my iPhone displayed on my watch!",
                negativeActionLabel = "Dismiss"
            )
            2 -> AncsNotification(
                uid = uid,
                eventId = AncsEventId.ADDED,
                eventFlags = AncsEventFlags(
                    AncsConstants.EVENT_FLAG_IMPORTANT or AncsConstants.EVENT_FLAG_NEGATIVE_ACTION
                ),
                categoryId = AncsCategoryId.SOCIAL,
                categoryCount = 1,
                appIdentifier = "net.whatsapp.WhatsApp",
                appDisplayName = "WhatsApp",
                title = "Sarah",
                message = "Are we still on for coffee?",
                negativeActionLabel = "Dismiss"
            )
            3 -> AncsNotification(
                uid = uid,
                eventId = AncsEventId.ADDED,
                eventFlags = AncsEventFlags(
                    AncsConstants.EVENT_FLAG_IMPORTANT or AncsConstants.EVENT_FLAG_NEGATIVE_ACTION
                ),
                categoryId = AncsCategoryId.SOCIAL,
                categoryCount = 1,
                appIdentifier = "com.burbn.instagram",
                appDisplayName = "Instagram",
                title = "alex_k",
                message = "Liked your story and sent a reply",
                negativeActionLabel = "Dismiss"
            )
            else -> AncsNotification(
                uid = uid,
                eventId = AncsEventId.ADDED,
                eventFlags = AncsEventFlags(
                    AncsConstants.EVENT_FLAG_IMPORTANT or AncsConstants.EVENT_FLAG_NEGATIVE_ACTION
                ),
                categoryId = AncsCategoryId.SOCIAL,
                categoryCount = 1,
                appIdentifier = "com.tinyspeck.chatlyio",
                appDisplayName = "Slack",
                title = "#engineering",
                message = "Build 42 passed all checks — ready to ship!",
                negativeActionLabel = "Dismiss"
            )
        }
        // Always allow test alert even if pressed repeatedly
        recentByFingerprint.clear()
        dispatch(testNotif, settings)
        return testNotif
    }

    fun enforceSingleDrawerCard() {
        val latest = activeByUid.maxByOrNull { it.value.postedAtMs } ?: return
        clearOtherDrawerNotifications(
            keepTag = latest.value.appTag,
            keepSystemId = latest.value.systemId,
            keepUid = latest.key
        )
    }

    private fun clearOtherDrawerNotifications(
        keepTag: String,
        keepSystemId: Int,
        keepUid: Int? = null,
        activeNotifs: Array<android.service.notification.StatusBarNotification>? = null
    ) {
        activeByUid.entries.removeIf { entry ->
            keepUid == null || entry.key != keepUid
        }
        activeUidByApp.entries.removeIf { entry ->
            entry.key != keepTag || (keepUid != null && entry.value != keepUid)
        }
        val nm = systemNm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (activeNotifs ?: nm.activeNotifications)
                .filter { it.id != SERVICE_NOTIFICATION_ID }
                .forEach { sbn ->
                    if (keepUid == null || sbn.tag != keepTag || sbn.id != keepSystemId) {
                        nm.cancel(sbn.tag, sbn.id)
                    }
                }
        }
    }

    private fun findActiveContentHash(
        appTag: String,
        systemId: Int,
        activeNotifs: Array<android.service.notification.StatusBarNotification>? = null
    ): Int? {
        val nm = systemNm ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val match = (activeNotifs ?: nm.activeNotifications)
            .firstOrNull { it.tag == appTag && it.id == systemId }
        val extras = match?.notification?.extras ?: return null
        return if (extras.containsKey(EXTRA_CONTENT_HASH)) extras.getInt(EXTRA_CONTENT_HASH) else null
    }

    private fun enforceMultiCardCap(
        maxActive: Int,
        incomingTag: String,
        incomingSystemId: Int,
        activeNotifs: Array<android.service.notification.StatusBarNotification>? = null
    ) {
        val nm = systemNm ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val mirrored = (activeNotifs ?: nm.activeNotifications)
            .filter {
                it.id != SERVICE_NOTIFICATION_ID &&
                    (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                    !(it.tag == incomingTag && it.id == incomingSystemId)
            }
            .sortedBy { it.postTime }
        val excess = mirrored.size - (maxActive - 1)
        if (excess > 0) {
            mirrored.take(excess).forEach { old ->
                nm.cancel(old.tag, old.id)
                activeByUid.entries.removeIf { it.value.appTag == old.tag && it.value.systemId == old.id }
            }
        }
    }

    fun cancelByUid(uid: Int) {
        val removed = activeByUid.remove(uid) ?: return
        recentByFingerprint.remove(removed.fingerprint)
        activeUidByApp.remove(removed.appTag, uid)
        val slotStillOccupied = activeByUid.values.any {
            it.appTag == removed.appTag && it.systemId == removed.systemId
        }
        if (!slotStillOccupied) {
            compatNm.cancel(removed.appTag, removed.systemId)
        }
    }

    fun cancelAllMirrored() {
        activeByUid.clear()
        activeUidByApp.clear()
        recentByFingerprint.clear()
        val nm = systemNm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.activeNotifications
                .filter { it.id != SERVICE_NOTIFICATION_ID }
                .forEach { nm.cancel(it.tag, it.id) }
        }
    }

    private fun cleanupExpiredFingerprints(now: Long) {
        val iter = recentByFingerprint.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value.postedAtMs > DUPLICATE_SUPPRESS_WINDOW_MS) {
                iter.remove()
            }
        }
    }

    private fun buildActionPendingIntent(
        uid: Int,
        appTag: String,
        systemId: Int,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_ANCS_UID, uid)
            putExtra(NotificationActionReceiver.EXTRA_SYSTEM_ID, systemId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_TAG, appTag)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    internal fun mapAppToSystemId(appTag: String): Int = computeAppSystemId(appTag)

    internal fun mapUidToSystemId(uid: Int): Int = computeUidSystemId(uid)

    private fun androidCategoryFor(category: AncsCategoryId): String {
        return when (category) {
            AncsCategoryId.INCOMING_CALL -> NotificationCompat.CATEGORY_CALL
            AncsCategoryId.MISSED_CALL -> NotificationCompat.CATEGORY_MISSED_CALL
            AncsCategoryId.SOCIAL -> NotificationCompat.CATEGORY_MESSAGE
            AncsCategoryId.EMAIL -> NotificationCompat.CATEGORY_EMAIL
            AncsCategoryId.SCHEDULE -> NotificationCompat.CATEGORY_EVENT
            else -> NotificationCompat.CATEGORY_SOCIAL
        }
    }
}
