package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.bluewave_mobile.MainDispatcherRule
import com.example.bluewave_mobile.data.BluetoothDeviceInfo
import com.example.bluewave_mobile.network.BluetoothDiscovery
import com.example.bluewave_mobile.ui.intent.DeviceListIntent
import com.example.bluewave_mobile.ui.state.DeviceListUiState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pure-JVM unit tests for [DeviceListViewModel].
 *
 * The reducer is intentionally a [kotlinx.coroutines.flow.Flow] so we
 * can drive it deterministically from a fake [BluetoothDiscovery] —
 * no Robolectric, no Android runtime, no emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val openScopes = mutableListOf<DeviceListViewModel>()

    private fun trackedVm(discovery: BluetoothDiscovery): DeviceListViewModel {
        val vm = DeviceListViewModel(discovery)
        openScopes += vm
        return vm
    }

    @After
    fun cancelViewModelScopes() {
        // Cancel viewModelScope and drain the test scheduler so leftover
        // `intents.collect` jobs cannot try to dispatch on Main after
        // `Dispatchers.resetMain` and crash the next test with a
        // `CompletionHandlerException`.
        openScopes.forEach { it.viewModelScope.cancel() }
        openScopes.clear()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `initial state is Idle`() = runTest {
        val discovery = mockk<BluetoothDiscovery>(relaxed = true)
        every { discovery.bondedDevices() } returns emptyList()
        every { discovery.discoverDevices() } returns flowOf()

        val vm = trackedVm(discovery)

        assertTrue(vm.uiState.value is DeviceListUiState.Idle)
    }

    @Test
    fun `StartScan emits Scanning then Loaded once discovery completes`() = runTest {
        val discovery = mockk<BluetoothDiscovery>()
        every { discovery.bondedDevices() } returns emptyList()
        every { discovery.discoverDevices() } returns flowOf(
            BluetoothDeviceInfo(name = "Alice", macAddress = "AA"),
            BluetoothDeviceInfo(name = "Bob", macAddress = "BB"),
        )

        val vm = trackedVm(discovery)
        vm.handleIntent(DeviceListIntent.StartScan)

        val loaded = vm.uiState.first { it is DeviceListUiState.Loaded } as DeviceListUiState.Loaded
        assertEquals(2, loaded.devices.size)
        assertEquals("AA", loaded.devices[0].macAddress)
        assertEquals("BB", loaded.devices[1].macAddress)
    }

    @Test
    fun `bonded devices are merged into the scan results`() = runTest {
        val discovery = mockk<BluetoothDiscovery>()
        every { discovery.bondedDevices() } returns listOf(
            BluetoothDeviceInfo(name = "Paired", macAddress = "PP", isPaired = true),
        )
        every { discovery.discoverDevices() } returns flowOf(
            BluetoothDeviceInfo(name = "Alice", macAddress = "AA"),
        )

        val vm = trackedVm(discovery)
        vm.handleIntent(DeviceListIntent.StartScan)

        val loaded = vm.uiState.first { it is DeviceListUiState.Loaded } as DeviceListUiState.Loaded
        assertEquals(2, loaded.devices.size)
        assertEquals(setOf("PP", "AA"), loaded.devices.map { it.macAddress }.toSet())
    }

    @Test
    fun `IllegalStateException from discovery surfaces as BluetoothDisabled`() = runTest {
        val discovery = mockk<BluetoothDiscovery>()
        every { discovery.bondedDevices() } returns emptyList()
        every { discovery.discoverDevices() } returns flow {
            throw IllegalStateException("BluetoothAdapter is unavailable on this device")
        }

        val vm = trackedVm(discovery)
        vm.handleIntent(DeviceListIntent.StartScan)

        val state = vm.uiState.first { it is DeviceListUiState.BluetoothDisabled }
        assertTrue(state is DeviceListUiState.BluetoothDisabled)
    }

    @Test
    fun `unexpected exception from discovery surfaces as Error`() = runTest {
        val discovery = mockk<BluetoothDiscovery>()
        every { discovery.bondedDevices() } returns emptyList()
        every { discovery.discoverDevices() } returns flow {
            throw RuntimeException("radio crashed")
        }

        val vm = trackedVm(discovery)
        vm.handleIntent(DeviceListIntent.StartScan)

        val state = vm.uiState.first { it is DeviceListUiState.Error } as DeviceListUiState.Error
        assertEquals("radio crashed", state.message)
    }

    @Test
    fun `PermissionsGranted resets state to Idle`() = runTest {
        val discovery = mockk<BluetoothDiscovery>(relaxed = true)
        every { discovery.bondedDevices() } returns emptyList()
        every { discovery.discoverDevices() } returns flowOf()

        val vm = trackedVm(discovery)
        vm.handleIntent(DeviceListIntent.PermissionsGranted)

        val state = vm.uiState.first { it is DeviceListUiState.Idle }
        assertTrue(state is DeviceListUiState.Idle)
    }
}
