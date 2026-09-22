package io.github.escossio.andy.features.command

import io.github.escossio.andy.sdk.clientapi.ClientCommand
import io.github.escossio.andy.sdk.clientapi.ClientCommandClient
import io.github.escossio.andy.sdk.clientapi.ClientCommandErrorCode
import io.github.escossio.andy.sdk.clientapi.ClientCommandListResult
import io.github.escossio.andy.sdk.clientapi.ClientCommandResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CommandCoordinatorTest {
    private val session = ClientSessionCredential(
        token = "cst_" + "a".repeat(43),
        sessionId = "csn_" + "b".repeat(20),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        humanIdentityId = "hid_" + "c".repeat(20),
        deviceId = "cdev_" + "d".repeat(20),
        tenantId = "tenant-synthetic",
    )

    @Test
    fun submitUsesServerAuthoritativeCommandResult() = runBlocking {
        val client = FakeClient()
        val coordinator = CommandCoordinator(
            client = client,
            sessionProvider = { session },
            requestIdFactory = { "req_fixed" },
        )

        coordinator.submit("pare")

        assertEquals(
            listOf(Triple("req_fixed", "pare", session)),
            client.submissions,
        )
        val state = coordinator.state.value as CommandState.Ready
        assertEquals("Andy pausada.", state.commands.single().responseText)
        assertEquals("COMPLETED", state.commands.single().state)
    }

    @Test
    fun refreshRestoresTimeline() = runBlocking {
        val client = FakeClient()
        client.timeline = listOf(command("retome", "Andy retomada."))
        val coordinator = CommandCoordinator(
            client = client,
            sessionProvider = { session },
        )

        coordinator.refresh()

        val state = coordinator.state.value as CommandState.Ready
        assertEquals(listOf("retome"), state.commands.map { it.inputText })
    }

    @Test
    fun missingSessionFailsClosed() = runBlocking {
        val coordinator = CommandCoordinator(
            client = FakeClient(),
            sessionProvider = { null },
        )

        coordinator.submit("pare")

        assertEquals(
            CommandFailure.CLIENT_SESSION_UNAUTHENTICATED,
            (coordinator.state.value as CommandState.Failure).reason,
        )
    }

    @Test
    fun serverConflictKeepsPriorTimeline() = runBlocking {
        val client = FakeClient()
        client.timeline = listOf(command("pare", "Andy pausada."))
        val coordinator = CommandCoordinator(
            client = client,
            sessionProvider = { session },
        )
        coordinator.refresh()
        client.submitFailure = ClientCommandErrorCode.CLIENT_COMMAND_CONFLICT

        coordinator.submit("retome")

        val state = coordinator.state.value as CommandState.Failure
        assertEquals(CommandFailure.CLIENT_COMMAND_CONFLICT, state.reason)
        assertEquals(listOf("pare"), state.commands.map { it.inputText })
    }

    private fun command(
        text: String,
        response: String,
    ) = ClientCommand(
        commandId = "cmd_" + text,
        clientRequestId = "req_" + text,
        modality = "TEXT",
        inputText = text,
        state = "COMPLETED",
        normalizedAction = "SET_AUTOMATIC_RESPONSES_ENABLED",
        responseText = response,
        errorCode = null,
        createdAt = Instant.parse("2026-09-22T10:30:00Z"),
        processedAt = Instant.parse("2026-09-22T10:30:01Z"),
    )

    private class FakeClient : ClientCommandClient {
        var timeline: List<ClientCommand> = emptyList()
        var submitFailure: ClientCommandErrorCode? = null
        val submissions =
            mutableListOf<Triple<String, String, ClientSessionCredential>>()

        override suspend fun submitText(
            session: ClientSessionCredential,
            clientRequestId: String,
            text: String,
        ): ClientCommandResult {
            submissions += Triple(clientRequestId, text, session)
            submitFailure?.let {
                return ClientCommandResult.Failure(it)
            }
            val result = ClientCommand(
                commandId = "cmd_result",
                clientRequestId = clientRequestId,
                modality = "TEXT",
                inputText = text,
                state = "COMPLETED",
                normalizedAction = "SET_AUTOMATIC_RESPONSES_ENABLED",
                responseText = "Andy pausada.",
                errorCode = null,
                createdAt = Instant.parse("2026-09-22T10:30:00Z"),
                processedAt = Instant.parse("2026-09-22T10:30:01Z"),
            )
            timeline = timeline + result
            return ClientCommandResult.Success(result)
        }

        override suspend fun listRecent(
            session: ClientSessionCredential,
            limit: Int,
        ): ClientCommandListResult =
            ClientCommandListResult.Success(timeline)
    }
}
