package com.leylink.app.ui.home

import com.leylink.core.model.Packet
import com.leylink.core.model.StoredMessage
import com.leylink.core.router.RouterStats

/** Everything the home screen renders. Immutable; the screen is a pure function of it. */
data class HomeUiState(
    val hasBle: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val meshRunning: Boolean = false,
    val peerCount: Int = 0,
    val messages: List<StoredMessage> = emptyList(),
    val stats: RouterStats = RouterStats(),
    val draft: String = "",
    val error: String? = null,
) {
    /** The Disaster Mode button is only usable when BLE exists and permissions are granted. */
    val canStartMesh: Boolean get() = hasBle && permissionsGranted

    val canSend: Boolean get() = draft.isNotBlank()

    val sosCharsLeft: Int get() = Packet.MAX_SOS_CHARS - draft.length
}
