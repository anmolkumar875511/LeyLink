package com.leylink.core.testing

import com.leylink.core.port.BulkChannel
import com.leylink.core.port.ReceiveSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** The shared medium between [FakeBulkChannel]s: a session id maps to a waiting receiver. */
class FakeBulkHub {
    internal val waiting = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    internal val nextId = AtomicInteger(1)

    /** Test knob: when true, every push fails (group formation never succeeds). */
    @Volatile var failPushes: Boolean = false

    /** Test knob: when false, receivers refuse to open a session (device without Wi-Fi Direct). */
    @Volatile var receiversAvailable: Boolean = true

    /** Total payloads that crossed the "air". */
    val transfers = AtomicInteger(0)
}

class FakeBulkChannel(private val hub: FakeBulkHub) : BulkChannel {
    override suspend fun openReceiver(): ReceiveSession? {
        if (!hub.receiversAvailable) return null
        val id = hub.nextId.getAndIncrement()
        val slot = CompletableDeferred<ByteArray>()
        hub.waiting[id] = slot
        return object : ReceiveSession {
            override val info: ByteArray = byteArrayOf(id.toByte())

            override suspend fun await(expectedSize: Int, timeoutMs: Long): ByteArray? =
                withTimeoutOrNull(timeoutMs) { slot.await() }

            override fun close() {
                hub.waiting.remove(id)
            }
        }
    }

    override suspend fun push(info: ByteArray, data: ByteArray): Boolean {
        if (hub.failPushes) return false
        val slot = hub.waiting[info[0].toInt()] ?: return false
        hub.transfers.incrementAndGet()
        return slot.complete(data.copyOf())
    }
}
