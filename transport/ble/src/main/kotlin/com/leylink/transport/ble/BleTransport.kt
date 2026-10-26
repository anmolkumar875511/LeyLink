package com.leylink.transport.ble

import com.leylink.core.model.NodeId
import com.leylink.core.model.Peer
import com.leylink.core.port.SendResult
import com.leylink.core.port.Transport
import com.leylink.core.port.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 1 stub. Stage 3 fills this in:
 * advertiser + scanner (ScanFilter on SERVICE_UUID), GATT server (INBOX/STREAM/INFO), GATT client,
 * per-link serialized operation queue, MTU negotiation, lower-nodeId-initiates rule, connection pool.
 */
class BleTransport : Transport {
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    private val mutablePeers = MutableStateFlow<List<Peer>>(emptyList())

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()
    override val connectedPeers: StateFlow<List<Peer>> = mutablePeers.asStateFlow()

    override suspend fun start() {
        // TODO(Stage 3): start advertising, scanning and the GATT server.
    }

    override suspend fun stop() {
        // TODO(Stage 3): stop everything and close all links.
    }

    override suspend fun send(peer: NodeId, frame: ByteArray): SendResult =
        // Report failure so callers never assume delivery before Stage 3 exists.
        SendResult.Failure("BLE transport not implemented yet")
}
