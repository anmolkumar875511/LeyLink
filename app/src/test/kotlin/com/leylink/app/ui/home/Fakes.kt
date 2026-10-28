package com.leylink.app.ui.home

import com.leylink.app.device.DeviceCapabilities
import com.leylink.app.mesh.MeshController
import com.leylink.app.mesh.MessagingController
import com.leylink.app.permissions.PermissionChecker
import com.leylink.core.model.NodeId
import com.leylink.core.model.PacketType
import com.leylink.core.model.StoredMessage
import com.leylink.core.router.RouterStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakePermissionChecker(var granted: Boolean = false) : PermissionChecker {
    override fun hasRequiredPermissions(): Boolean = granted
}

class FakeDeviceCapabilities(
    var ble: Boolean = true,
    var bluetoothOn: Boolean = true,
) : DeviceCapabilities {
    override fun hasBle(): Boolean = ble

    override fun isBluetoothEnabled(): Boolean = bluetoothOn
}

class FakeMeshController : MeshController {
    private val mutableRunning = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    var startCalls = 0
        private set
    var stopCalls = 0
        private set

    override fun start() {
        startCalls++
        mutableRunning.value = true
    }

    override fun stop() {
        stopCalls++
        mutableRunning.value = false
    }
}

class FakeMessagingController : MessagingController {
    override val selfId: NodeId = NodeId(ByteArray(8) { 1 })
    val history = MutableStateFlow<List<StoredMessage>>(emptyList())
    val peers = MutableStateFlow(0)

    override val messages: Flow<List<StoredMessage>> = history
    override val peerCount: StateFlow<Int> = peers
    override val stats: StateFlow<RouterStats> = MutableStateFlow(RouterStats())

    /** What the UI asked us to send, in order. */
    val sent = mutableListOf<Pair<PacketType, String>>()

    /** If set, the next send fails like the real router does for an invalid message. */
    var failWith: String? = null

    override suspend fun send(type: PacketType, text: String) {
        failWith?.let { throw IllegalArgumentException(it) }
        sent += type to text
    }
}
