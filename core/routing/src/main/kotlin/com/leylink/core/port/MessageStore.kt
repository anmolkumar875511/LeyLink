package com.leylink.core.port

import com.leylink.core.model.StoredMessage
import kotlinx.coroutines.flow.Flow

/** Message history. Implementations must be thread-safe and ignore a second insert of the same msgId. */
interface MessageStore {
    /** @return true if stored, false if a message with this ID already existed. */
    suspend fun insertIfAbsent(message: StoredMessage): Boolean

    /** Newest first. Emits again whenever the history changes. */
    fun observe(): Flow<List<StoredMessage>>
}
