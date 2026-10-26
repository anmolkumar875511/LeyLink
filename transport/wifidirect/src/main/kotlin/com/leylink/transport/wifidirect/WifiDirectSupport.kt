package com.leylink.transport.wifidirect

import android.content.Context
import android.content.pm.PackageManager

/**
 * Stage 1 stub. Stage 7 adds group formation and socket transfer via WifiP2pManager, triggered by an
 * XFER_OFFER sent over BLE.
 */
object WifiDirectSupport {
    /** Not every phone has Wi-Fi Direct; callers must fall back to BLE chunking when this is false. */
    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
}
