package com.leylink.core.codec

import com.leylink.core.port.SeenEntry

/**
 * 16-bit fingerprint of "which messages (with which best TTL) I hold", put in the BLE advertisement so
 * a phone can skip connecting to a neighbour whose digest equals its own: there is nothing to exchange.
 * Order independent (XOR of per-entry hashes). A collision only means a skipped sync that the next
 * contact repairs; the transport also never skips a first contact (see BleConfig.digestSkipWindowMs).
 */
object SyncDigest {
    fun of(entries: List<SeenEntry>): Int {
        var acc = 0
        for (e in entries) {
            val b = e.msgId.toByteArray()
            var h = 17
            for (x in b) h = h * 31 + (x.toInt() and 0xFF)
            h = h * 31 + e.ttl
            h = h xor (h ushr 15) // spread bits
            acc = acc xor (h * -0x61c88647) // golden-ratio multiplier
        }
        return (acc xor (acc ushr 16)) and 0xFFFF
    }
}
