package com.leylink.core.codec

import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.Packet
import com.leylink.core.model.PacketType
import com.leylink.core.model.Priority
import java.nio.ByteBuffer

sealed interface DecodeResult {
    data class Ok(val packet: Packet) : DecodeResult

    data class Error(val reason: String) : DecodeResult
}

/**
 * Binary packet format v1, big-endian (README section 5.1):
 *
 *  0 version | 1 type | 2 flags | 3 priority | 4 ttl | 5 hop | 6..13 msgId | 14..21 originId |
 *  22..29 timestampMs | 30..31 payloadLen | 32.. payload | [64-byte signature if SIGNED]
 *
 * [decode] never throws on hostile input; it returns [DecodeResult.Error].
 */
object PacketCodec {
    const val VERSION = 1

    fun encode(packet: Packet): ByteArray {
        val signature = packet.signature()
        val size = Packet.HEADER_SIZE + packet.payloadSize + (if (signature != null) Packet.SIGNATURE_SIZE else 0)
        val buf = ByteBuffer.allocate(size) // big-endian by default
        buf.put(packet.version.toByte())
        buf.put(packet.type.code.toByte())
        buf.put(packet.flags.toByte())
        buf.put(packet.priority.level.toByte())
        buf.put(packet.ttl.toByte())
        buf.put(packet.hop.toByte())
        buf.put(packet.msgId.toByteArray())
        buf.put(packet.originId.toByteArray())
        buf.putLong(packet.timestampMs)
        buf.putShort(packet.payloadSize.toShort())
        buf.put(packet.payload())
        if (signature != null) buf.put(signature)
        return buf.array()
    }

    @Suppress("ReturnCount")
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size < Packet.HEADER_SIZE) return DecodeResult.Error("too short: ${bytes.size} bytes")
        val buf = ByteBuffer.wrap(bytes)

        val version = buf.get().toInt() and 0xFF
        if (version != VERSION) return DecodeResult.Error("unsupported version $version")

        val typeCode = buf.get().toInt() and 0xFF
        val type = PacketType.fromCode(typeCode) ?: return DecodeResult.Error("unknown type $typeCode")

        val flags = buf.get().toInt() and 0xFF
        if ((flags and Packet.FLAGS_RESERVED_MASK) != 0) return DecodeResult.Error("reserved flag bits set")
        val signed = (flags and Packet.FLAG_SIGNED) != 0
        val beacon = (flags and Packet.FLAG_BEACON) != 0

        val level = buf.get().toInt() and 0xFF
        val priority = Priority.fromLevel(level) ?: return DecodeResult.Error("unknown priority $level")

        val ttl = buf.get().toInt() and 0xFF
        val hop = buf.get().toInt() and 0xFF
        if (ttl < 1 || ttl > Packet.MAX_TTL) return DecodeResult.Error("ttl out of range: $ttl")
        if (hop < 1) return DecodeResult.Error("hop out of range: $hop")
        // A hostile node must not be able to extend a packet's reach beyond the maximum TTL.
        if (ttl + hop - 1 > Packet.MAX_TTL) return DecodeResult.Error("ttl + hop exceeds origin ttl limit")

        val msgIdBytes = ByteArray(MsgId.LENGTH).also { buf.get(it) }
        val originBytes = ByteArray(NodeId.LENGTH).also { buf.get(it) }
        val timestampMs = buf.long
        val payloadLen = buf.short.toInt() and 0xFFFF

        val expected = Packet.HEADER_SIZE + payloadLen + (if (signed) Packet.SIGNATURE_SIZE else 0)
        if (bytes.size != expected) return DecodeResult.Error("length mismatch: expected $expected, got ${bytes.size}")

        val payload = ByteArray(payloadLen).also { buf.get(it) }
        val signature = if (signed) ByteArray(Packet.SIGNATURE_SIZE).also { buf.get(it) } else null

        return DecodeResult.Ok(
            Packet(
                type = type,
                priority = priority,
                ttl = ttl,
                hop = hop,
                msgId = MsgId(msgIdBytes),
                originId = NodeId(originBytes),
                timestampMs = timestampMs,
                payload = payload,
                signature = signature,
                beacon = beacon,
                version = version,
            ),
        )
    }

    /**
     * The bytes covered by the signature (Stage 6): version, type, flags, priority, msgId, originId,
     * timestamp, payloadLen and payload. `ttl` (offset 4) and `hop` (offset 5) are excluded because
     * relays change them.
     */
    fun signedRegion(packet: Packet): ByteArray {
        val raw = encode(packet)
        return raw.copyOfRange(0, 4) + raw.copyOfRange(6, Packet.HEADER_SIZE) + packet.payload()
    }
}
