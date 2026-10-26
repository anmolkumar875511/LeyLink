package com.leylink.core.codec

import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkerReassemblerTest {
    private val msgId = MsgId(ByteArray(8) { 7 })
    private val alice = NodeId(ByteArray(8) { 1 })
    private val bob = NodeId(ByteArray(8) { 2 })

    private fun packetOf(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun Reassembler.Result.bytes(): ByteArray = (this as Reassembler.Result.Complete).bytes

    @Test
    fun `small packet fits in one frame`() {
        val frames = Chunker.split(msgId, packetOf(40))
        assertEquals(1, frames.size)
        assertEquals(40 + ChunkFrame.HEADER_SIZE, frames[0].size)
    }

    @Test
    fun `600 byte packet needs four frames of at most 180 bytes`() {
        val frames = Chunker.split(msgId, packetOf(632))
        assertEquals(4, frames.size)
        assertTrue(frames.all { it.size <= 180 })
    }

    @Test
    fun `reassembles in order and out of order`() {
        val original = packetOf(632)
        val frames = Chunker.split(msgId, original)

        val inOrder = Reassembler()
        var result: Reassembler.Result = Reassembler.Result.Incomplete
        for (f in frames) result = inOrder.accept(alice, f, nowMs = 0)
        assertArrayEquals(original, result.bytes())

        val shuffled = Reassembler()
        for (f in frames.reversed()) result = shuffled.accept(alice, f, nowMs = 0)
        assertArrayEquals(original, result.bytes())
    }

    @Test
    fun `duplicate chunks are ignored`() {
        val original = packetOf(300)
        val frames = Chunker.split(msgId, original)
        val r = Reassembler()
        assertEquals(Reassembler.Result.Incomplete, r.accept(alice, frames[0], 0))
        assertEquals(Reassembler.Result.Incomplete, r.accept(alice, frames[0], 0)) // same chunk again
        val done = r.accept(alice, frames[1], 0)
        assertArrayEquals(original, done.bytes())
        assertEquals(0, r.pending)
    }

    @Test
    fun `rejects a packet that needs more than 255 frames`() {
        assertThrows(IllegalArgumentException::class.java) {
            Chunker.split(msgId, packetOf(Chunker.maxPacketBytes() + 1))
        }
    }

    @Test
    fun `rejects malformed frames and inconsistent totals`() {
        val r = Reassembler()
        assertTrue(r.accept(alice, byteArrayOf(1, 2, 3), 0) is Reassembler.Result.Rejected)

        val first = ChunkFrame(msgId, index = 0, total = 3, data = byteArrayOf(1)).encode()
        val liar = ChunkFrame(msgId, index = 1, total = 4, data = byteArrayOf(2)).encode()
        assertEquals(Reassembler.Result.Incomplete, r.accept(alice, first, 0))
        assertTrue(r.accept(alice, liar, 0) is Reassembler.Result.Rejected)
        assertEquals(0, r.pending) // the poisoned buffer is dropped
    }

    @Test
    fun `stale partial packets are evicted`() {
        val frames = Chunker.split(msgId, packetOf(300))
        val r = Reassembler(timeoutMs = 30_000)
        r.accept(alice, frames[0], nowMs = 0)
        assertEquals(1, r.pending)
        // The second chunk arrives after the timeout: the first half is gone, so we cannot complete.
        assertEquals(Reassembler.Result.Incomplete, r.accept(alice, frames[1], nowMs = 40_000))
        assertEquals(1, r.pending)
    }

    @Test
    fun `in flight buffers are bounded`() {
        val r = Reassembler(maxInFlight = 2)
        for (i in 1..3) {
            val id = MsgId(ByteArray(8) { i.toByte() })
            r.accept(alice, ChunkFrame(id, 0, 2, byteArrayOf(1)).encode(), nowMs = i.toLong())
        }
        assertEquals(2, r.pending)
    }

    @Test
    fun `chunks from different peers never mix`() {
        val frames = Chunker.split(msgId, packetOf(300))
        val r = Reassembler()
        assertEquals(Reassembler.Result.Incomplete, r.accept(alice, frames[0], 0))
        assertEquals(Reassembler.Result.Incomplete, r.accept(bob, frames[1], 0)) // same msgId, other sender
        assertEquals(2, r.pending)
        assertTrue(r.accept(alice, frames[1], 0) is Reassembler.Result.Complete)
    }

    @Test
    fun `dropFrom forgets a lost peer`() {
        val frames = Chunker.split(msgId, packetOf(300))
        val r = Reassembler()
        r.accept(alice, frames[0], 0)
        r.dropFrom(alice)
        assertEquals(0, r.pending)
    }
}
