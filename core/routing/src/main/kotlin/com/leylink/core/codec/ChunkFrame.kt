package com.leylink.core.codec

import com.leylink.core.model.MsgId

/** One GATT write (README section 5.4): 0xC0 | msgId(8) | index(1) | total(1) | data. */
class ChunkFrame(
    val msgId: MsgId,
    val index: Int,
    val total: Int,
    data: ByteArray,
) {
    private val dataBytes: ByteArray = data.copyOf()

    fun data(): ByteArray = dataBytes.copyOf()

    fun encode(): ByteArray {
        val out = ByteArray(HEADER_SIZE + dataBytes.size)
        out[0] = FRAME_TYPE.toByte()
        msgId.toByteArray().copyInto(out, destinationOffset = 1)
        out[9] = index.toByte()
        out[10] = total.toByte()
        dataBytes.copyInto(out, destinationOffset = HEADER_SIZE)
        return out
    }

    companion object {
        const val FRAME_TYPE = 0xC0
        const val HEADER_SIZE = 11
        const val MAX_CHUNKS = 255

        /** @return the parsed frame, or null if the bytes are not a valid chunk frame. */
        fun parse(frame: ByteArray): ChunkFrame? {
            if (frame.size <= HEADER_SIZE) return null
            if ((frame[0].toInt() and 0xFF) != FRAME_TYPE) return null
            val index = frame[9].toInt() and 0xFF
            val total = frame[10].toInt() and 0xFF
            if (total < 1 || index >= total) return null
            return ChunkFrame(
                msgId = MsgId(frame.copyOfRange(1, 9)),
                index = index,
                total = total,
                data = frame.copyOfRange(HEADER_SIZE, frame.size),
            )
        }
    }
}
