package com.leylink.core.model

/** A message as kept in history and shown in the chat list. */
data class StoredMessage(
    val msgId: MsgId,
    val originId: NodeId,
    val type: PacketType,
    val text: String,
    /** Sender's wall clock. Display only. */
    val timestampMs: Long,
    /** Links the copy crossed to reach us (0 for messages we wrote ourselves). */
    val hop: Int,
    val originTtl: Int,
    val receivedAtMs: Long,
    val mine: Boolean,
)
