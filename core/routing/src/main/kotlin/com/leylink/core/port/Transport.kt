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
