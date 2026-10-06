package io.github.escossio.andy.features.command

data class CommandExportPayload(
    val subject: String,
    val text: String,
)

fun commandExportPayload(state: CommandState): CommandExportPayload? {
    val commands = commandsOf(state).takeLast(MAX_EXPORTED_COMMANDS)
    if (commands.isEmpty()) return null

    val text = buildString {
        append(EXPORT_SUBJECT)
        commands.forEach { command ->
            append("\n\nVocê: ")
            append(command.inputText)
            command.responseText
                ?.takeIf { it.isNotBlank() }
                ?.let { response ->
                    append("\n\nAndy: ")
                    append(response)
                }
        }
    }

    return CommandExportPayload(
        subject = EXPORT_SUBJECT,
        text = text,
    )
}

private fun commandsOf(state: CommandState) =
    when (state) {
        CommandState.Idle -> emptyList()
        is CommandState.Loading -> state.previous
        is CommandState.Ready -> state.commands
        is CommandState.Sending -> state.commands
        is CommandState.Failure -> state.commands
    }

private const val MAX_EXPORTED_COMMANDS = 8
private const val EXPORT_SUBJECT = "Conversa com a Andy"
