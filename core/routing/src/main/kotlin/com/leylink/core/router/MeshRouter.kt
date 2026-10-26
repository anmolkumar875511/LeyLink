package com.leylink.core.router

import com.leylink.core.codec.Chunker
import com.leylink.core.codec.DecodeResult
import com.leylink.core.codec.PacketCodec
import com.leylink.core.codec.Reassembler
import com.leylink.core.model.DeliveredMessage
import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.OutboxEntry
import com.leylink.core.model.Packet
import com.leylink.core.model.PacketType
import com.leylink.core.model.Priority
import com.leylink.core.port.OutboxStore
import com.leylink.core.port.SeenOutcome
import com.leylink.core.port.SendResult
import com.leylink.core.port.Transport
import com.leylink.core.port.TransportEvent
import com.leylink.core.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Store-and-forward flood router (README section 6).
 *
 * Two coroutines, so slow radios never block packet processing:
 *  - the EVENT LOOP handles transport events sequentially: reassemble, decode, dedup, deliver, enqueue
 *    relays. It never sends.
 *  - the PUMP sends outbox entries to every reachable peer whenever it is signalled.
 *
 * Receive rule for a copy with ttl t >= 1 (see the proofs in the README):
 *  1. drop malformed packets and our own packets;
 *  2. record t against the dedup memory: DUPLICATE (t <= best) is dropped;
 *  3. NEW is shown to the user; NEW and IMPROVED are relayed if t >= 2 with ttl t - 1, hop + 1,
 *     to every neighbour except the one it came from (split horizon).
 *
 * "Contact" model: [TransportEvent.PeerDiscovered] means a usable link to that peer is up,
 * [TransportEvent.PeerLost] means it is gone. Whatever is in the outbox is offered on every new contact,
 * which is what makes the mesh delay-tolerant. (Stage 4 adds the summary handshake to avoid re-offering
 * packets the peer already has.)
 */
class MeshRouter(
    private val selfId: NodeId,
    private val transport: Transport,
    private val outbox: OutboxStore,
    private val dedup: DedupCache,
    private val clock: Clock,
    private val config: RouterConfig = RouterConfig(),
) {
    private val reassembler = Reassembler(config.reassemblyTimeoutMs, config.maxInFlightReassemblies)
    private val reachable = MutableStateFlow<Set<NodeId>>(emptySet())
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val lastHousekeepingMs = AtomicLong(clock.nowMs())

    private val mutableDeliveries = MutableSharedFlow<DeliveredMessage>(extraBufferCapacity = 256)
    private val mutableStats = MutableStateFlow(RouterStats())
    private var job: Job? = null

    /** Messages shown to the user, once per message ID. */
    val deliveries: SharedFlow<DeliveredMessage> = mutableDeliveries.asSharedFlow()

    val stats: StateFlow<RouterStats> = mutableStats.asStateFlow()

    // ------------------------------------------------------------------ lifecycle

    fun start(scope: CoroutineScope): Job {
        check(job == null) { "router already started" }
        val newJob = scope.launch {
            // UNDISPATCHED: subscribe to events before the transport starts producing them.
            launch(start = CoroutineStart.UNDISPATCHED) {
                transport.events.collect { handleEvent(it) }
            }
            launch { pump() }
            transport.start()
        }
        job = newJob
        return newJob
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        transport.stop()
    }

    // ------------------------------------------------------------------ originate

    /**
     * Creates, stores and queues a new CHAT or SOS message.
     *
     * @throws IllegalArgumentException for unsupported types, SOS over 280 characters, or messages too
     *         large for BLE chunking (those belong to bulk transfer, Stage 7).
     */
    suspend fun send(type: PacketType, text: String): MsgId {
        require(type == PacketType.CHAT || type == PacketType.SOS) { "only CHAT and SOS can be sent by users" }
        if (type == PacketType.SOS) {
            require(text.length <= Packet.MAX_SOS_CHARS) { "SOS is limited to ${Packet.MAX_SOS_CHARS} characters" }
        }
        val now = clock.nowMs()
        val packet = Packet(
            type = type,
            priority = if (type == PacketType.SOS) Priority.SOS else Priority.CHAT,
            ttl = config.originTtl,
            hop = 1, // the origin's first transmission crosses link number 1
            msgId = MsgId.random(),
            originId = selfId,
            timestampMs = now,
            payload = text.toByteArray(Charsets.UTF_8),
        )
        val bytes = PacketCodec.encode(packet)
        require(Chunker.fits(bytes.size, config.maxFrameSize)) { "message too long for BLE chunking" }

        // Our own packet counts as seen, so a copy that wanders back is dropped.
        // Stage 4 makes "mark seen + enqueue" a single database transaction.
        dedup.record(packet.msgId, packet.ttl, now)
        outbox.enqueue(OutboxEntry(packet.msgId, bytes, packet.priority, packet.ttl, now))
        requestFlush()
        return packet.msgId
    }

    // ------------------------------------------------------------------ event loop

    private suspend fun handleEvent(event: TransportEvent) {
        maybeHousekeep()
        when (event) {
            is TransportEvent.PeerDiscovered -> {
                reachable.update { it + event.peer.nodeId }
                requestFlush()
            }
            is TransportEvent.PeerLost -> {
                reachable.update { it - event.peerId }
                reassembler.dropFrom(event.peerId)
            }
            is TransportEvent.FrameReceived -> onFrame(event.from, event.frame)
        }
    }

    private suspend fun onFrame(from: NodeId, frame: ByteArray) {
        bump { it.copy(framesIn = it.framesIn + 1) }
        val now = clock.nowMs()
        when (val result = reassembler.accept(from, frame, now)) {
            is Reassembler.Result.Complete -> onPacketBytes(from, result.msgId, result.bytes, now)
            Reassembler.Result.Incomplete -> Unit
            is Reassembler.Result.Rejected -> bump { it.copy(malformed = it.malformed + 1) }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun onPacketBytes(from: NodeId, frameMsgId: MsgId, bytes: ByteArray, now: Long) {
        val packet = when (val decoded = PacketCodec.decode(bytes)) {
            is DecodeResult.Ok -> decoded.packet
            is DecodeResult.Error -> {
                bump { it.copy(malformed = it.malformed + 1) }
                return
            }
        }
        if (packet.msgId != frameMsgId) { // frames claimed one message, the packet says another
            bump { it.copy(malformed = it.malformed + 1) }
            return
        }
        if (packet.originId == selfId) { // our own packet came back around
            bump { it.copy(duplicates = it.duplicates + 1) }
            return
        }
        if (packet.type != PacketType.CHAT && packet.type != PacketType.SOS) {
            bump { it.copy(unsupported = it.unsupported + 1) } // ACK/ANNOUNCE/... arrive in later stages
            return
        }

        val outcome = dedup.record(packet.msgId, packet.ttl, now)
        if (outcome == SeenOutcome.DUPLICATE) {
            bump { it.copy(duplicates = it.duplicates + 1) }
            return
        }
        if (outcome == SeenOutcome.NEW) deliver(packet, now)
        relayIfAllowed(packet, from, now)
    }

    private suspend fun deliver(packet: Packet, now: Long) {
        mutableDeliveries.emit(
            DeliveredMessage(
                msgId = packet.msgId,
                originId = packet.originId,
                type = packet.type,
                text = String(packet.payload(), Charsets.UTF_8),
                timestampMs = packet.timestampMs,
                hop = packet.hop,
                originTtl = packet.originTtl,
                receivedAtMs = now,
            ),
        )
        bump { it.copy(delivered = it.delivered + 1) }
    }

    private suspend fun relayIfAllowed(packet: Packet, from: NodeId, now: Long) {
        if (packet.ttl < 2) { // TTL exhausted: this was the last hop
            bump { it.copy(ttlExhausted = it.ttlExhausted + 1) }
            return
        }
        val relay = packet.relayed()
        outbox.enqueue(OutboxEntry(relay.msgId, PacketCodec.encode(relay), relay.priority, relay.ttl, now))
        outbox.markSent(relay.msgId, relay.ttl, from) // split horizon: never echo to the sender
        bump { it.copy(relaysQueued = it.relaysQueued + 1) }
        requestFlush()
    }

    // ------------------------------------------------------------------ pump (sending)

    private fun requestFlush() {
        flushSignal.trySend(Unit)
    }

    private suspend fun pump() {
        while (true) {
            flushSignal.receive()
            val jitter = config.jitterMs()
            if (jitter > 0) delay(jitter)
            maybeHousekeep()
            for (peer in reachable.value) flushTo(peer)
        }
    }

    private suspend fun flushTo(peer: NodeId) {
        repeat(config.maxFlushRounds) {
            val batch = outbox.nextBatch(peer, config.batchSize)
            if (batch.isEmpty()) return
            for (entry in batch) {
                if (!sendEntry(peer, entry)) return // link trouble: retry on the next contact or signal
            }
        }
    }

    /** @return true if every frame of the entry was sent and the entry was marked as sent. */
    private suspend fun sendEntry(peer: NodeId, entry: OutboxEntry): Boolean {
        val frames = Chunker.split(entry.msgId, entry.packetBytes(), config.maxFrameSize)
        for (frame in frames) {
            when (transport.send(peer, frame)) {
                is SendResult.Success -> bump { it.copy(framesOut = it.framesOut + 1) }
                is SendResult.Failure -> {
                    bump { it.copy(sendFailures = it.sendFailures + 1) }
                    return false
                }
            }
        }
        outbox.markSent(entry.msgId, entry.ttl, peer)
        bump { it.copy(packetsSent = it.packetsSent + 1) }
        return true
    }

    // ------------------------------------------------------------------ housekeeping

    /** Lazy (no timer coroutine): runs when traffic exists, which is when memory can actually grow. */
    private suspend fun maybeHousekeep() {
        val now = clock.nowMs()
        val last = lastHousekeepingMs.get()
        if (now - last < config.housekeepingIntervalMs) return
        if (!lastHousekeepingMs.compareAndSet(last, now)) return
        outbox.expireOlderThan(now - config.retentionMs)
        dedup.purgeOlderThan(now - config.seenRetentionMs)
    }

    private fun bump(change: (RouterStats) -> RouterStats) {
        mutableStats.update(change)
    }
}
