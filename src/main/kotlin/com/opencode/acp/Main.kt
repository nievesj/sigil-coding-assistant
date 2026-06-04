package com.opencode.acp

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.decodeJsonRpcMessage
import com.agentclientprotocol.transport.StdioTransport
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.config.AcpServerConfig
import com.opencode.acp.session.SessionIdMap
import com.opencode.acp.session.SessionPersistence
import com.opencode.acp.transport.EmbeddedTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

fun main(args: Array<String>): Unit = runBlocking {
    try {
        // 1. Parse config
        val config = AcpServerConfig.parse(args)

        // 2. Connect to OpenCode engine (with health check)
        val httpClient = HttpClient(Java) {
            // TODO: configure timeouts, connection pool
        }
        val openCodeClient = OpenCodeClient(
            baseUrl = "http://${config.openCodeHost}:${config.openCodePort}",
            httpClient = httpClient,
            authToken = config.openCodePassword
        )
        require(openCodeClient.healthCheck()) { "OpenCode engine not reachable at ${config.openCodeHost}:${config.openCodePort}" }

        // 3. Create supporting components
        val sessionIdMap = SessionIdMap()
        val sessionPersistence = SessionPersistence(
            dir = java.nio.file.Path.of(config.sessionPersistenceDir)
        )

        // 4. Create transport
        val transport = when (config.transport) {
            TransportMode.STDIO -> {
                // Flow-based StdioTransport: read lines from stdin, write lines to stdout
                val inputFlow = flow {
                    val reader = BufferedReader(InputStreamReader(System.`in`))
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            emit(line)
                        }
                    } catch (_: Exception) {
                        // stdin closed
                    }
                }
                val outputWriter: suspend (String) -> Unit = { line ->
                    println(line)
                    System.out.flush()
                }
                StdioTransport(
                    parentScope = this,
                    ioDispatcher = Dispatchers.IO,
                    input = inputFlow,
                    output = outputWriter
                )
            }
            TransportMode.EMBEDDED -> EmbeddedTransport(scope = this)
        }

        // 5. Create SDK Protocol
        val protocol = Protocol(this, transport)

        // 6. Register agent
        val agentSupport = OpenCodeAgentSupport(
            openCodeClient = openCodeClient,
            sessionIdMap = sessionIdMap,
            sessionPersistence = sessionPersistence,
            config = config,
            scope = this
        )
        val agent = Agent(
            protocol = protocol,
            agentSupport = agentSupport
        )

        // 7. Start listening
        protocol.start()
    } catch (e: Exception) {
        System.err.println("Fatal: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}
