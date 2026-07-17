package com.opencode.acp.chat.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Unit tests for the type-safe ID value types in [ValueTypes.kt] (TDD §4.2.6).
 *
 * Covers:
 * - [SessionId.create] validation (ses_ prefix requirement, success/failure)
 * - [ModelIdentity] equality (data class semantics)
 * - [FileIdentity] equality
 * - [EventRoutingInfo] defaults
 * - toString() returns the underlying value
 */
class ValueTypesTest {

    // ── SessionId.create ──────────────────────────────────────────────────

    @Test
    fun `SessionId_create succeeds for ses_ prefix`() {
        val result = SessionId.create("ses_abc123")
        result.isSuccess shouldBe true
        result.getOrThrow().value shouldBe "ses_abc123"
    }

    @Test
    fun `SessionId_create fails for non-ses_ prefix`() {
        val result = SessionId.create("msg_abc")
        result.isFailure shouldBe true
        val ex = result.exceptionOrNull()
        ex shouldNotBe null
        assertInstanceOf(IllegalArgumentException::class.java, ex)
    }

    @Test
    fun `SessionId_create fails for empty string`() {
        val result = SessionId.create("")
        result.isFailure shouldBe true
    }

    @Test
    fun `SessionId_create succeeds for just ses_ prefix`() {
        val result = SessionId.create("ses_")
        result.isSuccess shouldBe true
        result.getOrThrow().value shouldBe "ses_"
    }

    @Test
    fun `SessionId toString returns underlying value`() {
        val sid = SessionId("ses_xyz")
        sid.toString() shouldBe "ses_xyz"
    }

    // ── MessageId / PartId / ToolCallId toString ──────────────────────────

    @Test
    fun `MessageId toString returns underlying value`() {
        val mid = MessageId("msg_123")
        mid.toString() shouldBe "msg_123"
    }

    @Test
    fun `PartId toString returns underlying value`() {
        val pid = PartId("prt_456")
        pid.toString() shouldBe "prt_456"
    }

    @Test
    fun `ToolCallId toString returns underlying value`() {
        val tid = ToolCallId("tc_789")
        tid.toString() shouldBe "tc_789"
    }

    // ── ModelIdentity equality ────────────────────────────────────────────

    @Test
    fun `ModelIdentity equal instances are equal`() {
        val a = ModelIdentity(providerID = "anthropic", modelID = "claude-sonnet")
        val b = ModelIdentity(providerID = "anthropic", modelID = "claude-sonnet")
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun `ModelIdentity different providerID not equal`() {
        val a = ModelIdentity(providerID = "anthropic", modelID = "claude-sonnet")
        val b = ModelIdentity(providerID = "openai", modelID = "claude-sonnet")
        a shouldNotBe b
    }

    @Test
    fun `ModelIdentity different modelID not equal`() {
        val a = ModelIdentity(providerID = "anthropic", modelID = "claude-sonnet")
        val b = ModelIdentity(providerID = "anthropic", modelID = "claude-opus")
        a shouldNotBe b
    }

    @Test
    fun `ModelIdentity copy produces equal instance`() {
        val a = ModelIdentity(providerID = "p", modelID = "m")
        val b = a.copy()
        a shouldBe b
    }

    // ── FileIdentity equality ─────────────────────────────────────────────

    @Test
    fun `FileIdentity equal instances are equal`() {
        val a = FileIdentity(path = "/tmp/x", mtimeMs = 100L, sizeBytes = 200L)
        val b = FileIdentity(path = "/tmp/x", mtimeMs = 100L, sizeBytes = 200L)
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun `FileIdentity different mtime not equal`() {
        val a = FileIdentity(path = "/tmp/x", mtimeMs = 100L, sizeBytes = 200L)
        val b = FileIdentity(path = "/tmp/x", mtimeMs = 200L, sizeBytes = 200L)
        a shouldNotBe b
    }

    @Test
    fun `FileIdentity different size not equal`() {
        val a = FileIdentity(path = "/tmp/x", mtimeMs = 100L, sizeBytes = 200L)
        val b = FileIdentity(path = "/tmp/x", mtimeMs = 100L, sizeBytes = 300L)
        a shouldNotBe b
    }

    // ── EventRoutingInfo defaults ─────────────────────────────────────────

    @Test
    fun `EventRoutingInfo defaults messageId and partId to null`() {
        val info = EventRoutingInfo(sessionId = "ses_1")
        info.sessionId shouldBe "ses_1"
        info.messageId shouldBe null
        info.partId shouldBe null
    }

    @Test
    fun `EventRoutingInfo with all fields populated`() {
        val info = EventRoutingInfo(sessionId = "ses_1", messageId = "msg_1", partId = "prt_1")
        info.sessionId shouldBe "ses_1"
        info.messageId shouldBe "msg_1"
        info.partId shouldBe "prt_1"
    }

    @Test
    fun `EventRoutingInfo equality`() {
        val a = EventRoutingInfo("s", "m", "p")
        val b = EventRoutingInfo("s", "m", "p")
        a shouldBe b
    }
}