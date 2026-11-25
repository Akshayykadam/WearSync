package com.wearsync.watch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wearsync.watch.data.DeviceStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val store = DeviceStore(context)
            if (store.getActiveDeviceAddress() != null) {
                WearSyncService.start(context)
            }
        }
    }
}
