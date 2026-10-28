package com.leylink.core.port

/**
 * High-bandwidth side channel (Wi-Fi Direct on Android) used for packets too big for BLE chunking to be
 * pleasant. BLE only NEGOTIATES (XFER_OFFER / accept / decline); the bytes travel through this port.
 *
 * Everything here may fail at any time: the router always falls back to BLE chunking or keeps the packet
 * queued, so an implementation simply returns null / false when it cannot do the job.
 */
interface BulkChannel {
    /**
     * Receiver side: prepare to receive ONE payload. Returns a session whose [ReceiveSession.info] is sent
     * back to the sender over BLE (e.g. group credentials), or null when bulk transfer is unavailable now.
     */
    suspend fun openReceiver(): ReceiveSession?

    /** Sender side: connect using the receiver's [info] and push [data]. @return true if fully sent. */
    suspend fun push(info: ByteArray, data: ByteArray): Boolean
}

interface ReceiveSession {
    /** Opaque connection details for the sender (small: it travels in one BLE packet). */
    val info: ByteArray

    /** Waits for the payload. @return the bytes, or null on timeout or error. */
    suspend fun await(expectedSize: Int, timeoutMs: Long): ByteArray?

    /** Always called, also after success: tears the group/socket down. */
    fun close()
}
