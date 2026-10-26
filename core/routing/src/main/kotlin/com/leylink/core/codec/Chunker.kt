package com.leylink.core.codec

import com.leylink.core.model.MsgId

/** Splits an encoded packet into GATT-sized frames. */
object Chunker {
    const val DEFAULT_FRAME_SIZE = 180

    /** Largest packet that fits in [MAX_CHUNKS][ChunkFrame.MAX_CHUNKS] frames of [maxFrameSize]. */
    fun maxPacketBytes(maxFrameSize: Int = DEFAULT_FRAME_SIZE): Int =
        ChunkFrame.MAX_CHUNKS * (maxFrameSize - ChunkFrame.HEADER_SIZE)

    fun fits(packetSize: Int, maxFrameSize: Int = DEFAULT_FRAME_SIZE): Boolean =
        packetSize in 1..maxPacketBytes(maxFrameSize)

    /**
     * @throws IllegalArgumentException if the packet is empty or needs more than 255 frames
     *         (use bulk transfer for such payloads).
     */
    fun split(msgId: MsgId, packetBytes: ByteArray, maxFrameSize: Int = DEFAULT_FRAME_SIZE): List<ByteArray> {
        require(maxFrameSize > ChunkFrame.HEADER_SIZE) { "maxFrameSize too small" }
        require(fits(packetBytes.size, maxFrameSize)) {
            "packet of ${packetBytes.size} bytes does not fit in ${ChunkFrame.MAX_CHUNKS} frames"
        }
        val dataSize = maxFrameSize - ChunkFrame.HEADER_SIZE
        val total = (packetBytes.size + dataSize - 1) / dataSize
        return List(total) { index ->
            val from = index * dataSize
            val to = minOf(from + dataSize, packetBytes.size)
            ChunkFrame(msgId, index, total, packetBytes.copyOfRange(from, to)).encode()
        }
    }
}
