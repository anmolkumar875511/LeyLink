package com.leylink.app.mesh

import android.content.Context
import android.os.Build
import com.leylink.app.security.KeystoreEd25519Signer
import com.leylink.app.security.PrefsKeyDirectory
import com.leylink.core.model.NodeId
import com.leylink.core.model.PacketType
import com.leylink.core.model.StoredMessage
import com.leylink.core.port.BulkChannel
import com.leylink.core.router.DedupCache
import com.leylink.core.router.MeshRouter
import com.leylink.core.router.RouterConfig
import com.leylink.core.router.RouterStats
import com.leylink.core.time.Clock
import com.leylink.data.storage.LeyLinkDatabase
import com.leylink.data.storage.RoomMessageStore
import com.leylink.data.storage.RoomOutboxStore
import com.leylink.data.storage.RoomSeenStore
import com.leylink.data.storage.RoomTransactionRunner
import com.leylink.transport.ble.BleTransport
import com.leylink.transport.wifidirect.WifiDirectBulkChannel
import com.leylink.transport.wifidirect.WifiDirectSupport
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The composition of the whole mesh stack: Room stores + BLE transport + MeshRouter.
 * One instance per process. MeshService starts and stops it; the UI talks to it through
 * [MessagingController], so queued messages can be written even while the radio is off.
 */
@Singleton
class MeshEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    nodeIds: NodeIdProvider,
) : MessagingController {

    /** Lives as long as the process: Bluetooth callbacks and connection attempts run here. */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val selfId: NodeId = nodeIds.nodeId

    private val database by lazy { LeyLinkDatabase.create(context) }
    private val messageStore by lazy { RoomMessageStore(database) }
    private val transport by lazy { BleTransport(context, selfId, engineScope) }

    /** Ed25519 identity; the private seed is wrapped by an Android Keystore key. */
    private val signer by lazy { KeystoreEd25519Signer(context) }
    private val keyDirectory by lazy { PrefsKeyDirectory(context) }

    /** Wi-Fi Direct escalation exists only on phones that support it; everything works without it. */
    private val bulk: BulkChannel? by lazy {
        if (WifiDirectSupport.isSupported(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiDirectBulkChannel(context)
        } else {
            null
        }
    }

    private val router by lazy {
        MeshRouter(
            selfId = selfId,
            transport = transport,
            outbox = RoomOutboxStore(database),
            dedup = DedupCache(RoomSeenStore(database)),
            clock = Clock.SYSTEM,
            config = RouterConfig(
                originTtl = ORIGIN_TTL,
                syncEnabled = true,
                requireSignatures = true,
                jitterMs = { Random.nextLong(0, MAX_JITTER_MS) },
            ),
            transactions = RoomTransactionRunner(database),
            messages = messageStore,
            signer = signer,
            keys = keyDirectory,
            bulk = bulk,
        )
    }

    override val messages: Flow<List<StoredMessage>> get() = messageStore.observe()

    override val stats: StateFlow<RouterStats> get() = router.stats

    override val peerCount: StateFlow<Int> by lazy {
        transport.connectedPeers.map { it.size }.stateIn(engineScope, SharingStarted.Eagerly, 0)
    }

    private val lifecycle = Mutex()
    private var running = false

    /** Starts routing and the radio. Safe to call twice. [scope] decides how long routing lives. */
    suspend fun start(scope: CoroutineScope) {
        lifecycle.withLock {
            if (running) return
            router.start(scope)
            running = true
        }
    }

    suspend fun stop() {
        lifecycle.withLock {
            if (!running) return
            router.stop()
            running = false
        }
    }

    override suspend fun send(type: PacketType, text: String) {
        router.send(type, text)
    }

    private companion object {
        const val ORIGIN_TTL = 5
        const val MAX_JITTER_MS = 150L
    }
}
