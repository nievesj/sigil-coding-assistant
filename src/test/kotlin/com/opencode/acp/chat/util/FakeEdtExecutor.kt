package com.opencode.acp.chat.util

/**
 * Synchronous EDT executor for tests — runs actions immediately on the calling thread.
 */
class FakeEdtExecutor : EdtExecutor {
    override fun runOnEdt(action: () -> Unit) = action()
    override fun runOnNonModalEdt(action: () -> Unit) = action()
}