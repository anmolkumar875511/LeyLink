package com.leylink.core.testing

import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.OutboxEntry
import com.leylink.core.port.OutboxStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory [OutboxStore] honouring the "replace-if-better" contract. Thread-safe. */
class InMemoryOutboxStore : OutboxStore {
    private class Stored(val entry: OutboxEntry) {
        val sentTo = HashSet<NodeId>()
    }

    private val mutex = Mutex()
    private val items = LinkedHashMap<MsgId, Stored>()

    override suspend fun enqueue(entry: OutboxEntry) {
        mutex.withLock {
            val existing = items[entry.msgId]
            if (existing == null || entry.ttl > existing.entry.ttl) {
                items[entry.msgId] = Stored(entry) // a replacement starts with an empty "sent to" set
            }
        }
    }

    override suspend fun nextBatch(peer: NodeId, limit: Int): List<OutboxEntry> = mutex.withLock {
        items.values
            .filter { peer !in it.sentTo }
            .map { it.entry }
            .sortedWith(compareBy({ it.priority.level }, { it.createdAtMs })) // stable: ties keep insertion order
            .take(limit)
    }

    override suspend fun markSent(msgId: MsgId, ttl: Int, peer: NodeId) {
        mutex.withLock {
            val stored = items[msgId]
            if (stored != null && stored.entry.ttl == ttl) stored.sentTo.add(peer)
        }
    }

    override suspend fun markPeerHas(peer: NodeId, msgId: MsgId, peerBestTtl: Int) {
        mutex.withLock {
            val stored = items[msgId]
            if (stored != null && stored.entry.ttl <= peerBestTtl) stored.sentTo.add(peer)
        }
    }

    override suspend fun expireOlderThan(cutoffMs: Long): Int = mutex.withLock {
        val before = items.size
        items.values.removeAll { it.entry.createdAtMs < cutoffMs }
        before - items.size
    }

    override suspend fun size(): Int = mutex.withLock { items.size }

    /** Test helper: the ttl currently stored for a message, or null. */
    suspend fun ttlOf(msgId: MsgId): Int? = mutex.withLock { items[msgId]?.entry?.ttl }
}
