package com.leylink.core.port

import com.leylink.core.model.MsgId

/** Result of recording an incoming copy against the dedup memory. */
enum class SeenOutcome {
    /** First time this message is seen: show it to the user and consider relaying. */
    NEW,

    /** Seen before, but this copy has a strictly higher TTL: relay it, do not show it again. */
    IMPROVED,

    /** Same or worse TTL than a copy we already accepted: drop. */
    DUPLICATE,
}

/**
 * Dedup memory with the TTL-aware rule from the README proofs: for each message we remember the
 * highest TTL ever accepted ("best"), not merely that we saw it. Implementations must be thread-safe.
 */
interface SeenStore {
    /** Highest TTL accepted so far for this message, or 0 if never seen. */
    suspend fun bestTtl(msgId: MsgId): Int

    /** Atomically records [ttl] if it is strictly higher than the stored best. */
    suspend fun recordIfBetter(msgId: MsgId, ttl: Int, nowMs: Long): SeenOutcome

    /** Forgets entries recorded before [cutoffMs]. Retention must be >= packet lifetime. */
    suspend fun purgeOlderThan(cutoffMs: Long): Int
}
