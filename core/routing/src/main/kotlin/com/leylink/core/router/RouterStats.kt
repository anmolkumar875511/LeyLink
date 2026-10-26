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
)
