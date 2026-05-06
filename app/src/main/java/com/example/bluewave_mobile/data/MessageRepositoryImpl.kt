package com.example.bluewave_mobile.data

import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.DecryptionResult
import kotlinx.coroutines.flow.Flow

/**
 * Concrete implementation of [MessageRepository] serving as the Single Source of Truth.
 *
 * This class coordinates data between the Room database (via [MessageDao])
 * and the Bluetooth network layer. All data flows through the database first:
 * incoming messages are persisted before the UI sees them, ensuring consistency.
 *
 * Dependencies are injected via constructor for testability — no direct
 * instantiation of DAO or database inside this class.
 *
 * @property messageDao The Room DAO for message CRUD operations.
 * @property cryptoManager AES-256-GCM facade used to encrypt outgoing
 *                          messages and decrypt incoming ones.
 */
class MessageRepositoryImpl(
    private val messageDao: MessageDao,
    private val cryptoManager: CryptoManager = CryptoManager()
) : MessageRepository {

    override fun getMessagesByDevice(macAddress: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesByDevice(macAddress)
    }

    override fun getLatestMessagePerDevice(): Flow<List<MessageEntity>> {
        return messageDao.getLatestMessagePerDevice()
    }

    override suspend fun insertMessage(message: MessageEntity): Long {
        return messageDao.insertMessage(message)
    }

    override suspend fun processIncomingMessage(
        macAddress: String,
        senderName: String,
        rawData: ByteArray
    ) {
        // Wire-format produced by sendMessage / step 19:
        //   [12 bytes IV][N bytes ciphertext+GCM-tag]
        // Anything shorter than 12 bytes cannot be a valid encrypted frame,
        // so we persist it as a corrupted record with empty IV — the UI
        // (step 26) renders this with the errorContainer treatment.
        if (rawData.size <= IV_LENGTH_BYTES) {
            messageDao.insertMessage(
                MessageEntity(
                    macAddress = macAddress,
                    encryptedPayload = rawData,
                    iv = ByteArray(0),
                    isOutgoing = false,
                    senderName = senderName
                )
            )
            return
        }

        val iv = rawData.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = rawData.copyOfRange(IV_LENGTH_BYTES, rawData.size)

        when (cryptoManager.decrypt(iv, ciphertext)) {
            is DecryptionResult.Success,
            is DecryptionResult.Tampered -> {
                // Both branches persist the same on-disk shape; the UI
                // distinguishes a corrupted message by attempting to
                // decrypt at render time (step 26 will move that logic
                // here when it lands).
                messageDao.insertMessage(
                    MessageEntity(
                        macAddress = macAddress,
                        encryptedPayload = ciphertext,
                        iv = iv,
                        isOutgoing = false,
                        senderName = senderName
                    )
                )
            }
        }
    }

    override suspend fun sendMessage(macAddress: String, plaintext: String) {
        val (iv, ciphertext) = cryptoManager.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        // Persist the encrypted payload locally first — Single Source of
        // Truth: the UI subscribes to the DB and updates automatically
        // as soon as the row lands.
        messageDao.insertMessage(
            MessageEntity(
                macAddress = macAddress,
                encryptedPayload = ciphertext,
                iv = iv,
                isOutgoing = true,
                senderName = "Me"
            )
        )
        // The actual transmission over the BluetoothSocket is delegated
        // to ConnectedThread in step 35 (cleanup / final wiring); for
        // now we keep the network layer pluggable.
    }

    override suspend fun deleteMessagesByDevice(macAddress: String) {
        messageDao.deleteMessagesByDevice(macAddress)
    }

    /**
     * Per-peer network state guard. `false` means we have observed a
     * `BluetoothDevice.ACTION_KEY_MISSING` for that MAC address (Android
     * 16 bond loss) and outgoing transmissions are suppressed until a
     * subsequent `ACTION_ENCRYPTION_CHANGE` flips it back via
     * [resumeNetworkOperations].
     *
     * Volatile-style synchronisation is sufficient here — writes only
     * happen on the BroadcastReceiver dispatch thread and reads are
     * cheap, so we use `@Synchronized` rather than `Mutex` to keep the
     * data layer free of coroutine plumbing for a single-bit flag.
     */
    private val pausedPeers: MutableSet<String> = mutableSetOf()

    override suspend fun pauseNetworkOperations(macAddress: String) {
        synchronized(pausedPeers) {
            pausedPeers.add(macAddress.uppercase())
        }
        // The actual socket close lives in step 35 (cleanup) once the
        // active connection registry is wired into the repository; for
        // now flipping the flag is enough to suppress sendMessage().
    }

    override suspend fun resumeNetworkOperations(macAddress: String) {
        synchronized(pausedPeers) {
            pausedPeers.remove(macAddress.uppercase())
        }
    }

    /**
     * Visible for tests / step 31: returns whether the given peer is
     * currently paused due to ACTION_KEY_MISSING.
     */
    internal fun isPausedFor(macAddress: String): Boolean {
        return synchronized(pausedPeers) { macAddress.uppercase() in pausedPeers }
    }

    private companion object {
        /**
         * Length of the GCM IV prefix in the on-wire frame. Kept in sync
         * with [CryptoManager.GCM_IV_LENGTH_BYTES] but duplicated here so
         * the data layer doesn't take a hard compile-time dependency on
         * the constant.
         */
        const val IV_LENGTH_BYTES: Int = 12
    }
}
