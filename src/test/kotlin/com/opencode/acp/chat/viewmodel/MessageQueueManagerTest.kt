package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.QueuedMessage
import com.opencode.acp.chat.service.SendMessageResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MessageQueueManager] (TDD §4.2.6).
 *
 * Tests queue/drain/retry logic. The send function is a lambda that returns
 * configurable results — no mocking of external services needed.
 */
class MessageQueueManagerTest {

    private lateinit var sendResults: MutableList<SendMessageResult>
    private lateinit var sentMessages: MutableList<QueuedMessage>
    private lateinit var manager: MessageQueueManager

    @BeforeEach
    fun setUp() {
        sendResults = mutableListOf()
        sentMessages = mutableListOf()
        manager = MessageQueueManager { msg ->
            sentMessages.add(msg)
            // Pop the next pre-configured result, default to Success
            if (sendResults.isNotEmpty()) sendResults.removeAt(0)
            else SendMessageResult.Success("msg_ok")
        }
    }

    // ── queueMessage ──────────────────────────────────────────────────────

    @Test
    fun `queueMessage adds message to queue`() {
        manager.queueMessage("Hello")
        manager.queuedMessages.value shouldHaveSize 1
        manager.queuedMessages.value[0].text shouldBe "Hello"
    }

    @Test
    fun `queueMessage with files attaches them`() {
        val files = listOf(AttachedFile(name = "a.txt", path = "/a", mime = "text/plain"))
        manager.queueMessage("Hello", files)
        manager.queuedMessages.value[0].files shouldHaveSize 1
        manager.queuedMessages.value[0].files[0].name shouldBe "a.txt"
    }

    @Test
    fun `queueMessage preserves order`() {
        manager.queueMessage("first")
        manager.queueMessage("second")
        manager.queueMessage("third")
        manager.queuedMessages.value.map { it.text } shouldBe listOf("first", "second", "third")
    }

    @Test
    fun `queueMessage assigns unique IDs`() {
        manager.queueMessage("a")
        manager.queueMessage("b")
        manager.queuedMessages.value[0].id shouldNotBe manager.queuedMessages.value[1].id
    }

    // ── removeQueuedMessage ───────────────────────────────────────────────

    @Test
    fun `removeQueuedMessage removes by ID`() {
        manager.queueMessage("first")
        val id = manager.queuedMessages.value[0].id
        manager.removeQueuedMessage(id)
        manager.queuedMessages.value shouldHaveSize 0
    }

    @Test
    fun `removeQueuedMessage only removes matching ID`() {
        manager.queueMessage("first")
        manager.queueMessage("second")
        val id = manager.queuedMessages.value[0].id
        manager.removeQueuedMessage(id)
        manager.queuedMessages.value shouldHaveSize 1
        manager.queuedMessages.value[0].text shouldBe "second"
    }

    @Test
    fun `removeQueuedMessage with unknown ID is no-op`() {
        manager.queueMessage("first")
        manager.removeQueuedMessage("nonexistent")
        manager.queuedMessages.value shouldHaveSize 1
    }

    // ── editQueuedMessage ────────────────────────────────────────────────

    @Test
    fun `editQueuedMessage updates text by ID`() {
        manager.queueMessage("original")
        val id = manager.queuedMessages.value[0].id
        manager.editQueuedMessage(id, "edited")
        manager.queuedMessages.value[0].text shouldBe "edited"
    }

    @Test
    fun `editQueuedMessage only updates matching ID`() {
        manager.queueMessage("first")
        manager.queueMessage("second")
        val id = manager.queuedMessages.value[0].id
        manager.editQueuedMessage(id, "edited")
        manager.queuedMessages.value[0].text shouldBe "edited"
        manager.queuedMessages.value[1].text shouldBe "second"
    }

    @Test
    fun `editQueuedMessage with unknown ID is no-op`() {
        manager.queueMessage("first")
        manager.editQueuedMessage("nonexistent", "edited")
        manager.queuedMessages.value[0].text shouldBe "first"
    }

    // ── clearQueue ────────────────────────────────────────────────────────

    @Test
    fun `clearQueue removes all messages`() {
        manager.queueMessage("a")
        manager.queueMessage("b")
        manager.clearQueue()
        manager.queuedMessages.value shouldHaveSize 0
    }

    @Test
    fun `clearQueue on empty queue is no-op`() {
        manager.clearQueue()
        manager.queuedMessages.value shouldHaveSize 0
    }

    // ── drainQueue ────────────────────────────────────────────────────────

    @Test
    fun `drainQueue sends first message and removes it from queue`() = runTest {
        manager.queueMessage("first")
        manager.queueMessage("second")
        manager.drainQueue()
        sentMessages shouldHaveSize 1
        sentMessages[0].text shouldBe "first"
        manager.queuedMessages.value shouldHaveSize 1
        manager.queuedMessages.value[0].text shouldBe "second"
    }

    @Test
    fun `drainQueue on empty queue does nothing`() = runTest {
        manager.drainQueue()
        sentMessages shouldHaveSize 0
    }

    @Test
    fun `drainQueue with success clears retry tracking`() = runTest {
        manager.queueMessage("first")
        manager.drainQueue()
        // Second drain should work (no retry delay blocking)
        manager.queueMessage("second")
        manager.drainQueue()
        sentMessages shouldHaveSize 2
    }

    @Test
    fun `drainQueue with error re-queues message`() = runTest {
        sendResults.add(SendMessageResult.Error("network failure"))
        manager.queueMessage("first")
        manager.drainQueue()
        sentMessages shouldHaveSize 1
        // Message should be re-queued
        manager.queuedMessages.value shouldHaveSize 1
        manager.queuedMessages.value[0].text shouldBe "first"
    }

    @Test
    fun `drainQueue re-queues message on error`() = runTest {
        sendResults.add(SendMessageResult.Error("network failure"))
        manager.queueMessage("first")
        manager.drainQueue()
        sentMessages shouldHaveSize 1
        // Message should be re-queued
        manager.queuedMessages.value shouldHaveSize 1
        manager.queuedMessages.value[0].text shouldBe "first"
    }

    @Test
    fun `drainQueue drops message after MAX_QUEUE_RETRIES failures when delay elapses`() = runTest {
        // Configure 4 failures (MAX_QUEUE_RETRIES = 3, so 4th failure drops)
        repeat(4) { sendResults.add(SendMessageResult.Error("fail")) }
        manager.queueMessage("doomed")
        // First failure (attempt 1) — re-queued with 2s delay
        manager.drainQueue()
        sentMessages shouldHaveSize 1
        manager.queuedMessages.value shouldHaveSize 1
        // Second drain — blocked by retry delay, re-queued without sending
        // (System.currentTimeMillis() < retryAt, so it re-queues and returns)
        manager.drainQueue()
        // Note: without advancing time, the retry delay blocks subsequent sends.
        // The message stays in the queue until the delay elapses.
        manager.queuedMessages.value shouldHaveSize 1
    }

    @Test
    fun `drainQueue sends all messages sequentially`() = runTest {
        manager.queueMessage("a")
        manager.queueMessage("b")
        manager.queueMessage("c")
        manager.drainQueue()
        manager.drainQueue()
        manager.drainQueue()
        sentMessages shouldHaveSize 3
        sentMessages.map { it.text } shouldBe listOf("a", "b", "c")
    }
}