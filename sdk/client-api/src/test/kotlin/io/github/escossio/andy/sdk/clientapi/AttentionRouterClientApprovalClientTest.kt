package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AttentionRouterClientApprovalClientTest {
    private val session = ClientSessionCredential(
        token = "cst_" + "a".repeat(43),
        sessionId = "csn_" + "b".repeat(20),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        humanIdentityId = "hid_" + "c".repeat(20),
        deviceId = "cdev_" + "d".repeat(20),
        tenantId = "tenant-synthetic",
    )

    @Test
    fun listPendingUsesClientSessionBearerAndParsesContract() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                assertEquals(
                    "/api/v1/client/approvals/pending",
                    chain.request().url.encodedPath,
                )
                assertEquals(
                    "Bearer " + "cst_" + "a".repeat(43),
                    chain.request().header("Authorization"),
                )
                response(
                    chain,
                    200,
                    """{"contract_version":"1","approvals":[{"approval_id":"apr_1","state":"PENDING_HUMAN_APPROVAL","capability":"conversation.reply","operation":"conversation.reply","target":"synthetic-target","preview":"Mensagem proposta","issued_at":"2026-09-22T06:45:00Z","expires_at":"2026-09-22T06:50:00Z"}]}""",
                )
            }
            .build()
        val client = AttentionRouterClientApprovalClient(
            "https://synthetic.invalid",
            http,
        )

        val result = client.listPending(session)

        assertTrue(result is ClientApprovalListResult.Success)
        val approval = (result as ClientApprovalListResult.Success).approvals.single()
        assertEquals("apr_1", approval.approvalId)
        assertEquals("conversation.reply", approval.capability)
    }

    @Test
    fun decideSendsDecisionOnly() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                assertEquals(
                    "/api/v1/client/approvals/apr_1/decision",
                    chain.request().url.encodedPath,
                )
                assertEquals("POST", chain.request().method)
                val body = chain.request().body!!.let { requestBody ->
                    okio.Buffer().use { buffer ->
                        requestBody.writeTo(buffer)
                        buffer.readUtf8()
                    }
                }
                assertEquals("""{"decision":"APPROVE"}""", body)
                response(
                    chain,
                    200,
                    """{"approval_id":"apr_1","state":"APPROVED","capability":"conversation.reply","operation":"conversation.reply","target":"synthetic-target","preview":"Mensagem proposta","issued_at":"2026-09-22T06:45:00Z","expires_at":"2026-09-22T06:50:00Z"}""",
                )
            }
            .build()
        val client = AttentionRouterClientApprovalClient(
            "https://synthetic.invalid",
            http,
        )

        val result = client.decide(
            session,
            "apr_1",
            ClientApprovalDecision.APPROVE,
        )

        assertTrue(result is ClientApprovalResult.Success)
        assertEquals(
            "APPROVED",
            (result as ClientApprovalResult.Success).approval.state,
        )
    }

    @Test
    fun invalidApprovalIdFailsBeforeNetwork() = runBlocking {
        val client = AttentionRouterClientApprovalClient(
            "https://synthetic.invalid",
            OkHttpClient.Builder()
                .addInterceptor {
                    error("network must not be reached")
                }
                .build(),
        )

        assertEquals(
            ClientApprovalResult.Failure(
                ClientApprovalErrorCode.INVALID_APPROVAL_ID,
            ),
            client.get(session, "../escape"),
        )
    }

    @Test
    fun boundedServerErrorIsMapped() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                response(
                    chain,
                    409,
                    """{"code":"CLIENT_APPROVAL_CONFLICT"}""",
                )
            }
            .build()
        val client = AttentionRouterClientApprovalClient(
            "https://synthetic.invalid",
            http,
        )

        assertEquals(
            ClientApprovalResult.Failure(
                ClientApprovalErrorCode.CLIENT_APPROVAL_CONFLICT,
            ),
            client.decide(
                session,
                "apr_1",
                ClientApprovalDecision.DENY,
            ),
        )
    }

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
