package com.leylink.app.ui.home

/** Everything the home screen renders. Immutable; the screen is a pure function of it. */
data class HomeUiState(
    val hasBle: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val meshRunning: Boolean = false,
) {
    /** The Disaster Mode button is only usable when BLE exists and permissions are granted. */
    val canStartMesh: Boolean get() = hasBle && permissionsGranted
}
