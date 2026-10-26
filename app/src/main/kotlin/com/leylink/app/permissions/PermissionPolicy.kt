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

    /** What we ask for in one dialog: required + notifications (useful, but never blocking). */
    fun requestAll(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            required(sdkInt) + Manifest.permission.POST_NOTIFICATIONS
        } else {
            required(sdkInt)
        }
}
