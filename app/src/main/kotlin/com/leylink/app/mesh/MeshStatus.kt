package com.leylink.app.mesh

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, observable "is Disaster Mode running". The service writes it; the UI reads it.
 * Replaces the static mutable flag that would otherwise leak between Service and Activity.
 */
@Singleton
class MeshStatus @Inject constructor() {
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    fun setRunning(value: Boolean) {
        mutableRunning.value = value
    }
}
