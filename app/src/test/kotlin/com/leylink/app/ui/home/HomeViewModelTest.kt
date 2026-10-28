package com.leylink.app.ui.home

import com.leylink.core.model.PacketType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val permissions = FakePermissionChecker()
    private val capabilities = FakeDeviceCapabilities()
    private val mesh = FakeMeshController()
    private val messaging = FakeMessagingController()

    private fun viewModel() = HomeViewModel(permissions, capabilities, mesh, messaging)

    /** stateIn(WhileSubscribed) only computes while someone collects, so keep one collector alive. */
    private fun TestScope.keepActive(vm: HomeViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `cannot start mesh while permissions are missing`() = runTest {
        val vm = viewModel()
        keepActive(vm)
        assertFalse(vm.uiState.value.permissionsGranted)
        assertFalse(vm.uiState.value.canStartMesh)
    }

    @Test
    fun `can start mesh when permissions are granted and BLE exists`() = runTest {
        permissions.granted = true
        val vm = viewModel()
        keepActive(vm)
        assertTrue(vm.uiState.value.canStartMesh)
    }

    @Test
    fun `cannot start mesh on a phone without BLE`() = runTest {
        permissions.granted = true
        capabilities.ble = false
        val vm = viewModel()
        keepActive(vm)
        assertFalse(vm.uiState.value.canStartMesh)
    }

    @Test
    fun `refresh picks up permissions granted later`() = runTest {
        val vm = viewModel()
        keepActive(vm)
        assertFalse(vm.uiState.value.canStartMesh)

        permissions.granted = true
        vm.refresh()

        assertTrue(vm.uiState.value.canStartMesh)
    }

    @Test
    fun `toggle starts the mesh when stopped and stops it when running`() = runTest {
        permissions.granted = true
        val vm = viewModel()
        keepActive(vm)

        vm.onToggleMesh()
        assertEquals(1, mesh.startCalls)
        assertTrue(vm.uiState.value.meshRunning)

        vm.onToggleMesh()
        assertEquals(1, mesh.stopCalls)
        assertFalse(vm.uiState.value.meshRunning)
    }
    @Test
    fun `sending a chat passes the trimmed draft to the mesh and clears it`() = runTest {
        val vm = viewModel()
        keepActive(vm)

        vm.onDraftChange("  hello  ")
        vm.onSend(PacketType.CHAT)

        assertEquals(listOf(PacketType.CHAT to "hello"), messaging.sent)
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun `an invalid message keeps the draft and shows the error`() = runTest {
        val vm = viewModel()
        keepActive(vm)
        messaging.failWith = "SOS is limited to 280 characters"

        vm.onDraftChange("too long")
        vm.onSend(PacketType.SOS)

        assertEquals("too long", vm.uiState.value.draft)
        assertEquals("SOS is limited to 280 characters", vm.uiState.value.error)

        vm.onDraftChange("shorter") // typing again clears the error
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun `blank drafts are never sent`() = runTest {
        val vm = viewModel()
        keepActive(vm)

        vm.onDraftChange("   ")
        vm.onSend(PacketType.CHAT)

        assertTrue(messaging.sent.isEmpty())
        assertFalse(vm.uiState.value.canSend)
    }

    @Test
    fun `peer count follows the transport`() = runTest {
        val vm = viewModel()
        keepActive(vm)

        messaging.peers.value = 2

        assertEquals(2, vm.uiState.value.peerCount)
    }
}
