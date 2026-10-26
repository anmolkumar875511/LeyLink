package com.leylink.core.concurrent

import kotlinx.coroutines.CoroutineDispatcher

/** Lets production code pick dispatchers while tests substitute deterministic ones. */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}
