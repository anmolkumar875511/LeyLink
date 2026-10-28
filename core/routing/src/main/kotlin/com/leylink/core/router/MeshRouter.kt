package com.leylink.core.router

import com.leylink.core.codec.Chunker
import com.leylink.core.codec.DecodeResult
import com.leylink.core.codec.PacketCodec
import com.leylink.core.codec.Reassembler
import com.leylink.core.codec.SyncDigest
import com.leylink.core.codec.SyncSummary
import com.leylink.core.codec.Xfer
import com.leylink.core.model.DeliveredMessage
import com.leylink.core.model.MsgId
import com.leylink.core.model.NodeId
import com.leylink.core.model.OutboxEntry
import com.leylink.core.model.Packet
import com.leylink.core.model.PacketType
import com.leylink.core.model.Priority
import com.leylink.core.model.StoredMessage
import com.leylink.core.port.BulkChannel
import com.leylink.core.port.KeyDirectory
import com.leylink.core.port.MessageStore
import com.leylink.core.port.OutboxStore
import com.leylink.core.port.PinResult
import com.leylink.core.port.ReceiveSession
import com.leylink.core.port.SeenOutcome
import com.leylink.core.port.SendResult
import com.leylink.core.port.Signer
import com.leylink.core.port.TransactionRunner
import com.leylink.core.port.Transport
import com.leylink.core.port.TransportEvent
import com.leylink.core.time.Clock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Store-and-forward flood router (README section 6).
 *
 * Two coroutines, so slow radios never block packet processing:
 *  - the EVENT LOOP handles transport events sequentially: reassemble, decode, dedup, store, enqueue
 *    relays. It never sends.
 *  - the PUMP sends: first our sync summary to new contacts, then outbox entries to every reachable peer.
 *
 * Receive rule for a copy with ttl t >= 1 (proofs in the README):
 *  1. drop malformed packets and our own packets;
 *  2. record t in the dedup memory: DUPLICATE (t <= best) is dropped;
 *  3. NEW is stored and shown; NEW and IMPROVED are relayed if t >= 2 with ttl t - 1, hop + 1, to every
 *     neighbour except the one it came from (split horizon).
 * Steps 2 and 3 run inside ONE [TransactionRunner] transaction.
 *
 * Contact model: [TransportEvent.PeerDiscovered] means a usable link to that peer is up,
 * [TransportEvent.PeerLost] that it is gone. With [RouterConfig.syncEnabled], each side first sends a
 * SYNC_SUMMARY ("what I already have") and the other side skips those packets.
 *
 * Victory-tier additions:
 *  - SIGNING (needs a [Signer]): originated packets carry the origin's public key and an Ed25519 signature
 *    over the immutable fields. Received CHAT/SOS/ANNOUNCE are verified BEFORE dedup, so a forged copy can
 *    never poison the dedup memory. Keys are pinned on first use ([KeyDirectory]); a different key for a
 *    known origin is rejected.
 *  - BEACON: while one of our own SOS packets has not been handed to any neighbour, the transport is told
 *    to beacon ([Transport.setBeacon]).
 *  - BULK: packets above [RouterConfig.bulkThresholdBytes] are first offered over the [BulkChannel]
 *    (XFER_OFFER over BLE negotiates, bytes travel over Wi-Fi Direct). Any failure falls back to BLE
 *    chunking, or the packet stays queued if it is too big for that.
 */
class MeshRouter(
    private val selfId: NodeId,
    private val transport: Transport,
    private val outbox: OutboxStore,
    private val dedup: DedupCache,
    private val clock: Clock,
    private val config: RouterConfig = RouterConfig(),
    private val transactions: TransactionRunner = TransactionRunner.NONE,
    private val messages: MessageStore? = null,
    private val signer: Signer? = null,
    private val keys: KeyDirectory = InMemoryKeyDirectory(),
    private val bulk: BulkChannel? = null,
) {
    private data class OfferKey(val peer: NodeId, val msgId: MsgId)

    private enum class SendOutcome { SENT, FAILED, SKIPPED }

    private val reassembler = Reassembler(config.reassemblyTimeoutMs, config.maxInFlightReassemblies)
    private val reachable = MutableStateFlow<Set<NodeId>>(emptySet())
    private val awaitingSummary = MutableStateFlow<Set<NodeId>>(emptySet())
    private val summaryToSend = MutableStateFlow<Set<NodeId>>(emptySet())
    private val announceToSend = MutableStateFlow<Set<NodeId>>(emptySet())
    private val unackedSos = ConcurrentHashMap.newKeySet<MsgId>()
    private val pendingOffers = ConcurrentHashMap<OfferKey, CompletableDeferred<ByteArray?>>()
    private val activeReceives = AtomicInteger(0)

    /** Serializes packet processing: the event loop and bulk arrivals both end up in [onPacketBytes]. */
    private val processing = Mutex()
    private var routerScope: CoroutineScope? = null
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val lastHousekeepingMs = AtomicLong(clock.nowMs())

    private val mutableDeliveries = MutableSharedFlow<DeliveredMessage>(extraBufferCapacity = 256)
    private val mutableStats = MutableStateFlow(RouterStats())
    private var job: Job? = null

    /** Messages shown to the user, once per message ID (also persisted when a [MessageStore] is given). */
    val deliveries: SharedFlow<DeliveredMessage> = mutableDeliveries.asSharedFlow()

    val stats: StateFlow<RouterStats> = mutableStats.asStateFlow()

    // ------------------------------------------------------------------ lifecycle

    fun start(scope: CoroutineScope): Job {
        check(job == null) { "router already started" }
        routerScope = scope
        val newJob = scope.launch {
            // UNDISPATCHED: subscribe to events before the transport starts producing them.
            launch(start = CoroutineStart.UNDISPATCHED) {
                transport.events.collect { handleEvent(it) }
            }
            launch { pump() }
            transport.start()
            refreshDigest()
            refreshBeacon()
        }
        job = newJob
        return newJob
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        reachable.value = emptySet()
        awaitingSummary.value = emptySet()
        summaryToSend.value = emptySet()
        announceToSend.value = emptySet()
        pendingOffers.values.forEach { it.complete(null) }
        pendingOffers.clear()
        routerScope = null
        transport.stop()
    }

    // ------------------------------------------------------------------ originate

    /**
     * Creates, stores and queues a new CHAT or SOS message.
     *
     * @throws IllegalArgumentException for unsupported types, SOS over 280 characters, or messages too
     *         large for BLE chunking when no bulk channel exists.
     */
    suspend fun send(type: PacketType, text: String): MsgId {
        require(type == PacketType.CHAT || type == PacketType.SOS) { "only CHAT and SOS can be sent by users" }
        if (type == PacketType.SOS) {
            require(text.length <= Packet.MAX_SOS_CHARS) { "SOS is limited to ${Packet.MAX_SOS_CHARS} characters" }
        }
        val now = clock.nowMs()
        val packet = signed(
            Packet(
                type = type,
                priority = if (type == PacketType.SOS) Priority.SOS else Priority.CHAT,
                ttl = config.originTtl,
                hop = 1, // the origin's first transmission crosses link number 1
                msgId = MsgId.random(),
                originId = selfId,
                timestampMs = now,
                payload = text.toByteArray(Charsets.UTF_8),
                beacon = type == PacketType.SOS, // "origin still has no taker" (README 5.1 flags)
            ),
        )
        val bytes = PacketCodec.encode(packet)
        require(Chunker.fits(bytes.size, config.maxFrameSize) || (bulk != null && text.length <= MAX_BULK_CHARS)) {
            "message too long"
        }

        // One transaction: our own packet counts as seen, is shown in our history, and is queued.
        transactions.run {
            dedup.record(packet.msgId, packet.ttl, now)
            messages?.insertIfAbsent(
                StoredMessage(
                    msgId = packet.msgId, originId = selfId, type = type, text = text,
                    timestampMs = now, hop = 0, originTtl = packet.ttl, receivedAtMs = now, mine = true,
                ),
            )
            outbox.enqueue(OutboxEntry(packet.msgId, bytes, packet.priority, packet.ttl, now))
        }
        if (type == PacketType.SOS) {
            unackedSos.add(packet.msgId)
            refreshBeacon()
        }
        refreshDigest()
        requestFlush()
        return packet.msgId
    }

    // ------------------------------------------------------------------ event loop

    private suspend fun handleEvent(event: TransportEvent) = guarded {
        processing.withLock {
            maybeHousekeep()
            when (event) {
                is TransportEvent.PeerDiscovered -> onContact(event.peer.nodeId)
                is TransportEvent.PeerLost -> onContactLost(event.peerId)
                is TransportEvent.FrameReceived -> onFrame(event.from, event.frame)
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A failed transaction may have rolled the store back while the in-memory cache moved on.
            dedup.clear()
            bump { it.copy(errors = it.errors + 1) }
        }
    }

    private fun onContact(peer: NodeId) {
        reachable.update { it + peer }
        if (config.syncEnabled) {
            awaitingSummary.update { it + peer }
            summaryToSend.update { it + peer }
        }
        if (signer != null) announceToSend.update { it + peer }
        requestFlush()
    }

    private fun onContactLost(peer: NodeId) {
        reachable.update { it - peer }
        awaitingSummary.update { it - peer }
        summaryToSend.update { it - peer }
        announceToSend.update { it - peer }
        reassembler.dropFrom(peer)
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
        if (packet.type == PacketType.SYNC_SUMMARY && config.syncEnabled) {
            onSyncSummary(from, packet)
            return
        }
        if (packet.type == PacketType.XFER_OFFER) {
            onXfer(from, packet)
            return
        }
        if (packet.type == PacketType.ANNOUNCE) {
            onAnnounce(from, packet)
            return
        }
        if (packet.type != PacketType.CHAT && packet.type != PacketType.SOS) {
            bump { it.copy(unsupported = it.unsupported + 1) } // ACK is reserved
            return
        }
        // Verify BEFORE dedup: a forged copy must not mark the real message as "already seen".
        if (!signatureOk(packet, mandatory = config.requireSignatures)) {
            bump {
                it.copy(
                    signatureRejected = it.signatureRejected + 1,
                    sosDropped = it.sosDropped + if (packet.type == PacketType.SOS) 1 else 0,
                )
            }
            return
        }

        val accepted = transactions.run { accept(packet, from, now) }
        if (accepted.outcome == SeenOutcome.DUPLICATE) {
            bump { it.copy(duplicates = it.duplicates + 1) }
            return
        }
        if (accepted.outcome == SeenOutcome.NEW) {
            mutableDeliveries.emit(
                DeliveredMessage(
                    msgId = packet.msgId, originId = packet.originId, type = packet.type,
                    text = String(packet.payload(), Charsets.UTF_8), timestampMs = packet.timestampMs,
                    hop = packet.hop, originTtl = packet.originTtl, receivedAtMs = now,
                ),
            )
            bump { it.copy(delivered = it.delivered + 1) }
        }
        refreshDigest()
        if (accepted.relayed) {
            bump { it.copy(relaysQueued = it.relaysQueued + 1) }
            requestFlush()
        } else {
            bump { it.copy(ttlExhausted = it.ttlExhausted + 1) } // this was the last hop
        }
    }

    // ------------------------------------------------------------------ signing

    /** Returns [packet] with our public key and signature attached (unchanged when there is no signer). */
    private fun signed(packet: Packet): Packet {
        val s = signer ?: return packet
        val key = s.publicKey()
        fun build(signature: ByteArray?) = Packet(
            type = packet.type, priority = packet.priority, ttl = packet.ttl, hop = packet.hop,
            msgId = packet.msgId, originId = packet.originId, timestampMs = packet.timestampMs,
            payload = packet.payload(), signature = signature, beacon = packet.beacon,
            version = packet.version, publicKey = key,
        )
        return build(s.sign(PacketCodec.signedRegion(build(null))))
    }

    /**
     * @param mandatory true if an unsigned packet must be rejected. A signed packet is ALWAYS verified
     *        when we have a signer, and its key is pinned on first use.
     */
    @Suppress("ReturnCount")
    private suspend fun signatureOk(packet: Packet, mandatory: Boolean): Boolean {
        val s = signer ?: return !mandatory
        val signature = packet.signature() ?: return !mandatory
        val key = packet.publicKey() ?: return false // signed but nothing to verify with
        if (!s.verify(key, PacketCodec.signedRegion(packet), signature)) return false
        return keys.pin(packet.originId, key) != PinResult.MISMATCH
    }

    private suspend fun onAnnounce(from: NodeId, packet: Packet) {
        val key = packet.publicKey()
        val valid = signer != null && key != null && packet.originId == from &&
            key.contentEquals(packet.payload()) && signatureOk(packet, mandatory = true)
        if (valid) {
            bump { it.copy(announcesIn = it.announcesIn + 1) }
        } else {
            bump { it.copy(signatureRejected = it.signatureRejected + 1) }
        }
    }

    // ------------------------------------------------------------------ bulk transfer (receiver side)

    private suspend fun onXfer(from: NodeId, packet: Packet) {
        when (val x = Xfer.decode(packet.payload())) {
            null -> bump { it.copy(malformed = it.malformed + 1) }
            is Xfer.Offer -> onOffer(from, x)
            is Xfer.Accept -> pendingOffers[OfferKey(from, x.msgId)]?.complete(x.info())
            is Xfer.Decline -> pendingOffers[OfferKey(from, x.msgId)]?.complete(null)
        }
    }

    /** Validates cheaply here; everything slow (radio, group formation) runs in its own coroutine. */
    private suspend fun onOffer(from: NodeId, offer: Xfer.Offer) {
        val channel = bulk
        val scope = routerScope
        val tooBig = offer.size > Packet.HEADER_SIZE + Packet.MAX_PAYLOAD + Packet.PUBLIC_KEY_SIZE + Packet.SIGNATURE_SIZE
        val wanted = !tooBig && activeReceives.get() < MAX_ACTIVE_RECEIVES && dedup.bestTtl(offer.msgId) < offer.ttl
        if (channel == null || scope == null || !wanted) {
            scope?.launch { sendControl(from, Xfer.encode(Xfer.Decline(offer.msgId))) }
            return
        }
        activeReceives.incrementAndGet()
        scope.launch {
            var session: ReceiveSession? = null
            try {
                session = channel.openReceiver()
                if (session == null || !sendControl(from, Xfer.encode(Xfer.Accept(offer.msgId, session.info)))) {
                    if (session == null) sendControl(from, Xfer.encode(Xfer.Decline(offer.msgId)))
                    return@launch
                }
                val data = session.await(offer.size, config.bulkTimeoutMs)
                val intact = data != null && data.size == offer.size && sha256(data).contentEquals(offer.sha256())
                if (data != null && intact) {
                    guarded { processing.withLock { onPacketBytes(from, offer.msgId, data, clock.nowMs()) } }
                    bump { it.copy(bulkReceived = it.bulkReceived + 1) }
                } else {
                    bump { it.copy(malformed = it.malformed + 1) } // timeout, truncated or tampered
                }
            } finally {
                session?.close()
                activeReceives.decrementAndGet()
            }
        }
    }

    private class Accepted(val outcome: SeenOutcome, val relayed: Boolean)

    /** The atomic part: dedup record, history insert and relay enqueue succeed or fail together. */
    private suspend fun accept(packet: Packet, from: NodeId, now: Long): Accepted {
        val outcome = dedup.record(packet.msgId, packet.ttl, now)
        if (outcome == SeenOutcome.DUPLICATE) return Accepted(outcome, relayed = false)

        if (outcome == SeenOutcome.NEW) {
            messages?.insertIfAbsent(
                StoredMessage(
                    msgId = packet.msgId, originId = packet.originId, type = packet.type,
                    text = String(packet.payload(), Charsets.UTF_8), timestampMs = packet.timestampMs,
                    hop = packet.hop, originTtl = packet.originTtl, receivedAtMs = now, mine = false,
                ),
            )
        }
        if (packet.ttl < 2) return Accepted(outcome, relayed = false)

        val relay = packet.relayed()
        outbox.enqueue(OutboxEntry(relay.msgId, PacketCodec.encode(relay), relay.priority, relay.ttl, now))
        outbox.markSent(relay.msgId, relay.ttl, from) // split horizon: never echo to the sender
        return Accepted(outcome, relayed = true)
    }

    private suspend fun onSyncSummary(from: NodeId, packet: Packet) {
        val entries = SyncSummary.decode(packet.payload())
        if (entries == null) {
            bump { it.copy(malformed = it.malformed + 1) }
            return
        }
        for (entry in entries) outbox.markPeerHas(from, entry.msgId, entry.ttl)
        awaitingSummary.update { it - from }
        bump { it.copy(summariesIn = it.summariesIn + 1) }
        requestFlush()
    }

    // ------------------------------------------------------------------ pump (sending)

    private fun requestFlush() {
        flushSignal.trySend(Unit)
    }

    private suspend fun pump() {
        while (true) {
            if (awaitingSummary.value.isEmpty()) {
                flushSignal.receive()
            } else if (withTimeoutOrNull(config.syncWaitMs) { flushSignal.receive() } == null) {
                awaitingSummary.value = emptySet() // peer never answered: send anyway
            }
            val jitter = config.jitterMs()
            if (jitter > 0) delay(jitter)
            try {
                maybeHousekeep()
                sendPendingSummaries()
                val waiting = awaitingSummary.value
                for (peer in reachable.value) {
                    if (peer !in waiting) flushTo(peer)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                bump { it.copy(errors = it.errors + 1) }
            }
        }
    }

    private suspend fun sendPendingSummaries() {
        val targets = summaryToSend.value
        if (targets.isNotEmpty()) {
            summaryToSend.update { it - targets }
            val now = clock.nowMs()
            val payload = SyncSummary.encode(dedup.snapshot(config.maxSummaryEntries))
            for (peer in targets) {
                if (peer !in reachable.value) continue
                if (sendControl(peer, payload, PacketType.SYNC_SUMMARY, now)) {
                    bump { it.copy(summariesOut = it.summariesOut + 1) }
                }
            }
        }
        sendPendingAnnounces()
    }

    /** One ANNOUNCE (public key, signed) per new contact, so the neighbour can pin our key right away. */
    private suspend fun sendPendingAnnounces() {
        val s = signer ?: return
        val targets = announceToSend.value
        if (targets.isEmpty()) return
        announceToSend.update { it - targets }
        for (peer in targets) {
            if (peer !in reachable.value) continue
            if (sendControl(peer, s.publicKey(), PacketType.ANNOUNCE, clock.nowMs())) {
                bump { it.copy(announcesOut = it.announcesOut + 1) }
            }
        }
    }

    /** Sends a one-link control packet (ttl 1) to [peer], signed when it is an ANNOUNCE. */
    private suspend fun sendControl(
        peer: NodeId,
        payload: ByteArray,
        type: PacketType = PacketType.XFER_OFFER,
        now: Long = clock.nowMs(),
    ): Boolean {
        val raw = Packet(
            type = type, priority = Priority.CONTROL, ttl = 1, hop = 1,
            msgId = MsgId.random(), originId = selfId, timestampMs = now, payload = payload,
        )
        val packet = if (type == PacketType.ANNOUNCE) signed(raw) else raw
        return sendFrames(peer, packet.msgId, PacketCodec.encode(packet))
    }

    private suspend fun flushTo(peer: NodeId) {
        val skipped = HashSet<MsgId>() // too big for BLE and no bulk channel: leave queued, do not block others
        repeat(config.maxFlushRounds) {
            val batch = outbox.nextBatch(peer, config.batchSize).filter { it.msgId !in skipped }
            if (batch.isEmpty()) return
            for (entry in batch) {
                when (sendEntry(peer, entry)) {
                    SendOutcome.FAILED -> return // link trouble: retry on the next contact or signal
                    SendOutcome.SKIPPED -> skipped += entry.msgId
                    SendOutcome.SENT -> Unit
                }
            }
        }
    }

    private suspend fun sendEntry(peer: NodeId, entry: OutboxEntry): SendOutcome {
        val bytes = entry.packetBytes()
        var delivered = false
        if (bulk != null && bytes.size > config.bulkThresholdBytes) {
            delivered = tryBulk(peer, entry, bytes)
            if (!delivered) bump { it.copy(bulkFallbacks = it.bulkFallbacks + 1) }
        }
        if (!delivered) {
            if (!Chunker.fits(bytes.size, config.maxFrameSize)) return SendOutcome.SKIPPED
            if (!sendFrames(peer, entry.msgId, bytes)) return SendOutcome.FAILED
        }
        outbox.markSent(entry.msgId, entry.ttl, peer)
        bump { it.copy(packetsSent = it.packetsSent + 1) }
        // "Acknowledged" for beacon mode means a neighbour now holds the SOS and carries it onward.
        if (unackedSos.remove(entry.msgId)) refreshBeacon()
        return SendOutcome.SENT
    }

    /** Offer over BLE, wait for the answer, push over the bulk channel. Any failure returns false. */
    private suspend fun tryBulk(peer: NodeId, entry: OutboxEntry, bytes: ByteArray): Boolean {
        val channel = bulk ?: return false
        val key = OfferKey(peer, entry.msgId)
        val answer = CompletableDeferred<ByteArray?>()
        pendingOffers[key] = answer
        try {
            val offer = Xfer.Offer(entry.msgId, entry.ttl, bytes.size, sha256(bytes))
            if (!sendControl(peer, Xfer.encode(offer))) return false
            val info = withTimeoutOrNull(config.offerTimeoutMs) { answer.await() } ?: return false
            if (!channel.push(info, bytes)) return false
            bump { it.copy(bulkSent = it.bulkSent + 1) }
            return true
        } finally {
            pendingOffers.remove(key)
        }
    }

    private suspend fun sendFrames(peer: NodeId, msgId: MsgId, packetBytes: ByteArray): Boolean {
        for (frame in Chunker.split(msgId, packetBytes, config.maxFrameSize)) {
            when (transport.send(peer, frame)) {
                is SendResult.Success -> bump { it.copy(framesOut = it.framesOut + 1) }
                is SendResult.Failure -> {
                    bump { it.copy(sendFailures = it.sendFailures + 1) }
                    return false
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ beacon and digest

    private fun refreshBeacon() {
        transport.setBeacon(unackedSos.isNotEmpty())
        bump { it.copy(unackedSos = unackedSos.size) }
    }

    private suspend fun refreshDigest() {
        transport.setDigest(SyncDigest.of(dedup.snapshot(config.maxSummaryEntries)))
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

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

    private companion object {
        const val MAX_ACTIVE_RECEIVES = 2
        const val MAX_BULK_CHARS = 60_000
    }
}
