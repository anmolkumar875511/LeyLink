package com.leylink.data.storage

/**
 * Placeholder. Stage 4 adds the Room database here:
 *  - RoomOutboxStore : OutboxStore and RoomSeenStore : SeenStore (the :core:routing ports)
 *  - entities: outbox, seen, messages, peers
 *  - WAL mode, one atomic "enqueue + mark seen" transaction, unique index on msgId
 *
 * This module never depends on :app and exposes only implementations of core ports,
 * so the routing core stays unaware of Room.
 */
internal object StorageModule
