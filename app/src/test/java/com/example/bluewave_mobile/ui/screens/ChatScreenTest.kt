package com.example.bluewave_mobile.ui.screens

import com.example.bluewave_mobile.ui.state.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-Kotlin sanity checks for the date-separator splitter that backs
 * the chat screen's `LazyColumn`. The helper is the only piece of
 * presentation logic in [ChatScreen] that runs without a Compose
 * runtime, so it is also the only piece that can be exercised in
 * `app:testDebug` without spinning up Robolectric.
 */
class ChatScreenTest {
    @Test
    fun `empty messages produces empty list`() {
        val items = buildChatListItems(messages = emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun `single day groups all messages under one header`() {
        val day = epochAtNoon(year = 2026, month = Calendar.MAY, dayOfMonth = 7)
        val messages = listOf(
            ChatMessage(id = 1L, text = "first", isOutgoing = true, timestamp = day),
            ChatMessage(id = 2L, text = "second", isOutgoing = false, timestamp = day + 60_000L),
            ChatMessage(id = 3L, text = "third", isOutgoing = true, timestamp = day + 120_000L),
        )
        val items = buildChatListItems(
            messages = messages,
            now = day + 5 * 60 * 60 * 1000L,
            locale = Locale.US,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
        )
        // Output is reverse-ordered so the LazyColumn (reverseLayout)
        // can consume it directly: newest message first, header last.
        assertEquals(4, items.size)
        assertTrue(items[0] is ChatListItem.Bubble)
        assertTrue(items[1] is ChatListItem.Bubble)
        assertTrue(items[2] is ChatListItem.Bubble)
        val header = items[3] as ChatListItem.DateHeader
        assertEquals("Today", header.label)
    }

    @Test
    fun `multiple days insert separators between days`() {
        val today = epochAtNoon(year = 2026, month = Calendar.MAY, dayOfMonth = 7)
        val yesterday = today - 24L * 60 * 60 * 1000L
        val twoDaysAgo = today - 2L * 24 * 60 * 60 * 1000L

        val messages = listOf(
            ChatMessage(id = 10L, text = "old", isOutgoing = true, timestamp = twoDaysAgo),
            ChatMessage(id = 11L, text = "yesterday-1", isOutgoing = false, timestamp = yesterday),
            ChatMessage(id = 12L, text = "yesterday-2", isOutgoing = true, timestamp = yesterday + 60_000L),
            ChatMessage(id = 13L, text = "now", isOutgoing = false, timestamp = today),
        )
        val items = buildChatListItems(
            messages = messages,
            now = today + 60_000L,
            locale = Locale.US,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
        )

        // Expected reversed render order:
        //   bubble (id=13, today)
        //   header "Today"
        //   bubble (id=12, yesterday)
        //   bubble (id=11, yesterday)
        //   header "Yesterday"
        //   bubble (id=10, twoDaysAgo)
        //   header "May 5, 2026"
        assertEquals(7, items.size)
        assertEquals(13L, (items[0] as ChatListItem.Bubble).message.id)
        assertEquals("Today", (items[1] as ChatListItem.DateHeader).label)
        assertEquals(12L, (items[2] as ChatListItem.Bubble).message.id)
        assertEquals(11L, (items[3] as ChatListItem.Bubble).message.id)
        assertEquals("Yesterday", (items[4] as ChatListItem.DateHeader).label)
        assertEquals(10L, (items[5] as ChatListItem.Bubble).message.id)
        assertTrue(items[6] is ChatListItem.DateHeader)
    }

    @Test
    fun `unsorted messages are sorted ascending before grouping`() {
        val today = epochAtNoon(year = 2026, month = Calendar.MAY, dayOfMonth = 7)
        val messages = listOf(
            ChatMessage(id = 2L, text = "second", isOutgoing = true, timestamp = today + 60_000L),
            ChatMessage(id = 1L, text = "first", isOutgoing = false, timestamp = today),
            ChatMessage(id = 3L, text = "third", isOutgoing = true, timestamp = today + 120_000L),
        )
        val items = buildChatListItems(
            messages = messages,
            now = today + 60 * 60 * 1000L,
            locale = Locale.US,
            todayLabel = "Today",
            yesterdayLabel = "Yesterday",
        )
        assertEquals(4, items.size)
        // Newest-first thanks to the reverse-pass at the end of
        // buildChatListItems().
        assertEquals(3L, (items[0] as ChatListItem.Bubble).message.id)
        assertEquals(2L, (items[1] as ChatListItem.Bubble).message.id)
        assertEquals(1L, (items[2] as ChatListItem.Bubble).message.id)
        assertEquals("Today", (items[3] as ChatListItem.DateHeader).label)
    }

    @Test
    fun `null today and yesterday labels fall back to absolute date`() {
        val today = epochAtNoon(year = 2026, month = Calendar.MAY, dayOfMonth = 7)
        val items = buildChatListItems(
            messages = listOf(
                ChatMessage(id = 1L, text = "x", isOutgoing = true, timestamp = today),
            ),
            now = today,
            locale = Locale.US,
        )
        val header = items.last() as ChatListItem.DateHeader
        assertEquals("May 7, 2026", header.label)
    }

    private fun epochAtNoon(year: Int, month: Int, dayOfMonth: Int): Long {
        // Pin every test instant to UTC so identical timestamps land
        // in the same calendar day on every CI worker, regardless of
        // the worker's default time zone.
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(year, month, dayOfMonth, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
