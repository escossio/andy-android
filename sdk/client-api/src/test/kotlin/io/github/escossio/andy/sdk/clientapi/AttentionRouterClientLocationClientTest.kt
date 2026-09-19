package io.github.escossio.andy.sdk.clientapi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AttentionRouterClientLocationClientTest {
    @Test
    fun invalidObservationFailsBeforeNetwork() = runBlocking {
        val client = AttentionRouterClientLocationClient("https://synthetic.invalid")
        val session = ClientSessionCredential(
            token = "cst_" + "a".repeat(43),
            sessionId = "csn_" + "b".repeat(20),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            humanIdentityId = "hid_" + "c".repeat(20),
            deviceId = "cdev_" + "d".repeat(20),
            tenantId = "tenant-synthetic",
        )

        val result = client.putCurrent(
            session,
            ClientLocationObservation(
                latitude = 100.0,
                longitude = 0.0,
                accuracyM = 10.0,
                capturedAt = Instant.parse("2026-09-19T01:00:00Z"),
                precision = "PRECISE",
            ),
        )

        assertEquals(
            ClientLocationResult.Failure(ClientLocationErrorCode.CLIENT_LOCATION_INVALID),
            result,
        )
    }
}
