package com.leylink.core.codec

import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.Packet
import com.leylink.core.model.PacketType
import com.leylink.core.model.Priority
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCodecTest {

    private fun filled(value: Int, size: Int = 8) = ByteArray(size) { value.toByte() }

    private fun sample(
        payload: ByteArray = "help".toByteArray(),
        signature: ByteArray? = null,
        ttl: Int = 5,
        hop: Int = 1,
        beacon: Boolean = false,
    ) = Packet(
        type = PacketType.SOS,
        priority = Priority.SOS,
        ttl = ttl,
        hop = hop,
        msgId = MsgId(filled(1)),
        originId = NodeId(filled(2)),
        timestampMs = 1_700_000_000_000L,
        payload = payload,
        signature = signature,
        beacon = beacon,
    )

    private fun decodeError(bytes: ByteArray): String =
        (PacketCodec.decode(bytes) as DecodeResult.Error).reason

    @Test
    fun `unsigned packet round trips`() {
        val original = sample()
        val bytes = PacketCodec.encode(original)
        assertEquals(Packet.HEADER_SIZE + 4, bytes.size)

        val decoded = (PacketCodec.decode(bytes) as DecodeResult.Ok).packet
        assertEquals(original.type, decoded.type)
        assertEquals(original.priority, decoded.priority)
        assertEquals(original.ttl, decoded.ttl)
        assertEquals(original.hop, decoded.hop)
        assertEquals(original.msgId, decoded.msgId)
        assertEquals(original.originId, decoded.originId)
        assertEquals(original.timestampMs, decoded.timestampMs)
        assertArrayEquals(original.payload(), decoded.payload())
        assertFalse(decoded.isSigned)
    }

    @Test
    fun `signed packet with beacon flag round trips`() {
        val signature = filled(9, Packet.SIGNATURE_SIZE)
        val bytes = PacketCodec.encode(sample(signature = signature, beacon = true))
        assertEquals(Packet.HEADER_SIZE + 4 + Packet.SIGNATURE_SIZE, bytes.size)

        val decoded = (PacketCodec.decode(bytes) as DecodeResult.Ok).packet
        assertTrue(decoded.isSigned)
        assertTrue(decoded.beacon)
        assertArrayEquals(signature, decoded.signature())
    }

    @Test
    fun `wire layout matches the specification`() {
        val bytes = PacketCodec.encode(sample(ttl = 4, hop = 2))
        assertEquals(1, bytes[0].toInt()) // version
        assertEquals(PacketType.SOS.code, bytes[1].toInt()) // type
        assertEquals(0, bytes[2].toInt()) // flags
        assertEquals(Priority.SOS.level, bytes[3].toInt()) // priority
        assertEquals(4, bytes[4].toInt()) // ttl
        assertEquals(2, bytes[5].toInt()) // hop
        assertEquals(1, bytes[6].toInt()) // first msgId byte
        assertEquals(2, bytes[14].toInt()) // first originId byte
        assertEquals(0, bytes[30].toInt()) // payloadLen high byte (big-endian)
        assertEquals(4, bytes[31].toInt()) // payloadLen low byte
        assertEquals('h'.code, bytes[32].toInt()) // payload starts at offset 32
    }

    @Test
    fun `originTtl is preserved by relaying`() {
        val origin = sample(ttl = 5, hop = 1)
        val relayed = origin.relayed()
        assertEquals(4, relayed.ttl)
        assertEquals(2, relayed.hop)
        assertEquals(origin.originTtl, relayed.originTtl)
        assertThrows(IllegalArgumentException::class.java) { sample(ttl = 1).relayed() }
    }

    @Test
    fun `decode rejects hostile or broken input without throwing`() {
        val good = PacketCodec.encode(sample())

        assertTrue(decodeError(ByteArray(10)).contains("too short"))
        assertTrue(decodeError(good.copyOf().also { it[0] = 2 }).contains("version"))
        assertTrue(decodeError(good.copyOf().also { it[1] = 0x7F }).contains("type"))
        assertTrue(decodeError(good.copyOf().also { it[2] = 0x80.toByte() }).contains("reserved"))
        assertTrue(decodeError(good.copyOf().also { it[3] = 9 }).contains("priority"))
        assertTrue(decodeError(good.copyOf().also { it[4] = 0 }).contains("ttl"))
        assertTrue(decodeError(good.copyOf().also { it[4] = 6 }).contains("ttl"))
        assertTrue(decodeError(good.copyOf().also { it[5] = 0 }).contains("hop"))
        assertTrue(decodeError(good + byteArrayOf(0)).contains("length"))
        assertTrue(decodeError(good.copyOf(good.size - 1)).contains("length"))
    }

    @Test
    fun `decode rejects packets that claim more reach than the maximum ttl`() {
        // ttl 5 after 2 links means the origin started with 6: impossible, so someone tampered with it.
        val tampered = PacketCodec.encode(sample(ttl = 5, hop = 2))
        assertTrue(decodeError(tampered).contains("origin ttl"))
    }

    @Test
    fun `signed region ignores ttl and hop but covers the payload`() {
        val a = PacketCodec.signedRegion(sample(ttl = 5, hop = 1))
        val relayed = PacketCodec.signedRegion(sample(ttl = 3, hop = 3))
        val other = PacketCodec.signedRegion(sample(payload = "HELP".toByteArray()))
        assertArrayEquals(a, relayed)
        assertNotEquals(a.toList(), other.toList())
    }
}

class PacketKeyCodecTest {
    private fun packet(signature: ByteArray?, key: ByteArray?, ttl: Int = 5, hop: Int = 1) = Packet(
        type = PacketType.SOS, priority = Priority.SOS, ttl = ttl, hop = hop,
        msgId = MsgId(ByteArray(8) { 1 }), originId = NodeId(ByteArray(8) { 2 }),
        timestampMs = 5, payload = "help".toByteArray(), signature = signature, publicKey = key,
    )

    @Test
    fun `packet with public key and signature round trips`() {
        val key = ByteArray(Packet.PUBLIC_KEY_SIZE) { 3 }
        val sig = ByteArray(Packet.SIGNATURE_SIZE) { 4 }
        val bytes = PacketCodec.encode(packet(sig, key))
        assertEquals(Packet.HEADER_SIZE + 4 + Packet.PUBLIC_KEY_SIZE + Packet.SIGNATURE_SIZE, bytes.size)

        val decoded = (PacketCodec.decode(bytes) as DecodeResult.Ok).packet
        assertArrayEquals(key, decoded.publicKey())
        assertArrayEquals(sig, decoded.signature())
        assertTrue(decoded.hasPublicKey)
    }

    @Test
    fun `a public key flag without a signature is rejected`() {
        val bytes = PacketCodec.encode(packet(null, ByteArray(Packet.PUBLIC_KEY_SIZE)))
        val reason = (PacketCodec.decode(bytes) as DecodeResult.Error).reason
        assertTrue(reason.contains("without signature"))
    }

    @Test
    fun `signed region is the same before and after the signature is attached`() {
        val key = ByteArray(Packet.PUBLIC_KEY_SIZE) { 3 }
        val before = PacketCodec.signedRegion(packet(null, key))
        val after = PacketCodec.signedRegion(packet(ByteArray(Packet.SIGNATURE_SIZE) { 9 }, key, ttl = 2, hop = 4))
        assertArrayEquals(before, after) // ttl, hop and the signature itself are not covered
    }

    @Test
    fun `signed region covers the public key`() {
        val a = PacketCodec.signedRegion(packet(null, ByteArray(Packet.PUBLIC_KEY_SIZE) { 3 }))
        val b = PacketCodec.signedRegion(packet(null, ByteArray(Packet.PUBLIC_KEY_SIZE) { 4 }))
        assertNotEquals(a.toList(), b.toList())
    }
}
