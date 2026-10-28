package com.leylink.core.port

import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.OutboxEntry

/**
 * Persistent store-and-forward queue. The Android implementation (Stage 4) is backed by Room/SQLite;
 * tests use an in-memory version. Implementations must be thread-safe.
 *
 * Contract:
 *  - Once [enqueue] returns, the entry survives a process crash.
 *  - One entry per message ID ("replace-if-better"): [enqueue] stores the entry only if there is no
 *    entry for that msgId yet or the new [OutboxEntry.ttl] is strictly higher than the stored one. A
 *    replacement CLEARS the "already sent to" markers, because peers that got the worse copy need the
 *    better one. A worse or equal entry is ignored.
 */
interface OutboxStore {
    suspend fun enqueue(entry: OutboxEntry)

    /**
     * Next entries to send to [peer], ordered by priority (lowest level first) then oldest first,
     * excluding entries already marked sent to that peer.
     */
    suspend fun nextBatch(peer: NodeId, limit: Int): List<OutboxEntry>

    /**
     * Records that the version of [msgId] with [ttl] was fully sent to [peer]. Ignored if the stored
     * entry has a different ttl (it was replaced meanwhile) or no longer exists.
     */
    suspend fun markSent(msgId: MsgId, ttl: Int, peer: NodeId)

    /**
     * The peer told us (sync summary) it already holds [msgId] with best TTL [peerBestTtl]. If our stored
     * version has a ttl <= that, there is nothing to send to this peer: mark it as sent.
     */
    suspend fun markPeerHas(peer: NodeId, msgId: MsgId, peerBestTtl: Int)

    /** Removes entries created before [cutoffMs]. Returns how many were removed. */
    suspend fun expireOlderThan(cutoffMs: Long): Int

    suspend fun size(): Int
}
