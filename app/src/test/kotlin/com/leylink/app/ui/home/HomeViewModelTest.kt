package com.leylink.app.ui.home

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

    private fun viewModel() = HomeViewModel(permissions, capabilities, mesh)

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
}
