package com.leylink.core.testing

import com.leylink.core.codec.ChunkFrame
import com.leylink.core.codec.Chunker
import com.leylink.core.codec.PacketCodec
import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.Packet
import com.leylink.core.model.PacketType
import com.leylink.core.model.Priority
import com.leylink.core.port.TransportEvent
import com.leylink.core.router.RouterConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end scenarios on simulated radios. Each test mirrors a lemma or rule from the README proofs.
 * `advanceUntilIdle()` lets every router coroutine run until nothing is left to do, which also proves
 * that the flood terminates (otherwise the test would never finish).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRouterScenarioTest {
    private val clock = TestClock(now = 1_000L)
    private val net = SimNetwork(clock)

    private fun TestScope.node(index: Int, config: RouterConfig = RouterConfig()): SimNode =
        net.addNode(index, config).also { it.start(backgroundScope) }

    @Test
    fun `sos crosses two hops through B`() = runTest {
        val a = node(1)
        val b = node(2)
        val c = node(3)
        net.link(a.id, b.id)
        net.link(b.id, c.id) // A and C are never linked

        a.router.send(PacketType.SOS, "help")
        advanceUntilIdle()

        assertTrue(a.delivered.isEmpty())
        val atB = b.delivered.single()
        assertEquals(1, atB.hop)
        val atC = c.delivered.single()
        assertEquals(2, atC.hop)
        assertEquals("help", atC.text)
        assertEquals(PacketType.SOS, atC.type)
        assertEquals(a.id, atC.originId)
        assertEquals(5, atC.originTtl)
        assertEquals(2, net.framesSent) // A to B, B to C. B never echoes back to A (split horizon).
    }

    @Test
    fun `message waits at B until C comes into range (delay tolerance)`() = runTest {
        val a = node(1)
        val b = node(2)
        val c = node(3)

        net.link(a.id, b.id)
        a.router.send(PacketType.SOS, "help")
        advanceUntilIdle()
        assertEquals(1, b.delivered.size)
        assertTrue(c.delivered.isEmpty())

        net.unlink(a.id, b.id) // A walks away
        clock.advance(10 * 60 * 1000L) // ten minutes pass
        net.link(b.id, c.id) // C finally comes into range of B
        advanceUntilIdle()

        val atC = c.delivered.single()
        assertEquals(2, atC.hop)
        assertEquals("help", atC.text)
    }

    @Test
    fun `ttl limits how far a message travels`() = runTest {
        val nodes = (1..7).map { i -> node(i, if (i == 1) RouterConfig(originTtl = 3) else RouterConfig()) }
        nodes.zipWithNext().forEach { (x, y) -> net.link(x.id, y.id) }

        nodes[0].router.send(PacketType.CHAT, "hello")
        advanceUntilIdle()

        val reached = nodes.map { it.delivered.size }
        assertEquals(listOf(0, 1, 1, 1, 0, 0, 0), reached) // exactly the nodes within 3 hops
        assertEquals(1L, nodes[3].router.stats.value.ttlExhausted) // the 4th node saw ttl 1 and stopped
    }

    /**
     * Lemma 3 from the README. Z first hears the message over a long path (ttl 1), so it cannot relay.
     * Later a shorter path appears (ttl 3). A plain "drop known IDs" rule would lose it; the TTL-aware
     * rule accepts the better copy and carries it on to W1 and W2.
     */
    @Test
    fun `a late higher-ttl copy is relayed even though the message was already seen`() = runTest {
        val cfg = RouterConfig(originTtl = 3)
        val a = node(1, cfg)
        val p = node(2)
        val q = node(3)
        val z = node(4)
        val w1 = node(5)
        val w2 = node(6)
        net.link(a.id, p.id)
        net.link(p.id, q.id)
        net.link(q.id, z.id)
        net.link(z.id, w1.id)
        net.link(w1.id, w2.id)

        a.router.send(PacketType.SOS, "help")
        advanceUntilIdle()

        assertEquals(3, z.delivered.single().hop) // arrived the long way round, ttl 1
        assertTrue(w1.delivered.isEmpty())
        assertTrue(w2.delivered.isEmpty())

        net.link(a.id, z.id) // the shorter path appears
        advanceUntilIdle()

        assertEquals(1, z.delivered.size) // Z does not show the message a second time
        assertEquals(2, w1.delivered.single().hop)
        assertEquals(3, w2.delivered.single().hop)
    }

    @Test
    fun `sos overtakes older chat in the outbox`() = runTest {
        val a = node(1)
        val b = node(2)

        a.router.send(PacketType.CHAT, "c1")
        a.router.send(PacketType.CHAT, "c2")
        a.router.send(PacketType.CHAT, "c3")
        a.router.send(PacketType.SOS, "help") // queued last, must be sent first
        advanceUntilIdle()

        net.link(a.id, b.id)
        advanceUntilIdle()

        assertEquals(listOf("help", "c1", "c2", "c3"), b.delivered.map { it.text })
    }

    @Test
    fun `a long message is chunked and reassembled intact`() = runTest {
        val a = node(1)
        val b = node(2)
        net.link(a.id, b.id)
        val text = "x".repeat(600)

        a.router.send(PacketType.CHAT, text)
        advanceUntilIdle()

        assertEquals(text, b.delivered.single().text)
        assertEquals(4, net.framesSent) // 632 bytes at 169 bytes of data per frame
    }

    @Test
    fun `storm - six nodes in a ring, each sends five SOS, nobody misses or repeats one`() = runTest {
        val nodes = (1..6).map { node(it) }
        nodes.forEachIndexed { i, n -> net.link(n.id, nodes[(i + 1) % nodes.size].id) }

        for (n in nodes) {
            repeat(5) { k -> n.router.send(PacketType.SOS, "sos ${n.id} #$k") }
        }
        advanceUntilIdle()

        for (n in nodes) {
            assertEquals(25, n.delivered.size) // everybody else's 5 messages
            assertEquals(n.delivered.size, n.delivered.map { it.msgId }.toSet().size) // no repeats
            assertEquals(0L, n.router.stats.value.malformed)
        }
        // Lemma 2: at most 2 * T * |E| transmissions per message.
        val bound = 30 * 2 * Packet.MAX_TTL * net.linkCount
        assertTrue("framesSent=${net.framesSent} exceeds bound $bound", net.framesSent <= bound)
    }

    /**
     * With the sync handshake, two nodes that both already hold a message do not retransmit it to each
     * other when they meet. Only the two small summary packets cross the new link.
     */
    @Test
    fun `sync summary suppresses retransmission of messages the peer already has`() = runTest {
        val cfg = RouterConfig(syncEnabled = true)
        val a = node(1, cfg)
        val b = node(2, cfg)
        val c = node(3, cfg)
        net.link(a.id, b.id)
        net.link(b.id, c.id)

        b.router.send(PacketType.SOS, "help") // B floods to A and C
        advanceUntilIdle()
        assertEquals(1, a.delivered.size)
        assertEquals(1, c.delivered.size)

        val before = net.framesSent
        net.link(a.id, c.id) // A and C meet; both already have the message
        advanceUntilIdle()

        assertEquals(2, net.framesSent - before) // exactly one summary frame each way
        assertEquals(0L, a.router.stats.value.duplicates)
        assertEquals(0L, c.router.stats.value.duplicates)
        assertEquals(2L, a.router.stats.value.summariesIn) // one from B earlier, one from C now
        assertEquals(2L, c.router.stats.value.summariesIn) // one from B earlier, one from A now
    }

    @Test
    fun `sync still delivers what the peer is missing`() = runTest {
        val cfg = RouterConfig(syncEnabled = true)
        val a = node(1, cfg)
        val b = node(2, cfg)

        a.router.send(PacketType.SOS, "help") // queued, nobody in range
        advanceUntilIdle()
        net.link(a.id, b.id)
        advanceUntilIdle()

        assertEquals("help", b.delivered.single().text)
    }

    @Test
    fun `a silent peer does not block sending forever`() = runTest {
        // B never answers with a summary (sync disabled on B), so A must time out and send anyway.
        val a = node(1, RouterConfig(syncEnabled = true, syncWaitMs = 500))
        val b = node(2, RouterConfig(syncEnabled = false))

        a.router.send(PacketType.SOS, "help")
        net.link(a.id, b.id)
        advanceUntilIdle() // advances virtual time past the 500 ms wait

        assertEquals("help", b.delivered.single().text)
    }

    @Test
    fun `garbage and tampered packets are counted and ignored`() = runTest {
        val a = node(1)
        val b = node(2)
        net.link(a.id, b.id)

        // 1) bytes that are not even a chunk frame
        b.transport.push(TransportEvent.FrameReceived(a.id, ByteArray(30) { 3 }))

        // 2) a valid chunk frame carrying junk instead of a packet
        val junk = ChunkFrame(MsgId(ByteArray(8) { 4 }), index = 0, total = 1, data = ByteArray(40) { 7 })
        b.transport.push(TransportEvent.FrameReceived(a.id, junk.encode()))

        // 3) a well-formed packet claiming more reach than any node may grant (ttl 5 after 2 links)
        val inflated = Packet(
            type = PacketType.SOS, priority = Priority.SOS, ttl = 5, hop = 2,
            msgId = MsgId(ByteArray(8) { 5 }), originId = NodeId(ByteArray(8) { 6 }),
            timestampMs = 0, payload = "fake".toByteArray(),
        )
        val bytes = PacketCodec.encode(inflated)
        for (frame in Chunker.split(inflated.msgId, bytes)) {
            b.transport.push(TransportEvent.FrameReceived(a.id, frame))
        }
        advanceUntilIdle()

        assertEquals(3L, b.router.stats.value.malformed)
        assertFalse(b.delivered.isNotEmpty())
    }
}
