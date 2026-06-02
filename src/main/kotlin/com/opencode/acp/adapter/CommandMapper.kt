package com.opencode.acp.adapter

import com.agentclientprotocol.model.AvailableCommand

/**
 * Maps OpenCode [CommandInfo] instances to ACP SDK [AvailableCommand] instances.
 */
class CommandMapper {

    /**
     * Converts a list of OpenCode [CommandInfo] to ACP [AvailableCommand] instances.
     * The ACP [AvailableCommand] type provides name and description fields
     * suitable for the available commands update mechanism.
     */
    fun toAvailableCommands(openCodeCommands: List<CommandInfo>): List<AvailableCommand> =
        openCodeCommands.map { command ->
            AvailableCommand(
                name = command.name,
                description = command.description ?: ""
            )
        }

    /**
     * Converts a single [CommandInfo] to [AvailableCommand].
     */
    fun toAvailableCommand(command: CommandInfo): AvailableCommand = AvailableCommand(
        name = command.name,
        description = command.description ?: ""
    )
}
