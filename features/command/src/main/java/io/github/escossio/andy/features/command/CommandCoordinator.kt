package io.github.escossio.andy.features.command

import io.github.escossio.andy.sdk.clientapi.ClientCommand
import io.github.escossio.andy.sdk.clientapi.ClientCommandClient
import io.github.escossio.andy.sdk.clientapi.ClientCommandErrorCode
import io.github.escossio.andy.sdk.clientapi.ClientCommandListResult
import io.github.escossio.andy.sdk.clientapi.ClientCommandResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class CommandFailure {
    NETWORK_FAILURE,
    CLIENT_COMMAND_DISABLED,
    CLIENT_COMMAND_AUTHORITY_REJECTED,
    CLIENT_COMMAND_CONFLICT,
    CLIENT_COMMAND_INVALID,
    CLIENT_COMMAND_UNAVAILABLE,
    CLIENT_SESSION_UNAUTHENTICATED,
    CLIENT_SESSION_AUTHORITY_REJECTED,
    CLIENT_SESSION_UNAVAILABLE,
    UNEXPECTED_RESPONSE,
}

sealed interface CommandState {
    data object Idle : CommandState
    data class Loading(val previous: List<ClientCommand>) : CommandState
    data class Ready(val commands: List<ClientCommand>) : CommandState
    data class Sending(
        val commands: List<ClientCommand>,
        val text: String,
    ) : CommandState
    data class Failure(
        val commands: List<ClientCommand>,
        val reason: CommandFailure,
    ) : CommandState
}

class CommandCoordinator(
    private val client: ClientCommandClient,
    private val sessionProvider: suspend () -> ClientSessionCredential?,
    private val requestIdFactory: () -> String = {
        "cmd_" + UUID.randomUUID().toString()
    },
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<CommandState>(CommandState.Idle)
    val state: StateFlow<CommandState> = mutable.asStateFlow()

    fun reset() {
        mutable.value = CommandState.Idle
    }

    suspend fun refresh() {
        mutex.withLock {
            val previous = commandsOf(mutable.value)
            val session = sessionProvider()
            if (session == null) {
                mutable.value = CommandState.Failure(
                    previous,
                    CommandFailure.CLIENT_SESSION_UNAUTHENTICATED,
                )
                return@withLock
            }
            mutable.value = CommandState.Loading(previous)
            when (val result = client.listRecent(session)) {
                is ClientCommandListResult.Success ->
                    mutable.value = CommandState.Ready(result.commands)
                is ClientCommandListResult.Failure ->
                    mutable.value = CommandState.Failure(
                        previous,
                        result.error.failure(),
                    )
            }
        }
    }

    suspend fun submit(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.length > 4000) {
            mutable.value = CommandState.Failure(
                commandsOf(mutable.value),
                CommandFailure.CLIENT_COMMAND_INVALID,
            )
            return
        }

        mutex.withLock {
            val previous = commandsOf(mutable.value)
            val session = sessionProvider()
            if (session == null) {
                mutable.value = CommandState.Failure(
                    previous,
                    CommandFailure.CLIENT_SESSION_UNAUTHENTICATED,
                )
                return@withLock
            }

            mutable.value = CommandState.Sending(previous, normalized)
            when (
                val result = client.submitText(
                    session,
                    requestIdFactory(),
                    normalized,
                )
            ) {
                is ClientCommandResult.Failure ->
                    mutable.value = CommandState.Failure(
                        previous,
                        result.error.failure(),
                    )
                is ClientCommandResult.Success -> {
                    val merged = (
                        previous.filterNot {
                            it.commandId == result.command.commandId
                        } + result.command
                    ).sortedBy { it.createdAt }
                    mutable.value = CommandState.Ready(merged)
                }
            }
        }
    }

    private fun commandsOf(state: CommandState): List<ClientCommand> =
        when (state) {
            CommandState.Idle -> emptyList()
            is CommandState.Loading -> state.previous
            is CommandState.Ready -> state.commands
            is CommandState.Sending -> state.commands
            is CommandState.Failure -> state.commands
        }

    private fun ClientCommandErrorCode.failure(): CommandFailure =
        when (this) {
            ClientCommandErrorCode.NETWORK_FAILURE ->
                CommandFailure.NETWORK_FAILURE
            ClientCommandErrorCode.CLIENT_COMMAND_DISABLED ->
                CommandFailure.CLIENT_COMMAND_DISABLED
            ClientCommandErrorCode.CLIENT_COMMAND_AUTHORITY_REJECTED ->
                CommandFailure.CLIENT_COMMAND_AUTHORITY_REJECTED
            ClientCommandErrorCode.CLIENT_COMMAND_CONFLICT ->
                CommandFailure.CLIENT_COMMAND_CONFLICT
            ClientCommandErrorCode.CLIENT_COMMAND_INVALID ->
                CommandFailure.CLIENT_COMMAND_INVALID
            ClientCommandErrorCode.CLIENT_COMMAND_UNAVAILABLE ->
                CommandFailure.CLIENT_COMMAND_UNAVAILABLE
            ClientCommandErrorCode.CLIENT_SESSION_UNAUTHENTICATED ->
                CommandFailure.CLIENT_SESSION_UNAUTHENTICATED
            ClientCommandErrorCode.CLIENT_SESSION_AUTHORITY_REJECTED ->
                CommandFailure.CLIENT_SESSION_AUTHORITY_REJECTED
            ClientCommandErrorCode.CLIENT_SESSION_UNAVAILABLE ->
                CommandFailure.CLIENT_SESSION_UNAVAILABLE
            ClientCommandErrorCode.UNEXPECTED_RESPONSE ->
                CommandFailure.UNEXPECTED_RESPONSE
        }
}
