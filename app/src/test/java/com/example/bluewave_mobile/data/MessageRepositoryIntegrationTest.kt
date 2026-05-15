package com.example.bluewave_mobile.data

import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.DecryptionResult
import com.example.bluewave_mobile.crypto.KeyManager
import com.example.bluewave_mobile.network.IncomingPeerMessage
import com.example.bluewave_mobile.network.MessageTransport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * Integration tests that exercise [MessageRepositoryImpl] end-to-end
 * **without** spinning up a Room database or a Bluetooth radio.
 *
 * Strategy:
 *  * [MessageDao] is mocked with mockk so `insertMessage` becomes a
 *    captured slot we can assert against;
 *  * [CryptoManager] is built around a JCE-only `SecretKey` so we can
 *    drive a real AES-256-GCM round trip on the host JVM and verify
 *    that the row that ends up in Room decrypts back to the original
 *    plaintext;
 *  * [MessageTransport] is mocked so we can assert that
 *    [MessageRepositoryImpl.sendMessage] hands the plaintext bytes to
 *    the transport (without involving a real `BluetoothSocket`).
 *
 * What we verify:
 *  * a synthesized [MessageRepository.processIncomingMessage] call
 *    treats the raw payload as plaintext, encrypts it with the local
 *    AES key for at-rest storage and persists the row through
 *    `messageDao.insertMessage`;
 *  * the persisted entity carries the correct macAddress, senderName,
 *    isOutgoing flag, a fresh 12-byte IV and a ciphertext that
 *    round-trips through `cryptoManager.decrypt`;
 *  * a plaintext sendMessage call frames the bytes through the
 *    injected transport and persists the encrypted row locally;
 *  * the Android-16 `pauseNetworkOperations` / `resumeNetworkOperations`
 *    pair flips the per-peer guard and tears down the live transport
 *    session so we don't write into a stale socket.
 */
class MessageRepositoryIntegrationTest {

    private fun newRepository(
        transport: MessageTransport? = null,
    ): TestSetup {
        val key = KeyGenerator.getInstance("AES")
            .apply { init(256) }
            .generateKey()
        val keyManager = mockk<KeyManager>()
        every { keyManager.getOrCreateAesKey() } returns key
        val cryptoManager = CryptoManager(keyManager)

        val messageDao = mockk<MessageDao>()
        coEvery { messageDao.insertMessage(any()) } returns 1L

        val repository = MessageRepositoryImpl(messageDao, cryptoManager, transport)
        return TestSetup(repository, messageDao, cryptoManager)
    }

    private data class TestSetup(
        val repository: MessageRepositoryImpl,
        val messageDao: MessageDao,
        val cryptoManager: CryptoManager
    )

    @Test
    fun `processIncomingMessage encrypts plaintext and inserts a decryptable row`() = runTest {
        val setup = newRepository()
        val plaintext = "ping from peer".toByteArray(Charsets.UTF_8)

        setup.repository.processIncomingMessage(
            macAddress = "AA:BB:CC:DD:EE:FF",
            senderName = "Peer",
            rawData = plaintext,
        )

        val captured = slot<MessageEntity>()
        coVerify(exactly = 1) { setup.messageDao.insertMessage(capture(captured)) }
        val stored = captured.captured
        assertEquals("AA:BB:CC:DD:EE:FF", stored.macAddress)
        assertEquals("Peer", stored.senderName)
        assertFalse("incoming message must not be marked outgoing", stored.isOutgoing)
        assertEquals(12, stored.iv.size)

        // The persisted row must round-trip through CryptoManager — i.e.
        // the repository encrypted the plaintext with the local key.
        val result = setup.cryptoManager.decrypt(stored.iv, stored.encryptedPayload)
        assertTrue(result is DecryptionResult.Success)
        assertArrayEquals(plaintext, (result as DecryptionResult.Success).plaintext)
    }

    @Test
    fun `sendMessage encrypts plaintext, persists the row and pushes bytes to the transport`() = runTest {
        val transport = mockk<MessageTransport>(relaxed = true)
        coEvery { transport.send(any(), any()) } returns true
        every { transport.incoming } returns emptyFlow()

        val setup = newRepository(transport)
        val plaintext = "outgoing payload"

        setup.repository.sendMessage(
            macAddress = "11:22:33:44:55:66",
            plaintext = plaintext,
        )

        // 1. Local persistence: encrypted-at-rest row landed in the DB.
        val captured = slot<MessageEntity>()
        coVerify(exactly = 1) { setup.messageDao.insertMessage(capture(captured)) }
        val stored = captured.captured
        assertEquals("11:22:33:44:55:66", stored.macAddress)
        assertEquals("Me", stored.senderName)
        assertTrue("outgoing message must be marked outgoing", stored.isOutgoing)
        assertEquals(12, stored.iv.size)

        val result = setup.cryptoManager.decrypt(stored.iv, stored.encryptedPayload)
        assertTrue(result is DecryptionResult.Success)
        assertArrayEquals(
            plaintext.toByteArray(Charsets.UTF_8),
            (result as DecryptionResult.Success).plaintext
        )

        // 2. Wire transmission: plaintext UTF-8 bytes were handed to the
        //    transport unchanged — the framing and the radio I/O are
        //    the transport's responsibility.
        val transportPayload = slot<ByteArray>()
        coVerify(exactly = 1) {
            transport.send(macAddress = "11:22:33:44:55:66", payload = capture(transportPayload))
        }
        assertArrayEquals(plaintext.toByteArray(Charsets.UTF_8), transportPayload.captured)
    }

    @Test
    fun `sendMessage skips the transport when the peer is paused`() = runTest {
        val transport = mockk<MessageTransport>(relaxed = true)
        every { transport.incoming } returns emptyFlow()

        val setup = newRepository(transport)
        val mac = "DE:AD:BE:EF:00:01"
        setup.repository.pauseNetworkOperations(mac)

        setup.repository.sendMessage(macAddress = mac, plaintext = "while paused")

        // The local row still lands so the user sees their bubble in
        // the UI; only the radio write is suppressed.
        coVerify(exactly = 1) { setup.messageDao.insertMessage(any()) }
        coVerify(exactly = 0) { transport.send(any(), any()) }
    }

    @Test
    fun `pauseNetworkOperations marks peer as paused, disconnects the transport, and resume clears it`() = runTest {
        val transport = mockk<MessageTransport>(relaxed = true)
        every { transport.incoming } returns emptyFlow()

        val setup = newRepository(transport)
        val mac = "DE:AD:BE:EF:00:01"

        assertFalse(setup.repository.isPausedFor(mac))
        setup.repository.pauseNetworkOperations(mac)
        assertTrue(setup.repository.isPausedFor(mac))
        assertTrue(
            "case-insensitive MAC lookup must work",
            setup.repository.isPausedFor(mac.lowercase()),
        )

        coVerify(exactly = 1) { transport.disconnect(mac) }

        setup.repository.resumeNetworkOperations(mac)
        assertFalse(setup.repository.isPausedFor(mac))
    }

    @Test
    fun `IncomingPeerMessage equality and hashCode are content-based`() {
        // Sanity check that the data class's manual equals / hashCode
        // overrides (needed because of the ByteArray field) actually
        // compare by content rather than reference.
        val a = IncomingPeerMessage("AA:BB:CC:DD:EE:FF", "Peer", "hi".toByteArray())
        val b = IncomingPeerMessage("AA:BB:CC:DD:EE:FF", "Peer", "hi".toByteArray())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val differentPayload = IncomingPeerMessage("AA:BB:CC:DD:EE:FF", "Peer", "ho".toByteArray())
        assertFalse(a == differentPayload)
    }
}
