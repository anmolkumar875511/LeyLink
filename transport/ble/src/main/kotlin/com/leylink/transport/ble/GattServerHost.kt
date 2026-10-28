package com.leylink.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.leylink.core.model.NodeId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Our GATT server (peripheral role). A central that connects to us becomes a [ServerLink] once it has
 * sent its HELLO frame. Frames arrive as writes to INBOX; we answer with notifications on STREAM.
 */
@SuppressLint("MissingPermission") // permissions are checked by the app before the mesh starts
@Suppress("DEPRECATION")
internal class GattServerHost(
    private val context: Context,
    private val selfId: NodeId,
    private val listener: LinkListener,
    private val writeTimeoutMs: Long,
) {
    private var server: BluetoothGattServer? = null
    private var streamCharacteristic: BluetoothGattCharacteristic? = null
    private var serviceAdded: CompletableDeferred<Boolean>? = null

    private val links = ConcurrentHashMap<String, ServerLink>() // by device address
    private val mtus = ConcurrentHashMap<String, Int>()
    private val pendingNotify = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** @return true when the GATT server is open and our service is registered. */
    suspend fun open(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val opened = manager.openGattServer(context, callback) ?: return false
        server = opened

        val stream = BluetoothGattCharacteristic(
            BleConstants.STREAM_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        stream.addDescriptor(
            BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        streamCharacteristic = stream

        val inbox = BluetoothGattCharacteristic(
            BleConstants.INBOX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val info = BluetoothGattCharacteristic(
            BleConstants.INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(inbox)
        service.addCharacteristic(stream)
        service.addCharacteristic(info)

        val added = CompletableDeferred<Boolean>()
        serviceAdded = added
        if (!opened.addService(service)) return false
        return withTimeoutOrNull(SERVICE_ADD_TIMEOUT_MS) { added.await() } ?: false
    }

    fun close() {
        links.values.forEach { it.markClosed() }
        links.clear()
        mtus.clear()
        pendingNotify.values.forEach { it.complete(false) }
        pendingNotify.clear()
        server?.close()
        server = null
        streamCharacteristic = null
    }

    private fun infoValue(): ByteArray = byteArrayOf(BleConstants.PROTOCOL_VERSION) + selfId.toByteArray()

    private suspend fun notifyAndAwait(device: BluetoothDevice, frame: ByteArray): Boolean {
        val gattServer = server
        val characteristic = streamCharacteristic
        if (gattServer == null || characteristic == null) return false
        val result = CompletableDeferred<Boolean>()
        pendingNotify[device.address] = result
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer.notifyCharacteristicChanged(device, characteristic, false, frame) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = frame
            gattServer.notifyCharacteristicChanged(device, characteristic, false)
        }
        if (!started) {
            pendingNotify.remove(device.address)
            return false
        }
        val ok = withTimeoutOrNull(writeTimeoutMs) { result.await() } ?: false
        pendingNotify.remove(device.address)
        return ok
    }

    /** A central that has said HELLO. Sends go out as notifications, one at a time. */
    private inner class ServerLink(
        val device: BluetoothDevice,
        override val peerId: NodeId,
    ) : BleLink {
        private val mutex = Mutex()

        @Volatile private var closed = false

        override val maxFrameSize: Int
            get() = (mtus[device.address] ?: DEFAULT_MTU) - BleConstants.ATT_OVERHEAD

        override suspend fun send(frame: ByteArray): Boolean = mutex.withLock {
            if (closed) false else notifyAndAwait(device, frame)
        }

        fun markClosed() {
            closed = true
        }

        override fun close() {
            if (closed) return
            closed = true
            server?.cancelConnection(device)
            if (links.remove(device.address, this)) listener.onLinkClosed(this)
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            serviceAdded?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mtus.remove(device.address)
                pendingNotify.remove(device.address)?.complete(false)
                links.remove(device.address)?.let {
                    it.markClosed()
                    listener.onLinkClosed(it)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val acceptable = characteristic.uuid == BleConstants.INBOX_UUID && !preparedWrite && value != null
            if (responseNeeded) {
                val status = if (acceptable) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                server?.sendResponse(device, requestId, status, 0, null)
            }
            if (acceptable && value != null) handleInbound(device, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = infoValue()
            if (characteristic.uuid == BleConstants.INFO_UUID && offset <= value.size) {
                server?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size),
                )
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pendingNotify[device.address]?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun handleInbound(device: BluetoothDevice, value: ByteArray) {
        val hello = HelloFrame.parse(value)
        if (hello != null) {
            val mtu = mtus[device.address] ?: DEFAULT_MTU
            if (mtu - BleConstants.ATT_OVERHEAD < BleConstants.TARGET_WRITE_SIZE) {
                logw("central ${device.address} negotiated MTU $mtu, too small; disconnecting")
                server?.cancelConnection(device)
                return
            }
            val link = ServerLink(device, hello)
            val previous = links.put(device.address, link)
            previous?.markClosed()
            listener.onLinkReady(link)
            return
        }
        val link = links[device.address] ?: return // frames before HELLO are ignored
        listener.onFrame(link.peerId, value)
    }

    private companion object {
        const val DEFAULT_MTU = 23
        const val SERVICE_ADD_TIMEOUT_MS = 3_000L
    }
}
