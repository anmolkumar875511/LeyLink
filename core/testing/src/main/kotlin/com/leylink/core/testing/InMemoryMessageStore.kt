package com.leylink.core.testing

import com.leylink.core.model.StoredMessage
import com.leylink.core.port.MessageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory [MessageStore]. */
class InMemoryMessageStore : MessageStore {
    private val all = MutableStateFlow<List<StoredMessage>>(emptyList())

    override suspend fun insertIfAbsent(message: StoredMessage): Boolean {
        var inserted = false
        all.update { current ->
            if (current.any { it.msgId == message.msgId }) {
                current
            } else {
                inserted = true
                current + message
            }
        }
        return inserted
    }

    override fun observe(): Flow<List<StoredMessage>> = all.map { list -> list.sortedByDescending { it.receivedAtMs } }
}
