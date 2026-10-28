package com.leylink.app.mesh

import com.leylink.core.model.NodeId
import com.leylink.core.model.PacketType
import com.leylink.core.model.StoredMessage
import com.leylink.core.router.RouterStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the chat UI needs from the mesh. Implemented by [MeshEngine]; faked in tests. */
interface MessagingController {
    val selfId: NodeId

    /** History, newest first. */
    val messages: Flow<List<StoredMessage>>

    /** Peers with an open BLE link right now. */
    val peerCount: StateFlow<Int>

    /** Live counters for the stats screen: deliveries, duplicates, SOS dropped, beacon, Wi-Fi Direct. */
    val stats: StateFlow<RouterStats>

    /** @throws IllegalArgumentException e.g. for an SOS longer than 280 characters. */
    suspend fun send(type: PacketType, text: String)
}
