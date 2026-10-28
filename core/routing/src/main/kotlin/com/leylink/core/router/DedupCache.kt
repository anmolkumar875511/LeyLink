package com.leylink.core.router

import com.leylink.core.model.MsgId
import com.leylink.core.port.SeenEntry
import com.leylink.core.port.SeenOutcome
import com.leylink.core.port.SeenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory LRU in front of a persistent [SeenStore], so the hot path ("is this a duplicate?")
 * rarely touches the database.
 *
 * The LRU only stores LOWER BOUNDS of the best TTL. A copy whose ttl is <= the lower bound is surely a
 * duplicate; anything else is decided by the store. This keeps the cache correct even after eviction.
 */
class DedupCache(
    private val store: SeenStore,
    private val capacity: Int = 4096,
) {
    private val mutex = Mutex()

    private val lowerBounds = object : LinkedHashMap<MsgId, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MsgId, Int>?): Boolean = size > capacity
    }

    suspend fun record(msgId: MsgId, ttl: Int, nowMs: Long): SeenOutcome = mutex.withLock {
        val known = lowerBounds[msgId]
        if (known != null && ttl <= known) return@withLock SeenOutcome.DUPLICATE
        val outcome = store.recordIfBetter(msgId, ttl, nowMs)
        lowerBounds[msgId] = maxOf(known ?: 0, ttl)
        outcome
    }

    /** Highest TTL accepted so far for [msgId], 0 if never seen. */
    suspend fun bestTtl(msgId: MsgId): Int = mutex.withLock { maxOf(lowerBounds[msgId] ?: 0, store.bestTtl(msgId)) }

    suspend fun snapshot(limit: Int): List<SeenEntry> = store.snapshot(limit)

    /** Forget the in-memory lower bounds, e.g. after a failed transaction rolled the store back. */
    suspend fun clear() = mutex.withLock { lowerBounds.clear() }

    suspend fun purgeOlderThan(cutoffMs: Long): Int = mutex.withLock {
        lowerBounds.clear() // lower bounds are always safe to forget
        store.purgeOlderThan(cutoffMs)
    }
}
