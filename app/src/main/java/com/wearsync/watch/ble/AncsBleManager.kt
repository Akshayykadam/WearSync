package com.wearsync.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.wearsync.watch.ams.AmsConstants
import com.wearsync.watch.ams.AmsParser
import com.wearsync.watch.ams.AmsRemoteCommand
import com.wearsync.watch.ams.MediaPlaybackState
import com.wearsync.watch.ancs.*
import com.wearsync.watch.complication.PhoneBatteryComplicationService
import com.wearsync.watch.data.DeviceStore
import com.wearsync.watch.tile.MusicControlTileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class AncsBleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deviceStore: DeviceStore
) {
    private val TAG = "AncsBleManager"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    private val gattQueue = GattCommandQueue(scope)
    private val ancsParser = AncsParser()
    private val amsParser = AmsParser()

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var activeDevice: BluetoothDevice? = null
    @Volatile private var userRequestedDisconnect = false
    private var lastDirectConnectMs = 0L
    private var serviceRetryCount = 0
    private var serviceRetryJob: Job? = null
    private var reconnectAttemptCount = 0
    private var reconnectJob: Job? = null

    // ANCS characteristics
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var ancsServiceMissing = false

    // AMS (Apple Media Service) characteristics
    private var amsRemoteCommandChar: BluetoothGattCharacteristic? = null
    private var amsEntityUpdateChar: BluetoothGattCharacteristic? = null
    private var amsEntityAttributeChar: BluetoothGattCharacteristic? = null
    private var pendingEntityAttrRead: Pair<Byte, Byte>? = null

    // Notification attribute request queue (processed one UID at a time)
    private val pendingAttributeUids = ArrayDeque<AncsNotification>()
    private var activeAttributeRequest: AncsNotification? = null
    private var activeAppNameRequest: String? = null
    private var attributeTimeoutJob: Job? = null
    private val pendingAppNameQueue = ArrayDeque<String>()
    private val requestedAppIdsInSession = hashSetOf<String>()

    // Scanned BLE candidates keyed by address
    private val discoveredDevices = ConcurrentHashMap<String, DeviceUiItem>()
    private var scanTimeoutJob: Job? = null
    private var isScanning = false

    // Deduplicate identical Notification Source BLE packets fired across Binder threads
    private var lastNsPacketHex: String? = null
    private var lastNsPacketTimeMs: Long = 0L

    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _activeDeviceName = MutableStateFlow<String?>(null)
    val activeDeviceName = _activeDeviceName.asStateFlow()

    private val _activeDeviceAddress = MutableStateFlow<String?>(null)
    val activeDeviceAddress = _activeDeviceAddress.asStateFlow()

    private val _ancsReady = MutableStateFlow(false)
    val ancsReady = _ancsReady.asStateFlow()

    private val _statusHint = MutableStateFlow<String?>(null)
    val statusHint = _statusHint.asStateFlow()

    private val _devicesList = MutableStateFlow<List<DeviceUiItem>>(emptyList())
    val devicesList = _devicesList.asStateFlow()

    private val _notificationsFlow = MutableSharedFlow<AncsNotification>(extraBufferCapacity = 64)
    val notificationsFlow = _notificationsFlow.asSharedFlow()

    private val _alertsSyncedCount = MutableStateFlow(0)
    val alertsSyncedCount = _alertsSyncedCount.asStateFlow()

    private val _mediaState = MutableStateFlow(MediaPlaybackState())
    val mediaState = _mediaState.asStateFlow()

    private val _phoneBatteryLevel = MutableStateFlow<Int?>(null)
    val phoneBatteryLevel = _phoneBatteryLevel.asStateFlow()

    init {
        loadInitialSavedAndBondedDevices()
        setupBondListeners()
    }

    private fun setupBondListeners() {
        BondStateReceiver.onDeviceBonded = { device ->
            if (device != null) {
                val name = resolvePreferredDeviceName(device.address, safeDeviceNameOrNull(device))
                deviceStore.upsertSavedDevice(device.address, name)
                refreshDevicesList()

                if (activeDevice?.address.equals(device.address, ignoreCase = true) ||
                    _connectionState.value == ConnectionStatus.PAIRING
                ) {
                    Log.i(TAG, "Bond completed for $name, connecting/subscribing to ANCS")
                    scope.launch {
                        delay(400)
                        val gatt = bluetoothGatt
                        if (gatt != null && gatt.device.address.equals(device.address, ignoreCase = true)) {
                            _connectionState.value = ConnectionStatus.DISCOVERING_SERVICES
                            gattQueue.enqueue("discoverServices-postBond") {
                                gatt.discoverServices()
                            }
                        } else {
                            connectToDevice(device, autoConnect = true)
                        }
                    }
                }
            }
        }

        BondStateReceiver.onBondFailed = { device ->
            if (device != null && activeDevice?.address.equals(device.address, ignoreCase = true)) {
                _statusHint.value = "Pairing cancelled"
                _connectionState.value = ConnectionStatus.DISCONNECTED
                refreshDevicesList()
            }
        }

        BondStateReceiver.onAclConnected = { device ->
            if (device != null && isBondedDevice(device)) {
                val activeAddr = deviceStore.getActiveDeviceAddress()
                val matchesActive = activeAddr?.equals(device.address, ignoreCase = true) == true
                val looksLikeApple = isAppleDeviceName(safeDeviceNameOrNull(device))
                if (matchesActive || (activeAddr == null && looksLikeApple)) {
                    val state = _connectionState.value
                    val recentlyStartedDirect = (System.currentTimeMillis() - lastDirectConnectMs) < 3_500L
                    if (!userRequestedDisconnect && !recentlyStartedDirect &&
                        (state == ConnectionStatus.DISCONNECTED || state == ConnectionStatus.CONNECTING)
                    ) {
                        Log.i(TAG, "Bonded iPhone ACL connected (${safeDeviceName(device)}), attaching ANCS directly")
                        connectToDevice(device, autoConnect = false)
                    }
                }
            }
        }
    }

    fun loadInitialSavedAndBondedDevices() {
        val lastAddr = deviceStore.getActiveDeviceAddress()
        val saved = deviceStore.getSavedDevices()
        if (lastAddr != null) {
            _activeDeviceAddress.value = lastAddr
            _activeDeviceName.value = saved.firstOrNull { it.address.equals(lastAddr, ignoreCase = true) }?.name
        } else if (saved.isNotEmpty()) {
            _activeDeviceAddress.value = saved.first().address
            _activeDeviceName.value = saved.first().name
        }
        refreshDevicesList()
    }

    fun tryAutoReconnectLastDevice() {
        if (userRequestedDisconnect) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        val targetAddr = deviceStore.getActiveDeviceAddress() ?: return
        val bondedMatch = adapter.bondedDevices?.firstOrNull {
            it.address.equals(targetAddr, ignoreCase = true)
        }
        if (bondedMatch != null) {
            val alreadyConnectedOnServer = try {
                bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT_SERVER)
                    ?.any { it.address.equals(bondedMatch.address, ignoreCase = true) } == true
            } catch (_: SecurityException) {
                false
            }
            Log.i(TAG, "Auto-reconnecting to last paired iPhone: ${safeDeviceName(bondedMatch)} (direct=$alreadyConnectedOnServer)")
            connectToDevice(bondedMatch, autoConnect = !alreadyConnectedOnServer)
        }
    }

    fun reconnectActiveDevice() {
        val addr = _activeDeviceAddress.value ?: deviceStore.getActiveDeviceAddress() ?: return
        connectByAddress(addr)
    }

    fun connectByAddress(address: String) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            _statusHint.value = "Turn on Bluetooth"
            return
        }
        val device = adapter.bondedDevices?.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: try {
                adapter.getRemoteDevice(address)
            } catch (_: Exception) {
                null
            }

        if (device == null) {
            _statusHint.value = "Device unavailable"
            return
        }
        userRequestedDisconnect = false
        stopScan()
        connectToDevice(device, autoConnect = false)
    }

    fun startScan(durationMs: Long = 18_000L) {
        val adapter = bluetoothAdapter ?: run {
            _statusHint.value = "Bluetooth unavailable"
            refreshDevicesList()
            return
        }
        if (!adapter.isEnabled) {
            _statusHint.value = "Turn on Bluetooth"
            refreshDevicesList()
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            _statusHint.value = "BLE scanner unavailable"
            refreshDevicesList()
            return
        }

        stopScan()
        discoveredDevices.clear()
        isScanning = true
        if (_connectionState.value == ConnectionStatus.DISCONNECTED) {
            _connectionState.value = ConnectionStatus.SCANNING
        }
        _statusHint.value = null
        refreshDevicesList()

        // Unfiltered BLE scan so we detect Apple Continuity (0x004C) advertisements,
        // since stock iOS does not advertise the ANCS UUID passively without an app.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            isScanning = false
            if (_connectionState.value == ConnectionStatus.SCANNING) {
                _connectionState.value = ConnectionStatus.DISCONNECTED
            }
            return
        }

        scanTimeoutJob = scope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (isScanning) {
            isScanning = false
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
            if (_connectionState.value == ConnectionStatus.SCANNING) {
                _connectionState.value = ConnectionStatus.DISCONNECTED
            }
            refreshDevicesList()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val appleData = record?.getManufacturerSpecificData(AncsConstants.APPLE_MANUFACTURER_ID)
            val hasAncsUuid = record?.serviceUuids?.contains(ParcelUuid(AncsConstants.ANCS_SERVICE_UUID)) == true
            val advertisedName = record?.deviceName ?: safeDeviceNameOrNull(device)

            val isApple = appleData != null || hasAncsUuid ||
                (advertisedName?.contains("iPhone", ignoreCase = true) == true) ||
                (advertisedName?.contains("iPad", ignoreCase = true) == true)

            if (!isApple) return

            // Filter out Apple FindMy / AirPods-only beacons when unnamed so list stays clean
            if (advertisedName.isNullOrBlank() && appleData != null && !isLikelyIphoneContinuityPacket(appleData)) {
                return
            }

            val displayName = advertisedName ?: "Apple iPhone (${device.address.takeLast(5)})"
            val item = DeviceUiItem(
                address = device.address,
                name = displayName,
                isBonded = isBondedDevice(device),
                isAppleCandidate = true,
                rssi = result.rssi,
                status = if (activeDevice?.address.equals(device.address, ignoreCase = true)) {
                    _connectionState.value
                } else {
                    ConnectionStatus.DISCONNECTED
                }
            )
            discoveredDevices[device.address] = item
            refreshDevicesList()
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: error=$errorCode")
            isScanning = false
            if (_connectionState.value == ConnectionStatus.SCANNING) {
                _connectionState.value = ConnectionStatus.DISCONNECTED
            }
            refreshDevicesList()
        }
    }

    /**
     * Apple Continuity frames:
     * 0x10 = Nearby Info (active iPhone/iPad), 0x0C = Handoff, 0x0E = Hotspot/Tethering
     */
    private fun isLikelyIphoneContinuityPacket(appleMfgData: ByteArray): Boolean {
        if (appleMfgData.isEmpty()) return false
        val type = appleMfgData[0].toInt() and 0xFF
        return type == 0x10 || type == 0x0C || type == 0x0E
    }

    fun connectToDevice(device: BluetoothDevice, autoConnect: Boolean = false) {
        val name = safeDeviceName(device)
        userRequestedDisconnect = false
        ancsServiceMissing = false
        serviceRetryCount = 0
        reconnectAttemptCount = 0
        serviceRetryJob?.cancel()
        serviceRetryJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        if (!autoConnect) {
            lastDirectConnectMs = System.currentTimeMillis()
        }
        _ancsReady.value = false
        _statusHint.value = null

        closeCurrentGattOnly()

        val preferredName = resolvePreferredDeviceName(device.address, safeDeviceNameOrNull(device))
        activeDevice = device
        _activeDeviceAddress.value = device.address
        _activeDeviceName.value = preferredName
        deviceStore.setActiveDeviceAddress(device.address)
        deviceStore.upsertSavedDevice(device.address, preferredName)

        if (!isBondedDevice(device)) {
            _connectionState.value = ConnectionStatus.PAIRING
            Log.i(TAG, "Initiating BLE bond with $preferredName [${device.address}]")
            device.createBond()
        } else {
            _connectionState.value = ConnectionStatus.CONNECTING
        }

        refreshDevicesList()

        bluetoothGatt = device.connectGatt(
            context,
            autoConnect,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun disconnect(forgetActive: Boolean = false) {
        userRequestedDisconnect = true
        serviceRetryJob?.cancel()
        serviceRetryJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttemptCount = 0
        stopScan()
        resetPendingAncsQueues(clearSessionApps = forgetActive)
        closeCurrentGattOnly()
        _ancsReady.value = false
        _statusHint.value = null
        _connectionState.value = ConnectionStatus.DISCONNECTED
        if (forgetActive) {
            activeDevice = null
            _activeDeviceAddress.value = null
            _activeDeviceName.value = null
            deviceStore.setActiveDeviceAddress(null)
        }
        refreshDevicesList()
    }

    fun shutdown() {
        BondStateReceiver.onDeviceBonded = null
        BondStateReceiver.onBondFailed = null
        BondStateReceiver.onAclConnected = null
        disconnect(forgetActive = false)
        discoveredDevices.clear()
    }

    fun forgetDevice(address: String) {
        val isActive = activeDevice?.address.equals(address, ignoreCase = true) ||
            _activeDeviceAddress.value.equals(address, ignoreCase = true)
        if (isActive) {
            disconnect(forgetActive = true)
        }
        discoveredDevices.remove(address)
        deviceStore.removeSavedDevice(address)

        try {
            val bonded = bluetoothAdapter?.bondedDevices?.firstOrNull {
                it.address.equals(address, ignoreCase = true)
            }
            if (bonded != null) {
                val removed = bonded.javaClass.getMethod("removeBond").invoke(bonded) as? Boolean
                Log.i(TAG, "removeBond(${safeDeviceName(bonded)}) returned $removed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not call removeBond for $address: ${e.message}")
        }

        refreshDevicesList()
    }

    fun performNotificationAction(uid: Int, isPositive: Boolean) {
        val gatt = bluetoothGatt ?: return
        val cp = controlPointChar ?: return
        val cmd = ancsParser.buildPerformNotificationActionCommand(uid, isPositive)
        gattQueue.enqueue("performAction-$uid") {
            writeCharacteristicCompat(gatt, cp, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    fun sendMediaCommand(command: AmsRemoteCommand) {
        val gatt = bluetoothGatt ?: return
        val rc = amsRemoteCommandChar ?: return
        val writeType = if ((rc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        gattQueue.enqueue("amsCommand-${command.name}") {
            writeCharacteristicCompat(gatt, rc, byteArrayOf(command.id), writeType)
        }
    }

    private fun closeCurrentGattOnly() {
        gattQueue.clear()
        controlPointChar = null
        dataSourceChar = null
        notificationSourceChar = null
        amsRemoteCommandChar = null
        amsEntityUpdateChar = null
        amsEntityAttributeChar = null
        pendingEntityAttrRead = null
        _mediaState.value = MediaPlaybackState()
        _phoneBatteryLevel.value = null
        deviceStore.setPhoneBatteryLevel(null)
        PhoneBatteryComplicationService.requestUpdate(context)
        MusicControlTileService.requestUpdate(context)
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        try {
            val refresh = gatt.javaClass.getMethod("refresh")
            val result = refresh.invoke(gatt) as? Boolean
            Log.d(TAG, "BluetoothGatt.refresh() -> $result")
        } catch (e: Exception) {
            Log.d(TAG, "BluetoothGatt.refresh() unavailable: ${e.message}")
        }
    }

    @Synchronized
    private fun resetPendingAncsQueues(clearSessionApps: Boolean = false) {
        attributeTimeoutJob?.cancel()
        attributeTimeoutJob = null
        pendingAttributeUids.clear()
        pendingAppNameQueue.clear()
        if (clearSessionApps) {
            requestedAppIdsInSession.clear()
        }
        activeAttributeRequest = null
        activeAppNameRequest = null
        ancsParser.resetAssembly()
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val devName = safeDeviceName(gatt.device)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (bluetoothGatt !== gatt) {
                        Log.d(TAG, "Ignoring stale GATT connected callback for $devName")
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                        return
                    }
                    reconnectAttemptCount = 0
                    reconnectJob?.cancel()
                    reconnectJob = null
                    Log.i(TAG, "GATT connected to $devName (status=$status)")
                    val resolvedName = resolvePreferredDeviceName(
                        gatt.device.address,
                        safeDeviceNameOrNull(gatt.device)
                    )
                    _activeDeviceName.value = resolvedName
                    deviceStore.upsertSavedDevice(gatt.device.address, resolvedName)
                    _connectionState.value = ConnectionStatus.DISCOVERING_SERVICES
                    refreshDevicesList()

                    gattQueue.enqueue("requestMtu") {
                        gatt.requestMtu(512)
                    }
                    gattQueue.enqueue("discoverServices") {
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val isCurrentGatt = bluetoothGatt === gatt
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    if (!isCurrentGatt) {
                        Log.d(TAG, "Ignoring stale GATT disconnect callback for $devName (status=$status)")
                        return
                    }

                    Log.w(TAG, "GATT disconnected from $devName (status=$status)")
                    bluetoothGatt = null
                    serviceRetryJob?.cancel()
                    serviceRetryJob = null
                    gattQueue.clear()
                    resetPendingAncsQueues()
                    controlPointChar = null
                    dataSourceChar = null
                    notificationSourceChar = null
                    _ancsReady.value = false

                    _connectionState.value = ConnectionStatus.DISCONNECTED
                    refreshDevicesList()

                    // If user did not explicitly disconnect and device is bonded, re-attach autoConnect with backoff
                    if (!userRequestedDisconnect &&
                        isBondedDevice(gatt.device) &&
                        bluetoothAdapter?.isEnabled == true
                    ) {
                        val backoffMs = minOf(30_000L, 2_000L shl minOf(reconnectAttemptCount, 4))
                        reconnectAttemptCount += 1
                        reconnectJob?.cancel()
                        reconnectJob = scope.launch {
                            delay(backoffMs)
                            if (!userRequestedDisconnect &&
                                bluetoothGatt == null &&
                                bluetoothAdapter.isEnabled &&
                                activeDevice?.address.equals(gatt.device.address, ignoreCase = true)
                            ) {
                                Log.i(TAG, "Re-arming background autoConnect for $devName (backoff=${backoffMs}ms)")
                                bluetoothGatt = gatt.device.connectGatt(
                                    context,
                                    true,
                                    this@AncsBleManager.gattCallback,
                                    BluetoothDevice.TRANSPORT_LE
                                )
                            }
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            gattQueue.completeCurrent(status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            gattQueue.completeCurrent(status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: status=$status")
                return
            }
            setupAncsSubscriptions(gatt)
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            Log.i(TAG, "GATT Service Changed reported by iPhone; re-discovering services")
            gattQueue.enqueue("discoverServices-onChange") {
                gatt.discoverServices()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            gattQueue.completeCurrent(status)
            val charUuid = descriptor.characteristic?.uuid
            Log.d(TAG, "CCCD write complete for $charUuid status=$status")

            if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ||
                status == 137 // GATT_AUTH_FAIL
            ) {
                Log.w(TAG, "ANCS requires encryption/pairing (status=$status)")
                if (!isBondedDevice(gatt.device)) {
                    _connectionState.value = ConnectionStatus.PAIRING
                    _statusHint.value = "Accept pairing on iPhone"
                    gatt.device.createBond()
                    refreshDevicesList()
                }
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS && charUuid == AncsConstants.NOTIFICATION_SOURCE_UUID) {
                reconnectAttemptCount = 0
                _ancsReady.value = true
                _statusHint.value = null
                _connectionState.value = ConnectionStatus.CONNECTED
                refreshDevicesList()
                PhoneBatteryComplicationService.requestUpdate(context)
                MusicControlTileService.requestUpdate(context)
                Log.i(TAG, "ANCS fully subscribed and ready!")
            }

            if (status == BluetoothGatt.GATT_SUCCESS && charUuid == AmsConstants.ENTITY_UPDATE_UUID) {
                _mediaState.value = _mediaState.value.copy(isAvailable = true)
                registerAmsEntityUpdates(gatt)
                Log.i(TAG, "AMS Entity Update subscribed; registering Player & Track attributes")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            gattQueue.completeCurrent(status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value?.copyOf() ?: ByteArray(0)
            handleCharacteristicRead(gatt, characteristic.uuid, bytes, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic.uuid, value.copyOf(), status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value?.copyOf() ?: return
            handleCharacteristicUpdate(characteristic.uuid, bytes)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicUpdate(characteristic.uuid, value.copyOf())
        }
    }

    private fun setupAncsSubscriptions(gatt: BluetoothGatt) {
        val allServices = gatt.services.joinToString { it.uuid.toString() }
        Log.i(TAG, "Discovered GATT services on ${safeDeviceName(gatt.device)}: [$allServices]")

        val ancsService = gatt.getService(AncsConstants.ANCS_SERVICE_UUID)
        if (ancsService == null) {
            if (serviceRetryCount < 3) {
                serviceRetryCount += 1
                val delayMs = serviceRetryCount * 1_500L
                Log.i(TAG, "ANCS service not ready yet; retrying discovery #$serviceRetryCount in ${delayMs}ms")
                _connectionState.value = ConnectionStatus.DISCOVERING_SERVICES
                if (gatt.services.isEmpty() || serviceRetryCount >= 2) {
                    refreshGattCache(gatt)
                }
                serviceRetryJob?.cancel()
                serviceRetryJob = scope.launch {
                    delay(delayMs)
                    if (bluetoothGatt === gatt) {
                        gattQueue.enqueue("discoverServices-retry-$serviceRetryCount") {
                            gatt.discoverServices()
                        }
                    }
                }
                return
            }

            ancsServiceMissing = true
            _ancsReady.value = false
            _connectionState.value = ConnectionStatus.CONNECTED
            _statusHint.value = "Enable 'Share System Notifications' on iPhone"
            refreshDevicesList()
            Log.w(TAG, "ANCS service not exposed yet. Check iPhone Bluetooth -> (i) -> Share System Notifications.")
            return
        }

        serviceRetryJob?.cancel()
        serviceRetryJob = null
        serviceRetryCount = 0

        ancsServiceMissing = false
        controlPointChar = ancsService.getCharacteristic(AncsConstants.CONTROL_POINT_UUID)
        dataSourceChar = ancsService.getCharacteristic(AncsConstants.DATA_SOURCE_UUID)
        notificationSourceChar = ancsService.getCharacteristic(AncsConstants.NOTIFICATION_SOURCE_UUID)

        Log.i(
            TAG,
            "ANCS chars: cp=${controlPointChar != null} ds=${dataSourceChar != null} ns=${notificationSourceChar != null}"
        )

        // Per Apple ANCS spec: subscribe to Data Source BEFORE Notification Source
        val ds = dataSourceChar
        val ns = notificationSourceChar
        if (ds != null) {
            enqueueEnableCccd(gatt, ds, "subscribe-DataSource")
        }
        if (ns != null) {
            enqueueEnableCccd(gatt, ns, "subscribe-NotificationSource")
        }

        // Read GAP Device Name (0x1800 -> 0x2A00) so we display the iPhone's actual name (e.g. "John's iPhone")
        val gapNameChar = gatt.getService(AncsConstants.GAP_SERVICE_UUID)
            ?.getCharacteristic(AncsConstants.GAP_DEVICE_NAME_CHAR_UUID)
        if (gapNameChar != null) {
            gattQueue.enqueue("readGapDeviceName") {
                gatt.readCharacteristic(gapNameChar)
            }
        }

        setupAmsSubscriptions(gatt)
        setupBatterySubscription(gatt)
    }

    private fun setupBatterySubscription(gatt: BluetoothGatt) {
        val batChar = gatt.getService(AncsConstants.BATTERY_SERVICE_UUID)
            ?.getCharacteristic(AncsConstants.BATTERY_LEVEL_CHAR_UUID)
            ?: return
        gattQueue.enqueue("readPhoneBattery") {
            gatt.readCharacteristic(batChar)
        }
        if (batChar.getDescriptor(AncsConstants.CCCD_UUID) != null) {
            enqueueEnableCccd(gatt, batChar, "subscribe-PhoneBattery")
        }
    }

    private fun setupAmsSubscriptions(gatt: BluetoothGatt) {
        val amsService = gatt.getService(AmsConstants.AMS_SERVICE_UUID)
        if (amsService == null) {
            Log.d(TAG, "AMS service not exposed on ${safeDeviceName(gatt.device)}")
            return
        }

        amsRemoteCommandChar = amsService.getCharacteristic(AmsConstants.REMOTE_COMMAND_UUID)
        amsEntityUpdateChar = amsService.getCharacteristic(AmsConstants.ENTITY_UPDATE_UUID)
        amsEntityAttributeChar = amsService.getCharacteristic(AmsConstants.ENTITY_ATTRIBUTE_UUID)

        Log.i(
            TAG,
            "AMS chars: rc=${amsRemoteCommandChar != null} eu=${amsEntityUpdateChar != null} ea=${amsEntityAttributeChar != null}"
        )

        amsEntityUpdateChar?.let { eu ->
            enqueueEnableCccd(gatt, eu, "subscribe-AmsEntityUpdate")
        }
        amsRemoteCommandChar?.let { rc ->
            enqueueEnableCccd(gatt, rc, "subscribe-AmsRemoteCommand")
        }
    }

    private fun registerAmsEntityUpdates(gatt: BluetoothGatt) {
        val eu = amsEntityUpdateChar ?: return
        val playerCmd = amsParser.buildRegisterEntityUpdateCommand(
            AmsConstants.ENTITY_PLAYER,
            AmsConstants.PLAYER_ATTR_NAME,
            AmsConstants.PLAYER_ATTR_PLAYBACK_INFO,
            AmsConstants.PLAYER_ATTR_VOLUME
        )
        val trackCmd = amsParser.buildRegisterEntityUpdateCommand(
            AmsConstants.ENTITY_TRACK,
            AmsConstants.TRACK_ATTR_ARTIST,
            AmsConstants.TRACK_ATTR_ALBUM,
            AmsConstants.TRACK_ATTR_TITLE,
            AmsConstants.TRACK_ATTR_DURATION
        )
        val queueCmd = amsParser.buildRegisterEntityUpdateCommand(
            AmsConstants.ENTITY_QUEUE,
            AmsConstants.QUEUE_ATTR_SHUFFLE_MODE,
            AmsConstants.QUEUE_ATTR_REPEAT_MODE
        )
        gattQueue.enqueue("amsRegisterPlayer") {
            writeCharacteristicCompat(gatt, eu, playerCmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        gattQueue.enqueue("amsRegisterTrack") {
            writeCharacteristicCompat(gatt, eu, trackCmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        gattQueue.enqueue("amsRegisterQueue") {
            writeCharacteristicCompat(gatt, eu, queueCmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        uuid: UUID,
        data: ByteArray,
        status: Int
    ) {
        gattQueue.completeCurrent(status)
        if (status == BluetoothGatt.GATT_SUCCESS && uuid == AncsConstants.GAP_DEVICE_NAME_CHAR_UUID) {
            val resolvedName = data.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            if (resolvedName.isNotBlank()) {
                Log.i(TAG, "Resolved remote device name via GAP: $resolvedName")
                if (activeDevice?.address.equals(gatt.device.address, ignoreCase = true) ||
                    _activeDeviceAddress.value.equals(gatt.device.address, ignoreCase = true)
                ) {
                    _activeDeviceName.value = resolvedName
                }
                deviceStore.upsertSavedDevice(gatt.device.address, resolvedName)
                refreshDevicesList()
            }
        } else if (status == BluetoothGatt.GATT_SUCCESS && uuid == AmsConstants.ENTITY_ATTRIBUTE_UUID) {
            val target = pendingEntityAttrRead
            pendingEntityAttrRead = null
            if (target != null) {
                val fullValue = data.toString(Charsets.UTF_8)
                _mediaState.value = amsParser.applyEntityUpdate(
                    current = _mediaState.value,
                    entityId = target.first,
                    attributeId = target.second,
                    value = fullValue
                )
                MusicControlTileService.requestUpdate(context)
            }
        } else if (status == BluetoothGatt.GATT_SUCCESS && uuid == AncsConstants.BATTERY_LEVEL_CHAR_UUID) {
            updatePhoneBatteryFromBytes(data)
        }
    }

    private fun updatePhoneBatteryFromBytes(data: ByteArray) {
        if (data.isEmpty()) return
        val level = (data[0].toInt() and 0xFF).coerceIn(0, 100)
        if (_phoneBatteryLevel.value != level) {
            _phoneBatteryLevel.value = level
            deviceStore.setPhoneBatteryLevel(level)
            PhoneBatteryComplicationService.requestUpdate(context)
            Log.d(TAG, "iPhone battery level: $level%")
        }
    }

    fun onRemoteConnectedToWatchServer(device: BluetoothDevice) {
        val sameDevice = activeDevice?.address.equals(device.address, ignoreCase = true)
        val state = _connectionState.value
        val recentlyStartedDirect = (System.currentTimeMillis() - lastDirectConnectMs) < 3_500L
        if (sameDevice && (
                recentlyStartedDirect ||
                    state == ConnectionStatus.CONNECTED ||
                    state == ConnectionStatus.DISCOVERING_SERVICES ||
                    state == ConnectionStatus.PAIRING
                )
        ) {
            Log.d(TAG, "Watch GATT server connected by active iPhone ($state); keeping existing ANCS link")
            return
        }
        Log.i(TAG, "Watch GATT server connected by iPhone while state=$state; attaching ANCS directly")
        connectToDevice(device, autoConnect = false)
    }

    private fun enqueueEnableCccd(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        opName: String
    ) {
        gattQueue.enqueue(opName) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(AncsConstants.CCCD_UUID)
                ?: return@enqueue false
            writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    @Synchronized
    private fun handleCharacteristicUpdate(uuid: UUID, data: ByteArray) {
        when (uuid) {
            AncsConstants.NOTIFICATION_SOURCE_UUID -> {
                val hex = data.joinToString("") { "%02X".format(it) }
                val now = System.currentTimeMillis()
                if (hex == lastNsPacketHex && (now - lastNsPacketTimeMs) < 500L) {
                    Log.d(TAG, "Skipping duplicate ANCS NotificationSource packet: $hex")
                    return
                }
                lastNsPacketHex = hex
                lastNsPacketTimeMs = now

                val notif = ancsParser.parseNotificationSource(data) ?: return
                Log.i(
                    TAG,
                    "ANCS event=${notif.eventId} uid=${notif.uid} cat=${notif.categoryId} flags=0x${notif.eventFlags.raw.toString(16)}"
                )
                if (notif.eventId == AncsEventId.REMOVED) {
                    pendingAttributeUids.removeAll { it.uid == notif.uid }
                    if (activeAttributeRequest?.uid == notif.uid) {
                        abortActiveAttributeRequest()
                    }
                    _notificationsFlow.tryEmit(notif)
                } else {
                    // Replace any queued duplicate/pending request for the same UID
                    pendingAttributeUids.removeAll { it.uid == notif.uid }
                    pendingAttributeUids.addLast(notif)
                    pumpNextControlPointRequest()
                }
            }

            AncsConstants.DATA_SOURCE_UUID -> {
                Log.d(TAG, "ANCS DataSource chunk (${data.size}B) for uid=${activeAttributeRequest?.uid}")
                when (val result = ancsParser.appendDataSourceChunk(data, activeAttributeRequest)) {
                    is DataSourceParseResult.Incomplete -> {
                        // Wait for remaining BLE chunks
                    }
                    is DataSourceParseResult.NotificationAttributesReady -> {
                        attributeTimeoutJob?.cancel()
                        attributeTimeoutJob = null
                        val completed = result.notification
                        activeAttributeRequest = null
                        ancsParser.resetAssembly()

                        val appId = completed.appIdentifier
                        val cachedName = deviceStore.getCachedAppName(appId)
                        completed.appDisplayName = AncsConstants.friendlyAppName(
                            appIdentifier = appId,
                            cachedName = cachedName,
                            categoryId = completed.categoryId
                        )

                        if (appId.isNotBlank() &&
                            cachedName == null &&
                            !AncsConstants.KNOWN_APP_NAMES.containsKey(appId) &&
                            requestedAppIdsInSession.add(appId)
                        ) {
                            pendingAppNameQueue.addLast(appId)
                        }

                        _alertsSyncedCount.value += 1
                        _notificationsFlow.tryEmit(completed)
                        pumpNextControlPointRequest()
                    }
                    is DataSourceParseResult.AppAttributesReady -> {
                        attributeTimeoutJob?.cancel()
                        attributeTimeoutJob = null
                        activeAppNameRequest = null
                        ancsParser.resetAssembly()
                        deviceStore.cacheAppName(result.appIdentifier, result.displayName)
                        Log.d(TAG, "Cached iOS app name: ${result.appIdentifier} -> ${result.displayName}")
                        pumpNextControlPointRequest()
                    }
                }
            }

            AmsConstants.REMOTE_COMMAND_UUID -> {
                val cmds = amsParser.parseRemoteCommands(data)
                _mediaState.value = _mediaState.value.copy(
                    isAvailable = true,
                    supportedCommands = cmds,
                    lastUpdatedMs = System.currentTimeMillis()
                )
                MusicControlTileService.requestUpdate(context)
            }

            AmsConstants.ENTITY_UPDATE_UUID -> {
                val packet = amsParser.parseEntityUpdate(data) ?: return
                _mediaState.value = amsParser.applyEntityUpdate(
                    current = _mediaState.value,
                    entityId = packet.entityId,
                    attributeId = packet.attributeId,
                    value = packet.value
                )
                MusicControlTileService.requestUpdate(context)
                if (packet.isTruncated) {
                    val gatt = bluetoothGatt
                    val ea = amsEntityAttributeChar
                    if (gatt != null && ea != null) {
                        val readReq = amsParser.buildEntityAttributeReadCommand(packet.entityId, packet.attributeId)
                        gattQueue.enqueue("amsWriteEntityAttr") {
                            writeCharacteristicCompat(gatt, ea, readReq, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        }
                        gattQueue.enqueue("amsReadEntityAttr") {
                            pendingEntityAttrRead = packet.entityId to packet.attributeId
                            gatt.readCharacteristic(ea)
                        }
                    }
                }
            }

            AncsConstants.BATTERY_LEVEL_CHAR_UUID -> {
                updatePhoneBatteryFromBytes(data)
            }
        }
    }

    @Synchronized
    private fun pumpNextControlPointRequest() {
        if (activeAttributeRequest != null || activeAppNameRequest != null) return

        val gatt = bluetoothGatt ?: return
        val cp = controlPointChar ?: return

        if (pendingAttributeUids.isNotEmpty()) {
            val nextNotif = pendingAttributeUids.removeFirst()
            activeAttributeRequest = nextNotif
            // Keep requested attributes to the 5 core fields (like NotiMirror) so iOS
            // never rejects or omits optional action labels; action buttons still work via eventFlags.
            val (cmdBytes, expectedAttrs) = ancsParser.buildGetNotificationAttributesCommand(
                uid = nextNotif.uid,
                includePositiveAction = false,
                includeNegativeAction = false
            )
            ancsParser.startNotificationAssembly(expectedAttrs)

            val requestedUid = nextNotif.uid
            startAssemblyTimeout {
                handleAssemblyTimeout(expectedUid = requestedUid)
            }

            gattQueue.enqueue(
                name = "getNotifAttrs-$requestedUid",
                onResult = { status ->
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Control Point write failed for uid=$requestedUid status=$status")
                        abortActiveAttributeRequest(expectedUid = requestedUid)
                    }
                }
            ) {
                writeCharacteristicCompat(gatt, cp, cmdBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
            return
        }

        if (pendingAppNameQueue.isNotEmpty()) {
            val appId = pendingAppNameQueue.removeFirst()
            activeAppNameRequest = appId
            ancsParser.startAppAttributeAssembly()
            val cmdBytes = ancsParser.buildGetAppAttributesCommand(appId)

            startAssemblyTimeout {
                handleAssemblyTimeout(expectedAppId = appId)
            }

            gattQueue.enqueue("getAppAttrs-$appId") {
                writeCharacteristicCompat(gatt, cp, cmdBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
        }
    }

    @Synchronized
    private fun abortActiveAttributeRequest(expectedUid: Int? = null) {
        if (expectedUid != null && activeAttributeRequest?.uid != expectedUid) {
            return
        }
        attributeTimeoutJob?.cancel()
        attributeTimeoutJob = null
        activeAttributeRequest = null
        ancsParser.resetAssembly()
        pumpNextControlPointRequest()
    }

    @Synchronized
    private fun handleAssemblyTimeout(expectedUid: Int? = null, expectedAppId: String? = null) {
        if (expectedUid != null) {
            val partial = activeAttributeRequest
            if (partial == null || partial.uid != expectedUid) {
                return
            }
            activeAttributeRequest = null
            ancsParser.resetAssembly()
            Log.w(
                TAG,
                "ANCS attr timeout uid=${partial.uid} app=${partial.appIdentifier} title=${partial.title}"
            )
            if (partial.title.isNotBlank() || partial.message.isNotBlank() || partial.appIdentifier.isNotBlank()) {
                partial.appDisplayName = AncsConstants.friendlyAppName(
                    appIdentifier = partial.appIdentifier,
                    cachedName = deviceStore.getCachedAppName(partial.appIdentifier),
                    categoryId = partial.categoryId
                )
                _alertsSyncedCount.value += 1
                _notificationsFlow.tryEmit(partial)
            }
            pumpNextControlPointRequest()
            return
        }
        if (expectedAppId != null && activeAppNameRequest == expectedAppId) {
            activeAppNameRequest = null
            ancsParser.resetAssembly()
            pumpNextControlPointRequest()
        }
    }

    private fun startAssemblyTimeout(onTimeout: () -> Unit) {
        attributeTimeoutJob?.cancel()
        attributeTimeoutJob = scope.launch {
            delay(2_200L)
            onTimeout()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = value
            characteristic.writeType = writeType
            gatt.writeCharacteristic(characteristic)
        }
    }

    fun refreshDevicesList() {
        val merged = linkedMapOf<String, DeviceUiItem>()
        val activeAddr = activeDevice?.address ?: _activeDeviceAddress.value

        // 1. Saved previously paired devices
        for (saved in deviceStore.getSavedDevices()) {
            val isActive = activeAddr != null && saved.address.equals(activeAddr, ignoreCase = true)
            merged[saved.address] = DeviceUiItem(
                address = saved.address,
                name = saved.name,
                isBonded = true,
                isAppleCandidate = true,
                status = if (isActive) _connectionState.value else ConnectionStatus.DISCONNECTED
            )
        }

        // 2. System bonded Apple devices (excluding Android companion phones and deleted pairings)
        val hidden = deviceStore.getHiddenAddresses()
        try {
            bluetoothAdapter?.bondedDevices?.forEach { bonded ->
                if (bonded.address.uppercase() in hidden) return@forEach
                val bondedName = safeDeviceNameOrNull(bonded)
                val existingName = merged[bonded.address]?.name
                val name = pickPreferredDeviceName(existingName, bondedName)
                val isAppleBonded = (bondedName != null && isAppleDeviceName(bondedName)) ||
                    merged.containsKey(bonded.address)
                if (isAppleBonded) {
                    val isActive = activeAddr != null && bonded.address.equals(activeAddr, ignoreCase = true)
                    merged[bonded.address] = DeviceUiItem(
                        address = bonded.address,
                        name = name,
                        isBonded = true,
                        isAppleCandidate = true,
                        status = if (isActive) _connectionState.value else ConnectionStatus.DISCONNECTED
                    )
                }
            }
        } catch (_: SecurityException) {
        }

        // 3. Nearby scanned Apple BLE devices
        discoveredDevices.values.forEach { scanned ->
            val existing = merged[scanned.address]
            if (existing == null) {
                merged[scanned.address] = scanned
            } else {
                merged[scanned.address] = existing.copy(rssi = scanned.rssi)
            }
        }

        // Sort: Connected/active first, then bonded, then strongest RSSI
        val sorted = merged.values.sortedWith(
            compareByDescending<DeviceUiItem> { it.status == ConnectionStatus.CONNECTED }
                .thenByDescending { it.address.equals(activeAddr, ignoreCase = true) }
                .thenByDescending { it.isBonded }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
        )

        _devicesList.value = sorted
    }

    private fun isBondedDevice(device: BluetoothDevice): Boolean {
        return try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }
    }

    private fun resolvePreferredDeviceName(address: String, candidate: String?): String {
        val existingSaved = deviceStore.getSavedDevices()
            .firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?.name
        val activeMatch = if (_activeDeviceAddress.value.equals(address, ignoreCase = true)) {
            _activeDeviceName.value
        } else {
            null
        }
        return pickPreferredDeviceName(existingSaved ?: activeMatch, candidate)
    }

    private fun pickPreferredDeviceName(existing: String?, candidate: String?): String {
        val cleanExisting = existing?.trim()?.takeIf { it.isNotBlank() }
        val cleanCandidate = candidate?.trim()?.takeIf { it.isNotBlank() }
        if (cleanExisting != null &&
            !isGenericAppleName(cleanExisting) &&
            (cleanCandidate == null || isGenericAppleName(cleanCandidate))
        ) {
            return cleanExisting
        }
        return cleanCandidate ?: cleanExisting ?: "iPhone"
    }

    private fun isAppleDeviceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.contains("iPhone", ignoreCase = true) ||
            name.contains("iPad", ignoreCase = true) ||
            name.contains("Apple", ignoreCase = true)
    }

    private fun isGenericAppleName(name: String): Boolean {
        return name.equals("iPhone", ignoreCase = true) ||
            name.equals("iPad", ignoreCase = true) ||
            name.equals("Apple Device", ignoreCase = true) ||
            name.equals("Unknown Device", ignoreCase = true)
    }

    private fun safeDeviceName(device: BluetoothDevice): String {
        return safeDeviceNameOrNull(device) ?: "iPhone"
    }

    private fun safeDeviceNameOrNull(device: BluetoothDevice): String? {
        return try {
            val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                device.alias?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            alias ?: device.name?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }
}
