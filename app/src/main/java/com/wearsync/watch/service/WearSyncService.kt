package com.wearsync.watch.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wearsync.watch.R
import com.wearsync.watch.ancs.AncsCategoryId
import com.wearsync.watch.ancs.AncsEventId
import com.wearsync.watch.ancs.AncsNotification
import com.wearsync.watch.ancs.ConnectionStatus
import com.wearsync.watch.ble.AncsBleManager
import com.wearsync.watch.ble.BleAdvertiserManager
import com.wearsync.watch.data.DeviceStore
import com.wearsync.watch.data.SyncSettings
import com.wearsync.watch.notifications.WatchNotificationDispatcher
import com.wearsync.watch.presentation.NotificationAlertActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class WearSyncService : LifecycleService() {

    private val TAG = "WearSyncService"

    lateinit var deviceStore: DeviceStore
        private set
    lateinit var bleManager: AncsBleManager
        private set
    private lateinit var advertiserManager: BleAdvertiserManager
    private lateinit var notificationDispatcher: WatchNotificationDispatcher
    private var systemNm: NotificationManager? = null
    private var cachedVibrator: Vibrator? = null
    private var alertWakeLock: PowerManager.WakeLock? = null
    @Volatile private var activeCallUid: Int? = null

    private val _settings = MutableStateFlow(SyncSettings())
    val settings = _settings.asStateFlow()

    private val _recentNotifications = MutableStateFlow<List<AncsNotification>>(emptyList())
    val recentNotifications = _recentNotifications.asStateFlow()

    private val _popupAlerts = MutableStateFlow<List<AncsNotification>>(emptyList())
    val popupAlerts = _popupAlerts.asStateFlow()

    companion object {
        var activeInstance: WearSyncService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, WearSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WearSyncService created")
        activeInstance = this

        deviceStore = DeviceStore(this)
        _settings.value = deviceStore.getSettings()
        systemNm = getSystemService(NotificationManager::class.java)
        cachedVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        @Suppress("DEPRECATION")
        alertWakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WearSync:AlertWake"
        )?.apply {
            setReferenceCounted(false)
        }

        notificationDispatcher = WatchNotificationDispatcher(this)
        notificationDispatcher.ensureChannels()
        startForegroundCompat()

        bleManager = AncsBleManager(this, lifecycleScope, deviceStore)
        advertiserManager = BleAdvertiserManager(this) { remoteDevice ->
            // When an iPhone connects to the watch GATT server from iPhone Settings -> Bluetooth,
            // attach our ANCS GATT client to pull notifications from that iPhone.
            lifecycleScope.launch {
                bleManager.onRemoteConnectedToWatchServer(remoteDevice)
            }
        }

        if (_settings.value.discoverableEnabled) {
            advertiserManager.startAdvertising()
        }
        bleManager.tryAutoReconnectLastDevice()

        observeConnectionState()
        observeIncomingNotifications()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val initialNotif = notificationDispatcher.buildServiceNotification(
            getString(R.string.service_waiting)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    WatchNotificationDispatcher.SERVICE_NOTIFICATION_ID,
                    initialNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } catch (e: Exception) {
                Log.w(TAG, "Fallback startForeground without type: ${e.message}")
                startForeground(WatchNotificationDispatcher.SERVICE_NOTIFICATION_ID, initialNotif)
            }
        } else {
            startForeground(WatchNotificationDispatcher.SERVICE_NOTIFICATION_ID, initialNotif)
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            combine(
                bleManager.connectionState,
                bleManager.activeDeviceName
            ) { state, activeName ->
                state to (activeName?.takeIf { it.isNotBlank() } ?: "iPhone")
            }.collectLatest { (state, name) ->
                if (state == ConnectionStatus.CONNECTED) {
                    advertiserManager.pauseBeacons()
                } else if (state == ConnectionStatus.DISCONNECTED && _settings.value.discoverableEnabled) {
                    advertiserManager.startAdvertising()
                }
                val text = when (state) {
                    ConnectionStatus.CONNECTED -> getString(R.string.service_connected, name)
                    ConnectionStatus.CONNECTING,
                    ConnectionStatus.DISCOVERING_SERVICES,
                    ConnectionStatus.PAIRING -> "Connecting to $name…"
                    ConnectionStatus.SCANNING -> "Scanning for iPhone…"
                    ConnectionStatus.DISCONNECTED -> getString(R.string.service_waiting)
                }
                notificationDispatcher.updateServiceNotification(text)
            }
        }
    }

    private fun observeIncomingNotifications() {
        lifecycleScope.launch {
            bleManager.notificationsFlow.collect { notif ->
                if (notif.eventId == AncsEventId.REMOVED) {
                    stopCallRinging(notif.uid)
                    notificationDispatcher.dispatch(notif, _settings.value)
                    _recentNotifications.value = _recentNotifications.value.filterNot { it.uid == notif.uid }
                    _popupAlerts.value = _popupAlerts.value.filterNot { it.uid == notif.uid }
                } else {
                    val posted = notificationDispatcher.dispatch(notif, _settings.value)
                    if (posted) {
                        val updated = (_recentNotifications.value.filterNot { it.uid == notif.uid } + notif)
                            .sortedByDescending { it.timestamp }
                            .take(15)
                        _recentNotifications.value = updated

                        if (!notif.eventFlags.isPreExisting && !notif.eventFlags.isSilent) {
                            triggerAlertFeedback(notif)
                            if (_settings.value.popupAlertsEnabled || notif.categoryId == AncsCategoryId.INCOMING_CALL) {
                                showPopupCard(notif)
                            }
                        }
                    }
                }
            }
        }
    }

    fun updateSettings(newSettings: SyncSettings) {
        val prev = _settings.value
        _settings.value = newSettings
        deviceStore.updateSettings(newSettings)
        if (!prev.singleDrawerCard && newSettings.singleDrawerCard) {
            notificationDispatcher.enforceSingleDrawerCard()
        }
        if (newSettings.discoverableEnabled) {
            advertiserManager.startAdvertising()
        } else {
            advertiserManager.stopAdvertising()
        }
    }

    fun sendTestNotification() {
        val testNotif = notificationDispatcher.dispatchTestNotification(_settings.value)
        _recentNotifications.value = (listOf(testNotif) + _recentNotifications.value).take(15)
        if (_settings.value.popupAlertsEnabled) {
            showPopupCard(testNotif)
        }
    }

    private fun triggerAlertFeedback(notif: AncsNotification) {
        val filter = systemNm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) {
            return
        }

        val isIncomingCall = notif.categoryId == AncsCategoryId.INCOMING_CALL
        // Briefly wake watch screen so incoming alerts appear immediately like native watch alerts
        try {
            alertWakeLock?.acquire(if (isIncomingCall) 30_000L else 3_500L)
        } catch (_: Exception) {
        }

        if (!_settings.value.vibrateEnabled) return

        try {
            if (isIncomingCall) {
                activeCallUid = notif.uid
                val ringPattern = longArrayOf(0, 400, 300, 400)
                cachedVibrator?.vibrate(VibrationEffect.createWaveform(ringPattern, 0))
            } else {
                val pattern = longArrayOf(0, 180, 90, 180)
                cachedVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        } catch (_: Exception) {
        }
    }

    private fun stopCallRinging(uid: Int? = null) {
        if (uid != null && activeCallUid != uid) return
        if (activeCallUid == null && uid != null) return
        activeCallUid = null
        try {
            cachedVibrator?.cancel()
        } catch (_: Exception) {
        }
        try {
            if (alertWakeLock?.isHeld == true) {
                alertWakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun showPopupCard(notif: AncsNotification) {
        _popupAlerts.value = (listOf(notif) + _popupAlerts.value.filterNot { it.uid == notif.uid }).take(8)
        try {
            val intent = Intent(this, NotificationAlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch popup card directly: ${e.message}")
        }
    }

    fun dismissPopupAlert(uid: Int) {
        dismissNotification(uid)
    }

    fun hidePopupAlert(uid: Int) {
        _popupAlerts.value = _popupAlerts.value.filterNot { it.uid == uid }
    }

    fun dismissNotification(uid: Int) {
        stopCallRinging(uid)
        val target = _popupAlerts.value.firstOrNull { it.uid == uid }
            ?: _recentNotifications.value.firstOrNull { it.uid == uid }
        _popupAlerts.value = _popupAlerts.value.filterNot { it.uid == uid }
        _recentNotifications.value = _recentNotifications.value.filterNot { it.uid == uid }
        notificationDispatcher.cancelByUid(uid)
        if (target != null && _settings.value.syncDismissToIphone && target.eventFlags.hasNegativeAction) {
            bleManager.performNotificationAction(uid, false)
        }
    }

    fun clearPopupAlerts() {
        stopCallRinging()
        _popupAlerts.value = emptyList()
    }

    fun clearAllNotifications() {
        notificationDispatcher.cancelAllMirrored()
        _recentNotifications.value = emptyList()
        _popupAlerts.value = emptyList()
    }

    fun performNotificationAction(uid: Int, isPositive: Boolean) {
        stopCallRinging(uid)
        bleManager.performNotificationAction(uid, isPositive)
        notificationDispatcher.cancelByUid(uid)
        _popupAlerts.value = _popupAlerts.value.filterNot { it.uid == uid }
        _recentNotifications.value = _recentNotifications.value.filterNot { it.uid == uid }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "WearSyncService stopping")
        stopCallRinging()
        advertiserManager.stopAdvertising()
        bleManager.shutdown()
        activeInstance = null
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
