package com.leylink.core.testing

import com.leylink.core.model.MsgId
import com.leylink.core.port.SeenOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemorySeenStoreTest {
    private val id = MsgId(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1))

    @Test
    fun `first copy is NEW and unseen ttl is zero`() = runTest {
        val store = InMemorySeenStore()
        assertEquals(0, store.bestTtl(id))
        assertEquals(SeenOutcome.NEW, store.recordIfBetter(id, ttl = 2, nowMs = 0))
        assertEquals(2, store.bestTtl(id))
    }

    @Test
    fun `equal or lower ttl copies are DUPLICATE`() = runTest {
        val store = InMemorySeenStore()
        store.recordIfBetter(id, ttl = 3, nowMs = 0)
        assertEquals(SeenOutcome.DUPLICATE, store.recordIfBetter(id, ttl = 3, nowMs = 1))
        assertEquals(SeenOutcome.DUPLICATE, store.recordIfBetter(id, ttl = 1, nowMs = 2))
        assertEquals(3, store.bestTtl(id))
    }

    /** The "late higher-TTL copy" case from the README proofs: it must still be accepted. */
    @Test
    fun `later copy with higher ttl is IMPROVED`() = runTest {
        val store = InMemorySeenStore()
        assertEquals(SeenOutcome.NEW, store.recordIfBetter(id, ttl = 1, nowMs = 0)) // arrived first via a long path
        assertEquals(SeenOutcome.IMPROVED, store.recordIfBetter(id, ttl = 4, nowMs = 5)) // later, via a shorter path
        assertEquals(4, store.bestTtl(id))
    }

    @Test
    fun `purge removes only old entries`() = runTest {
        val store = InMemorySeenStore()
        val other = MsgId(byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2))
        store.recordIfBetter(id, ttl = 1, nowMs = 10)
        store.recordIfBetter(other, ttl = 1, nowMs = 100)
        assertEquals(1, store.purgeOlderThan(cutoffMs = 50))
        assertEquals(0, store.bestTtl(id))
        assertEquals(1, store.bestTtl(other))
    }
}
