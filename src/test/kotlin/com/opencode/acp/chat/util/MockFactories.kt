package com.opencode.acp.chat.util

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.SessionState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

/**
 * Standardized mock factories. Builds on the existing MockK pattern from 6 test files.
 */
object MockFactories {
    fun mockOpenCodeClient(healthResult: Boolean = true) = mockk<OpenCodeClient>(relaxed = true).also {
        coEvery { it.healthCheck() } returns healthResult
    }

    fun mockSessionManager(activeSession: SessionState? = null) = mockk<SessionManager>(relaxed = true).also {
        every { it.getActiveSession() } returns activeSession
    }

    fun mockClock(fixedTime: Long = 1000L) = FakeClock(fixedTime)
}