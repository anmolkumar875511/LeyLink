package com.leylink.transport.ble

import java.util.UUID

/** GATT profile constants (README section 4). Replace the UUIDs if you generate your own. */
object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("18b866bd-d745-464a-988e-a0450588f273")

    /** Peer writes chunk frames here. */
    val INBOX_UUID: UUID = UUID.fromString("18b866bd-d745-464a-988e-a04505880001")

    /** Server pushes chunk frames to the connected peer via notifications. */
    val STREAM_UUID: UUID = UUID.fromString("18b866bd-d745-464a-988e-a04505880002")

    /** Read: protocol version, nodeId, capability flags. */
    val INFO_UUID: UUID = UUID.fromString("18b866bd-d745-464a-988e-a04505880003")

    /** Requested ATT MTU; usable payload is MTU - 3 = 244 bytes. */
    const val REQUESTED_MTU = 247

    /** Target size of one GATT write (11 B chunk header + up to 169 B data). */
    const val TARGET_WRITE_SIZE = 180
}
