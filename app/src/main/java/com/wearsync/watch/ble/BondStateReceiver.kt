package com.wearsync.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BondStateReceiver : BroadcastReceiver() {

    private val TAG = "BondStateReceiver"

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        when (intent.action) {
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        Log.i(TAG, "Bonded with ${device?.name} [${device?.address}]")
                        onDeviceBonded?.invoke(device)
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        Log.d(TAG, "Bonding in progress with ${device?.name}")
                    }
                    BluetoothDevice.BOND_NONE -> {
                        Log.w(TAG, "Bond removed or cancelled for ${device?.name}")
                        onBondFailed?.invoke(device)
                    }
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.d(TAG, "ACL connected: ${device?.name} [${device?.address}]")
                onAclConnected?.invoke(device)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.d(TAG, "ACL disconnected: ${device?.name} [${device?.address}]")
            }
        }
    }

    companion object {
        var onDeviceBonded: ((BluetoothDevice?) -> Unit)? = null
        var onBondFailed: ((BluetoothDevice?) -> Unit)? = null
        var onAclConnected: ((BluetoothDevice?) -> Unit)? = null
    }
}
