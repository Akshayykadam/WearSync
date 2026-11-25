package com.wearsync.watch.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class SavedDeviceRecord(
    val address: String,
    val name: String,
    val lastSeenMs: Long
)

data class SyncSettings(
    val vibrateEnabled: Boolean = true,
    val popupAlertsEnabled: Boolean = true,
    val singleDrawerCard: Boolean = true,
    val syncPreExisting: Boolean = false,
    val syncDismissToIphone: Boolean = true,
    val discoverableEnabled: Boolean = true
)

class DeviceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wearsync_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACTIVE_ADDRESS = "active_device_address"
        private const val KEY_SAVED_DEVICES = "saved_devices_json"
        private const val KEY_HIDDEN_ADDRESSES = "hidden_bonded_addresses"
        private const val KEY_APP_NAME_PREFIX = "app_name_"
        private const val KEY_VIBRATE = "pref_vibrate"
        private const val KEY_POPUP_ALERTS = "pref_popup_alerts"
        private const val KEY_SINGLE_DRAWER_CARD = "pref_single_drawer_card"
        private const val KEY_SYNC_PRE_EXISTING = "pref_sync_pre_existing"
        private const val KEY_SYNC_DISMISS = "pref_sync_dismiss"
        private const val KEY_DISCOVERABLE = "pref_discoverable"
    }

    fun getActiveDeviceAddress(): String? = prefs.getString(KEY_ACTIVE_ADDRESS, null)

    fun setActiveDeviceAddress(address: String?) {
        prefs.edit().putString(KEY_ACTIVE_ADDRESS, address).apply()
    }

    fun getSavedDevices(): List<SavedDeviceRecord> {
        val raw = prefs.getString(KEY_SAVED_DEVICES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        SavedDeviceRecord(
                            address = obj.getString("address"),
                            name = obj.getString("name"),
                            lastSeenMs = obj.optLong("lastSeenMs", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsertSavedDevice(address: String, name: String) {
        unhideAddress(address)
        val current = getSavedDevices().toMutableList()
        current.removeAll { it.address.equals(address, ignoreCase = true) }
        current.add(
            0,
            SavedDeviceRecord(
                address = address,
                name = name.ifBlank { "iPhone" },
                lastSeenMs = System.currentTimeMillis()
            )
        )
        val arr = JSONArray()
        current.take(8).forEach { rec ->
            arr.put(
                JSONObject().apply {
                    put("address", rec.address)
                    put("name", rec.name)
                    put("lastSeenMs", rec.lastSeenMs)
                }
            )
        }
        prefs.edit().putString(KEY_SAVED_DEVICES, arr.toString()).apply()
    }

    fun removeSavedDevice(address: String) {
        val current = getSavedDevices().filterNot { it.address.equals(address, ignoreCase = true) }
        val arr = JSONArray()
        current.forEach { rec ->
            arr.put(
                JSONObject().apply {
                    put("address", rec.address)
                    put("name", rec.name)
                    put("lastSeenMs", rec.lastSeenMs)
                }
            )
        }
        prefs.edit().putString(KEY_SAVED_DEVICES, arr.toString()).apply()
        if (getActiveDeviceAddress().equals(address, ignoreCase = true)) {
            setActiveDeviceAddress(null)
        }
        hideAddress(address)
    }

    fun getHiddenAddresses(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_ADDRESSES, emptySet())?.map { it.uppercase() }?.toSet()
            ?: emptySet()

    fun hideAddress(address: String) {
        val next = getHiddenAddresses().toMutableSet()
        next.add(address.uppercase())
        prefs.edit().putStringSet(KEY_HIDDEN_ADDRESSES, next).apply()
    }

    fun unhideAddress(address: String) {
        val current = getHiddenAddresses()
        if (address.uppercase() in current) {
            val next = current.toMutableSet()
            next.remove(address.uppercase())
            prefs.edit().putStringSet(KEY_HIDDEN_ADDRESSES, next).apply()
        }
    }

    fun getCachedAppName(appIdentifier: String): String? =
        prefs.getString("$KEY_APP_NAME_PREFIX$appIdentifier", null)

    fun cacheAppName(appIdentifier: String, displayName: String) {
        if (appIdentifier.isNotBlank() && displayName.isNotBlank()) {
            prefs.edit().putString("$KEY_APP_NAME_PREFIX$appIdentifier", displayName).apply()
        }
    }

    fun getSettings(): SyncSettings = SyncSettings(
        vibrateEnabled = prefs.getBoolean(KEY_VIBRATE, true),
        popupAlertsEnabled = prefs.getBoolean(KEY_POPUP_ALERTS, true),
        // Enforce single unstacked drawer notification and live alerts only (no settings drawer panel)
        singleDrawerCard = true,
        syncPreExisting = false,
        syncDismissToIphone = prefs.getBoolean(KEY_SYNC_DISMISS, true),
        discoverableEnabled = prefs.getBoolean(KEY_DISCOVERABLE, true)
    )

    fun updateSettings(settings: SyncSettings) {
        prefs.edit()
            .putBoolean(KEY_VIBRATE, settings.vibrateEnabled)
            .putBoolean(KEY_POPUP_ALERTS, settings.popupAlertsEnabled)
            .putBoolean(KEY_SINGLE_DRAWER_CARD, settings.singleDrawerCard)
            .putBoolean(KEY_SYNC_PRE_EXISTING, settings.syncPreExisting)
            .putBoolean(KEY_SYNC_DISMISS, settings.syncDismissToIphone)
            .putBoolean(KEY_DISCOVERABLE, settings.discoverableEnabled)
            .apply()
    }
}
