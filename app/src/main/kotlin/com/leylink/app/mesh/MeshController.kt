package com.leylink.app.mesh

import kotlinx.coroutines.flow.StateFlow

/** What the UI is allowed to know about the mesh runtime. Implemented by a service-backed controller. */
interface MeshController {
    val running: StateFlow<Boolean>

    fun start()

    fun stop()
}
