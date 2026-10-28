package com.leylink.core.codec

import com.leylink.core.model.MsgId
import java.nio.ByteBuffer

/**
 * XFER_OFFER payload (README section 5.5). First byte is the sub-type:
 *  - OFFER  : 0x01 | msgId(8) | ttl(1) | size(4) | sha256(32)   sender -> receiver
 *  - ACCEPT : 0x02 | msgId(8) | info...                         receiver -> sender (bulk connection info)
 *  - DECLINE: 0x03 | msgId(8)                                   receiver -> sender
 * All of it travels over BLE; the packet itself then goes through the bulk channel.
 */
sealed interface Xfer {
    val msgId: MsgId

    class Offer(override val msgId: MsgId, val ttl: Int, val size: Int, sha256: ByteArray) : Xfer {
        private val hash: ByteArray = sha256.copyOf()

        fun sha256(): ByteArray = hash.copyOf()
    }

    class Accept(override val msgId: MsgId, info: ByteArray) : Xfer {
        private val infoBytes: ByteArray = info.copyOf()

        fun info(): ByteArray = infoBytes.copyOf()
    }

    class Decline(override val msgId: MsgId) : Xfer

    companion object {
        const val SHA_SIZE = 32
        private const val OFFER = 1
        private const val ACCEPT = 2
        private const val DECLINE = 3
        private const val OFFER_SIZE = 1 + MsgId.LENGTH + 1 + 4 + SHA_SIZE
        private const val MAX_INFO = 200

        fun encode(x: Xfer): ByteArray = when (x) {
            is Offer -> ByteBuffer.allocate(OFFER_SIZE)
                .put(OFFER.toByte()).put(x.msgId.toByteArray()).put(x.ttl.toByte())
                .putInt(x.size).put(x.sha256()).array()
            is Accept -> byteArrayOf(ACCEPT.toByte()) + x.msgId.toByteArray() + x.info()
            is Decline -> byteArrayOf(DECLINE.toByte()) + x.msgId.toByteArray()
        }

        /** @return the message, or null if the payload is malformed (hostile input never throws). */
        @Suppress("ReturnCount")
        fun decode(payload: ByteArray): Xfer? {
            if (payload.size < 1 + MsgId.LENGTH) return null
            val msgId = MsgId(payload.copyOfRange(1, 1 + MsgId.LENGTH))
            return when (payload[0].toInt() and 0xFF) {
                OFFER -> {
                    if (payload.size != OFFER_SIZE) return null
                    val buf = ByteBuffer.wrap(payload, 1 + MsgId.LENGTH, payload.size - 1 - MsgId.LENGTH)
                    val ttl = buf.get().toInt() and 0xFF
                    val size = buf.int
                    val hash = ByteArray(SHA_SIZE).also { buf.get(it) }
                    if (ttl < 1 || size <= 0) null else Offer(msgId, ttl, size, hash)
                }
                ACCEPT -> {
                    val info = payload.copyOfRange(1 + MsgId.LENGTH, payload.size)
                    if (info.isEmpty() || info.size > MAX_INFO) null else Accept(msgId, info)
                }
                DECLINE -> if (payload.size == 1 + MsgId.LENGTH) Decline(msgId) else null
                else -> null
            }
        }
    }
}
