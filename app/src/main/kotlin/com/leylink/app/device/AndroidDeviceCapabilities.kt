package com.leylink.app.device

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidDeviceCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceCapabilities {

    override fun hasBle(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    // BluetoothAdapter.isEnabled needs no runtime permission.
    override fun isBluetoothEnabled(): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
}
