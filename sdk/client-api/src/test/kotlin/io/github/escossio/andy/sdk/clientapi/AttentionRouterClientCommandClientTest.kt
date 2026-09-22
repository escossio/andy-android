package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AttentionRouterClientCommandClientTest {
    private val session = ClientSessionCredential(
        token = "cst_" + "a".repeat(43),
        sessionId = "csn_" + "b".repeat(20),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        humanIdentityId = "hid_" + "c".repeat(20),
        deviceId = "cdev_" + "d".repeat(20),
        tenantId = "tenant-synthetic",
    )

    @Test
    fun submitUsesBearerAndSendsOnlyRequestIdAndText() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                assertEquals(
                    "/api/v1/client/commands",
                    chain.request().url.encodedPath,
                )
                assertEquals(
                    "Bearer " + "cst_" + "a".repeat(43),
                    chain.request().header("Authorization"),
                )
                assertEquals("POST", chain.request().method)
                val body = Buffer().use { buffer ->
                    chain.request().body!!.writeTo(buffer)
                    buffer.readUtf8()
                }
                assertEquals(
                    """{"client_request_id":"req_1","text":"pare"}""",
                    body,
                )
                response(chain, 200, commandJson())
            }
            .build()
        val client = AttentionRouterClientCommandClient(
            "https://synthetic.invalid",
            http,
        )

        val result = client.submitText(session, "req_1", "pare")

        assertTrue(result is ClientCommandResult.Success)
        val command = (result as ClientCommandResult.Success).command
        assertEquals("COMPLETED", command.state)
        assertEquals(
            "SET_AUTOMATIC_RESPONSES_ENABLED",
            command.normalizedAction,
        )
    }

    @Test
    fun listParsesConversationTimeline() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                assertEquals(
                    "/api/v1/client/commands",
                    chain.request().url.encodedPath,
                )
                assertEquals("50", chain.request().url.queryParameter("limit"))
                response(
                    chain,
                    200,
                    """{"contract_version":"1","commands":[""" +
                        commandJson() +
                        "]}",
                )
            }
            .build()
        val client = AttentionRouterClientCommandClient(
            "https://synthetic.invalid",
            http,
        )

        val result = client.listRecent(session)

        assertTrue(result is ClientCommandListResult.Success)
        assertEquals(
            listOf("pare"),
            (result as ClientCommandListResult.Success).commands.map {
                it.inputText
            },
        )
    }

    @Test
    fun conflictIsBounded() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                response(
                    chain,
                    409,
                    """{"code":"CLIENT_COMMAND_CONFLICT"}""",
                )
            }
            .build()
        val client = AttentionRouterClientCommandClient(
            "https://synthetic.invalid",
            http,
        )

        assertEquals(
            ClientCommandResult.Failure(
                ClientCommandErrorCode.CLIENT_COMMAND_CONFLICT,
            ),
            client.submitText(session, "req_1", "pare"),
        )
    }

    @Test
    fun invalidRequestNeverTouchesNetwork() = runBlocking {
        val client = AttentionRouterClientCommandClient(
            "https://synthetic.invalid",
            OkHttpClient.Builder()
                .addInterceptor { error("network must not be reached") }
                .build(),
        )

        assertEquals(
            ClientCommandResult.Failure(
                ClientCommandErrorCode.CLIENT_COMMAND_INVALID,
            ),
            client.submitText(session, "../escape", "pare"),
        )
    }

    private fun commandJson(): String =
        """{"command_id":"cmd_1","client_request_id":"req_1","modality":"TEXT","input_text":"pare","state":"COMPLETED","normalized_action":"SET_AUTOMATIC_RESPONSES_ENABLED","response_text":"Andy pausada.","error_code":null,"created_at":"2026-09-22T10:30:00Z","processed_at":"2026-09-22T10:30:00Z"}"""

    private fun response(
        chain: Interceptor.Chain,
        status: Int,
        body: String,
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("synthetic")
        .body(
            body.toResponseBody(
                "application/json; charset=utf-8".toMediaType(),
            ),
        )
        .build()
}
