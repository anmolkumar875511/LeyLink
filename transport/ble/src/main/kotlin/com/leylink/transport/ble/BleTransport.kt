package com.leylink.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.leylink.core.model.NodeId
import com.leylink.core.model.Peer
import com.leylink.core.port.SendResult
import com.leylink.core.port.Transport
import com.leylink.core.port.TransportEvent
import com.leylink.core.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BLE implementation of [Transport]. Every phone is BOTH peripheral (advertises, runs a GATT server) and
 * central (scans, connects out). To avoid two phones connecting to each other at the same time, the
 * node with the LOWER nodeId initiates the connection; the other one waits to be called.
 *
 * To the router a "peer" appears only when a link is fully usable (MTU ok, notifications on, HELLO
 * exchanged): [TransportEvent.PeerDiscovered] means link up, [TransportEvent.PeerLost] link down.
 *
 * Power and reach tuning (Victory tier):
 *  - BEACON: while the router says one of our SOS messages has no taker, the advertisement carries a
 *    beacon flag. A scanner that sees it connects even if its nodeId is higher (rescuer initiates), and
 *    both sides scan continuously instead of duty cycling.
 *  - DIGEST: the advertisement carries a 16-bit fingerprint of our dedup memory. If a neighbour's digest
 *    equals ours and we were linked recently, we skip the connection: nothing to exchange.
 *  - SCAN DUTY CYCLE and IDLE EVICTION: scan in windows, close links that carried nothing for a while.
 *    Both are reversed automatically when there is something to send (digest changes, beacon).
 *
 * Failed connection attempts back off exponentially (with jitter), so one bad device cannot monopolise
 * the radio.
 */
@SuppressLint("MissingPermission") // the app checks permissions before starting the mesh
class BleTransport(
    private val context: Context,
    private val selfId: NodeId,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.SYSTEM,
    private val config: BleConfig = BleConfig(),
) : Transport {

    private class RetryState(val failures: Int, val notBeforeMs: Long)

    private val inbox = Channel<TransportEvent>(Channel.UNLIMITED)
    private val mutablePeers = MutableStateFlow<List<Peer>>(emptyList())

    override val events: Flow<TransportEvent> = inbox.receiveAsFlow()
    override val connectedPeers: StateFlow<List<Peer>> = mutablePeers.asStateFlow()

    private val links = ConcurrentHashMap<NodeId, BleLink>()
    private val connecting = ConcurrentHashMap.newKeySet<NodeId>()
    private val retry = ConcurrentHashMap<NodeId, RetryState>()
    private val rssi = ConcurrentHashMap<NodeId, Int>()
    private val lastActivity = ConcurrentHashMap<NodeId, Long>()
    private val lastLinkedAt = ConcurrentHashMap<NodeId, Long>()
    private val remoteBeaconAt = ConcurrentHashMap<NodeId, Long>()

    private var server: GattServerHost? = null
    private var advertiser: BleAdvertiser? = null
    private var scanner: BleScanner? = null
    private val jobs = mutableListOf<Job>()
    private val advertSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var running = false

    @Volatile private var localBeacon = false

    @Volatile private var localDigest = 0

    @Volatile private var digestKnown = false

    override suspend fun start() {
        if (running) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            logw("Bluetooth is off or unavailable; the transport stays stopped")
            return
        }
        try {
            val host = GattServerHost(context, selfId, linkListener, config.writeTimeoutMs)
            server = host
            if (!host.open()) {
                logw("could not open the GATT server")
                host.close()
                server = null
                return
            }
            advertiser = BleAdvertiser(adapter, selfId).also {
                it.update(localBeacon, localDigest)
                it.start()
            }
            scanner = BleScanner(adapter, ::onAdvertisement).also { it.start() }
            running = true
            jobs += scope.launch { scanLoop() }
            jobs += scope.launch { idleLoop() }
            jobs += scope.launch { advertLoop() }
            logd("transport started as $selfId")
        } catch (e: SecurityException) {
            logw("missing Bluetooth permission", e)
            stop()
        }
    }

    override suspend fun stop() {
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        scanner?.stop()
        advertiser?.stop()
        links.values.toList().forEach { it.close() }
        server?.close()
        scanner = null
        advertiser = null
        server = null
        links.clear()
        connecting.clear()
        publishPeers()
    }

    override suspend fun send(peer: NodeId, frame: ByteArray): SendResult {
        val link = links[peer] ?: return SendResult.Failure("no link to peer")
        if (frame.size > link.maxFrameSize) return SendResult.Failure("frame larger than the link's MTU allows")
        val ok = link.send(frame)
        if (ok) lastActivity[peer] = clock.nowMs()
        return if (ok) SendResult.Success else SendResult.Failure("radio write failed")
    }

    override fun setBeacon(active: Boolean) {
        if (localBeacon == active) return
        localBeacon = active
        logd("beacon mode ${if (active) "ON" else "off"}")
        advertSignal.trySend(Unit)
    }

    override fun setDigest(digest: Int) {
        if (digestKnown && localDigest == digest) return
        localDigest = digest
        digestKnown = true
        advertSignal.trySend(Unit)
    }

    // ------------------------------------------------------------------ background loops

    /** Applies beacon/digest changes to the advertisement, at most once per update interval. */
    private suspend fun advertLoop() {
        while (true) {
            advertSignal.receive()
            advertiser?.update(localBeacon, localDigest)
            delay(config.advertUpdateIntervalMs)
        }
    }

    /** Scan in windows to save battery; scan continuously while a beacon is involved. */
    private suspend fun scanLoop() {
        while (scope.isActive && running) {
            scanner?.start()
            delay(config.scanOnMs)
            val continuous = !config.dutyCycleEnabled || config.scanOffMs <= 0 || beaconInvolved()
            if (!continuous) {
                scanner?.stop()
                delay(config.scanOffMs)
            }
        }
    }

    private fun beaconInvolved(): Boolean {
        if (localBeacon) return true
        val now = clock.nowMs()
        return remoteBeaconAt.values.any { now - it < config.remoteBeaconTtlMs }
    }

    /** Closes links that carried nothing for [BleConfig.idleTimeoutMs]. They return on demand. */
    private suspend fun idleLoop() {
        while (scope.isActive && running) {
            delay(config.idleCheckIntervalMs)
            val now = clock.nowMs()
            for ((peer, link) in links) {
                val last = lastActivity[peer] ?: now
                if (now - last > config.idleTimeoutMs) {
                    logd("closing idle link to $peer")
                    link.close()
                }
            }
        }
    }

    // ------------------------------------------------------------------ discovery -> connection

    private fun onAdvertisement(device: BluetoothDevice, advert: BleConstants.Advert, signal: Int) {
        val nodeId = advert.nodeId
        if (!running || nodeId == selfId) return
        val now = clock.nowMs()
        rssi[nodeId] = signal
        if (advert.beacon) remoteBeaconAt[nodeId] = now else remoteBeaconAt.remove(nodeId)

        // The lower nodeId initiates, except that a beaconing node is always approached by whoever hears it.
        if (selfId > nodeId && !advert.beacon) return
        if (links.containsKey(nodeId) || links.size >= config.maxLinks) return
        if (now < (retry[nodeId]?.notBeforeMs ?: 0L)) return // still backing off
        if (!advert.beacon && !localBeacon && sameDigestRecently(nodeId, advert.digest, now)) return
        if (!connecting.add(nodeId)) return // an attempt is already running
        scope.launch {
            try {
                connectTo(device, nodeId)
            } finally {
                connecting.remove(nodeId)
            }
        }
    }

    /** True when both sides hold the same messages and we talked recently: skip the connection. */
    private fun sameDigestRecently(nodeId: NodeId, remoteDigest: Int, now: Long): Boolean {
        if (!digestKnown || remoteDigest != localDigest) return false
        val last = lastLinkedAt[nodeId] ?: return false // a first contact always connects
        return now - last < config.digestSkipWindowMs
    }

    private suspend fun connectTo(device: BluetoothDevice, nodeId: NodeId) {
        logd("connecting to $nodeId")
        val link = ClientLink(context, device, nodeId, selfId, scope, linkListener, config.writeTimeoutMs)
        if (link.connect(config.connectTimeoutMs)) {
            retry.remove(nodeId)
        } else {
            val failures = (retry[nodeId]?.failures ?: 0) + 1
            val backoff = minOf(config.retryCapMs, config.retryBaseMs shl minOf(failures, MAX_SHIFT))
            val jittered = (backoff * Random.nextDouble(0.5, 1.0)).toLong()
            retry[nodeId] = RetryState(failures, clock.nowMs() + jittered)
            logd("connect to $nodeId failed ($failures in a row); retry in ${jittered}ms")
        }
    }

    // ------------------------------------------------------------------ link events
    /** Kept private: [LinkListener] and [BleLink] are module-internal, the transport itself is public. */
    private val linkListener = object : LinkListener {

        override fun onLinkReady(link: BleLink) {
            if (!running) {
                link.close()
                return
            }
            if (links.putIfAbsent(link.peerId, link) != null) { // we already have a link to this peer
                link.close()
                return
            }
            val now = clock.nowMs()
            lastActivity[link.peerId] = now
            lastLinkedAt[link.peerId] = now
            publishPeers()
            inbox.trySend(TransportEvent.PeerDiscovered(Peer(link.peerId, rssi[link.peerId] ?: 0, now)))
            logd("link up: ${link.peerId}")
        }

        override fun onFrame(from: NodeId, frame: ByteArray) {
            lastActivity[from] = clock.nowMs()
            inbox.trySend(TransportEvent.FrameReceived(from, frame))
        }

        override fun onLinkClosed(link: BleLink) {
            if (links.remove(link.peerId, link)) {
                lastActivity.remove(link.peerId)
                lastLinkedAt[link.peerId] = clock.nowMs()
                publishPeers()
                inbox.trySend(TransportEvent.PeerLost(link.peerId))
                logd("link down: ${link.peerId}")
            }
        }
    }

    private fun publishPeers() {
        val now = clock.nowMs()
        mutablePeers.value = links.keys.map { Peer(it, rssi[it] ?: 0, now) }
    }

    private companion object {
        const val MAX_SHIFT = 6
    }
}
