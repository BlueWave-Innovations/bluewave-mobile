package com.example.bluewave_mobile.ui.state

import com.example.bluewave_mobile.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [ChatUiState] sealed hierarchy.
 *
 * The MVI compiler-enforces exhaustiveness, but it cannot prove that
 * each branch has the correct *defaults*. In particular,
 * [ChatUiState.Success.isPeerPaused] must default to `false` —
 * otherwise the bond-loss banner would appear on the very first
 * emission of the chat history before any Android-16 broadcast had
 * fired. A test pinning that default to false is cheaper than a flaky
 * UI screenshot regression.
 */
class ChatUiStateTest {

    @Test
    fun `Success defaults to not paused`() {
        val state = ChatUiState.Success(messages = emptyList())
        assertFalse(state.isPeerPaused)
    }

    @Test
    fun `Success preserves message list ordering`() {
        val one = MessageEntity(
            id = 1L,
            macAddress = "AA:BB",
            encryptedPayload = ByteArray(0),
            iv = ByteArray(12),
            timestamp = 1L,
            isOutgoing = true,
        )
        val two = MessageEntity(
            id = 2L,
            macAddress = "AA:BB",
            encryptedPayload = ByteArray(0),
            iv = ByteArray(12),
            timestamp = 2L,
            isOutgoing = false,
        )
        val state = ChatUiState.Success(messages = listOf(one, two))
        assertEquals(listOf(one, two), state.messages)
    }

    @Test
    fun `Loading and Error are distinct singletons`() {
        val loading: ChatUiState = ChatUiState.Loading
        val error: ChatUiState = ChatUiState.Error("boom")
        assertTrue(loading is ChatUiState.Loading)
        assertTrue(error is ChatUiState.Error)
        assertEquals("boom", (error as ChatUiState.Error).message)
    }
}
