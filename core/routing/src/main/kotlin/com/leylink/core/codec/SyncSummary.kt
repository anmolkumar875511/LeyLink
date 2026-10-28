package com.leylink.core.codec

import com.leylink.core.model.MsgId
import com.leylink.core.port.SeenEntry

/**
 * SYNC_SUMMARY payload (README section 5.3): count(1) | flags(1) | count x [msgId(8) + bestTtl(1)].
 * "Here is what I already have", so the peer can skip sending those packets.
 */
object SyncSummary {
    const val MAX_ENTRIES = 255
    private const val HEADER = 2
    private const val ENTRY = MsgId.LENGTH + 1

    fun encode(entries: List<SeenEntry>): ByteArray {
        require(entries.size <= MAX_ENTRIES) { "at most $MAX_ENTRIES entries per summary" }
        val out = ByteArray(HEADER + ENTRY * entries.size)
        out[0] = entries.size.toByte()
        entries.forEachIndexed { i, e ->
            val at = HEADER + i * ENTRY
            e.msgId.toByteArray().copyInto(out, destinationOffset = at)
            out[at + MsgId.LENGTH] = e.ttl.toByte()
        }
        return out
    }

    /** @return the entries, or null if the payload is malformed. */
    fun decode(payload: ByteArray): List<SeenEntry>? {
        if (payload.size < HEADER) return null
        val count = payload[0].toInt() and 0xFF
        if (payload.size != HEADER + ENTRY * count) return null
        return List(count) { i ->
            val at = HEADER + i * ENTRY
            SeenEntry(
                msgId = MsgId(payload.copyOfRange(at, at + MsgId.LENGTH)),
                ttl = payload[at + MsgId.LENGTH].toInt() and 0xFF,
            )
        }
    }
}
