package com.leylink.core.model

/**
 * One encoded packet waiting to be (re)sent.
 *
 * [packetBytes] holds the packet exactly as it must be transmitted NEXT (for a relayed packet that is
 * already ttl - 1 and hop + 1), and [ttl] mirrors the ttl inside those bytes so a store can compare
 * versions of the same message without decoding.
 */
class OutboxEntry(
    val msgId: MsgId,
    packetBytes: ByteArray,
    val priority: Priority,
    val ttl: Int,
    /** LOCAL creation/receive time. Never the sender's clock (phones in a disaster may have wrong time). */
    val createdAtMs: Long,
) {
    private val bytes: ByteArray = packetBytes.copyOf()

    fun packetBytes(): ByteArray = bytes.copyOf()
}
