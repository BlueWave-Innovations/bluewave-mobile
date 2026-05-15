package com.example.bluewave_mobile.network

import com.example.bluewave_mobile.utils.BlueWaveLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Single source of truth for the network-layer [CoroutineScope].
 *
 * Structured Concurrency rules of thumb followed by every network class
 * in this module ([AcceptThread], [ConnectThread], [ConnectedThread],
 * [BluetoothDiscovery], [BondLossReceiver]):
 *
 *  * a single failing child coroutine MUST NOT cancel the entire network
 *    pipeline — that's why we use [SupervisorJob] instead of a regular
 *    [kotlinx.coroutines.Job];
 *  * the scope is rooted on [Dispatchers.IO] because every network class
 *    performs blocking socket / stream operations;
 *  * uncaught exceptions are logged through a shared
 *    [CoroutineExceptionHandler] rather than crashing the app — losing a
 *    Bluetooth connection is an expected condition, not a bug.
 *
 * Centralising the construction here makes it trivial to:
 *  * substitute a [kotlinx.coroutines.test.TestDispatcher] from JUnit;
 *  * collect every uncaught exception in a single place for crashlytics
 *    once analytics ship.
 */
object BluetoothScopeFactory {

    private const val TAG = "NetworkScope"

    /**
     * Default coroutine exception handler for the network module.
     * Emits a `Log.w` per failure so the radio link can drop without
     * tearing down the parent scope or crashing the process.
     */
    val defaultExceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { context, throwable ->
            BlueWaveLogger.w(TAG, "Uncaught network coroutine failure: ${context[kotlinx.coroutines.CoroutineName]}", throwable)
        }

    /**
     * Builds a brand-new [CoroutineScope] suitable for ONE
     * [ConnectThread]/[ConnectedThread]/[AcceptThread] lifecycle. The
     * caller is responsible for cancelling the scope through the
     * matching `cancel()` method when the connection ends.
     *
     * The returned scope wires together:
     *  * a fresh [SupervisorJob] (failures of one child don't cancel
     *    siblings),
     *  * the IO dispatcher (network IO is blocking),
     *  * [defaultExceptionHandler] for silent logging.
     */
    fun createNetworkScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO + defaultExceptionHandler)
    }
}
