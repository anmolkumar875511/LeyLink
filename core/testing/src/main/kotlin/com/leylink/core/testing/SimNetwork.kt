package com.leylink.core.testing

import com.leylink.core.model.NodeId
import com.leylink.core.model.Peer
import com.leylink.core.port.SendResult
import com.leylink.core.port.TransportEvent
import com.leylink.core.router.DedupCache
import com.leylink.core.router.MeshRouter
import com.leylink.core.router.RouterConfig
import com.leylink.core.time.Clock

/**
 * A controllable radio environment for simulations: you decide which nodes are in range of each other
 * ([link] / [unlink]) and the router under test reacts exactly as it would to real BLE contacts.
 */
class SimNetwork(private val clock: Clock) {
    private data class LinkKey(val a: NodeId, val b: NodeId)

    private val transports = LinkedHashMap<NodeId, FakeTransport>()
    private val links = HashSet<LinkKey>()

    /** Every frame successfully handed to a peer. Used to check the traffic bounds from the README proofs. */
    var framesSent: Int = 0
        private set

    /** Number of links currently up (|E| in the proofs). */
    val linkCount: Int get() = links.size

    /** Creates a full node (transport, stores, router) with a deterministic id derived from [index]. */
    fun addNode(index: Int, config: RouterConfig = RouterConfig()): SimNode {
        val id = NodeId(byteArrayOf(0, 0, 0, 0, 0, 0, 0, index.toByte()))
        val transport = FakeTransport(id, this)
        val outbox = InMemoryOutboxStore()
        val router = MeshRouter(id, transport, outbox, DedupCache(InMemorySeenStore()), clock, config)
        transports[id] = transport
        return SimNode(id, transport, outbox, router)
    }

    fun link(a: NodeId, b: NodeId) {
        if (!links.add(key(a, b))) return
        signalContact(a, b, up = true)
        signalContact(b, a, up = true)
    }

    fun unlink(a: NodeId, b: NodeId) {
        if (!links.remove(key(a, b))) return
        signalContact(a, b, up = false)
        signalContact(b, a, up = false)
    }

    internal fun deliver(from: NodeId, to: NodeId, frame: ByteArray): SendResult {
        if (key(from, to) !in links) return SendResult.Failure("no link between the two nodes")
        val target = transports[to] ?: return SendResult.Failure("unknown node")
        framesSent++
        target.push(TransportEvent.FrameReceived(from, frame.copyOf()))
        return SendResult.Success
    }

    /** Tells [self] that [other] came into range ([up]) or left it. */
    private fun signalContact(self: NodeId, other: NodeId, up: Boolean) {
        val transport = transports[self] ?: return
        if (up) {
            transport.push(TransportEvent.PeerDiscovered(Peer(other, rssi = -50, lastSeenMs = clock.nowMs())))
        } else {
            transport.push(TransportEvent.PeerLost(other))
        }
        transport.setPeers(
            links.filter { self == it.a || self == it.b }
                .map { if (it.a == self) it.b else it.a }
                .map { Peer(it, rssi = -50, lastSeenMs = clock.nowMs()) },
        )
    }

    private fun key(a: NodeId, b: NodeId): LinkKey = if (a <= b) LinkKey(a, b) else LinkKey(b, a)
}
