package com.leylink.core.codec

import com.leylink.core.model.MsgId
import com.leylink.core.port.SeenEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XferAndDigestTest {
    private val id = MsgId(ByteArray(8) { 7 })

    @Test
    fun `offer round trips`() {
        val hash = ByteArray(32) { it.toByte() }
        val decoded = Xfer.decode(Xfer.encode(Xfer.Offer(id, ttl = 4, size = 12_345, sha256 = hash))) as Xfer.Offer
        assertEquals(id, decoded.msgId)
        assertEquals(4, decoded.ttl)
        assertEquals(12_345, decoded.size)
        assertArrayEquals(hash, decoded.sha256())
    }

    @Test
    fun `accept and decline round trip`() {
        val info = "DIRECT-ab\u0000secret".toByteArray()
        val accept = Xfer.decode(Xfer.encode(Xfer.Accept(id, info))) as Xfer.Accept
        assertArrayEquals(info, accept.info())
        assertTrue(Xfer.decode(Xfer.encode(Xfer.Decline(id))) is Xfer.Decline)
    }

    @Test
    fun `malformed payloads decode to null`() {
        assertNull(Xfer.decode(ByteArray(0)))
        assertNull(Xfer.decode(byteArrayOf(9) + id.toByteArray())) // unknown sub-type
        assertNull(Xfer.decode(byteArrayOf(1) + id.toByteArray())) // offer without body
        assertNull(Xfer.decode(byteArrayOf(2) + id.toByteArray())) // accept without info
        assertNull(Xfer.decode(byteArrayOf(3) + id.toByteArray() + 0)) // decline with trailing byte
    }

    @Test
    fun `digest ignores order and notices a different ttl`() {
        val a = SeenEntry(MsgId(ByteArray(8) { 1 }), 5)
        val b = SeenEntry(MsgId(ByteArray(8) { 2 }), 3)
        assertEquals(SyncDigest.of(listOf(a, b)), SyncDigest.of(listOf(b, a)))
        assertNotEquals(SyncDigest.of(listOf(a, b)), SyncDigest.of(listOf(a, SeenEntry(b.msgId, 4))))
        assertTrue(SyncDigest.of(listOf(a, b)) in 0..0xFFFF)
    }
}
