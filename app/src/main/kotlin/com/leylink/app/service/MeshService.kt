package com.leylink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.leylink.app.R
import com.leylink.app.mesh.MeshStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that will own the BLE transport and router (Disaster Mode).
 * Stage 1: lifecycle and the persistent notification only. Stages 3 and 4 wire in the mesh.
 */
@AndroidEntryPoint
class MeshService : Service() {

    @Inject
    lateinit var status: MeshStatus

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        status.setRunning(true)
        // TODO(Stage 3/4): create and start BleTransport + MeshRouter here.
        return START_STICKY
    }

    override fun onDestroy() {
        // TODO(Stage 3/4): stop transport and router here.
        status.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "mesh"
        const val NOTIFICATION_ID = 1
    }
}
