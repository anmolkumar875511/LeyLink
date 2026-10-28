package com.leylink.core.router

import com.leylink.core.codec.Chunker
import com.leylink.core.model.Packet

/** Tunables for [MeshRouter]. Defaults follow the README. */
data class RouterConfig(
    /** TTL given to packets this node originates (README: 3 to 5). */
    val originTtl: Int = Packet.MAX_TTL,
    /** Size of one GATT write, chunk header included. */
    val maxFrameSize: Int = Chunker.DEFAULT_FRAME_SIZE,
    /** Outbox entries fetched per round when flushing to a peer. */
    val batchSize: Int = 16,
    /** Safety cap on rounds per flush call so a misbehaving store cannot loop forever. */
    val maxFlushRounds: Int = 64,
    val reassemblyTimeoutMs: Long = 30_000L,
    val maxInFlightReassemblies: Int = 32,
    /** How long unsent packets stay in the outbox (measured from LOCAL receive time). */
    val retentionMs: Long = 24L * 60 * 60 * 1000,
    /** Dedup memory must outlive packets, otherwise an expired ID lets an old packet back in. */
    val seenRetentionMs: Long = retentionMs * 2,
    val housekeepingIntervalMs: Long = 60_000L,
    /** Exchange SYNC_SUMMARY on every new contact so only missing packets are sent (Stage 4). */
    val syncEnabled: Boolean = false,
    /** How long to wait for the peer's summary before sending anyway. */
    val syncWaitMs: Long = 2_000L,
    val maxSummaryEntries: Int = 255,
    /** Drop CHAT/SOS packets that are unsigned or fail verification (needs a Signer). */
    val requireSignatures: Boolean = false,
    /** Packets bigger than this are offered over the bulk channel (Wi-Fi Direct) when one exists. */
    val bulkThresholdBytes: Int = 4_096,
    /** How long the sender waits for ACCEPT / DECLINE over BLE. */
    val offerTimeoutMs: Long = 8_000L,
    /** How long the receiver waits for the payload on the bulk channel. */
    val bulkTimeoutMs: Long = 30_000L,
    /** Random delay before forwarding, to avoid synchronized rebroadcast bursts. */
    val jitterMs: () -> Long = { 0L },
) {
    init {
        require(originTtl in 1..Packet.MAX_TTL) { "originTtl must be in 1..${Packet.MAX_TTL}" }
        require(seenRetentionMs >= retentionMs) { "seenRetentionMs must be >= retentionMs" }
        require(maxSummaryEntries in 1..255) { "maxSummaryEntries must be in 1..255" }
        require(bulkThresholdBytes > 0) { "bulkThresholdBytes must be positive" }
        require(batchSize > 0 && maxFlushRounds > 0) { "batchSize and maxFlushRounds must be positive" }
    }
}
