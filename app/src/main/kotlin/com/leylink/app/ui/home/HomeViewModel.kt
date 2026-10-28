package com.leylink.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leylink.app.device.DeviceCapabilities
import com.leylink.app.mesh.MeshController
import com.leylink.app.mesh.MessagingController
import com.leylink.app.permissions.PermissionChecker
import com.leylink.core.model.PacketType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Unidirectional data flow: the UI sends events (functions), observes one immutable [HomeUiState]. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val permissions: PermissionChecker,
    private val capabilities: DeviceCapabilities,
    private val mesh: MeshController,
    private val messaging: MessagingController,
) : ViewModel() {

    /** UI-only inputs: a refresh counter, the text being typed, and the last send error. */
    private data class Inputs(val refreshTick: Int = 0, val draft: String = "", val error: String? = null)

    private val inputs = MutableStateFlow(Inputs())

    val uiState: StateFlow<HomeUiState> =
        combine(
            mesh.running, messaging.peerCount, messaging.messages, messaging.stats, inputs,
        ) { running, peers, messages, stats, input ->
            HomeUiState(
                hasBle = capabilities.hasBle(),
                bluetoothEnabled = capabilities.isBluetoothEnabled(),
                permissionsGranted = permissions.hasRequiredPermissions(),
                meshRunning = running,
                peerCount = peers,
                messages = messages,
                stats = stats,
                draft = input.draft,
                error = input.error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = HomeUiState(),
        )

    /** Permissions and Bluetooth state can change outside our control; the screen calls this on resume. */
    fun refresh() {
        inputs.update { it.copy(refreshTick = it.refreshTick + 1) }
    }

    fun onToggleMesh() {
        if (mesh.running.value) mesh.stop() else mesh.start()
    }

    fun onDraftChange(text: String) {
        inputs.update { it.copy(draft = text, error = null) }
    }

    fun onSend(type: PacketType) {
        val text = inputs.value.draft.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            try {
                messaging.send(type, text)
                inputs.update { it.copy(draft = "", error = null) }
            } catch (e: IllegalArgumentException) {
                inputs.update { it.copy(error = e.message) } // e.g. an SOS over 280 characters
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
