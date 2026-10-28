package com.leylink.transport.ble

import com.leylink.core.model.NodeId
import java.util.UUID

/** GATT profile and advertising constants (README section 4). Replace the UUIDs if you generate your own. */
object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("18b866bd-d745-464a-988e-a0450588f273")

    /** Central writes frames here (HELLO first, then chunk frames). */
    val INBOX_UUID: UUID = UUID.fromString("18b866bd-d745-464a-988e-a04505880001")

    /** Peripheral pushes frames to the connected central via notifications. */
    val STREAM_UUID: UUID = UUID.fromString("18b866bd-d745-464a-988e-a04505880002")

    /** Read: protocol version and nodeId. */
    val INFO_UUID: UUID = UUID.fromString("18b866bd-d745-464a-988e-a04505880003")

    /** Standard Client Characteristic Configuration descriptor (enables notifications). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Requested ATT MTU; usable payload is MTU - 3 = 244 bytes. */
    const val REQUESTED_MTU = 247

    /** Target size of one GATT write (11 B chunk header + up to 169 B data). Links with less are refused. */
    const val TARGET_WRITE_SIZE = 180

    /** ATT overhead subtracted from the MTU. */
    const val ATT_OVERHEAD = 3

    /** 0xFFFF is the Bluetooth SIG "reserved for testing" company id. */
    const val COMPANY_ID = 0xFFFF
    const val PROTOCOL_VERSION: Byte = 1

    /** Scan-response payload: version(1) + nodeId(8) + flags(1) + digest(2, big-endian). */
    const val ADVERT_SIZE = 1 + NodeId.LENGTH + 1 + 2
    const val FLAG_BEACON = 0x01

    /** What a scanner learns from one advertisement. */
    data class Advert(val nodeId: NodeId, val beacon: Boolean, val digest: Int)

    fun advertPayload(nodeId: NodeId, beacon: Boolean = false, digest: Int = 0): ByteArray =
        byteArrayOf(PROTOCOL_VERSION) + nodeId.toByteArray() +
            byteArrayOf(
                (if (beacon) FLAG_BEACON else 0).toByte(),
                (digest ushr 8).toByte(),
                digest.toByte(),
            )

    fun parseAdvertPayload(data: ByteArray): Advert? {
        if (data.size != ADVERT_SIZE || data[0] != PROTOCOL_VERSION) return null
        val nodeId = NodeId(data.copyOfRange(1, 1 + NodeId.LENGTH))
        val flags = data[1 + NodeId.LENGTH].toInt() and 0xFF
        val digest = ((data[2 + NodeId.LENGTH].toInt() and 0xFF) shl 8) or (data[3 + NodeId.LENGTH].toInt() and 0xFF)
        return Advert(nodeId, beacon = (flags and FLAG_BEACON) != 0, digest = digest)
    }
}

/** Tunables for [BleTransport]. */
data class BleConfig(
    /** Concurrent GATT connections (both directions together). */
    val maxLinks: Int = 3,
    val connectTimeoutMs: Long = 10_000L,
    val writeTimeoutMs: Long = 5_000L,
    val retryBaseMs: Long = 1_000L,
    val retryCapMs: Long = 60_000L,
    /**
     * Scan duty cycle. Scanning is the biggest battery cost, so when nothing special is going on we scan
     * [scanOnMs], pause [scanOffMs], repeat. Android allows about 5 scan starts per 30 s: the defaults
     * give one start per 30 s. Scanning is continuous while we beacon or see a neighbour beaconing.
     */
    val scanOnMs: Long = 12_000L,
    val scanOffMs: Long = 18_000L,
    /** Turn off for the "before tuning" battery measurement: one long continuous scan. */
    val dutyCycleEnabled: Boolean = true,
    /** A link with no traffic for this long is closed to save power; it is re-opened only if needed. */
    val idleTimeoutMs: Long = 120_000L,
    val idleCheckIntervalMs: Long = 15_000L,
    /**
     * Do not connect to a neighbour whose advertised digest equals ours if we were linked to it within
     * this window: both sides already hold the same messages. A first contact always connects.
     */
    val digestSkipWindowMs: Long = 5 * 60_000L,
    /** Minimum time between two advertisement payload updates (restarting advertising is not free). */
    val advertUpdateIntervalMs: Long = 3_000L,
    /** A neighbour counts as beaconing for this long after its last beacon advertisement. */
    val remoteBeaconTtlMs: Long = 30_000L,
)
