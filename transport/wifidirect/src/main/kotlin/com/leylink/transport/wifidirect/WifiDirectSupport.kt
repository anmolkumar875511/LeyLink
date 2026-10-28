package com.leylink.transport.wifidirect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Capability and permission checks for Wi-Fi Direct. The BLE path never depends on any of this. */
object WifiDirectSupport {
    /** Not every phone has Wi-Fi Direct; callers must fall back to BLE chunking when this is false. */
    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    /** Android 13+ needs NEARBY_WIFI_DEVICES; older versions need fine location for Wi-Fi Direct. */
    fun hasPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
