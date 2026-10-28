package com.leylink.core.testing

import com.leylink.core.codec.Chunker
import com.leylink.core.codec.PacketCodec
import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.Packet
import com.leylink.core.model.PacketType
import com.leylink.core.model.Priority
import com.leylink.core.port.Signer
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

/** Victory tier on simulated radios: signing, beacon mode, Wi-Fi Direct escalation, storm. */
@OptIn(ExperimentalCoroutinesApi::class)
class VictoryScenarioTest {
    private val clock = TestClock(now = 1_000L)
    private val net = SimNetwork(clock)
    private val strict = RouterConfig(requireSignatures = true)

    private fun TestScope.node(
        index: Int,
        config: RouterConfig = strict,
        signed: Boolean = true,
        bulk: Boolean = false,
    ): SimNode = net.addNode(index, config, signed = signed, bulk = bulk).also { it.start(backgroundScope) }

    /** Frames of an SOS from [origin], signed by [signer]; [tamper] changes the text after signing. */
    private fun sosFrames(
        signer: Signer?,
        origin: NodeId,
        msgByte: Int,
        text: String,
        tamper: Boolean = false,
    ): List<ByteArray> {
        fun packet(payload: ByteArray, signature: ByteArray?) = Packet(
            type = PacketType.SOS, priority = Priority.SOS, ttl = 5, hop = 1,
            msgId = MsgId(ByteArray(8) { msgByte.toByte() }), originId = origin, timestampMs = 1,
            payload = payload, signature = signature, beacon = true, publicKey = signer?.publicKey(),
        )
        val signature = signer?.sign(PacketCodec.signedRegion(packet(text.toByteArray(), null)))
        val shown = if (tamper) "TAMPERED" else text
        val p = packet(shown.toByteArray(), signature)
        return Chunker.split(p.msgId, PacketCodec.encode(p))
    }

    private fun SimNode.receive(from: NodeId, frames: List<ByteArray>) =
        frames.forEach { transport.push(TransportEvent.FrameReceived(from, it)) }

    // ------------------------------------------------------------------ signing

    @Test
    fun `signed sos crosses two hops and every relay verifies the same signature`() = runTest {
        val a = node(1)
        val b = node(2)
        val c = node(3)
        net.link(a.id, b.id)
        net.link(b.id, c.id)

        a.router.send(PacketType.SOS, "help")
        advanceUntilIdle()

        assertEquals("help", c.delivered.single().text)
        assertEquals(2, c.delivered.single().hop) // ttl and hop changed in transit, signature still valid
        for (n in listOf(a, b, c)) assertEquals(0L, n.router.stats.value.signatureRejected)
        assertTrue(b.router.stats.value.announcesIn >= 2) // A and C introduced themselves to B
    }

    @Test
    fun `a packet changed after signing is dropped before it can poison the dedup memory`() = runTest {
        val b = node(2)
        val forger = HashSigner(1)
        val origin = NodeId(ByteArray(8) { 9 })

        b.receive(origin, sosFrames(forger, origin, msgByte = 7, text = "real help", tamper = true))
        advanceUntilIdle()
        assertTrue(b.delivered.isEmpty())
        assertEquals(1L, b.router.stats.value.signatureRejected)
        assertEquals(1L, b.router.stats.value.sosDropped)

        // The genuine message with the SAME msgId is still accepted afterwards.
        b.receive(origin, sosFrames(forger, origin, msgByte = 7, text = "real help"))
        advanceUntilIdle()
        assertEquals("real help", b.delivered.single().text)
    }

    @Test
    fun `unsigned packets are dropped when signatures are required`() = runTest {
        val b = node(2)
        val origin = NodeId(ByteArray(8) { 9 })

        b.receive(origin, sosFrames(null, origin, msgByte = 8, text = "anonymous"))
        advanceUntilIdle()

        assertTrue(b.delivered.isEmpty())
        assertEquals(1L, b.router.stats.value.sosDropped)
    }

    @Test
    fun `a second key claiming a known origin is rejected (trust on first use)`() = runTest {
        val b = node(2)
        val origin = NodeId(ByteArray(8) { 9 })

        b.receive(origin, sosFrames(HashSigner(1), origin, msgByte = 1, text = "first"))
        advanceUntilIdle()
        b.receive(origin, sosFrames(HashSigner(2), origin, msgByte = 2, text = "impostor"))
        advanceUntilIdle()

        assertEquals(listOf("first"), b.delivered.map { it.text })
        assertEquals(1L, b.router.stats.value.signatureRejected)
    }

    // ------------------------------------------------------------------ beacon mode

    @Test
    fun `beacon mode stays on until a neighbour takes the sos`() = runTest {
        val a = node(1)
        val b = node(2)

        a.router.send(PacketType.SOS, "help")
        advanceUntilIdle()
        assertTrue(a.transport.beacon)
        assertEquals(1, a.router.stats.value.unackedSos)

        net.link(a.id, b.id)
        advanceUntilIdle()

        assertFalse(a.transport.beacon)
        assertEquals(0, a.router.stats.value.unackedSos)
        assertEquals("help", b.delivered.single().text)
    }

    @Test
    fun `chat alone never turns beacon mode on`() = runTest {
        val a = node(1)
        a.router.send(PacketType.CHAT, "hi")
        advanceUntilIdle()
        assertFalse(a.transport.beacon)
    }

    // ------------------------------------------------------------------ Wi-Fi Direct escalation

    private val bulkConfig = strict.copy(bulkThresholdBytes = 500)

    @Test
    fun `a large message goes over the bulk channel and BLE only negotiates`() = runTest {
        val a = node(1, bulkConfig, bulk = true)
        val b = node(2, bulkConfig, bulk = true)
        net.link(a.id, b.id)
        advanceUntilIdle()
        val before = net.framesSent
        val text = "y".repeat(2_000)

        a.router.send(PacketType.CHAT, text)
        advanceUntilIdle()

        assertEquals(text, b.delivered.single().text)
        assertEquals(1, net.bulkHub.transfers.get())
        assertEquals(2, net.framesSent - before) // one XFER_OFFER and one accept, no data chunks
        assertEquals(1L, a.router.stats.value.bulkSent)
        assertEquals(1L, b.router.stats.value.bulkReceived)
    }

    @Test
    fun `when the bulk transfer fails the message falls back to BLE chunking`() = runTest {
        net.bulkHub.failPushes = true
        val a = node(1, bulkConfig, bulk = true)
        val b = node(2, bulkConfig, bulk = true)
        net.link(a.id, b.id)
        val text = "z".repeat(2_000)

        a.router.send(PacketType.CHAT, text)
        advanceUntilIdle()

        assertEquals(text, b.delivered.single().text)
        assertEquals(0, net.bulkHub.transfers.get())
        assertEquals(1L, a.router.stats.value.bulkFallbacks)
    }

    @Test
    fun `a receiver without Wi-Fi Direct declines and BLE chunking delivers`() = runTest {
        net.bulkHub.receiversAvailable = false
        val a = node(1, bulkConfig, bulk = true)
        val b = node(2, bulkConfig, bulk = true)
        net.link(a.id, b.id)

        a.router.send(PacketType.CHAT, "q".repeat(1_500))
        advanceUntilIdle()

        assertEquals(1, b.delivered.size)
        assertEquals(1L, a.router.stats.value.bulkFallbacks)
    }

    @Test
    fun `a message too big for BLE is refused without a bulk channel`() = runTest {
        val a = node(1)
        val failure = runCatching { a.router.send(PacketType.CHAT, "x".repeat(50_000)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `an oversized message that cannot move does not block the messages behind it`() = runTest {
        net.bulkHub.failPushes = true
        val a = node(1, bulkConfig, bulk = true)
        val b = node(2, bulkConfig, bulk = true)
        a.router.send(PacketType.CHAT, "x".repeat(50_000)) // needs bulk, bulk is down
        a.router.send(PacketType.CHAT, "small")
        net.link(a.id, b.id)
        advanceUntilIdle()

        assertEquals(listOf("small"), b.delivered.map { it.text })
    }

    // ------------------------------------------------------------------ storm

    @Test
    fun `storm - eight signed nodes, full mesh plus a tail, everybody gets every sos once`() = runTest {
        val nodes = (1..8).map { node(it) }
        for (i in 0 until 6) for (j in i + 1 until 6) net.link(nodes[i].id, nodes[j].id) // nodes 1..6 full mesh
        net.link(nodes[5].id, nodes[6].id)
        net.link(nodes[6].id, nodes[7].id) // tail of two more hops

        for (n in nodes) repeat(3) { k -> n.router.send(PacketType.SOS, "sos ${n.id} #$k") }
        advanceUntilIdle()

        for (n in nodes) {
            assertEquals(21, n.delivered.size) // 7 other nodes x 3 messages
            assertEquals(21, n.delivered.map { it.msgId }.toSet().size)
            assertEquals(0L, n.router.stats.value.signatureRejected)
            assertEquals(0, n.router.stats.value.unackedSos)
        }
    }
}
