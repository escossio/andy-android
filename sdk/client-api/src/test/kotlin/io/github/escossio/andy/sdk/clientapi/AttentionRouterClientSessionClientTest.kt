package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class AttentionRouterClientSessionClientTest {
    @Test
    fun startUsesOnlySpkiAndOptionalTenantAssertion() = runBlocking {
        var requestedPath = ""
        var requestedBody = ""
        val transport = object : ClientSessionTransport {
            override suspend fun post(path: String, body: String): ClientSessionTransportResponse {
                requestedPath = path
                requestedBody = body
                return ClientSessionTransportResponse(201, challengeJson())
            }

            override suspend fun get(
                path: String,
                bearerToken: String,
            ) = failTransport()
        }

        val result = client(transport).start(PUBLIC_KEY, "tnt_synthetic")

        assertEquals("/api/v1/session/device/challenges", requestedPath)
        assertEquals(
            "{"public_key_spki_b64url":"$PUBLIC_KEY","requested_tenant_id":"tnt_synthetic"}",
            requestedBody,
        )
        assertEquals(
            ClientSessionChallengeResult.Success(
                ClientSessionChallenge(
                    CHALLENGE_ID,
                    "b".repeat(43),
                    Instant.parse("2030-01-01T00:05:00Z"),
                ),
            ),
            result,
        )
    }

    @Test
    fun completeParsesCredentialAndRedactsRawToken() = runBlocking {
        val transport = object : ClientSessionTransport {
            override suspend fun post(path: String, body: String): ClientSessionTransportResponse {
                assertEquals(
                    "/api/v1/session/device/challenges/$CHALLENGE_ID/complete",
                    path,
                )
                assertEquals("{"device_signature_b64url":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}", body)
                return ClientSessionTransportResponse(200, sessionJson())
            }

            override suspend fun get(
                path: String,
                bearerToken: String,
            ) = failTransport()
        }

        val result = client(transport).complete(CHALLENGE_ID, "c".repeat(96))
        assertTrue(result is ClientSessionCompleteResult.Success)
        val credential = (result as ClientSessionCompleteResult.Success).session
        assertEquals(SESSION_ID, credential.sessionId)
        assertEquals(HUMAN_ID, credential.humanIdentityId)
        assertEquals(DEVICE_ID, credential.deviceId)
        assertEquals("tnt_synthetic", credential.tenantId)
        assertEquals(SESSION_TOKEN, credential.withToken { it })
        assertFalse(credential.toString().contains(SESSION_TOKEN))
    }

    @Test
    fun authenticatedBootstrapUsesBearerOnlyAndReturnsNoCredential() = runBlocking {
        var observedPath = ""
        var observedBearer = ""
        val transport = object : ClientSessionTransport {
            override suspend fun post(
                path: String,
                body: String,
            ) = failTransport()

            override suspend fun get(
                path: String,
                bearerToken: String,
            ): ClientSessionTransportResponse {
                observedPath = path
                observedBearer = bearerToken
                return ClientSessionTransportResponse(200, bootstrapJson())
            }
        }
        val credential = credential()

        val result = client(transport).bootstrap(credential)

        assertEquals("/api/v1/client/bootstrap", observedPath)
        assertEquals(SESSION_TOKEN, observedBearer)
        assertTrue(result is AuthenticatedBootstrapResult.Success)
        val bootstrap = (result as AuthenticatedBootstrapResult.Success).bootstrap
        assertEquals(HUMAN_ID, bootstrap.humanIdentityId)
        assertEquals("tnt_synthetic", bootstrap.activeTenantId)
        assertEquals(DEVICE_ID, bootstrap.device.deviceId)
        assertEquals(1, bootstrap.memberships.size)
        assertFalse(bootstrap.toString().contains(SESSION_TOKEN))
    }

    @Test
    fun malformedSessionResponsesFailClosed() = runBlocking {
        val malformed = listOf(
            sessionJson().replace(""token_type":"Bearer"", ""token_type":"Basic""),
            sessionJson().replace(SESSION_TOKEN, "cst_bad"),
            sessionJson().replace(
                ""tenant_id":"tnt_synthetic"",
                ""tenant_id":"tnt_synthetic","extra":true",
            ),
            sessionJson().replace(""session_id":"$SESSION_ID"", ""session_id":123"),
        )
        for (body in malformed) {
            val result = client(response = ClientSessionTransportResponse(200, body))
                .complete(CHALLENGE_ID, "c".repeat(96))
            assertEquals(
                ClientSessionCompleteResult.Failure(
                    ClientSessionErrorCode.UNEXPECTED_RESPONSE,
                ),
                result,
            )
        }
    }

    @Test
    fun bootstrapRejectsActiveTenantOutsideMemberships() = runBlocking {
        val body = bootstrapJson().replace(
            ""active_tenant_id":"tnt_synthetic"",
            ""active_tenant_id":"tnt_other"",
        )
        val result = client(
            getResponse = ClientSessionTransportResponse(200, body),
        ).bootstrap(credential())

        assertEquals(
            AuthenticatedBootstrapResult.Failure(
                ClientSessionErrorCode.UNEXPECTED_RESPONSE,
            ),
            result,
        )
    }

    @Test
    fun everyOfficialSessionErrorIsMappedLiterally() = runBlocking {
        val mapping = mapOf(
            "CLIENT_SESSION_DEVICE_REJECTED" to
                ClientSessionErrorCode.CLIENT_SESSION_DEVICE_REJECTED,
            "CLIENT_SESSION_CHALLENGE_NOT_FOUND" to
                ClientSessionErrorCode.CLIENT_SESSION_CHALLENGE_NOT_FOUND,
            "CLIENT_SESSION_CHALLENGE_EXPIRED" to
                ClientSessionErrorCode.CLIENT_SESSION_CHALLENGE_EXPIRED,
            "CLIENT_SESSION_CHALLENGE_CONSUMED" to
                ClientSessionErrorCode.CLIENT_SESSION_CHALLENGE_CONSUMED,
            "CLIENT_SESSION_SIGNATURE_INVALID" to
                ClientSessionErrorCode.CLIENT_SESSION_SIGNATURE_INVALID,
            "CLIENT_SESSION_ACTIVE_TENANT_REQUIRED" to
                ClientSessionErrorCode.CLIENT_SESSION_ACTIVE_TENANT_REQUIRED,
            "CLIENT_SESSION_TENANT_FORBIDDEN" to
                ClientSessionErrorCode.CLIENT_SESSION_TENANT_FORBIDDEN,
            "CLIENT_SESSION_UNAUTHENTICATED" to
                ClientSessionErrorCode.CLIENT_SESSION_UNAUTHENTICATED,
            "CLIENT_SESSION_AUTHORITY_REJECTED" to
                ClientSessionErrorCode.CLIENT_SESSION_AUTHORITY_REJECTED,
            "CLIENT_SESSION_UNAVAILABLE" to
                ClientSessionErrorCode.CLIENT_SESSION_UNAVAILABLE,
        )
        for ((wire, local) in mapping) {
            val response = ClientSessionTransportResponse(
                400,
                "{"code":"$wire"}",
            )
            assertEquals(
                ClientSessionChallengeResult.Failure(local),
                client(response = response).start(PUBLIC_KEY),
            )
        }
    }

    @Test
    fun networkFailuresAreNotAcceptedFromWireVocabulary() = runBlocking {
        val failing = AttentionRouterClientSessionClient(
            "https://synthetic.invalid",
            object : ClientSessionTransport {
                override suspend fun post(
                    path: String,
                    body: String,
                ): ClientSessionTransportResponse = throw IllegalStateException("synthetic")

                override suspend fun get(
                    path: String,
                    bearerToken: String,
                ): ClientSessionTransportResponse = throw IllegalStateException("synthetic")
            },
        )
        assertEquals(
            ClientSessionChallengeResult.Failure(ClientSessionErrorCode.NETWORK_FAILURE),
            failing.start(PUBLIC_KEY),
        )
        assertEquals(
            ClientSessionChallengeResult.Failure(ClientSessionErrorCode.UNEXPECTED_RESPONSE),
            client(
                response = ClientSessionTransportResponse(
                    400,
                    "{"code":"NETWORK_FAILURE"}",
                ),
            ).start(PUBLIC_KEY),
        )
    }

    @Test
    fun httpBaseUrlIsRejectedBeforeConnection() = runBlocking {
        val transport = OkHttpClientSessionTransport("http://synthetic.invalid")
        try {
            transport.post("/synthetic", "{}")
            fail("HTTP base URL must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            transport.get("/synthetic", SESSION_TOKEN)
            fail("HTTP base URL must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun client(
        transport: ClientSessionTransport,
    ) = AttentionRouterClientSessionClient("https://synthetic.invalid", transport)

    private fun client(
        response: ClientSessionTransportResponse =
            ClientSessionTransportResponse(201, challengeJson()),
        getResponse: ClientSessionTransportResponse =
            ClientSessionTransportResponse(200, bootstrapJson()),
    ) = client(
        object : ClientSessionTransport {
            override suspend fun post(
                path: String,
                body: String,
            ) = response

            override suspend fun get(
                path: String,
                bearerToken: String,
            ) = getResponse
        },
    )

    private fun credential() = ClientSessionCredential(
        token = SESSION_TOKEN,
        sessionId = SESSION_ID,
        expiresAt = Instant.parse("2030-01-01T00:15:00Z"),
        humanIdentityId = HUMAN_ID,
        deviceId = DEVICE_ID,
        tenantId = "tnt_synthetic",
    )

    private fun challengeJson() =
        "{"session_challenge_id":"$CHALLENGE_ID","challenge_b64url":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","expires_at":"2030-01-01T00:05:00Z"}"

    private fun sessionJson() =
        "{"status":"CLIENT_SESSION_ESTABLISHED","session":{"session_id":"$SESSION_ID","session_token":"$SESSION_TOKEN","token_type":"Bearer","expires_at":"2030-01-01T00:15:00Z","human_identity_id":"$HUMAN_ID","device_id":"$DEVICE_ID","tenant_id":"tnt_synthetic"}}"

    private fun bootstrapJson() =
        "{"contract_version":"1","human_identity_id":"$HUMAN_ID","active_tenant_id":"tnt_synthetic","memberships":[{"membership_id":"ctm_synthetic","tenant_id":"tnt_synthetic","role":"OWNER","status":"ACTIVE"}],"device":{"device_id":"$DEVICE_ID","public_key_fingerprint":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","canonical_name":"Synthetic Android","platform":"ANDROID","roles":["CLIENT","CAPABILITY_NODE"],"status":"ACTIVE"},"session_expires_at":"2030-01-01T00:15:00Z","server_time":"2030-01-01T00:01:00Z"}"

    private fun failTransport(): ClientSessionTransportResponse =
        throw AssertionError("unexpected transport call")

    private companion object {
        const val CHALLENGE_ID = "csc_examplechallenge123456789"
        const val SESSION_ID = "csn_examplesession12345678901"
        const val SESSION_TOKEN = "cst_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HUMAN_ID = "hid_exampleopaqueidentity123"
        const val DEVICE_ID = "cdev_exampledevice12345678901"
        const val PUBLIC_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
