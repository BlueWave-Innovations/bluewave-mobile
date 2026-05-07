package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.bluewave_mobile.MainDispatcherRule
import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.DecryptionResult
import com.example.bluewave_mobile.data.MessageEntity
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.ui.intent.ChatIntent
import com.example.bluewave_mobile.ui.state.ChatUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pure-JVM unit tests for [ChatViewModel].
 *
 * The ViewModel exercises a fairly intricate flow: it observes Room
 * messages, decrypts them off-main, layers an optimistic outbound
 * bubble on top, and exposes the result as both [ChatViewModel.uiState]
 * and [ChatViewModel.messages]. These tests pin the contractual
 * invariants users rely on:
 *
 *  * `sendMessage` actually delegates to the repository;
 *  * a successful decryption produces the plaintext on the UI;
 *  * a tampered decryption produces an `isCorrupted = true` bubble;
 *  * an empty IV (raw, unencrypted frame) is rendered as corrupted.
 *
 * Mocks are built with mockk so we don't have to spin up Room or a
 * real `CryptoManager` (which both require Android).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val openScopes = mutableListOf<ChatViewModel>()

    private fun trackedVm(
        repository: MessageRepository,
        crypto: CryptoManager,
        deviceMac: String = "AA:BB",
    ): ChatViewModel {
        val vm = ChatViewModel(deviceMac = deviceMac, repository = repository, crypto = crypto)
        openScopes += vm
        return vm
    }

    @After
    fun cancelViewModelScopes() {
        // The ViewModel never auto-cancels its `viewModelScope` outside
        // of an Activity lifecycle, so leftover collectors would happily
        // try to dispatch on the now-reset Main and crash subsequent
        // tests with `CompletionHandlerException`. We cancel them here
        // *and* drain the test scheduler so cancellation completes
        // before the rule resets `Dispatchers.Main`.
        openScopes.forEach { it.viewModelScope.cancel() }
        openScopes.clear()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `sendMessage delegates to repository`() = runTest {
        val repo = mockk<MessageRepository>(relaxed = true)
        every { repo.getMessagesByDevice(any()) } returns MutableStateFlow(emptyList())
        coEvery { repo.sendMessage(any(), any()) } returns Unit
        val crypto = mockk<CryptoManager>(relaxed = true)

        val vm = trackedVm(repo, crypto)

        vm.handleIntent(ChatIntent.SendMessage("hi"))

        coVerify(exactly = 1) { repo.sendMessage("AA:BB", "hi") }
    }

    @Test
    fun `blank plaintext is dropped without hitting the repository`() = runTest {
        val repo = mockk<MessageRepository>(relaxed = true)
        every { repo.getMessagesByDevice(any()) } returns MutableStateFlow(emptyList())
        val crypto = mockk<CryptoManager>(relaxed = true)

        val vm = trackedVm(repo, crypto)

        vm.handleIntent(ChatIntent.SendMessage("   "))

        coVerify(exactly = 0) { repo.sendMessage(any(), any()) }
    }

    @Test
    fun `clearHistory delegates to repository`() = runTest {
        val repo = mockk<MessageRepository>(relaxed = true)
        every { repo.getMessagesByDevice(any()) } returns MutableStateFlow(emptyList())
        val crypto = mockk<CryptoManager>(relaxed = true)

        val vm = trackedVm(repo, crypto)

        vm.handleIntent(ChatIntent.ClearHistory)

        coVerify(exactly = 1) { repo.deleteMessagesByDevice("AA:BB") }
    }

    @Test
    fun `decrypted entity surfaces as plaintext message`() = runTest {
        val repo = mockk<MessageRepository>(relaxed = true)
        val entity = MessageEntity(
            id = 1L,
            macAddress = "AA",
            encryptedPayload = byteArrayOf(1, 2, 3),
            iv = ByteArray(12) { 0 },
            timestamp = 100L,
            isOutgoing = false,
        )
        every { repo.getMessagesByDevice("AA") } returns MutableStateFlow(listOf(entity))
        val crypto = mockk<CryptoManager>()
        every { crypto.decrypt(any(), any()) } returns
            DecryptionResult.Success("hello".toByteArray(Charsets.UTF_8))

        val vm = trackedVm(repo, crypto, deviceMac = "AA")

        // Drain the cold flow to its first non-empty emission.
        val messages = vm.messages.first { it.isNotEmpty() }
        assertEquals(1, messages.size)
        assertEquals("hello", messages[0].text)
        assertTrue(!messages[0].isCorrupted)
    }

    @Test
    fun `tampered payload surfaces as corrupted message`() = runTest {
        val repo = mockk<MessageRepository>(relaxed = true)
        val entity = MessageEntity(
            id = 2L,
            macAddress = "AA",
            encryptedPayload = byteArrayOf(9, 9),
            iv = ByteArray(12) { 1 },
            timestamp = 200L,
            isOutgoing = false,
        )
        every { repo.getMessagesByDevice("AA") } returns MutableStateFlow(listOf(entity))
        val crypto = mockk<CryptoManager>()
        every { crypto.decrypt(any(), any()) } returns
            DecryptionResult.Tampered(IllegalStateException("bad tag"))

        val vm = trackedVm(repo, crypto, deviceMac = "AA")

        val messages = vm.messages.first { it.isNotEmpty() }
        assertEquals(1, messages.size)
        assertTrue("tampered payload must produce corrupted bubble", messages[0].isCorrupted)
        assertEquals("", messages[0].text)
    }

    @Test
    fun `empty iv marks the row as corrupted without calling decrypt`() = runTest {
        val repo = mockk<MessageRepository>(relaxed = true)
        val entity = MessageEntity(
            id = 3L,
            macAddress = "AA",
            encryptedPayload = byteArrayOf(0, 0),
            iv = ByteArray(0),
            timestamp = 300L,
            isOutgoing = false,
        )
        every { repo.getMessagesByDevice("AA") } returns MutableStateFlow(listOf(entity))
        val crypto = mockk<CryptoManager>(relaxed = true)

        val vm = trackedVm(repo, crypto, deviceMac = "AA")

        val messages = vm.messages.first { it.isNotEmpty() }
        assertTrue(messages[0].isCorrupted)
    }

    @Test
    fun `uiState surfaces decryption error as Error state`() = runTest {
        val repo = mockk<MessageRepository>()
        every { repo.getMessagesByDevice(any()) } returns flow {
            throw RuntimeException("db corrupted")
        }
        val crypto = mockk<CryptoManager>(relaxed = true)

        val vm = trackedVm(repo, crypto, deviceMac = "AA")

        val state = vm.uiState.first { it is ChatUiState.Error }
        assertEquals("db corrupted", (state as ChatUiState.Error).message)
    }
}
