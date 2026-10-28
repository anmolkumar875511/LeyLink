package com.leylink.core.router

/** Counters for the debug/stats screen and the storm test. */
data class RouterStats(
    val framesIn: Long = 0,
    val framesOut: Long = 0,
    val packetsSent: Long = 0,
    val sendFailures: Long = 0,
    val malformed: Long = 0,
    val duplicates: Long = 0,
    val unsupported: Long = 0,
    val delivered: Long = 0,
    val relaysQueued: Long = 0,
    val ttlExhausted: Long = 0,
    val summariesIn: Long = 0,
    val summariesOut: Long = 0,
    val errors: Long = 0,
    /** SOS packets thrown away before delivery (bad signature, key mismatch, unsigned while required). */
    val sosDropped: Long = 0,
    val signatureRejected: Long = 0,
    val announcesIn: Long = 0,
    val announcesOut: Long = 0,
    val bulkSent: Long = 0,
    val bulkReceived: Long = 0,
    /** Offers that failed or were declined, so BLE chunking (or waiting) took over. */
    val bulkFallbacks: Long = 0,
    /** Our own SOS messages not yet handed to any neighbour (beacon mode is on while > 0). */
    val unackedSos: Int = 0,
)
