package com.leylink.core.testing

import com.leylink.core.model.MsgId
import com.leylink.core.port.SeenOutcome
import com.leylink.core.port.SeenStore
import com.leylink.core.router.DedupCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DedupCacheTest {

    /** Counts calls so tests can prove the LRU answers without touching the store. */
    private class CountingSeenStore(private val inner: SeenStore) : SeenStore {
        var recordCalls = 0
            private set
        var purgeCalls = 0
            private set

        override suspend fun bestTtl(msgId: MsgId): Int = inner.bestTtl(msgId)

        override suspend fun recordIfBetter(msgId: MsgId, ttl: Int, nowMs: Long): SeenOutcome {
            recordCalls++
            return inner.recordIfBetter(msgId, ttl, nowMs)
        }

        override suspend fun purgeOlderThan(cutoffMs: Long): Int {
            purgeCalls++
            return inner.purgeOlderThan(cutoffMs)
        }
    }

    private fun id(n: Int) = MsgId(ByteArray(8) { n.toByte() })

    @Test
    fun `known duplicates are answered without touching the store`() = runTest {
        val store = CountingSeenStore(InMemorySeenStore())
        val cache = DedupCache(store)

        assertEquals(SeenOutcome.NEW, cache.record(id(1), ttl = 3, nowMs = 0))
        assertEquals(SeenOutcome.DUPLICATE, cache.record(id(1), ttl = 3, nowMs = 1))
        assertEquals(SeenOutcome.DUPLICATE, cache.record(id(1), ttl = 2, nowMs = 2))
        assertEquals(1, store.recordCalls)
    }

    @Test
    fun `a higher ttl copy goes through to the store and is IMPROVED`() = runTest {
        val store = CountingSeenStore(InMemorySeenStore())
        val cache = DedupCache(store)

        assertEquals(SeenOutcome.NEW, cache.record(id(1), ttl = 1, nowMs = 0))
        assertEquals(SeenOutcome.IMPROVED, cache.record(id(1), ttl = 4, nowMs = 1))
        assertEquals(SeenOutcome.DUPLICATE, cache.record(id(1), ttl = 4, nowMs = 2))
        assertEquals(2, store.recordCalls)
    }

    @Test
    fun `eviction from the LRU never makes a duplicate look new`() = runTest {
        val store = CountingSeenStore(InMemorySeenStore())
        val cache = DedupCache(store, capacity = 2)

        cache.record(id(1), ttl = 3, nowMs = 0)
        cache.record(id(2), ttl = 3, nowMs = 0)
        cache.record(id(3), ttl = 3, nowMs = 0) // evicts id(1) from the LRU

        // The store still remembers it, so the answer stays correct (just slower).
        assertEquals(SeenOutcome.DUPLICATE, cache.record(id(1), ttl = 3, nowMs = 1))
        assertEquals(4, store.recordCalls)
    }

    @Test
    fun `purge clears the lru and the store`() = runTest {
        val store = CountingSeenStore(InMemorySeenStore())
        val cache = DedupCache(store)
        cache.record(id(1), ttl = 3, nowMs = 0)

        assertEquals(1, cache.purgeOlderThan(cutoffMs = 100))
        assertEquals(1, store.purgeCalls)
        assertEquals(SeenOutcome.NEW, cache.record(id(1), ttl = 3, nowMs = 200))
    }
}
