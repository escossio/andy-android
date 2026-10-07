package io.github.escossio.andy.sdk.clientapi

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionRouterPersonalContextBootstrapClientTest {
    private val token = "cst_" + "a".repeat(43)
    private val selectionId = "pbs_" + "b".repeat(32)
    private val session = ClientSessionCredential(
        token = token,
        sessionId = "csn_" + "c".repeat(20),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        humanIdentityId = "hid_" + "d".repeat(20),
        deviceId = "cdev_" + "e".repeat(20),
        tenantId = "synthetic-tenant",
    )

    @Test
    fun pendingUsesSessionBearerAndOnlyGetsTheSanitizedSelection() = runBlocking {
        var requestCount = 0
        val client = client { chain ->
            requestCount++
            assertEquals("GET", chain.request().method)
            assertEquals(
                "/api/v1/personal-context/bootstrap/selections/pending",
                chain.request().url.encodedPath,
            )
            assertEquals("Bearer $token", chain.request().header("Authorization"))
            reply(chain, 200, pendingBody())
        }

        val result = client.pending(session)

        assertEquals(1, requestCount)
        assertTrue(result is PersonalContextBootstrapSelectionResult.Success)
        val selection = (result as PersonalContextBootstrapSelectionResult.Success).selection!!
        assertEquals(selectionId, selection.selectionId)
        assertEquals(5, selection.chats.size)
        assertEquals(1, selection.chats.count { it.threadType == "GROUP" })
        assertFalse(selection.toString().contains(token))
    }

    @Test
    fun confirmSendsExactBudgetAndConsentOnlyAfterExplicitCall() = runBlocking {
        var postCount = 0
        val client = client { chain ->
            postCount++
            assertEquals("POST", chain.request().method)
            assertEquals(
                "/api/v1/personal-context/bootstrap/selections/$selectionId/confirm",
                chain.request().url.encodedPath,
            )
            assertEquals("Bearer $token", chain.request().header("Authorization"))
            val body = Buffer().use { buffer ->
                chain.request().body!!.writeTo(buffer)
                Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            }
            assertEquals(
                setOf("consent_ref", "processing_budget"), body.keys,
            )
            assertEquals(
                "owner-explicit-whatsapp-bootstrap-20261007-selected-4-13-17-23-25",
                body["consent_ref"].toString().trim('"'),
            )
            val budget = body["processing_budget"]!!.jsonObject
            assertEquals("50", budget["page_size"].toString())
            assertEquals("1000", budget["max_messages_per_chat"].toString())
            assertEquals("5000", budget["max_total_messages"].toString())
            reply(
                chain, 200,
                """{"contract_version":"1","run_id":"123e4567-e89b-12d3-a456-426614174000","source_kind":"WHATSAPP_TEXT","state":"QUEUED"}""",
            )
        }

        assertEquals(0, postCount)
        val result = client.confirm(session, selectionId)
        assertEquals(1, postCount)
        assertEquals(
            "123e4567-e89b-12d3-a456-426614174000",
            (result as PersonalContextBootstrapConfirmResult.Success).run.runId,
        )
    }

    @Test
    fun missingSessionAndInvalidSelectionNeverReachNetwork() = runBlocking {
        val client = client { error("network must not be reached") }
        assertEquals(
            PersonalContextBootstrapSelectionResult.Failure(
                PersonalContextBootstrapError.SESSION_REQUIRED,
            ), client.pending(null),
        )
        assertEquals(
            PersonalContextBootstrapConfirmResult.Failure(
                PersonalContextBootstrapError.SESSION_REQUIRED,
            ), client.confirm(null, selectionId),
        )
        assertEquals(
            PersonalContextBootstrapConfirmResult.Failure(
                PersonalContextBootstrapError.BOOTSTRAP_SELECTION_NOT_FOUND,
            ), client.confirm(session, "../escape"),
        )
    }

    @Test
    fun mapsAuthorityConflictAndAvailabilityErrorsWithoutTokenDisclosure() = runBlocking {
        val cases = listOf(
            Triple(401, "CLIENT_SESSION_UNAUTHENTICATED",
                PersonalContextBootstrapError.CLIENT_SESSION_UNAUTHENTICATED),
            Triple(403, "PERSONAL_CONTEXT_BOOTSTRAP_TENANT_FORBIDDEN",
                PersonalContextBootstrapError.PERSONAL_CONTEXT_BOOTSTRAP_TENANT_FORBIDDEN),
            Triple(409, "BOOTSTRAP_SELECTION_EXPIRED",
                PersonalContextBootstrapError.BOOTSTRAP_SELECTION_EXPIRED),
            Triple(503, "PERSONAL_CONTEXT_BOOTSTRAP_DISABLED",
                PersonalContextBootstrapError.PERSONAL_CONTEXT_BOOTSTRAP_DISABLED),
        )
        cases.forEach { (status, code, expected) ->
            val client = client { chain ->
                reply(chain, status, """{"code":"$code"}""")
            }
            val result = client.confirm(session, selectionId)
            assertEquals(PersonalContextBootstrapConfirmResult.Failure(expected), result)
            assertFalse(result.toString().contains(token))
        }
        assertFalse(session.toString().contains(token))
    }

    @Test
    fun malformedOrOverlargeResponseFailsClosed() = runBlocking {
        val malformed = client { chain ->
            reply(chain, 200, pendingBody().replace("\"display_name\"", "\"external_thread_key\""))
        }
        assertEquals(
            PersonalContextBootstrapSelectionResult.Failure(
                PersonalContextBootstrapError.UNEXPECTED_RESPONSE,
            ), malformed.pending(session),
        )
        val oversized = client { chain -> reply(chain, 200, "x".repeat(17_000)) }
        assertEquals(
            PersonalContextBootstrapSelectionResult.Failure(
                PersonalContextBootstrapError.UNEXPECTED_RESPONSE,
            ), oversized.pending(session),
        )
    }

    private fun pendingBody(): String {
        val chats = (1..5).joinToString(",") { index ->
            """{"index":$index,"display_name":"Synthetic $index","thread_type":"${if (index == 1) "GROUP" else "DIRECT"}"}"""
        }
        return """{"contract_version":"1","selection":{"selection_id":"$selectionId","expires_at":"2030-01-01T00:00:00Z","chats":[$chats]}}"""
    }

    private fun client(interceptor: (Interceptor.Chain) -> Response) =
        AttentionRouterPersonalContextBootstrapClient(
            "https://synthetic.invalid",
            OkHttpClient.Builder().addInterceptor { chain -> interceptor(chain) }.build(),
        )

    private fun reply(chain: Interceptor.Chain, status: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("synthetic")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
}
