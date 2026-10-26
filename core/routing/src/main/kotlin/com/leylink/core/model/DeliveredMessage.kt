package com.leylink.core.model

/** A message shown to the user the first time this node sees it. */
data class DeliveredMessage(
    val msgId: MsgId,
    val originId: NodeId,
    val type: PacketType,
    val text: String,
    /** Sender's wall clock. Display only. */
    val timestampMs: Long,
    /** Links this copy crossed to reach us (1 = direct neighbour). */
    val hop: Int,
    /** TTL the origin started with (ttl + hop - 1). */
    val originTtl: Int,
    val receivedAtMs: Long,
)
