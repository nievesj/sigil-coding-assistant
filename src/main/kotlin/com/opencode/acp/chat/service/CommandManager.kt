package com.opencode.acp.chat.service

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.ui.compose.SlashCommand
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Manages slash command discovery and execution.
 */
class CommandManager(
    private val clientProvider: () -> OpenCodeClient?,
    private val sessionIdProvider: () -> String?,
) {

    private val logger = KotlinLogging.logger {}

    suspend fun fetchAvailableCommands(): List<SlashCommand> {
        val client = clientProvider() ?: return emptyList()
        return try {
            client.listCommands().map { cmd ->
                SlashCommand(
                    name = cmd.id ?: cmd.name,
                    description = cmd.description ?: cmd.name,
                    iconKey = null,
                    isServerCommand = true
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch available commands" }
            emptyList()
        }
    }

    suspend fun executeServerCommand(commandName: String, args: String = "") {
        val sessionId = sessionIdProvider() ?: return
        val client = clientProvider() ?: return
        try {
            client.executeCommand(sessionId, commandName, args)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to execute command: $commandName" }
        }
    }
}
