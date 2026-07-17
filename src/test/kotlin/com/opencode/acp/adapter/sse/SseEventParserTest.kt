package com.opencode.acp.adapter.sse

import com.opencode.acp.SseEvent
import com.opencode.acp.SseQuestionInfo
import com.opencode.acp.SseTodoItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeTypeOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SseEventParser] (TDD §4.2.6).
 *
 * Tests V1/V2 SSE event parsing with real JSON strings — no mocking.
 * Covers: text/reasoning/tool events, unknown types → Ignored,
 * parse errors → Ignored, reasoningPartIds state disambiguation.
 *
 * The parser receives already-normalized eventType + props (V1/V2 detection
 * is done by the caller). These tests call [SseEventParser.parse] directly
 * with normalized inputs.
 */
class SseEventParserTest {

    private lateinit var parser: SseEventParser
    private val sessionId = "ses_test_123"

    @BeforeEach
    fun setUp() {
        parser = SseEventParser()
    }

    private fun parse(eventType: String, propsJson: String): SseEvent {
        val props = Json.parseToJsonElement(propsJson).let {
            if (it is JsonObject) it else JsonObject(emptyMap())
        }
        return parser.parse(eventType, props, sessionId)
    }

    private fun props(vararg pairs: Pair<String, Any?>): String {
        val obj = buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    null -> {}
                    is String -> put(k, v)
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    is JsonObject -> put(k, v)
                    is JsonArray -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
        return obj.toString()
    }

    // ── V2 Text events ────────────────────────────────────────────────────

    @Test
    fun `V2 text delta parses to TextChunk`() {
        val event = parse("session.next.text.delta", props("delta" to "Hello"))
        event.shouldBeTypeOf<SseEvent.TextChunk>()
        (event as SseEvent.TextChunk).text shouldBe "Hello"
        event.sessionId shouldBe sessionId
    }

    @Test
    fun `V2 text delta missing delta returns Ignored`() {
        val event = parse("session.next.text.delta", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
        (event as SseEvent.Ignored).reason shouldNotBe null
    }

    @Test
    fun `V2 text ended parses to TextReplace`() {
        val event = parse("session.next.text.ended", props("text" to "Final text"))
        event.shouldBeTypeOf<SseEvent.TextReplace>()
        (event as SseEvent.TextReplace).text shouldBe "Final text"
    }

    @Test
    fun `V2 text started returns Ignored (intentional no-op)`() {
        val event = parse("session.next.text.started", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
        (event as SseEvent.Ignored).reason shouldBe "intentional no-op"
    }

    // ── V2 Reasoning events ───────────────────────────────────────────────

    @Test
    fun `V2 reasoning delta parses to ThinkingChunk`() {
        val event = parse("session.next.reasoning.delta", props("delta" to "Thinking..."))
        event.shouldBeTypeOf<SseEvent.ThinkingChunk>()
        (event as SseEvent.ThinkingChunk).text shouldBe "Thinking..."
    }

    @Test
    fun `V2 reasoning ended parses to ThinkingReplace`() {
        val event = parse("session.next.reasoning.ended", props("text" to "Final reasoning"))
        event.shouldBeTypeOf<SseEvent.ThinkingReplace>()
        (event as SseEvent.ThinkingReplace).text shouldBe "Final reasoning"
    }

    @Test
    fun `V2 reasoning started returns Ignored`() {
        val event = parse("session.next.reasoning.started", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    // ── V2 Tool events ───────────────────────────────────────────────────

    @Test
    fun `V2 tool called parses to ToolUse`() {
        val event = parse("session.next.tool.called", props(
            "callID" to "tc_1",
            "tool" to "read",
        ))
        event.shouldBeTypeOf<SseEvent.ToolUse>()
        (event as SseEvent.ToolUse).toolCallId shouldBe "tc_1"
        event.toolName shouldBe "read"
    }

    @Test
    fun `V2 tool called missing callID returns Ignored`() {
        val event = parse("session.next.tool.called", props("tool" to "read"))
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `V2 tool called missing tool defaults to tool`() {
        val event = parse("session.next.tool.called", props("callID" to "tc_1"))
        event.shouldBeTypeOf<SseEvent.ToolUse>()
        (event as SseEvent.ToolUse).toolName shouldBe "tool"
    }

    @Test
    fun `V2 tool success parses to ToolResult with isError false`() {
        val event = parse("session.next.tool.success", props("callID" to "tc_1"))
        event.shouldBeTypeOf<SseEvent.ToolResult>()
        (event as SseEvent.ToolResult).isError shouldBe false
    }

    @Test
    fun `V2 tool failed parses to ToolResult with isError true`() {
        val event = parse("session.next.tool.failed", props("callID" to "tc_1"))
        event.shouldBeTypeOf<SseEvent.ToolResult>()
        (event as SseEvent.ToolResult).isError shouldBe true
    }

    @Test
    fun `V2 tool input started returns Ignored`() {
        val event = parse("session.next.tool.input.started", props("callID" to "tc_1"))
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    // ── V2 Step events ───────────────────────────────────────────────────

    @Test
    fun `V2 step started returns Ignored`() {
        parse("session.next.step.started", "{}").shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `V2 step ended returns Ignored`() {
        parse("session.next.step.ended", "{}").shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `V2 step failed returns Ignored`() {
        parse("session.next.step.failed", "{}").shouldBeTypeOf<SseEvent.Ignored>()
    }

    // ── V2 Prompted event ─────────────────────────────────────────────────

    @Test
    fun `V2 prompted parses to UserMessage`() {
        val json = buildJsonObject {
            putJsonObject("prompt") {
                put("text", "Hello agent")
                putJsonArray("files") { add("file1.txt") }
            }
        }.toString()
        val event = parse("session.next.prompted", json)
        event.shouldBeTypeOf<SseEvent.UserMessage>()
        (event as SseEvent.UserMessage).text shouldBe "Hello agent"
        event.files shouldBe listOf("file1.txt")
    }

    @Test
    fun `V2 prompted missing prompt returns Ignored`() {
        val event = parse("session.next.prompted", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    // ── V2 Permission event ───────────────────────────────────────────────

    @Test
    fun `V2 permission parses to Permission`() {
        val json = buildJsonObject {
            put("id", "perm_1")
            put("permission", "edit")
            putJsonObject("tool") { put("callID", "tc_1") }
        }.toString()
        val event = parse("session.next.permission", json)
        event.shouldBeTypeOf<SseEvent.Permission>()
        (event as SseEvent.Permission).permissionId shouldBe "perm_1"
        event.action shouldBe "edit"
        event.toolCallId shouldBe "tc_1"
    }

    @Test
    fun `V2 permission missing id returns Ignored`() {
        val event = parse("session.next.permission", props("permission" to "edit"))
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `V2 permission missing permission returns Ignored`() {
        val event = parse("session.next.permission", props("id" to "perm_1"))
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    // ── V2 Question event ────────────────────────────────────────────────

    @Test
    fun `V2 question parses to QuestionAsked`() {
        val json = buildJsonObject {
            put("id", "que_1")
            putJsonArray("questions") {
                add(buildJsonObject {
                    put("question", "What color?")
                    put("header", "Color")
                    put("multiple", false)
                    put("custom", true)
                    putJsonArray("options") {
                        add(buildJsonObject {
                            put("label", "Red")
                            put("description", "The color red")
                        })
                    }
                })
            }
        }.toString()
        val event = parse("session.next.question", json)
        event.shouldBeTypeOf<SseEvent.QuestionAsked>()
        (event as SseEvent.QuestionAsked).requestId shouldBe "que_1"
        event.questions shouldHaveSize 1
        event.questions[0].question shouldBe "What color?"
        event.questions[0].header shouldBe "Color"
        event.questions[0].options shouldHaveSize 1
        event.questions[0].options[0].label shouldBe "Red"
    }

    @Test
    fun `V2 question missing id returns Ignored`() {
        val event = parse("session.next.question", props("questions" to "[]"))
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `V2 question missing questions returns Ignored`() {
        val event = parse("session.next.question", props("id" to "que_1"))
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    // ── V2 Todo event ─────────────────────────────────────────────────────

    @Test
    fun `V2 todo updated parses to TodoUpdated`() {
        val json = buildJsonObject {
            putJsonArray("todos") {
                add(buildJsonObject {
                    put("content", "Task 1")
                    put("status", "pending")
                    put("priority", "high")
                })
            }
        }.toString()
        val event = parse("session.next.todo.updated", json)
        event.shouldBeTypeOf<SseEvent.TodoUpdated>()
        (event as SseEvent.TodoUpdated).todos shouldHaveSize 1
        event.todos[0] shouldBe SseTodoItem("Task 1", "pending", "high")
    }

    @Test
    fun `V2 todo updated missing todos returns Ignored`() {
        val event = parse("session.next.todo.updated", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    // ── V2 Stop / Session events ──────────────────────────────────────────

    @Test
    fun `V2 stopped parses to Stop`() {
        val event = parse("session.next.stopped", props("stopReason" to "end_turn"))
        event.shouldBeTypeOf<SseEvent.Stop>()
        (event as SseEvent.Stop).stopReason shouldBe "end_turn"
    }

    @Test
    fun `V2 stopped missing stopReason defaults to stop`() {
        val event = parse("session.next.stopped", "{}")
        event.shouldBeTypeOf<SseEvent.Stop>()
        (event as SseEvent.Stop).stopReason shouldBe "stop"
    }

    @Test
    fun `V2 session created parses to SessionCreated`() {
        val event = parse("session.next.created", "{}")
        event.shouldBeTypeOf<SseEvent.SessionCreated>()
    }

    // ── Legacy V1 events ──────────────────────────────────────────────────

    @Test
    fun `V1 message_part_delta with field text and no reasoning tracking parses to TextChunk`() {
        val event = parse("message.part.delta", props(
            "field" to "text",
            "delta" to "Hello",
            "messageID" to "msg_1",
            "partID" to "pt_1",
        ))
        event.shouldBeTypeOf<SseEvent.TextChunk>()
        (event as SseEvent.TextChunk).text shouldBe "Hello"
        event.messageId shouldBe "msg_1"
        event.partId shouldBe "pt_1"
    }

    @Test
    fun `V1 message_part_delta with field thinking parses to ThinkingChunk`() {
        val event = parse("message.part.delta", props(
            "field" to "thinking",
            "delta" to "Reasoning",
        ))
        event.shouldBeTypeOf<SseEvent.ThinkingChunk>()
        (event as SseEvent.ThinkingChunk).text shouldBe "Reasoning"
    }

    @Test
    fun `V1 message_part_delta with field reasoning parses to ThinkingChunk`() {
        val event = parse("message.part.delta", props(
            "field" to "reasoning",
            "delta" to "Reasoning",
        ))
        event.shouldBeTypeOf<SseEvent.ThinkingChunk>()
    }

    @Test
    fun `V1 message_part_delta with field text and tracked reasoning partId parses to ThinkingChunk`() {
        // First, emit a reasoning part.updated to track the partId
        val reasoningPartJson = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "reasoning")
                put("id", "pt_reasoning_1")
                put("text", "Initial reasoning")
            })
        }.toString()
        parse("message.part.updated", reasoningPartJson)

        // Now a text delta with the reasoning partId should be ThinkingChunk
        val event = parse("message.part.delta", props(
            "field" to "text",
            "delta" to "More reasoning",
            "partID" to "pt_reasoning_1",
        ))
        event.shouldBeTypeOf<SseEvent.ThinkingChunk>()
        (event as SseEvent.ThinkingChunk).text shouldBe "More reasoning"
    }

    @Test
    fun `V1 message_part_delta missing delta returns Ignored`() {
        val event = parse("message.part.delta", props("field" to "text"))
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `V1 message_part_delta with unknown field returns Ignored`() {
        val event = parse("message.part.delta", props(
            "field" to "unknown",
            "delta" to "data",
        ))
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    // ── V1 message.part.updated ──────────────────────────────────────────

    @Test
    fun `V1 message_part_updated text part parses to TextReplace`() {
        val json = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "text")
                put("id", "pt_1")
                put("text", "Hello world")
            })
        }.toString()
        val event = parse("message.part.updated", json)
        event.shouldBeTypeOf<SseEvent.TextReplace>()
        (event as SseEvent.TextReplace).text shouldBe "Hello world"
    }

    @Test
    fun `V1 message_part_updated reasoning part tracks partId and parses to ThinkingReplace`() {
        val json = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "reasoning")
                put("id", "pt_r1")
                put("text", "Reasoning text")
            })
        }.toString()
        val event = parse("message.part.updated", json)
        event.shouldBeTypeOf<SseEvent.ThinkingReplace>()
        (event as SseEvent.ThinkingReplace).text shouldBe "Reasoning text"
    }

    @Test
    fun `V1 message_part_updated tool part with running status parses to ToolUse`() {
        val json = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "tool")
                put("callID", "tc_1")
                put("tool", "read")
                putJsonObject("state") {
                    put("status", "running")
                }
            })
        }.toString()
        val event = parse("message.part.updated", json)
        event.shouldBeTypeOf<SseEvent.ToolUse>()
        (event as SseEvent.ToolUse).toolCallId shouldBe "tc_1"
        event.toolName shouldBe "read"
    }

    @Test
    fun `V1 message_part_updated tool part with completed status parses to ToolResult`() {
        val json = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "tool")
                put("callID", "tc_1")
                put("tool", "read")
                putJsonObject("state") {
                    put("status", "completed")
                }
            })
        }.toString()
        val event = parse("message.part.updated", json)
        event.shouldBeTypeOf<SseEvent.ToolResult>()
        (event as SseEvent.ToolResult).isError shouldBe false
    }

    @Test
    fun `V1 message_part_updated tool part with error status parses to ToolResult with isError`() {
        val json = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "tool")
                put("callID", "tc_1")
                put("tool", "bash")
                putJsonObject("state") {
                    put("status", "error")
                }
            })
        }.toString()
        val event = parse("message.part.updated", json)
        event.shouldBeTypeOf<SseEvent.ToolResult>()
        (event as SseEvent.ToolResult).isError shouldBe true
    }

    @Test
    fun `V1 message_part_updated missing part returns Ignored`() {
        val event = parse("message.part.updated", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `V1 message_part_updated tool part missing callID returns Ignored`() {
        val json = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "tool")
                put("tool", "read")
            })
        }.toString()
        val event = parse("message.part.updated", json)
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `V1 message_part_updated text part clears reasoning tracking`() {
        // Track a reasoning part
        val reasoningJson = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "reasoning")
                put("id", "pt_r1")
                put("text", "r")
            })
        }.toString()
        parse("message.part.updated", reasoningJson)

        // Emit a text part — should clear reasoning tracking
        val textJson = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "text")
                put("id", "pt_t1")
                put("text", "text")
            })
        }.toString()
        parse("message.part.updated", textJson)

        // Now a text delta with the old reasoning partId should be TextChunk (not ThinkingChunk)
        val event = parse("message.part.delta", props(
            "field" to "text",
            "delta" to "after clear",
            "partID" to "pt_r1",
        ))
        event.shouldBeTypeOf<SseEvent.TextChunk>()
    }

    // ── Session events ────────────────────────────────────────────────────

    @Test
    fun `session created parses to SessionCreated`() {
        parse("session.created", "{}").shouldBeTypeOf<SseEvent.SessionCreated>()
    }

    @Test
    fun `session idle parses to SessionIdle`() {
        parse("session.idle", "{}").shouldBeTypeOf<SseEvent.SessionIdle>()
    }

    @Test
    fun `session error parses to SessionError with message from data`() {
        val json = buildJsonObject {
            putJsonObject("error") {
                put("name", "ProviderAuthError")
                putJsonObject("data") {
                    put("message", "Invalid API key")
                }
            }
        }.toString()
        val event = parse("session.error", json)
        event.shouldBeTypeOf<SseEvent.SessionError>()
        (event as SseEvent.SessionError).errorMessage shouldBe "Invalid API key"
    }

    @Test
    fun `session error falls back to error name when data message absent`() {
        val json = buildJsonObject {
            putJsonObject("error") {
                put("name", "ProviderAuthError")
            }
        }.toString()
        val event = parse("session.error", json)
        event.shouldBeTypeOf<SseEvent.SessionError>()
        (event as SseEvent.SessionError).errorMessage shouldNotBe null
    }

    @Test
    fun `session compacted parses to SessionCompacted`() {
        parse("session.compacted", "{}").shouldBeTypeOf<SseEvent.SessionCompacted>()
    }

    @Test
    fun `session updated returns Ignored (informational)`() {
        val event = parse("session.updated", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `session deleted returns Ignored (informational)`() {
        val event = parse("session.deleted", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `message removed parses to MessageRemoved`() {
        val event = parse("message.removed", props("messageID" to "msg_1"))
        event.shouldBeTypeOf<SseEvent.MessageRemoved>()
        (event as SseEvent.MessageRemoved).messageId shouldBe "msg_1"
    }

    // ── Legacy stop / text_chunk / tool_use / tool_result ─────────────────

    @Test
    fun `legacy stop parses to Stop`() {
        val event = parse("stop", props("stopReason" to "end_turn", "messageID" to "msg_1"))
        event.shouldBeTypeOf<SseEvent.Stop>()
        (event as SseEvent.Stop).stopReason shouldBe "end_turn"
        event.messageId shouldBe "msg_1"
    }

    @Test
    fun `legacy text_chunk parses to TextChunk`() {
        val event = parse("text_chunk", props("text" to "Hello", "messageID" to "msg_1"))
        event.shouldBeTypeOf<SseEvent.TextChunk>()
        (event as SseEvent.TextChunk).text shouldBe "Hello"
    }

    @Test
    fun `legacy text_chunk missing text returns Ignored`() {
        val event = parse("text_chunk", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `legacy tool_use parses to ToolUse`() {
        val event = parse("tool_use", props(
            "toolCallId" to "tc_1",
            "toolName" to "read",
        ))
        event.shouldBeTypeOf<SseEvent.ToolUse>()
        (event as SseEvent.ToolUse).toolCallId shouldBe "tc_1"
        event.toolName shouldBe "read"
    }

    @Test
    fun `legacy tool_use missing toolCallId returns Ignored`() {
        val event = parse("tool_use", props("toolName" to "read"))
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    @Test
    fun `legacy tool_result parses to ToolResult`() {
        val event = parse("tool_result", props(
            "toolCallId" to "tc_1",
            "isError" to "false",
        ))
        event.shouldBeTypeOf<SseEvent.ToolResult>()
        (event as SseEvent.ToolResult).isError shouldBe false
    }

    @Test
    fun `legacy tool_result with isError true parses to ToolResult with isError`() {
        val event = parse("tool_result", props(
            "toolCallId" to "tc_1",
            "isError" to "true",
        ))
        event.shouldBeTypeOf<SseEvent.ToolResult>()
        (event as SseEvent.ToolResult).isError shouldBe true
    }

    // ── Permission / Question legacy ─────────────────────────────────────

    @Test
    fun `legacy permission_asked parses to Permission`() {
        val event = parse("permission.asked", props(
            "id" to "perm_1",
            "permission" to "edit",
        ))
        event.shouldBeTypeOf<SseEvent.Permission>()
        (event as SseEvent.Permission).permissionId shouldBe "perm_1"
        event.action shouldBe "edit"
    }

    @Test
    fun `legacy permission_replied parses to PermissionReplied`() {
        val event = parse("permission.replied", props(
            "requestID" to "perm_1",
            "reply" to "allow",
        ))
        event.shouldBeTypeOf<SseEvent.PermissionReplied>()
        (event as SseEvent.PermissionReplied).permissionId shouldBe "perm_1"
        event.reply shouldBe "allow"
    }

    @Test
    fun `legacy question_asked parses to QuestionAsked`() {
        val json = buildJsonObject {
            put("id", "que_1")
            putJsonArray("questions") {
                add(buildJsonObject {
                    put("question", "Q?")
                    put("header", "H")
                    putJsonArray("options") {
                        add(buildJsonObject {
                            put("label", "A")
                            put("description", "desc")
                        })
                    }
                })
            }
        }.toString()
        val event = parse("question.asked", json)
        event.shouldBeTypeOf<SseEvent.QuestionAsked>()
        (event as SseEvent.QuestionAsked).requestId shouldBe "que_1"
        event.questions shouldHaveSize 1
    }

    // ── Error / Todo / Plan / MessageComplete ─────────────────────────────

    @Test
    fun `error event parses to Error`() {
        val event = parse("error", props("message" to "Something went wrong"))
        event.shouldBeTypeOf<SseEvent.Error>()
        (event as SseEvent.Error).message shouldBe "Something went wrong"
    }

    @Test
    fun `error event missing message defaults to Unknown error`() {
        val event = parse("error", "{}")
        event.shouldBeTypeOf<SseEvent.Error>()
        (event as SseEvent.Error).message shouldBe "Unknown error"
    }

    @Test
    fun `todo updated parses to TodoUpdated`() {
        val json = buildJsonObject {
            putJsonArray("todos") {
                add(buildJsonObject {
                    put("content", "Task")
                    put("status", "in_progress")
                    put("priority", "medium")
                })
            }
        }.toString()
        val event = parse("todo.updated", json)
        event.shouldBeTypeOf<SseEvent.TodoUpdated>()
        (event as SseEvent.TodoUpdated).todos shouldHaveSize 1
    }

    @Test
    fun `plan parses to Plan`() {
        val json = buildJsonObject {
            putJsonArray("entries") {
                add(buildJsonObject {
                    put("description", "Step 1")
                    put("priority", "high")
                    put("status", "pending")
                })
            }
        }.toString()
        val event = parse("plan", json)
        event.shouldBeTypeOf<SseEvent.Plan>()
        (event as SseEvent.Plan).entries shouldHaveSize 1
    }

    @Test
    fun `message_complete parses to MessageComplete`() {
        val event = parse("message_complete", props("messageID" to "msg_1"))
        event.shouldBeTypeOf<SseEvent.MessageComplete>()
        (event as SseEvent.MessageComplete).messageId shouldBe "msg_1"
    }

    // ── Unknown types → Ignored ───────────────────────────────────────────

    @Test
    fun `unknown event type returns Ignored`() {
        val event = parse("completely.unknown.type", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
        (event as SseEvent.Ignored).reason shouldBe "unknown type"
    }

    @Test
    fun `empty event type returns Ignored`() {
        val event = parse("", "{}")
        event.shouldBeTypeOf<SseEvent.Ignored>()
    }

    // ── Parse errors → Ignored (not exceptions) ───────────────────────────

    @Test
    fun `malformed JSON in props does not crash - returns Ignored`() {
        // The parser receives a JsonObject, so we can't pass malformed JSON directly.
        // But we can pass a props object that causes an internal parse error
        // (e.g., accessing a non-primitive as a primitive).
        val json = buildJsonObject {
            put("delta", buildJsonObject { put("nested", "value") })
        }.toString()
        val event = parse("session.next.text.delta", json)
        event.shouldBeTypeOf<SseEvent.Ignored>()
        (event as SseEvent.Ignored).reason shouldNotBe null
    }

    // ── resetReasoningTracking ────────────────────────────────────────────

    @Test
    fun `resetReasoningTracking clears tracked reasoning partIds`() {
        // Track a reasoning part
        val reasoningJson = buildJsonObject {
            put("part", buildJsonObject {
                put("type", "reasoning")
                put("id", "pt_r1")
                put("text", "r")
            })
        }.toString()
        parse("message.part.updated", reasoningJson)

        // Reset
        parser.resetReasoningTracking()

        // Now a text delta with the old reasoning partId should be TextChunk
        val event = parse("message.part.delta", props(
            "field" to "text",
            "delta" to "after reset",
            "partID" to "pt_r1",
        ))
        event.shouldBeTypeOf<SseEvent.TextChunk>()
    }
}