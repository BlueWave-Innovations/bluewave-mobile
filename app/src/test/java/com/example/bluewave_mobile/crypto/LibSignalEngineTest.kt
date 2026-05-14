package com.example.bluewave_mobile.crypto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [LibSignalEngine] backed by the JVM build of
 * libsignal-client (added to `testImplementation` in
 * `app/build.gradle.kts`).
 *
 * Goals:
 *
 *  * Verify that two independent [LibSignalEngine] instances —
 *    standing in for two devices — can exchange `KEY_BUNDLE`
 *    payloads, build matching Signal sessions, and round-trip
 *    plaintext bytes through `encrypt` / `decryptPreKeyMessage`
 *    / `decryptSignalMessage`.
 *  * Assert the handshake's first ciphertext is correctly tagged as
 *    [SignalEngine.Ciphertext.Type.PREKEY_SIGNAL_MESSAGE] and every
 *    follow-up as [SignalEngine.Ciphertext.Type.SIGNAL_MESSAGE].
 *  * Assert the bundle codec rejects garbage cleanly with a
 *    [SignalEngineException] (defence-in-depth against malformed
 *    peers).
 *
 * Each call site is named after the contract it validates so a CI
 * failure points directly at the broken invariant.
 */
class LibSignalEngineTest {

    private val aliceMac = "AA:AA:AA:AA:AA:AA"
    private val bobMac = "BB:BB:BB:BB:BB:BB"

    @Test
    fun `two engines exchange bundles and round-trip plaintext`() = runTest {
        val alice = LibSignalEngine.create()
        val bob = LibSignalEngine.create()

        // Pre-condition: neither engine has a session for the other
        // peer. `hasSession` is the public flag the repository keys
        // off when deciding whether to send a KEY_BUNDLE first.
        assertFalse(alice.hasSession(bobMac))
        assertFalse(bob.hasSession(aliceMac))

        // Symmetric handshake: each side processes the peer's bundle.
        // After this Alice can encrypt to Bob and vice versa.
        alice.processPeerKeyBundle(bobMac, bob.localKeyBundle())
        bob.processPeerKeyBundle(aliceMac, alice.localKeyBundle())

        assertTrue(alice.hasSession(bobMac))
        assertTrue(bob.hasSession(aliceMac))

        // Forward direction: Alice → Bob, first message is a PreKey
        // Signal message because Bob has not yet acked Alice's
        // session.
        val plaintext = "ping from alice".toByteArray(Charsets.UTF_8)
        val first = alice.encrypt(bobMac, plaintext)
        assertEquals(SignalEngine.Ciphertext.Type.PREKEY_SIGNAL_MESSAGE, first.type)
        assertNotEquals(
            "ciphertext must not equal plaintext",
            plaintext.toList(),
            first.bytes.toList(),
        )

        val recoveredOnBob = bob.decryptPreKeyMessage(aliceMac, first.bytes)
        assertArrayEquals(plaintext, recoveredOnBob)

        // Reverse direction: Bob → Alice. Bob just decrypted a real
        // PreKey envelope from Alice, so libsignal collapses Bob's
        // pending session into a fully-acked one — Bob's first
        // outbound packet therefore arrives as a plain
        // `SignalMessage` (not a `PreKeySignalMessage`).
        val replyPlain = "pong from bob".toByteArray(Charsets.UTF_8)
        val reply = bob.encrypt(aliceMac, replyPlain)
        assertEquals(SignalEngine.Ciphertext.Type.SIGNAL_MESSAGE, reply.type)
        val recoveredReply = alice.decryptSignalMessage(bobMac, reply.bytes)
        assertArrayEquals(replyPlain, recoveredReply)

        // After Alice has decoded one of Bob's `SignalMessage` frames
        // her own session is fully ack'd as well, so subsequent
        // encrypt() calls also produce plain `SignalMessage`
        // payloads instead of `PreKeySignalMessage`.
        val followUp = "second message".toByteArray(Charsets.UTF_8)
        val second = alice.encrypt(bobMac, followUp)
        assertEquals(SignalEngine.Ciphertext.Type.SIGNAL_MESSAGE, second.type)
        val recoveredFollowUp = bob.decryptSignalMessage(aliceMac, second.bytes)
        assertArrayEquals(followUp, recoveredFollowUp)
    }

    @Test
    fun `processPeerKeyBundle rejects malformed bytes`() = runTest {
        val alice = LibSignalEngine.create()
        val bogus = ByteArray(8) // smaller than a real bundle's header
        try {
            alice.processPeerKeyBundle(bobMac, bogus)
            throw AssertionError("processPeerKeyBundle must reject malformed bundles")
        } catch (_: SignalEngineException) {
            // expected
        } catch (_: IllegalArgumentException) {
            // libsignal raises this on certain malformed inputs;
            // either flavour is acceptable as long as we surface a
            // crash before mutating any state.
        }
        assertFalse(alice.hasSession(bobMac))
    }

    @Test
    fun `encrypt without a session raises SignalEngineException`() = runTest {
        val alice = LibSignalEngine.create()
        try {
            alice.encrypt(bobMac, "no session yet".toByteArray(Charsets.UTF_8))
            throw AssertionError("encrypt must require a session")
        } catch (_: SignalEngineException) {
            // expected
        }
    }
}
