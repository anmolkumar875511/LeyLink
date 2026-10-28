package com.leylink.data.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One packet waiting to be (re)sent. One row per message ("replace-if-better"). */
@Entity(tableName = "outbox")
class OutboxRow(
    @PrimaryKey val msgIdHex: String,
    val packet: ByteArray,
    val priority: Int,
    val ttl: Int,
    val createdAtMs: Long,
)

/** "This version of the message was already sent to this peer." */
@Entity(tableName = "outbox_sent", primaryKeys = ["msgIdHex", "peerHex"], indices = [Index("peerHex")])
class OutboxSentRow(
    val msgIdHex: String,
    val peerHex: String,
)

/** Dedup memory: the best TTL accepted per message. */
@Entity(tableName = "seen", indices = [Index("recordedAtMs")])
class SeenRow(
    @PrimaryKey val msgIdHex: String,
    val ttl: Int,
    val recordedAtMs: Long,
)

/** Chat history. The primary key makes a second insert of the same message impossible. */
@Entity(tableName = "messages", indices = [Index("receivedAtMs")])
class MessageRow(
    @PrimaryKey val msgIdHex: String,
    val originHex: String,
    val typeCode: Int,
    val text: String,
    val timestampMs: Long,
    val hop: Int,
    val originTtl: Int,
    val receivedAtMs: Long,
    val mine: Boolean,
)
