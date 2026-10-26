package com.leylink.core.model

/** A nearby node as seen by a transport. */
data class Peer(
    val nodeId: NodeId,
    val rssi: Int,
    val lastSeenMs: Long,
)
