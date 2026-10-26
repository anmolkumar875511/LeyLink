package com.leylink.app.mesh

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.leylink.app.service.MeshService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

class ServiceMeshController @Inject constructor(
    @ApplicationContext private val context: Context,
    status: MeshStatus,
) : MeshController {

    override val running: StateFlow<Boolean> = status.running

    override fun start() {
        ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
    }

    override fun stop() {
        context.stopService(Intent(context, MeshService::class.java))
    }
}
