package com.leylink.core.port

import com.leylink.core.model.NodeId
import com.leylink.core.model.Peer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Port implemented by BleTransport (Stage 3) and a FakeTransport used in tests (Stage 2).
 * The routing core only ever talks to this interface.
 */
interface Transport {
    /** Discovery, loss and inbound frames. */
    val events: Flow<TransportEvent>

    /** Peers with an open link right now. */
    val connectedPeers: StateFlow<List<Peer>>

    suspend fun start()

    suspend fun stop()

    /** Sends one frame (for BLE: one chunk frame) to a connected peer. Never throws for radio errors. */
    suspend fun send(peer: NodeId, frame: ByteArray): SendResult

    /**
     * Beacon mode: true while one of OUR SOS messages has not yet been handed to any neighbour. The radio
     * should then make itself easy to find and let neighbours connect to us regardless of the usual
     * tie-break. Default: no-op (fakes, Wi-Fi only transports).
     */
    fun setBeacon(active: Boolean) = Unit

    /** [SyncDigest] of our dedup memory, advertised so neighbours can skip pointless connections. */
    fun setDigest(digest: Int) = Unit
}

sealed interface TransportEvent {
    data class PeerDiscovered(val peer: Peer) : TransportEvent

    data class PeerLost(val peerId: NodeId) : TransportEvent

    /** [frame] is shared, not copied (hot path). Receivers must not mutate it. */
    class FrameReceived(val from: NodeId, val frame: ByteArray) : TransportEvent
}

sealed interface SendResult {
    data object Success : SendResult

    data class Failure(val reason: String) : SendResult
}
