package com.leylink.data.storage

import androidx.room.withTransaction
import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.OutboxEntry
import com.leylink.core.model.PacketType
import com.leylink.core.model.Priority
import com.leylink.core.model.StoredMessage
import com.leylink.core.port.MessageStore
import com.leylink.core.port.OutboxStore
import com.leylink.core.port.SeenEntry
import com.leylink.core.port.SeenOutcome
import com.leylink.core.port.SeenStore
import com.leylink.core.port.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [OutboxStore]. Every mutation is a transaction, so the outbox survives crashes. */
class RoomOutboxStore(private val db: LeyLinkDatabase) : OutboxStore {
    private val dao get() = db.outboxDao()

    override suspend fun enqueue(entry: OutboxEntry) {
        db.withTransaction {
            val id = entry.msgId.toString()
            val existing = dao.find(id)
            // Replace-if-better: a replacement forgets who already got the worse copy.
            if (existing == null || entry.ttl > existing.ttl) {
                dao.clearSent(id)
                dao.upsert(OutboxRow(id, entry.packetBytes(), entry.priority.level, entry.ttl, entry.createdAtMs))
            }
        }
    }

    override suspend fun nextBatch(peer: NodeId, limit: Int): List<OutboxEntry> =
        dao.nextBatch(peer.toString(), limit).map { row ->
            OutboxEntry(
                msgId = MsgId.fromHex(row.msgIdHex),
                packetBytes = row.packet,
                priority = Priority.fromLevel(row.priority) ?: Priority.CHAT,
                ttl = row.ttl,
                createdAtMs = row.createdAtMs,
            )
        }

    override suspend fun markSent(msgId: MsgId, ttl: Int, peer: NodeId) {
        db.withTransaction {
            val row = dao.find(msgId.toString())
            if (row != null && row.ttl == ttl) dao.insertSent(row.msgIdHex, peer.toString())
        }
    }

    override suspend fun markPeerHas(peer: NodeId, msgId: MsgId, peerBestTtl: Int) {
        db.withTransaction {
            val row = dao.find(msgId.toString())
            if (row != null && row.ttl <= peerBestTtl) dao.insertSent(row.msgIdHex, peer.toString())
        }
    }

    override suspend fun expireOlderThan(cutoffMs: Long): Int =
        db.withTransaction {
            val removed = dao.expire(cutoffMs)
            dao.deleteOrphanSent()
            removed
        }

    override suspend fun size(): Int = dao.count()
}

/** Room-backed [SeenStore] implementing the TTL-aware dedup rule. */
class RoomSeenStore(private val db: LeyLinkDatabase) : SeenStore {
    private val dao get() = db.seenDao()

    override suspend fun bestTtl(msgId: MsgId): Int = dao.ttlOf(msgId.toString()) ?: 0

    override suspend fun recordIfBetter(msgId: MsgId, ttl: Int, nowMs: Long): SeenOutcome =
        db.withTransaction {
            val id = msgId.toString()
            val current = dao.ttlOf(id)
            when {
                current == null -> {
                    dao.upsert(SeenRow(id, ttl, nowMs))
                    SeenOutcome.NEW
                }
                ttl > current -> {
                    dao.upsert(SeenRow(id, ttl, nowMs))
                    SeenOutcome.IMPROVED
                }
                else -> SeenOutcome.DUPLICATE
            }
        }

    override suspend fun snapshot(limit: Int): List<SeenEntry> =
        dao.recent(limit).map { SeenEntry(MsgId.fromHex(it.msgIdHex), it.ttl) }

    override suspend fun purgeOlderThan(cutoffMs: Long): Int = dao.purge(cutoffMs)
}

class RoomMessageStore(private val db: LeyLinkDatabase) : MessageStore {
    private val dao get() = db.messageDao()

    override suspend fun insertIfAbsent(message: StoredMessage): Boolean =
        dao.insert(
            MessageRow(
                msgIdHex = message.msgId.toString(),
                originHex = message.originId.toString(),
                typeCode = message.type.code,
                text = message.text,
                timestampMs = message.timestampMs,
                hop = message.hop,
                originTtl = message.originTtl,
                receivedAtMs = message.receivedAtMs,
                mine = message.mine,
            ),
        ) != -1L

    override fun observe(): Flow<List<StoredMessage>> =
        dao.observeAll().map { rows ->
            rows.map { row ->
                StoredMessage(
                    msgId = MsgId.fromHex(row.msgIdHex),
                    originId = NodeId.fromHex(row.originHex),
                    type = PacketType.fromCode(row.typeCode) ?: PacketType.CHAT,
                    text = row.text,
                    timestampMs = row.timestampMs,
                    hop = row.hop,
                    originTtl = row.originTtl,
                    receivedAtMs = row.receivedAtMs,
                    mine = row.mine,
                )
            }
        }
}

/** One SQLite transaction around a block of store calls. */
class RoomTransactionRunner(private val db: LeyLinkDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}
