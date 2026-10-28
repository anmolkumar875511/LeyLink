package com.leylink.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.leylink.core.model.NodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A link where WE are the central: we connect to the peer's GATT server.
 *
 * Connection sequence (each step starts from the previous step's callback):
 * connect -> request MTU -> discover services -> enable notifications on STREAM -> write HELLO -> ready.
 * Writes to INBOX are strictly one at a time: the next write waits for the previous write's callback.
 */
@SuppressLint("MissingPermission") // permissions are checked by the app before the mesh starts
@Suppress("DEPRECATION")
internal class ClientLink(
    private val context: Context,
    private val device: BluetoothDevice,
    override val peerId: NodeId,
    private val selfId: NodeId,
    private val scope: CoroutineScope,
    private val listener: LinkListener,
    private val writeTimeoutMs: Long,
) : BleLink {

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var inbox: BluetoothGattCharacteristic? = null

    @Volatile private var mtu = DEFAULT_MTU

    @Volatile private var closed = false

    private val ready = CompletableDeferred<Boolean>()
    private val writeMutex = Mutex()

    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null

    override val maxFrameSize: Int get() = mtu - BleConstants.ATT_OVERHEAD

    /** @return true once the link is fully usable; false on any failure or timeout (the link is closed). */
    suspend fun connect(timeoutMs: Long): Boolean {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
        if (!ok) close()
        return ok
    }

    override suspend fun send(frame: ByteArray): Boolean = writeMutex.withLock {
        val g = gatt
        val characteristic = inbox
        if (closed || g == null || characteristic == null) return@withLock false
        val result = CompletableDeferred<Boolean>()
        pendingWrite = result
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = frame
            g.writeCharacteristic(characteristic)
        }
        if (!started) {
            pendingWrite = null
            return@withLock false
        }
        val ok = withTimeoutOrNull(writeTimeoutMs) { result.await() } ?: false
        pendingWrite = null
        ok
    }

    override fun close() {
        if (closed) return
        closed = true
        ready.complete(false)
        pendingWrite?.complete(false)
        gatt?.let {
            it.disconnect()
            it.close() // always close: a leaked BluetoothGatt blocks future connections (error 133)
        }
        gatt = null
        listener.onLinkClosed(this)
    }

    private fun fail(reason: String) {
        logw("link to $peerId failed: $reason")
        close()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!gatt.requestMtu(BleConstants.REQUESTED_MTU)) gatt.discoverServices()
            } else {
                fail("connection state $newState, status $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@ClientLink.mtu = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(BleConstants.SERVICE_UUID)
            val stream = service?.getCharacteristic(BleConstants.STREAM_UUID)
            val inboxChar = service?.getCharacteristic(BleConstants.INBOX_UUID)
            val cccd = stream?.getDescriptor(BleConstants.CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || stream == null || inboxChar == null || cccd == null) {
                fail("service discovery failed, status $status")
                return
            }
            if (maxFrameSize < BleConstants.TARGET_WRITE_SIZE) {
                fail("MTU $mtu is too small for ${BleConstants.TARGET_WRITE_SIZE}-byte frames")
                return
            }
            inbox = inboxChar
            gatt.setCharacteristicNotification(stream, true)
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            if (!started) fail("could not enable notifications")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("enabling notifications failed, status $status")
                return
            }
            // Introduce ourselves; the link is usable once the HELLO write is confirmed.
            scope.launch {
                if (send(HelloFrame.encode(selfId))) {
                    listener.onLinkReady(this@ClientLink)
                    ready.complete(true)
                } else {
                    fail("HELLO write failed")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+ delivers the value directly ...
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            listener.onFrame(peerId, value)
        }

        // ... older versions deliver it through the (deprecated) characteristic.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { listener.onFrame(peerId, it) }
            }
        }
    }

    private companion object {
        const val DEFAULT_MTU = 23
    }
}
