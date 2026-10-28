package com.leylink.core.router

import com.leylink.core.model.NodeId
import com.leylink.core.port.KeyDirectory
import com.leylink.core.port.PinResult
import java.util.concurrent.ConcurrentHashMap

/** Volatile [KeyDirectory]: fine for tests and as a default, the app persists pins instead. */
class InMemoryKeyDirectory : KeyDirectory {
    private val pins = ConcurrentHashMap<NodeId, List<Byte>>()

    override suspend fun pin(origin: NodeId, publicKey: ByteArray): PinResult {
        val key = publicKey.toList()
        val existing = pins.putIfAbsent(origin, key) ?: return PinResult.NEW
        return if (existing == key) PinResult.KNOWN else PinResult.MISMATCH
    }

    override suspend fun count(): Int = pins.size
}
