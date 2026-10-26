package com.leylink.core.codec

import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId

/**
 * Rebuilds packets from chunk frames. Bounded: at most [maxInFlight] partial packets, each dropped
 * after [timeoutMs] without completion, so a dropped link cannot leak memory.
 *
 * NOT thread-safe: owned by the router's single event loop.
 */
class Reassembler(
    private val timeoutMs: Long = 30_000L,
    private val maxInFlight: Int = 32,
) {
    sealed interface Result {
        class Complete(val msgId: MsgId, val bytes: ByteArray) : Result

        data object Incomplete : Result

        data class Rejected(val reason: String) : Result
    }

    private data class Key(val from: NodeId, val msgId: MsgId)

    private class Buffer(val total: Int, var lastUpdateMs: Long) {
        val parts: Array<ByteArray?> = arrayOfNulls(total)
        var received: Int = 0
    }

    private val buffers = LinkedHashMap<Key, Buffer>()

    val pending: Int get() = buffers.size

    @Suppress("ReturnCount")
    fun accept(from: NodeId, frame: ByteArray, nowMs: Long): Result {
        evictStale(nowMs)
        val chunk = ChunkFrame.parse(frame) ?: return Result.Rejected("malformed chunk frame")

        if (chunk.total == 1) return Result.Complete(chunk.msgId, chunk.data()) // fast path

        val key = Key(from, chunk.msgId)
        var buffer = buffers[key]
        if (buffer != null && buffer.total != chunk.total) {
            buffers.remove(key)
            return Result.Rejected("inconsistent chunk total")
        }
        if (buffer == null) {
            if (buffers.size >= maxInFlight) {
                buffers.remove(buffers.keys.first()) // evict the oldest partial packet
            }
            buffer = Buffer(chunk.total, nowMs)
            buffers[key] = buffer
        }

        if (buffer.parts[chunk.index] != null) return Result.Incomplete // duplicate chunk, ignore
        buffer.parts[chunk.index] = chunk.data()
        buffer.received++
        buffer.lastUpdateMs = nowMs
        if (buffer.received < buffer.total) return Result.Incomplete

        buffers.remove(key)
        val size = buffer.parts.sumOf { it?.size ?: 0 }
        val out = ByteArray(size)
        var offset = 0
        for (part in buffer.parts) {
            val bytes = part ?: continue
            bytes.copyInto(out, destinationOffset = offset)
            offset += bytes.size
        }
        return Result.Complete(chunk.msgId, out)
    }

    /** Drops partial packets from a peer whose link went away. */
    fun dropFrom(peer: NodeId) {
        buffers.keys.removeAll { it.from == peer }
    }

    private fun evictStale(nowMs: Long) {
        buffers.values.removeAll { nowMs - it.lastUpdateMs > timeoutMs }
    }
}
