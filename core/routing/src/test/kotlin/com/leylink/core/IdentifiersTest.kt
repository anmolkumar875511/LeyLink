package com.leylink.core

import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.PacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifiersTest {

    @Test
    fun `id rejects wrong length`() {
        assertThrows(IllegalArgumentException::class.java) { MsgId(ByteArray(7)) }
        assertThrows(IllegalArgumentException::class.java) { NodeId(ByteArray(9)) }
    }

    @Test
    fun `same bytes are equal and hash equal`() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val a = MsgId(raw)
        val b = MsgId(raw.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(setOf(a).contains(b))
    }

    @Test
    fun `id is defensively copied`() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val id = MsgId(raw)
        raw[0] = 99 // mutate the caller's array
        id.toByteArray()[1] = 99 // mutate a returned copy
        assertEquals("0102030405060708", id.toString())
    }

    @Test
    fun `node id and msg id with same bytes are different types`() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertNotEquals(NodeId(raw), MsgId(raw))
    }

    @Test
    fun `node id ordering is unsigned`() {
        val low = NodeId(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
        val high = NodeId(byteArrayOf(0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0))
        assertTrue(low < high) // 0xFF must sort AFTER 0x00
        assertTrue(high > low)
        assertEquals(0, low.compareTo(NodeId(low.toByteArray())))
    }

    @Test
    fun `random ids differ`() {
        assertNotEquals(MsgId.random(), MsgId.random())
        assertNotEquals(NodeId.random(), NodeId.random())
    }

    @Test
    fun `packet type codes round trip`() {
        for (type in PacketType.entries) {
            assertEquals(type, PacketType.fromCode(type.code))
        }
        assertNull(PacketType.fromCode(0x7F))
    }
}
