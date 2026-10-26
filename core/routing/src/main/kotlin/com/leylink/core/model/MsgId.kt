package com.leylink.core.model

import java.security.SecureRandom

/** Random per-message identifier used for deduplication. Immutable, usable as a map key. */
class MsgId(bytes: ByteArray) {
    private val raw: ByteArray = bytes.copyOf()

    init {
        require(raw.size == LENGTH) { "MsgId must be exactly $LENGTH bytes, got ${raw.size}" }
    }

    fun toByteArray(): ByteArray = raw.copyOf()

    override fun equals(other: Any?): Boolean = other is MsgId && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = raw.toHex()

    companion object {
        const val LENGTH = 8

        fun random(random: SecureRandom = SecureRandom()): MsgId =
            MsgId(ByteArray(LENGTH).also { random.nextBytes(it) })
    }
}
