package com.leylink.core.port

/**
 * Runs several store operations as ONE atomic unit (Room: a single SQLite transaction), so a crash
 * can never leave "marked as seen" without "queued for relay".
 */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T

    companion object {
        /** No real transaction. For tests and in-memory stores. */
        val NONE: TransactionRunner = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
    }
}
