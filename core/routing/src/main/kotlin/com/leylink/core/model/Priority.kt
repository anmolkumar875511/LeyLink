package com.leylink.core.model

/** Lower level is sent first (README: packet byte offset 3). */
enum class Priority(val level: Int) {
    SOS(0),
    CONTROL(1), // ANNOUNCE, ACK, SYNC_SUMMARY
    CHAT(2),
    ;

    companion object {
        fun fromLevel(level: Int): Priority? = entries.firstOrNull { it.level == level }
    }
}
