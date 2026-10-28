package com.leylink.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE msgIdHex = :id")
    suspend fun find(id: String): OutboxRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: OutboxRow)

    @Query("DELETE FROM outbox_sent WHERE msgIdHex = :id")
    suspend fun clearSent(id: String)

    /** Priority first (SOS = 0), then oldest first, skipping what this peer already has. */
    @Query(
        """
        SELECT o.* FROM outbox o
        WHERE NOT EXISTS (
            SELECT 1 FROM outbox_sent s WHERE s.msgIdHex = o.msgIdHex AND s.peerHex = :peer
        )
        ORDER BY o.priority ASC, o.createdAtMs ASC, o.rowid ASC
        LIMIT :limit
        """,
    )
    suspend fun nextBatch(peer: String, limit: Int): List<OutboxRow>

    @Query("INSERT OR IGNORE INTO outbox_sent (msgIdHex, peerHex) VALUES (:id, :peer)")
    suspend fun insertSent(id: String, peer: String)

    @Query("DELETE FROM outbox WHERE createdAtMs < :cutoff")
    suspend fun expire(cutoff: Long): Int

    @Query("DELETE FROM outbox_sent WHERE msgIdHex NOT IN (SELECT msgIdHex FROM outbox)")
    suspend fun deleteOrphanSent(): Int

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int
}

@Dao
interface SeenDao {
    @Query("SELECT ttl FROM seen WHERE msgIdHex = :id")
    suspend fun ttlOf(id: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SeenRow)

    @Query("SELECT * FROM seen ORDER BY recordedAtMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SeenRow>

    @Query("DELETE FROM seen WHERE recordedAtMs < :cutoff")
    suspend fun purge(cutoff: Long): Int
}

@Dao
interface MessageDao {
    /** @return the new row id, or -1 if a message with this ID already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: MessageRow): Long

    @Query("SELECT * FROM messages ORDER BY receivedAtMs DESC, rowid DESC")
    fun observeAll(): Flow<List<MessageRow>>
}
