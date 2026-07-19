package com.opencode.acp.chat.util

import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Base class for unit tests. Provides shared test infrastructure:
 * - [testScope] with [StandardTestDispatcher] for coroutine testing
 * - [fakeClock] for controllable time
 * - [fakeSettings] for in-memory settings
 * - [fakeEdt] for synchronous EDT execution
 * - [fakeFollowAgent] for no-op Follow Agent dispatch
 *
 * NOTE: [testScope.cancel] cleans up pending coroutines. Tests that intentionally
 * launch fire-and-forget coroutines should use `runTest` (which auto-cleans)
 * instead of manual `scope.launch`.
 */
abstract class BaseUnitTest {
    protected val testScope = TestScope(StandardTestDispatcher())
    protected val fakeClock = FakeClock()
    protected val fakeSettings = FakeSettingsProvider()
    protected val fakeEdt = FakeEdtExecutor()
    protected val fakeFollowAgent = FakeFollowAgentDispatcher()

    @BeforeEach
    open fun setUp() {}

    @AfterEach
    open fun tearDown() {
        testScope.cancel()
    }
}