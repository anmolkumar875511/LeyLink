package com.leylink.transport.ble

import android.util.Log
import com.leylink.core.model.NodeId

internal const val TAG = "LeyBle"

internal fun logd(message: String) {
    Log.d(TAG, message)
}

internal fun logw(message: String, error: Throwable? = null) {
    Log.w(TAG, message, error)
}

/** One open GATT connection to one peer, whichever side initiated it. */
internal interface BleLink {
    val peerId: NodeId

    /** Largest frame this link can carry, derived from the negotiated MTU. */
    val maxFrameSize: Int

    /** Sends one frame and waits for the radio to confirm. Operations on a link are serialized. */
    suspend fun send(frame: ByteArray): Boolean

    fun close()
}

/** Events a link or the GATT server reports up to the transport. May arrive on binder threads. */
internal interface LinkListener {
    fun onLinkReady(link: BleLink)

    fun onFrame(from: NodeId, frame: ByteArray)

    fun onLinkClosed(link: BleLink)
}

/** First frame a central writes after connecting, so the peripheral learns who is calling. */
internal object HelloFrame {
    const val TYPE = 0xB0

    fun encode(nodeId: NodeId): ByteArray = byteArrayOf(TYPE.toByte()) + nodeId.toByteArray()

    fun parse(frame: ByteArray): NodeId? =
        if (frame.size == 1 + NodeId.LENGTH && (frame[0].toInt() and 0xFF) == TYPE) {
            NodeId(frame.copyOfRange(1, frame.size))
        } else {
            null
        }
}
