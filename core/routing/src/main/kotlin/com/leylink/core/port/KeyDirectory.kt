package com.leylink.core.port

import com.leylink.core.model.NodeId

/** Result of pinning an origin's public key (trust on first use). */
enum class PinResult {
    /** First time we see this origin: its key is now pinned. */
    NEW,

    /** Same key as the pinned one. */
    KNOWN,

    /** A DIFFERENT key than the pinned one: possible impersonation, the packet must be dropped. */
    MISMATCH,
}

/**
 * Trust-on-first-use directory of origin public keys. Implementations must be thread-safe; the Android
 * one persists the pins so they survive a restart.
 */
interface KeyDirectory {
    suspend fun pin(origin: NodeId, publicKey: ByteArray): PinResult

    /** Number of origins pinned so far (shown on the stats screen). */
    suspend fun count(): Int
}
