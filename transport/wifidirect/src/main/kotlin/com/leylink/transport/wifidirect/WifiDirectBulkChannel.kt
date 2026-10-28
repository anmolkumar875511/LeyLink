package com.leylink.transport.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.leylink.core.port.BulkChannel
import com.leylink.core.port.ReceiveSession
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [BulkChannel] over Wi-Fi Direct. BLE negotiates, this moves the bytes.
 *
 * Protocol (the receiver is always the group owner, so no MAC address has to be exchanged):
 *  1. Receiver: `createGroup()`, read the group's network name and passphrase, open a TCP server socket.
 *     [ReceiveSession.info] = `networkName \n passphrase` (sent to the sender over BLE).
 *  2. Sender: `connect()` with that network name and passphrase (Android 10+), wait until the group is
 *     formed, open a socket to the group owner (192.168.49.1 unless Android says otherwise).
 *  3. Sender writes `length(4) | bytes`, receiver reads it, both tear the group down.
 *  The router verifies the SHA-256 from the XFER_OFFER, so corruption is caught above this layer.
 *
 * EXPERIMENTAL: Wi-Fi Direct behaves differently on every vendor. Every failure here returns null/false,
 * and the router then falls back to BLE chunking. Needs NEARBY_WIFI_DEVICES (Android 13+) or
 * ACCESS_FINE_LOCATION (12 and below) granted at runtime, otherwise [openReceiver] returns null.
 */
@SuppressLint("MissingPermission", "NewApi") // permission and SDK level are checked in usable()
class WifiDirectBulkChannel(context: Context) : BulkChannel {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(appContext, appContext.mainLooper, null)

    /** Only one Wi-Fi Direct group at a time. */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun usable(): Boolean =
        manager != null && channel != null && WifiDirectSupport.isSupported(appContext) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && WifiDirectSupport.hasPermission(appContext)

    // ------------------------------------------------------------------ receiver (group owner)

    override suspend fun openReceiver(): ReceiveSession? {
        if (!usable() || !busy.compareAndSet(false, true)) return null
        val m = manager ?: return release()
        val ch = channel ?: return release()
        return try {
            removeGroup(m, ch) // a stale group from an earlier crash would make createGroup fail
            if (!createGroup(m, ch)) return release()
            val group = groupInfo(m, ch) ?: return release().also { removeGroup(m, ch) }
            val name = group.networkName
            val pass = group.passphrase
            if (name.isNullOrEmpty() || pass.isNullOrEmpty()) return release().also { removeGroup(m, ch) }
            val server = withContext(Dispatchers.IO) { ServerSocket(PORT) }
            Session("$name\n$pass".toByteArray(Charsets.UTF_8), server, m, ch)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "openReceiver failed", e)
            removeGroup(m, ch)
            release()
        }
    }

    private fun release(): ReceiveSession? {
        busy.set(false)
        return null
    }

    private inner class Session(
        override val info: ByteArray,
        private val server: ServerSocket,
        private val m: WifiP2pManager,
        private val ch: WifiP2pManager.Channel,
    ) : ReceiveSession {
        override suspend fun await(expectedSize: Int, timeoutMs: Long): ByteArray? =
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    try {
                        server.accept().use { socket -> readPayload(socket, expectedSize) }
                    } catch (e: java.io.IOException) {
                        Log.w(TAG, "receive failed", e)
                        null
                    }
                }
            }

        override fun close() {
            runCatching { server.close() }
            removeGroup(m, ch)
            busy.set(false)
        }
    }

    private fun readPayload(socket: Socket, expectedSize: Int): ByteArray? {
        val input = DataInputStream(socket.getInputStream().buffered())
        val length = input.readInt()
        if (length != expectedSize || length <= 0) return null
        return ByteArray(length).also { input.readFully(it) }
    }

    // ------------------------------------------------------------------ sender (joins the group)

    override suspend fun push(info: ByteArray, data: ByteArray): Boolean {
        if (!usable() || !busy.compareAndSet(false, true)) return false
        val m = manager ?: return releaseFlag()
        val ch = channel ?: return releaseFlag()
        val parts = String(info, Charsets.UTF_8).split('\n')
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return releaseFlag()
        return try {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(parts[0])
                .setPassphrase(parts[1])
                .build()
            if (!connect(m, ch, config)) return false
            val owner = awaitGroupOwner(m, ch) ?: return false
            withContext(Dispatchers.IO) { send(owner, data) }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "push failed", e)
            false
        } finally {
            cancelConnect(m, ch)
            removeGroup(m, ch)
            busy.set(false)
        }
    }

    private fun releaseFlag(): Boolean {
        busy.set(false)
        return false
    }

    private fun send(owner: InetAddress, data: ByteArray): Boolean {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(owner, PORT), CONNECT_TIMEOUT_MS)
            val out = DataOutputStream(socket.getOutputStream().buffered())
            out.writeInt(data.size)
            out.write(data)
            out.flush()
        }
        return true
    }

    /** Waits until Android reports a formed group and tells us who the owner is. */
    private suspend fun awaitGroupOwner(m: WifiP2pManager, ch: WifiP2pManager.Channel): InetAddress? =
        withTimeoutOrNull(GROUP_TIMEOUT_MS) {
            var owner: InetAddress? = null
            while (owner == null) {
                val info = connectionInfo(m, ch)
                if (info?.groupFormed == true) owner = info.groupOwnerAddress
                if (owner == null) delay(POLL_MS)
            }
            owner
        }

    // ------------------------------------------------------------------ WifiP2pManager callbacks as suspend functions

    private class ActionResult : WifiP2pManager.ActionListener {
        val done = CompletableDeferred<Boolean>()

        override fun onSuccess() {
            done.complete(true)
        }

        override fun onFailure(reason: Int) {
            done.complete(false)
        }
    }

    private suspend fun createGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel): Boolean {
        val r = ActionResult()
        m.createGroup(ch, r)
        return withTimeoutOrNull(GROUP_TIMEOUT_MS) { r.done.await() } ?: false
    }

    private suspend fun connect(m: WifiP2pManager, ch: WifiP2pManager.Channel, config: WifiP2pConfig): Boolean {
        val r = ActionResult()
        m.connect(ch, config, r)
        return withTimeoutOrNull(GROUP_TIMEOUT_MS) { r.done.await() } ?: false
    }

    private fun cancelConnect(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        m.cancelConnect(ch, null)
    }

    private fun removeGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        m.removeGroup(ch, null)
    }

    private suspend fun groupInfo(m: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(GROUP_TIMEOUT_MS) {
            suspendCancellableCoroutine<WifiP2pGroup?> { cont -> m.requestGroupInfo(ch) { group -> cont.resume(group) } }
        }

    private suspend fun connectionInfo(m: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pInfo? =
        suspendCancellableCoroutine<WifiP2pInfo?> { cont -> m.requestConnectionInfo(ch) { info -> cont.resume(info) } }

    private companion object {
        const val TAG = "LeyWifiDirect"
        const val PORT = 8988
        const val CONNECT_TIMEOUT_MS = 8_000
        const val GROUP_TIMEOUT_MS = 15_000L
        const val POLL_MS = 500L
    }
}
