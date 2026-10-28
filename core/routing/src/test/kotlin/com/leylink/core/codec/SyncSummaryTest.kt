package com.leylink.core.codec

import com.leylink.core.model.MsgId
import com.leylink.core.port.SeenEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncSummaryTest {
    private fun entry(n: Int, ttl: Int) = SeenEntry(MsgId(ByteArray(8) { n.toByte() }), ttl)

    @Test
    fun `round trips entries`() {
        val entries = listOf(entry(1, 5), entry(2, 3))
        val bytes = SyncSummary.encode(entries)
        assertEquals(2 + 9 * 2, bytes.size)
        assertEquals(entries, SyncSummary.decode(bytes))
    }

    @Test
    fun `empty summary is valid`() {
        assertEquals(emptyList<SeenEntry>(), SyncSummary.decode(SyncSummary.encode(emptyList())))
    }

    @Test
    fun `rejects malformed payloads`() {
        assertNull(SyncSummary.decode(ByteArray(0)))
        assertNull(SyncSummary.decode(byteArrayOf(2, 0, 1, 2, 3))) // claims 2 entries, has none
        val good = SyncSummary.encode(listOf(entry(1, 5)))
        assertNull(SyncSummary.decode(good + byteArrayOf(0))) // trailing byte
    }

    @Test
    fun `rejects more than 255 entries`() {
        val tooMany = List(256) { entry(it, 1) }
        assertThrows(IllegalArgumentException::class.java) { SyncSummary.encode(tooMany) }
    }
}
