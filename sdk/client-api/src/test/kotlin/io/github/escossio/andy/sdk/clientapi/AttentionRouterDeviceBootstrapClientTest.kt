package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AttentionRouterDeviceBootstrapClientTest {
    @Test
    fun startUsesContinuationOnlyOnWireAndParsesStrictChallenge() = runBlocking {
        var path = ""
        var body = ""
        val transport = object : HumanAuthTransport {
            override suspend fun post(requestPath: String, requestBody: String): TransportResponse {
                path = requestPath
                body = requestBody
                return TransportResponse(
                    201,
                    """{"bootstrap_challenge_id":"$CHALLENGE_ID","challenge_b64url":"$CHALLENGE","expires_at":"2030-01-01T00:05:00Z"}""",
                )
            }
        }
        val result = client(transport).start(
            grant(),
            PUBLIC_KEY,
            "Synthetic Android",
            setOf(DeviceBootstrapRole.CLIENT, DeviceBootstrapRole.CAPABILITY_NODE),
        )

        assertEquals("/api/v1/bootstrap/device/challenges", path)
        assertTrue(body.contains(GRANT_TOKEN))
        assertFalse(result.toString().contains(GRANT_TOKEN))
        assertEquals(
            DeviceBootstrapChallengeResult.Success(
                DeviceBootstrapChallenge(
                    CHALLENGE_ID,
                    CHALLENGE,
                    Instant.parse("2030-01-01T00:05:00Z"),
                ),
            ),
            result,
        )
    }

    @Test
    fun completeParsesBoundedAuthorityWithoutSessionMaterial() = runBlocking {
        val response = """{
          "status":"DEVICE_BOOTSTRAP_ESTABLISHED",
          "human_identity_id":"$HUMAN_ID",
          "memberships":[{"membership_id":"ctm_synthetic","tenant_id":"tnt_synthetic","role":"OWNER","status":"ACTIVE"}],
          "initial_tenant_id":"tnt_synthetic",
          "device":{"device_id":"$DEVICE_ID","public_key_fingerprint":"$FINGERPRINT","canonical_name":"Synthetic Android","platform":"ANDROID","roles":["CLIENT","CAPABILITY_NODE"],"status":"ACTIVE"}
        }""".trimIndent()
        val result = client(FixedTransport(TransportResponse(200, response))).complete(
            CHALLENGE_ID,
            SIGNATURE,
        )

        assertTrue(result is DeviceBootstrapCompleteResult.Success)
        val established = (result as DeviceBootstrapCompleteResult.Success).established
        assertEquals(HUMAN_ID, established.humanIdentityId)
        assertEquals("tnt_synthetic", established.initialTenantId)
        assertEquals(ClientTenantRole.OWNER, established.memberships.single().role)
        assertEquals(DEVICE_ID, established.device.deviceId)
        assertEquals(
            setOf(DeviceBootstrapRole.CLIENT, DeviceBootstrapRole.CAPABILITY_NODE),
            established.device.roles,
        )
        assertFalse(established.toString().contains("access_token"))
        assertFalse(established.toString().contains(GRANT_TOKEN))
    }

    @Test
    fun multipleMembershipsAllowNullInitialTenant() = runBlocking {
        val response = """{
          "status":"DEVICE_BOOTSTRAP_ESTABLISHED",
          "human_identity_id":"$HUMAN_ID",
          "memberships":[
            {"membership_id":"ctm_a","tenant_id":"tnt_a","role":"OWNER","status":"ACTIVE"},
            {"membership_id":"ctm_b","tenant_id":"tnt_b","role":"MEMBER","status":"ACTIVE"}
          ],
          "initial_tenant_id":null,
          "device":{"device_id":"$DEVICE_ID","public_key_fingerprint":"$FINGERPRINT","canonical_name":"Synthetic Android","platform":"ANDROID","roles":["CLIENT"],"status":"ACTIVE"}
        }""".trimIndent()

        val result = client(FixedTransport(TransportResponse(200, response))).complete(
            CHALLENGE_ID,
            SIGNATURE,
        ) as DeviceBootstrapCompleteResult.Success

        assertEquals(null, result.established.initialTenantId)
        assertEquals(2, result.established.memberships.size)
    }

    @Test
    fun malformedSuccessBodiesFailClosed() = runBlocking {
        val valid = """{"status":"DEVICE_BOOTSTRAP_ESTABLISHED","human_identity_id":"$HUMAN_ID","memberships":[{"membership_id":"ctm_a","tenant_id":"tnt_a","role":"OWNER","status":"ACTIVE"}],"initial_tenant_id":"tnt_a","device":{"device_id":"$DEVICE_ID","public_key_fingerprint":"$FINGERPRINT","canonical_name":"Synthetic Android","platform":"ANDROID","roles":["CLIENT"],"status":"ACTIVE"}}"""
        val malformed = listOf<String?>(
            null,
            "",
            "not-json",
            valid.replace(""status":"ACTIVE"}", ""status":"ACTIVE","extra":true}"),
            valid.replace(FINGERPRINT, "sha256:bad"),
            valid.replace(""roles":["CLIENT"]", ""roles":["CLIENT","CLIENT"]"),
            valid.replace(""initial_tenant_id":"tnt_a"", ""initial_tenant_id":123"),
        )
        for (body in malformed) {
            assertEquals(
                DeviceBootstrapCompleteResult.Failure(
                    DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE,
                ),
                client(FixedTransport(TransportResponse(200, body))).complete(
                    CHALLENGE_ID,
                    SIGNATURE,
                ),
            )
        }
    }

    @Test
    fun backendErrorsMapLiterallyAndUnknownDoesNotAuthorize() = runBlocking {
        val errors = mapOf(
            "DEVICE_BOOTSTRAP_GRANT_REJECTED" to
                DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_GRANT_REJECTED,
            "DEVICE_BOOTSTRAP_CHALLENGE_NOT_FOUND" to
                DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_CHALLENGE_NOT_FOUND,
            "DEVICE_BOOTSTRAP_CHALLENGE_EXPIRED" to
                DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_CHALLENGE_EXPIRED,
            "DEVICE_BOOTSTRAP_CHALLENGE_CONSUMED" to
                DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_CHALLENGE_CONSUMED,
            "DEVICE_BOOTSTRAP_DEVICE_KEY_INVALID" to
                DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_DEVICE_KEY_INVALID,
            "DEVICE_BOOTSTRAP_SIGNATURE_INVALID" to
                DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_SIGNATURE_INVALID,
            "DEVICE_BOOTSTRAP_MEMBERSHIP_CONFLICT" to
                DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_MEMBERSHIP_CONFLICT,
            "DEVICE_BOOTSTRAP_DEVICE_CONFLICT" to
                DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_DEVICE_CONFLICT,
            "DEVICE_BOOTSTRAP_UNAVAILABLE" to
                DeviceBootstrapErrorCode.DEVICE_BOOTSTRAP_UNAVAILABLE,
        )
        for ((wire, expected) in errors) {
            assertEquals(
                DeviceBootstrapChallengeResult.Failure(expected),
                client(FixedTransport(TransportResponse(400, """{"code":"$wire"}"""))).start(
                    grant(),
                    PUBLIC_KEY,
                    "Synthetic Android",
                    setOf(DeviceBootstrapRole.CLIENT),
                ),
            )
        }
        assertEquals(
            DeviceBootstrapChallengeResult.Failure(
                DeviceBootstrapErrorCode.UNEXPECTED_RESPONSE,
            ),
            client(FixedTransport(TransportResponse(400, """{"code":"UNKNOWN"}"""))).start(
                grant(),
                PUBLIC_KEY,
                "Synthetic Android",
                setOf(DeviceBootstrapRole.CLIENT),
            ),
        )
    }

    @Test
    fun transportExceptionIsTheOnlyNetworkFailure() = runBlocking {
        val failing = client(object : HumanAuthTransport {
            override suspend fun post(path: String, body: String): TransportResponse {
                throw IllegalStateException("synthetic")
            }
        })
        assertEquals(
            DeviceBootstrapChallengeResult.Failure(DeviceBootstrapErrorCode.NETWORK_FAILURE),
            failing.start(
                grant(),
                PUBLIC_KEY,
                "Synthetic Android",
                setOf(DeviceBootstrapRole.CLIENT),
            ),
        )
    }

    private fun client(transport: HumanAuthTransport) =
        AttentionRouterDeviceBootstrapClient("https://synthetic.invalid", transport)

    private class FixedTransport(private val response: TransportResponse) : HumanAuthTransport {
        override suspend fun post(path: String, body: String) = response
    }

    private companion object {
        const val GRANT_TOKEN = "hcg_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PUBLIC_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val CHALLENGE_ID = "dbc_examplechallenge123456789"
        const val CHALLENGE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SIGNATURE =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val HUMAN_ID = "hid_exampleopaqueidentity123"
        const val DEVICE_ID = "cdev_exampleopaquedevice12345"
        const val FINGERPRINT =
            "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

        fun grant() = HumanAuthContinuationGrant(
            GRANT_TOKEN,
            HumanAuthContinuationPurpose.DEVICE_BOOTSTRAP,
            Instant.parse("2030-01-01T00:05:00Z"),
        )
    }
}
