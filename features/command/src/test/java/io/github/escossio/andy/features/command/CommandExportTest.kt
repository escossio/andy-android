package io.github.escossio.andy.features.command

import io.github.escossio.andy.sdk.clientapi.ClientCommand
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExportTest {
    @Test
    fun emptyConversationHasNothingToExport() {
        assertNull(commandExportPayload(CommandState.Idle))
        assertNull(commandExportPayload(CommandState.Ready(emptyList())))
    }

    @Test
    fun exportMatchesTheEightRenderedTurnsAndOmitsInternalMetadata() {
        val commands = (1..9).map { index ->
            command(
                index = index,
                input = "mensagem $index",
                response = "resposta $index",
            )
        }

        val payload = commandExportPayload(CommandState.Ready(commands))

        requireNotNull(payload)
        assertEquals("Conversa com a Andy", payload.subject)
        assertFalse(payload.text.contains("mensagem 1"))
        assertFalse(payload.text.contains("resposta 1"))
        assertTrue(payload.text.contains("mensagem 2"))
        assertTrue(payload.text.contains("resposta 9"))
        assertFalse(payload.text.contains("cmd-interno-"))
        assertFalse(payload.text.contains("request-interno-"))
        assertFalse(payload.text.contains("INTERNAL_ACTION"))
        assertFalse(payload.text.contains("INTERNAL_ERROR"))
    }

    @Test
    fun pendingDraftIsNotExportedBeforeItBecomesAPersistedCommand() {
        val persisted = command(
            index = 1,
            input = "já enviado",
            response = "já respondido",
        )

        val payload = commandExportPayload(
            CommandState.Sending(
                commands = listOf(persisted),
                text = "ainda pendente",
            ),
        )

        requireNotNull(payload)
        assertTrue(payload.text.contains("já enviado"))
        assertFalse(payload.text.contains("ainda pendente"))
    }

    @Test
    fun blankAndyResponseIsNotInventedInTheExport() {
        val payload = commandExportPayload(
            CommandState.Ready(
                listOf(
                    command(
                        index = 1,
                        input = "sem resposta",
                        response = "   ",
                    ),
                ),
            ),
        )

        requireNotNull(payload)
        assertEquals(
            "Conversa com a Andy\n\nVocê: sem resposta",
            payload.text,
        )
    }

    private fun command(
        index: Int,
        input: String,
        response: String?,
    ) = ClientCommand(
        commandId = "cmd-interno-$index",
        clientRequestId = "request-interno-$index",
        modality = "TEXT",
        inputText = input,
        state = "COMPLETED",
        normalizedAction = "INTERNAL_ACTION",
        responseText = response,
        errorCode = "INTERNAL_ERROR",
        createdAt = Instant.parse("2026-10-04T13:00:00Z").plusSeconds(index.toLong()),
        processedAt = Instant.parse("2026-10-04T13:00:30Z").plusSeconds(index.toLong()),
    )
}
