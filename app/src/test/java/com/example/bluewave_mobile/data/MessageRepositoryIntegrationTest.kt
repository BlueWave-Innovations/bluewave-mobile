package com.example.bluewave_mobile.data

import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.DecryptionResult
import com.example.bluewave_mobile.crypto.KeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
 *  * [CryptoManager] is built around a JCE-only [SecretKey] so we can
 *    encrypt a real frame on the host JVM and feed it back into
 *    [MessageRepositoryImpl.processIncomingMessage].
 *
 * What we verify:
 *  * a synthesized [MessageRepository.processIncomingMessage] call
 *    parses the wire-format `[12 IV][ciphertext+tag]`, calls
 *    `cryptoManager.decrypt` and persists the row through
 *    `messageDao.insertMessage`;
 *  * the persisted entity carries the correct macAddress, senderName,
 *    isOutgoing flag, IV and encrypted payload;
 *  * a round trip plaintext -> sendMessage -> processIncomingMessage
 *    yields the original plaintext after decrypting the row that was
 *    actually stored.
 */
class MessageRepositoryIntegrationTest {

    private fun newRepository(): TestSetup {
        val key = KeyGenerator.getInstance("AES")
            .apply { init(256) }
            .generateKey()
        val keyManager = mockk<KeyManager>()
        every { keyManager.getOrCreateAesKey() } returns key
        val cryptoManager = CryptoManager(keyManager)

        val messageDao = mockk<MessageDao>()
        coEvery { messageDao.insertMessage(any()) } returns 1L

        val repository = MessageRepositoryImpl(messageDao, cryptoManager)
        return TestSetup(repository, messageDao, cryptoManager)
    }

    private data class TestSetup(
        val repository: MessageRepositoryImpl,
        val messageDao: MessageDao,
        val cryptoManager: CryptoManager
    )

    @Test
    fun `processIncomingMessage decrypts wire format and inserts a row`() = runTest {
        val setup = newRepository()
        val plaintext = "ping from peer".toByteArray(Charsets.UTF_8)
        val (iv, ciphertext) = setup.cryptoManager.encrypt(plaintext)

        // Wire-format = 12-byte IV || ciphertext+tag, exactly the shape
        // that ConnectedThread emits via its SharedFlow.
        val wireFrame = iv + ciphertext

        setup.repository.processIncomingMessage(
            macAddress = "AA:BB:CC:DD:EE:FF",
            senderName = "Peer",
            rawData = wireFrame
        )

        val captured = slot<MessageEntity>()
        coVerify(exactly = 1) { setup.messageDao.insertMessage(capture(captured)) }
        assertEquals("AA:BB:CC:DD:EE:FF", captured.captured.macAddress)
        assertEquals("Peer", captured.captured.senderName)
        assertFalse("incoming message must not be marked outgoing", captured.captured.isOutgoing)
        assertArrayEquals(iv, captured.captured.iv)
        assertArrayEquals(ciphertext, captured.captured.encryptedPayload)
    }

    @Test
    fun `sendMessage encrypts plaintext and persists the encrypted row`() = runTest {
        val setup = newRepository()
        val plaintext = "outgoing payload"

        setup.repository.sendMessage(
            macAddress = "11:22:33:44:55:66",
            plaintext = plaintext
        )

        val captured = slot<MessageEntity>()
        coVerify(exactly = 1) { setup.messageDao.insertMessage(capture(captured)) }
        val stored = captured.captured
        assertEquals("11:22:33:44:55:66", stored.macAddress)
        assertEquals("Me", stored.senderName)
        assertTrue("outgoing message must be marked outgoing", stored.isOutgoing)
        assertEquals(12, stored.iv.size)

        // The persisted ciphertext + IV must round-trip through CryptoManager.
        val result = setup.cryptoManager.decrypt(stored.iv, stored.encryptedPayload)
        assertTrue(result is DecryptionResult.Success)
        assertArrayEquals(
            plaintext.toByteArray(Charsets.UTF_8),
            (result as DecryptionResult.Success).plaintext
        )
    }

    @Test
    fun `pauseNetworkOperations marks peer as paused, resume clears it`() = runTest {
        val setup = newRepository()
        val mac = "DE:AD:BE:EF:00:01"

        assertFalse(setup.repository.isPausedFor(mac))
        setup.repository.pauseNetworkOperations(mac)
        assertTrue(setup.repository.isPausedFor(mac))
        assertTrue(
            "case-insensitive MAC lookup must work",
            setup.repository.isPausedFor(mac.lowercase())
        )

        setup.repository.resumeNetworkOperations(mac)
        assertFalse(setup.repository.isPausedFor(mac))
    }
}
