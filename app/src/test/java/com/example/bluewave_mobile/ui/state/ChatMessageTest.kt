package com.example.bluewave_mobile.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defensive unit tests for the [ChatMessage] data class.
 *
 * `ChatMessage` is the LazyColumn item type for the chat history;
 * its `id` is the diff key. If the auto-generated `data class`
 * `equals` / `hashCode` ever drifted (e.g. someone manually overrode
 * one of the methods) the LazyColumn would stop reusing rows on
 * recomposition, producing a noticeable flicker every time a new
 * message arrives. These tests pin the contract so future refactors
 * cannot regress it silently.
 */
class ChatMessageTest {

    @Test
    fun `default isCorrupted is false`() {
        val message = ChatMessage(id = 1, text = "hello", isOutgoing = false, timestamp = 0L)
        assertFalse(message.isCorrupted)
    }

    @Test
    fun `equality is structural`() {
        val a = ChatMessage(id = 1, text = "hi", isOutgoing = true, timestamp = 100L)
        val b = ChatMessage(id = 1, text = "hi", isOutgoing = true, timestamp = 100L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `messages with different ids are not equal`() {
        val a = ChatMessage(id = 1, text = "hi", isOutgoing = true, timestamp = 100L)
        val b = ChatMessage(id = 2, text = "hi", isOutgoing = true, timestamp = 100L)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves unspecified fields`() {
        val original = ChatMessage(id = 1, text = "hi", isOutgoing = true, timestamp = 100L)
        val tampered = original.copy(isCorrupted = true, text = "")
        assertEquals(original.id, tampered.id)
        assertEquals(original.timestamp, tampered.timestamp)
        assertEquals(original.isOutgoing, tampered.isOutgoing)
        assertTrue(tampered.isCorrupted)
        assertEquals("", tampered.text)
    }
}
