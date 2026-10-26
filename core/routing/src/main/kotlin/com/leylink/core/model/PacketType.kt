package com.leylink.core.model

/** Packet type codes (README: binary packet specification, byte offset 1). */
enum class PacketType(val code: Int) {
    CHAT(0x01),
    SOS(0x02),
    ACK(0x03),
    ANNOUNCE(0x04),
    SYNC_SUMMARY(0x05),
    XFER_OFFER(0x06),
    ;

    companion object {
        /** @return the matching type, or null for an unknown code (the caller must drop the packet). */
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}
