package com.leylink.app.ui.home

import com.leylink.app.device.DeviceCapabilities
import com.leylink.app.mesh.MeshController
import com.leylink.app.permissions.PermissionChecker
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
