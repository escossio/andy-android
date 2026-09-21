package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AttentionRouterGmailConnectionClientTest {
    @Test
    fun statusUsesClientSessionAndParsesConnectedContract() = runBlocking {
        val transport = FakeGmailTransport(
            getResponse = response(200, connectedBody()),
        )
        val result = AttentionRouterGmailConnectionClient(
            "https://example.invalid",
            transport,
        ).getGmailConnection(session())

        assertTrue(result is GmailConnectionResult.Success)
        val connection = (result as GmailConnectionResult.Success).connection
        assertEquals(GmailConnectionStatus.CONNECTED, connection.status)
        assertEquals(setOf(GMAIL_METADATA_SCOPE), connection.grantedScopes)
        assertEquals(listOf("GET", "/api/v1/integrations/gmail", token()), transport.calls.single())
    }

    @Test
    fun connectSendsOnlyAuthorizationCodeUnderClientSession() = runBlocking {
        val transport = FakeGmailTransport(
            postResponse = response(200, connectedBody()),
        )
        val code = "code-" + "x".repeat(12)
        val result = AttentionRouterGmailConnectionClient(
            "https://example.invalid",
            transport,
        ).connectGmail(session(), code)

        assertTrue(result is GmailConnectionResult.Success)
        assertEquals(
            listOf(
                "POST",
                "/api/v1/integrations/gmail",
                token(),
                "{\"authorization_code\":\"$code\"}",
            ),
            transport.calls.single(),
        )
    }

    @Test
    fun disconnectParsesDisconnectedContract() = runBlocking {
        val transport = FakeGmailTransport(
            deleteResponse = response(200, disconnectedBody()),
        )
        val result = AttentionRouterGmailConnectionClient(
            "https://example.invalid",
            transport,
        ).disconnectGmail(session())

        assertEquals(
            GmailConnection(
                GmailConnectionStatus.DISCONNECTED,
                emptySet(),
            ),
            (result as GmailConnectionResult.Success).connection,
        )
    }

    @Test
    fun refreshTokenRequiredIsPreservedForExplicitConsentRetry() = runBlocking {
        val transport = FakeGmailTransport(
            postResponse = response(
                409,
                "{\"code\":\"GMAIL_REFRESH_TOKEN_REQUIRED\"}",
            ),
        )
        val result = AttentionRouterGmailConnectionClient(
            "https://example.invalid",
            transport,
        ).connectGmail(session(), "code-" + "x".repeat(12))

        assertEquals(
            GmailConnectionResult.Failure(
                GmailConnectionErrorCode.GMAIL_REFRESH_TOKEN_REQUIRED,
            ),
            result,
        )
    }

    @Test
    fun connectedContractWithoutMetadataScopeFailsClosed() = runBlocking {
        val transport = FakeGmailTransport(
            getResponse = response(
                200,
                """{"contract_version":"1","provider":"GOOGLE","product":"GMAIL","status":"CONNECTED","installation_id":"paz_synthetic","granted_scopes":[]}""",
            ),
        )
        val result = AttentionRouterGmailConnectionClient(
            "https://example.invalid",
            transport,
        ).getGmailConnection(session())

        assertEquals(
            GmailConnectionResult.Failure(
                GmailConnectionErrorCode.UNEXPECTED_RESPONSE,
            ),
            result,
        )
    }

    private fun token() = "cst_" + "t".repeat(43)

    private fun session() = ClientSessionCredential(
        token = token(),
        sessionId = "csn_" + "s".repeat(20),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        humanIdentityId = "hid_" + "h".repeat(20),
        deviceId = "cdev_" + "d".repeat(20),
        tenantId = "tenant-synthetic",
    )

    private fun connectedBody() =
        """{"contract_version":"1","provider":"GOOGLE","product":"GMAIL","status":"CONNECTED","installation_id":"paz_synthetic","granted_scopes":["$GMAIL_METADATA_SCOPE"]}"""

    private fun disconnectedBody() =
        """{"contract_version":"1","provider":"GOOGLE","product":"GMAIL","status":"DISCONNECTED","installation_id":null,"granted_scopes":[]}"""

    private fun response(status: Int, body: String) =
        GmailConnectionTransportResponse(status, body)

    private class FakeGmailTransport(
        private val getResponse: GmailConnectionTransportResponse =
            response(500, "{}"),
        private val postResponse: GmailConnectionTransportResponse =
            response(500, "{}"),
        private val deleteResponse: GmailConnectionTransportResponse =
            response(500, "{}"),
    ) : GmailConnectionTransport {
        val calls = mutableListOf<List<String>>()

        override suspend fun get(
            path: String,
            bearerToken: String,
        ): GmailConnectionTransportResponse {
            calls += listOf("GET", path, bearerToken)
            return getResponse
        }

        override suspend fun post(
            path: String,
            bearerToken: String,
            body: String,
        ): GmailConnectionTransportResponse {
            calls += listOf("POST", path, bearerToken, body)
            return postResponse
        }

        override suspend fun delete(
            path: String,
            bearerToken: String,
        ): GmailConnectionTransportResponse {
            calls += listOf("DELETE", path, bearerToken)
            return deleteResponse
        }
    }
}
