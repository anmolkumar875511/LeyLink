package com.leylink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.leylink.app.R
import com.leylink.app.mesh.MeshEngine
import com.leylink.app.mesh.MeshStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service for Disaster Mode: keeps the mesh alive while the app is in the background.
 * It follows the Bluetooth switch: the mesh starts when Bluetooth is on and stops when it goes off.
 * (Airplane mode with Bluetooth switched back on keeps working, because no network is involved.)
 */
@AndroidEntryPoint
class MeshService : Service() {

    @Inject
    lateinit var status: MeshStatus

    @Inject
    lateinit var engine: MeshEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startMesh()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> stopMesh()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        status.setRunning(true)
        if (bluetoothIsOn()) startMesh()
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(bluetoothReceiver)
            receiverRegistered = false
        }
        // The service scope is cancelled below, so stop the engine from an independent scope.
        CoroutineScope(Dispatchers.Default).launch { engine.stop() }
        scope.cancel()
        status.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMesh() {
        scope.launch { engine.start(scope) }
    }

    private fun stopMesh() {
        scope.launch { engine.stop() }
    }

    private fun bluetoothIsOn(): Boolean =
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

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
