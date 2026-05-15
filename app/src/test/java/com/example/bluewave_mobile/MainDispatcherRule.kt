package com.example.bluewave_mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit rule that swaps `Dispatchers.Main` for a [TestDispatcher] for
 * the duration of a single test method, then resets it.
 *
 * `viewModelScope` defaults to `Dispatchers.Main.immediate`, which is
 * unavailable on a pure-JVM test runner — without this rule any test
 * that constructs a [androidx.lifecycle.ViewModel] crashes with
 * `IllegalStateException: Module with the Main dispatcher had failed
 * to initialize`. Wrapping the dispatcher swap in a JUnit rule keeps
 * the per-test plumbing to a single `@get:Rule` line.
 *
 * We default to [UnconfinedTestDispatcher] so any
 * `viewModelScope.launch { intents.collect { … } }` inside a
 * ViewModel `init` block runs eagerly — otherwise an intent emitted
 * by a test would be dropped before any collector subscribes, since
 * `MutableSharedFlow(replay = 0)` has nowhere to buffer it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
