package com.leylink.core.model

import java.security.SecureRandom

/** Identifies a phone in the mesh (the packet's originId). Immutable, usable as a map key. */
class NodeId(bytes: ByteArray) : Comparable<NodeId> {
    private val raw: ByteArray = bytes.copyOf() // defensive copy: callers cannot mutate our key

    init {
        require(raw.size == LENGTH) { "NodeId must be exactly $LENGTH bytes, got ${raw.size}" }
    }

    fun toByteArray(): ByteArray = raw.copyOf()

    /** Unsigned lexicographic order. Used for the "lower nodeId initiates" tie-break. */
    override fun compareTo(other: NodeId): Int {
        for (i in 0 until LENGTH) {
            val diff = (raw[i].toInt() and 0xFF) - (other.raw[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is NodeId && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = raw.toHex()

    companion object {
        const val LENGTH = 8

        fun random(random: SecureRandom = SecureRandom()): NodeId =
            NodeId(ByteArray(LENGTH).also { random.nextBytes(it) })
    }
}
