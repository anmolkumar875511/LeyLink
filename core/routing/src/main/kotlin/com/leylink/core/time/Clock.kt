package com.leylink.core.time

/** Time source port. Tests inject a virtual clock; the app uses [SYSTEM]. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
