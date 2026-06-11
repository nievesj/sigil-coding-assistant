@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.opencode.acp

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.opencode.acp.adapter.*
import com.opencode.acp.config.AcpServerConfig
import com.opencode.acp.session.SessionIdMap
import com.opencode.acp.session.SessionPersistence
import kotlinx.coroutines.CoroutineScope

/**
 * Implements the ACP SDK's AgentSupport interface.
 * Bridges ACP session lifecycle to OpenCode engine operations.
 */
class OpenCodeAgentSupport(
    private val openCodeClient: OpenCodeClient,
    private val sessionIdMap: SessionIdMap,
    private val sessionPersistence: SessionPersistence,
    private val config: AcpServerConfig,
    private val scope: CoroutineScope
) : AgentSupport {

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        return AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = AgentCapabilities(
                loadSession = true,
                promptCapabilities = PromptCapabilities(
                    image = true,
                    audio = false,
                    embeddedContext = true
                ),
                mcpCapabilities = McpCapabilities(http = true, sse = false),
                sessionCapabilities = SessionCapabilities()
            )
        )
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        val cwd = sessionParameters.cwd
        val ocSession = openCodeClient.createSession(cwd)
        val acpSessionId = SessionId("acp_${ocSession.id}")
        sessionIdMap.put(acpSessionId.value, ocSession.id)

        return OpenCodeAgentSession(
            sessionId = acpSessionId,
            openCodeSessionId = ocSession.id,
            openCodeClient = openCodeClient,
            contentMapper = ContentMapper(),
            permissionBridge = PermissionBridge(),
            terminalExecutor = TerminalExecutor(scope),
            sessionIdMap = sessionIdMap,
            scope = scope
        )
    }

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        val stored = sessionPersistence.load(sessionId.value)
            ?: throw SessionNotFoundException("Session ${sessionId.value} not found")

        // OpenCode sessions are ephemeral — create a fresh session and replay history
        val ocSession = openCodeClient.createSession(sessionParameters.cwd)
        sessionIdMap.put(sessionId.value, ocSession.id)

        return OpenCodeAgentSession(
            sessionId = sessionId,
            openCodeSessionId = ocSession.id,
            openCodeClient = openCodeClient,
            contentMapper = ContentMapper(),
            permissionBridge = PermissionBridge(),
            terminalExecutor = TerminalExecutor(scope),
            sessionIdMap = sessionIdMap,
            scope = scope,
            replayMessages = stored
        )
    }
}
