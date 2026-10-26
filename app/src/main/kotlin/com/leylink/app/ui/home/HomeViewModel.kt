package com.leylink.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leylink.app.device.DeviceCapabilities
import com.leylink.app.mesh.MeshController
import com.leylink.app.permissions.PermissionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Unidirectional data flow: the UI sends events (functions), observes one immutable [HomeUiState]. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val permissions: PermissionChecker,
    private val capabilities: DeviceCapabilities,
    private val mesh: MeshController,
) : ViewModel() {

    // Permissions and Bluetooth state can change outside our control; the screen calls refresh() on resume.
    private val refreshTick = MutableStateFlow(0)

    val uiState: StateFlow<HomeUiState> =
        combine(mesh.running, refreshTick) { running, _ ->
            HomeUiState(
                hasBle = capabilities.hasBle(),
                bluetoothEnabled = capabilities.isBluetoothEnabled(),
                permissionsGranted = permissions.hasRequiredPermissions(),
                meshRunning = running,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = HomeUiState(),
        )

    fun refresh() {
        refreshTick.update { it + 1 }
    }

    fun onToggleMesh() {
        if (mesh.running.value) mesh.stop() else mesh.start()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
