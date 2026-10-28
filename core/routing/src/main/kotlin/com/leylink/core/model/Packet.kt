package com.leylink.core.model

import com.leylink.core.codec.PacketCodec

/**
 * Logical packet (README: binary packet specification v1). Immutable.
 *
 * `ttl` and `hop` are the only fields that change in transit. A copy that has crossed `hop` links
 * carries `ttl = originTtl - hop + 1`, so `originTtl = ttl + hop - 1`.
 */
class Packet(
    val type: PacketType,
    val priority: Priority,
    val ttl: Int,
    val hop: Int,
    val msgId: MsgId,
    val originId: NodeId,
    val timestampMs: Long,
    payload: ByteArray,
    signature: ByteArray? = null,
    val beacon: Boolean = false,
    val version: Int = PacketCodec.VERSION,
    publicKey: ByteArray? = null,
) {
    private val payloadBytes: ByteArray = payload.copyOf()
    private val signatureBytes: ByteArray? = signature?.copyOf()
    private val publicKeyBytes: ByteArray? = publicKey?.copyOf()

    init {
        require(payloadBytes.size <= MAX_PAYLOAD) { "payload too large: ${payloadBytes.size}" }
        require(signatureBytes == null || signatureBytes.size == SIGNATURE_SIZE) {
            "signature must be $SIGNATURE_SIZE bytes"
        }
        require(publicKeyBytes == null || publicKeyBytes.size == PUBLIC_KEY_SIZE) {
            "public key must be $PUBLIC_KEY_SIZE bytes"
        }
        require(ttl in 0..255 && hop in 0..255) { "ttl and hop must fit in one byte" }
    }

    val payloadSize: Int get() = payloadBytes.size
    val isSigned: Boolean get() = signatureBytes != null
    val hasPublicKey: Boolean get() = publicKeyBytes != null
    val originTtl: Int get() = ttl + hop - 1

    /** Wire flags byte. Reserved bits are always zero. */
    val flags: Int
        get() = (if (isSigned) FLAG_SIGNED else 0) or
            (if (beacon) FLAG_BEACON else 0) or
            (if (hasPublicKey) FLAG_PUBKEY else 0)

    fun payload(): ByteArray = payloadBytes.copyOf()

    fun signature(): ByteArray? = signatureBytes?.copyOf()

    fun publicKey(): ByteArray? = publicKeyBytes?.copyOf()

    /** The copy a relay forwards: one less TTL, one more hop. */
    fun relayed(): Packet {
        require(ttl >= 2) { "a packet with ttl $ttl cannot be relayed" }
        return Packet(
            type, priority, ttl - 1, hop + 1, msgId, originId, timestampMs,
            payloadBytes, signatureBytes, beacon, version, publicKeyBytes,
        )
    }

    companion object {
        const val HEADER_SIZE = 32
        const val SIGNATURE_SIZE = 64
        const val PUBLIC_KEY_SIZE = 32
        const val MAX_PAYLOAD = 0xFFFF
        const val MAX_TTL = 5
        const val MAX_SOS_CHARS = 280

        const val FLAG_SIGNED = 0x01
        const val FLAG_BEACON = 0x02

        /** The packet carries the origin's 32-byte Ed25519 public key (always together with FLAG_SIGNED). */
        const val FLAG_PUBKEY = 0x04
        const val FLAGS_RESERVED_MASK = 0xF8
    }
}
