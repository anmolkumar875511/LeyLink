package com.leylink.core.testing

import com.leylink.core.model.NodeId
import com.leylink.core.model.Peer
import com.leylink.core.port.SendResult
import com.leylink.core.port.Transport
import com.leylink.core.port.TransportEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** In-memory transport wired to a [SimNetwork]. Events are buffered, so none are lost before collection. */
class FakeTransport(
    val nodeId: NodeId,
    private val network: SimNetwork,
) : Transport {
    private val inbox = Channel<TransportEvent>(Channel.UNLIMITED)
    private val mutablePeers = MutableStateFlow<List<Peer>>(emptyList())

    override val events: Flow<TransportEvent> = inbox.receiveAsFlow()
    override val connectedPeers: StateFlow<List<Peer>> = mutablePeers.asStateFlow()

    /** Last beacon state the router asked for (beacon mode tests). */
    @Volatile var beacon: Boolean = false
        private set

    /** Last digest the router published. */
    @Volatile var digest: Int = 0
        private set

    override fun setBeacon(active: Boolean) {
        beacon = active
    }

    override fun setDigest(digest: Int) {
        this.digest = digest
    }

    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override suspend fun send(peer: NodeId, frame: ByteArray): SendResult = network.deliver(nodeId, peer, frame)

    internal fun push(event: TransportEvent) {
        inbox.trySend(event)
    }

    internal fun setPeers(peers: List<Peer>) {
        mutablePeers.value = peers
    }
}
