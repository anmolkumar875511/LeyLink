package com.leylink.app.permissions

import android.Manifest
import android.os.Build

/**
 * Pure function of the SDK level, so it can be unit-tested without a device.
 * The rules follow the Android BLE permission model (11 and below vs 12+).
 */
object PermissionPolicy {

    /** Permissions without which BLE cannot work. */
    fun required(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * What we ask for in one dialog: required + optional extras that never block Disaster Mode:
     * notifications and Wi-Fi Direct (Android 13+: NEARBY_WIFI_DEVICES, Android 12: fine location).
     * Without the Wi-Fi permission the mesh simply keeps using BLE chunking.
     */
    fun requestAll(sdkInt: Int): List<String> = when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU ->
            required(sdkInt) + Manifest.permission.POST_NOTIFICATIONS + Manifest.permission.NEARBY_WIFI_DEVICES
        sdkInt >= Build.VERSION_CODES.S -> required(sdkInt) + Manifest.permission.ACCESS_FINE_LOCATION
        else -> required(sdkInt)
    }
}
