package com.wearsync.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.wearsync.watch.ancs.AncsConstants

/**
 * Advertises the watch over BLE with a lightweight Device Information GATT server
 * and ANCS Service Solicitation UUID so an iPhone user can discover and pair
 * directly from iPhone Settings -> Bluetooth.
 */
@SuppressLint("MissingPermission")
class BleAdvertiserManager(
    private val context: Context,
    private val onIphoneConnectedToServer: (BluetoothDevice) -> Unit
) {
    private val TAG = "BleAdvertiserManager"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "BLE advertising started (watch discoverable by iPhone)")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.w(TAG, "BLE advertising failed with code=$errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Remote device connected to watch GATT server: ${device.name} [${device.address}]")
                onIphoneConnectedToServer(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == AncsConstants.MANUFACTURER_NAME_CHAR_UUID) {
                val value = Build.MANUFACTURER.toByteArray(Charsets.UTF_8)
                val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }
    }

    fun startAdvertising() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || isAdvertising) return

        try {
            if (gattServer == null) {
                gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)?.apply {
                    val disService = BluetoothGattService(
                        AncsConstants.DEVICE_INFO_SERVICE_UUID,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY
                    )
                    val mfgChar = BluetoothGattCharacteristic(
                        AncsConstants.MANUFACTURER_NAME_CHAR_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ
                    )
                    disService.addCharacteristic(mfgChar)
                    addService(disService)
                }
            }

            advertiser = adapter.bluetoothLeAdvertiser ?: run {
                Log.w(TAG, "BluetoothLeAdvertiser unavailable on this device")
                return
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()

            // Keep primary payload <= 31 bytes: Flags (3B) + 128-bit ANCS Solicitation UUID (18B) = 21B.
            // Put the watch's device name in the scan response so iPhone Settings -> Bluetooth shows it.
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceSolicitationUuid(ParcelUuid(AncsConstants.ANCS_SERVICE_UUID))
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE advertising", e)
        }
    }

    fun pauseBeacons() {
        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                Log.i(TAG, "Paused BLE advertising beacons while iPhone is connected")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing advertising beacons", e)
        } finally {
            isAdvertising = false
        }
    }

    fun stopAdvertising() {
        pauseBeacons()
        try {
            gattServer?.close()
        } catch (_: Exception) {
        } finally {
            gattServer = null
        }
    }
}
