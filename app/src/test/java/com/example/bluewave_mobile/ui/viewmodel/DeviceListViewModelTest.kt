package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.bluewave_mobile.MainDispatcherRule
import com.example.bluewave_mobile.data.BluetoothDeviceInfo
import com.example.bluewave_mobile.data.ConversationSummary
import com.example.bluewave_mobile.data.MessageEntity
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.network.ApkSender
import com.example.bluewave_mobile.network.BlueWaveSdpProber
import com.example.bluewave_mobile.network.BluetoothDiscovery
import com.example.bluewave_mobile.ui.intent.DeviceListIntent
import com.example.bluewave_mobile.ui.model.ContactRow
import com.example.bluewave_mobile.ui.state.DeviceListUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Two layers are exercised:
 *
 *  * The reducer is intentionally a [kotlinx.coroutines.flow.Flow] so
 *    we can drive it deterministically from a fake
 *    [BluetoothDiscovery] / [MessageRepository] / [BlueWaveSdpProber]
 *    triplet — no Robolectric, no Android runtime, no emulator.
 *  * The pure projection [DeviceListViewModel.buildRows] is exercised
 *    in isolation through the package-internal entry point so the
 *    section ordering invariants are pinned without spinning the full
 *    state machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val openScopes = mutableListOf<DeviceListViewModel>()

    private fun trackedVm(
        discovery: BluetoothDiscovery,
        repository: MessageRepository = noopRepository(),
        sdp: BlueWaveSdpProber = noopProber(),
        apk: ApkSender = noopApk(),
    ): DeviceListViewModel {
        val vm = DeviceListViewModel(
            bluetoothDiscovery = discovery,
            messageRepository = repository,
            sdpProber = sdp,
            apkSender = apk,
        )
        openScopes += vm
        return vm
    }

    private fun noopRepository(): MessageRepository = mockk(relaxed = true) {
        every { observeAllConversations() } returns flowOf(emptyList())
        every { observeAllPeerProfiles() } returns flowOf(emptyList())
    }

    private fun noopProber(): BlueWaveSdpProber = mockk(relaxed = true) {
        every { appPresence } returns MutableStateFlow(emptyMap())
    }

    private fun noopApk(): ApkSender = mockk(relaxed = true)

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
    fun `StartScan emits Scanning rows for discovered peers`() = runTest {
        val discovery = mockk<BluetoothDiscovery>()
        every { discovery.bondedDevices() } returns emptyList()
        every { discovery.discoverDevices() } returns flowOf(
            BluetoothDeviceInfo(name = "Alice", macAddress = "AA"),
            BluetoothDeviceInfo(name = "Bob", macAddress = "BB"),
        )

        val vm = trackedVm(discovery)
        vm.handleIntent(DeviceListIntent.StartScan)

        val scanning = vm.uiState.first { it is DeviceListUiState.Scanning && it.rows.isNotEmpty() }
            as DeviceListUiState.Scanning
        val macs = scanning.rows.mapTo(HashSet()) { it.macAddress }
        assertTrue("Alice missing", "AA" in macs)
        assertTrue("Bob missing", "BB" in macs)
    }

    @Test
    fun `bonded devices appear in the scan rows immediately`() = runTest {
        val discovery = mockk<BluetoothDiscovery>()
        every { discovery.bondedDevices() } returns listOf(
            BluetoothDeviceInfo(name = "Paired", macAddress = "PP", isPaired = true),
        )
        every { discovery.discoverDevices() } returns flowOf()

        val vm = trackedVm(discovery)
        vm.handleIntent(DeviceListIntent.StartScan)

        val scanning = vm.uiState.first { it is DeviceListUiState.Scanning && it.rows.isNotEmpty() }
            as DeviceListUiState.Scanning
        assertTrue(scanning.rows.any { it.macAddress == "PP" })
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

    @Test
    fun `DeviceSelected fires markPeerAsRead on the repository`() = runTest {
        val discovery = mockk<BluetoothDiscovery>(relaxed = true)
        every { discovery.bondedDevices() } returns emptyList()
        every { discovery.discoverDevices() } returns flowOf()
        val repository: MessageRepository = mockk(relaxed = true) {
            every { observeAllConversations() } returns flowOf(emptyList())
            coEvery { markPeerAsRead(any()) } returns Unit
        }

        val vm = trackedVm(discovery, repository = repository)
        vm.handleIntent(DeviceListIntent.DeviceSelected("AA:BB:CC"))

        // Drain pending coroutines so the side-effect coroutine launched
        // by the reducer has a chance to run.
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        coVerify(atLeast = 1) { repository.markPeerAsRead("AA:BB:CC") }
    }

    @Test
    fun `buildRows orders chats first, then candidates, then install suggestions`() = runTest {
        val discovery = mockk<BluetoothDiscovery>(relaxed = true)
        every { discovery.bondedDevices() } returns emptyList()
        every { discovery.discoverDevices() } returns flowOf()
        val vm = trackedVm(discovery)

        val rows = vm.buildRows(
            peers = mapOf(
                "AA" to BluetoothDeviceInfo(name = "Alice", macAddress = "AA"),
                "BB" to BluetoothDeviceInfo(name = "Bob", macAddress = "BB"),
                "CC" to BluetoothDeviceInfo(name = "Carla", macAddress = "CC"),
            ),
            conversations = listOf(
                ConversationSummary(
                    macAddress = "DD",
                    lastMessage = MessageEntity(
                        id = 1L,
                        macAddress = "DD",
                        encryptedPayload = ByteArray(0),
                        iv = ByteArray(0),
                        timestamp = 100L,
                        isOutgoing = true,
                    ),
                    unreadCount = 0,
                ),
            ),
            presence = mapOf(
                "AA" to true,
                "BB" to false,
                // CC is unknown — under the strict SDP filter that
                // mirrors `BlueWaveSdpProber`'s 2 s timeout, every
                // unbonded peer without a positive SDP answer
                // lands in "No app yet". A late `ACTION_UUID`
                // would flip the cell to a candidate on the next
                // emission; the projection itself is pure.
            ),
        )

        // 1) Existing chat for DD, then 2) candidate (AA only),
        // then 3) install suggestions for BB and CC.
        assertEquals(4, rows.size)
        assertTrue(rows[0] is ContactRow.ExistingChat)
        assertEquals("DD", rows[0].macAddress)
        assertTrue(rows[1] is ContactRow.StartChatCandidate)
        assertEquals("AA", rows[1].macAddress)
        val tail = rows.subList(2, 4)
        assertTrue(tail.all { it is ContactRow.InstallSuggestion })
        assertEquals(setOf("BB", "CC"), tail.map { it.macAddress }.toSet())
    }
}
