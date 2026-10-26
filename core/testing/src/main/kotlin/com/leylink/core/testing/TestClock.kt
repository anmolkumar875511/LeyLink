package com.leylink.core.testing

import com.leylink.core.time.Clock

/** Virtual clock for deterministic tests. */
class TestClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        now += ms
    }

    fun set(ms: Long) {
        now = ms
    }
}
