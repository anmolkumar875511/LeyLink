package com.leylink.core.testing

import com.leylink.core.model.MsgId
import com.leylink.core.port.SeenEntry
import com.leylink.core.port.SeenOutcome
import com.leylink.core.port.SeenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory [SeenStore] for simulator and unit tests. Thread-safe. */
class InMemorySeenStore : SeenStore {
    private data class Entry(val ttl: Int, val recordedAtMs: Long)

    private val mutex = Mutex()
    private val entries = HashMap<MsgId, Entry>()

    override suspend fun bestTtl(msgId: MsgId): Int = mutex.withLock { entries[msgId]?.ttl ?: 0 }

    override suspend fun recordIfBetter(msgId: MsgId, ttl: Int, nowMs: Long): SeenOutcome = mutex.withLock {
        val current = entries[msgId]?.ttl
        when {
            current == null -> {
                entries[msgId] = Entry(ttl, nowMs)
                SeenOutcome.NEW
            }
            ttl > current -> {
                entries[msgId] = Entry(ttl, nowMs)
                SeenOutcome.IMPROVED
            }
            else -> SeenOutcome.DUPLICATE
        }
    }

    override suspend fun snapshot(limit: Int): List<SeenEntry> = mutex.withLock {
        entries.entries
            .sortedByDescending { it.value.recordedAtMs }
            .take(limit)
            .map { SeenEntry(it.key, it.value.ttl) }
    }

    override suspend fun purgeOlderThan(cutoffMs: Long): Int = mutex.withLock {
        val before = entries.size
        entries.values.removeAll { it.recordedAtMs < cutoffMs }
        before - entries.size
    }
}
