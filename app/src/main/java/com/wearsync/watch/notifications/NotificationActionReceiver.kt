package com.wearsync.watch.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.wearsync.watch.service.WearSyncService

class NotificationActionReceiver : BroadcastReceiver() {

    private val TAG = "NotifActionReceiver"

    companion object {
        const val ACTION_POSITIVE = "com.wearsync.watch.ACTION_POSITIVE"
        const val ACTION_NEGATIVE = "com.wearsync.watch.ACTION_NEGATIVE"
        const val ACTION_DISMISS = "com.wearsync.watch.ACTION_DISMISS"
        const val EXTRA_ANCS_UID = "extra_ancs_uid"
        const val EXTRA_SYSTEM_ID = "extra_system_id"
        const val EXTRA_NOTIFICATION_TAG = "extra_notification_tag"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getIntExtra(EXTRA_ANCS_UID, -1)
        val systemId = intent.getIntExtra(EXTRA_SYSTEM_ID, -1)
        val tag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG)
        if (uid == -1) return

        val isPositive = intent.action == ACTION_POSITIVE
        Log.i(TAG, "Watch notification action=${intent.action} uid=$uid tag=$tag")

        if (systemId != -1) {
            NotificationManagerCompat.from(context).cancel(tag, systemId)
        }

        WearSyncService.activeInstance?.performNotificationAction(uid, isPositive)
    }
}
