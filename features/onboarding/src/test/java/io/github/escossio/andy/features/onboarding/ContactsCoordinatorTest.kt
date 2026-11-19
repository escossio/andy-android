package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.sdk.clientapi.*
import io.github.escossio.andy.integrations.googleauthorization.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ContactsCoordinatorTest {
    private fun session(tenant: String = "tenant") = ClientSessionCredential("cst_"+"s".repeat(43), "csn_"+"s".repeat(20), Instant.parse("2030-01-01T00:00:00Z"), "hid_"+"h".repeat(20), "cdev_"+"d".repeat(20), tenant)
    private class Client : ContactsClient {
        var connects = 0
        override suspend fun status(session: ClientSessionCredential) = ContactsResult.Success.state("ACTIVE", "READY")
        override suspend fun connect(session: ClientSessionCredential, code: String): ContactsResult {
            connects++; assertEquals("synthetic-code",code)
            return ContactsResult.Success.state("CONNECTED", "PENDING")
        }
        override suspend fun disconnect(session: ClientSessionCredential) = ContactsResult.Success.state("DISCONNECTED")
        override suspend fun sync(session: ClientSessionCredential) = ContactsResult.Success.state("SCHEDULED")
    }
    @Test fun consentRequestsOnlyContactsAndTransitionsThroughSync() = runBlocking {
        val client=Client()
        val authorization=object: GoogleAuthorizationAcquirer {
            override suspend fun acquire(requestedScopes: Set<String>,forceConsent: Boolean): GoogleAuthorizationResult {
                assertEquals(setOf(CONTACTS_READONLY_SCOPE),requestedScopes);assertTrue(forceConsent)
                return GoogleAuthorizationResult.Authorized(GoogleServerAuthorizationCode("synthetic-code"))
            }
        }
        val coordinator=ContactsCoordinator(client,authorization) { session() }
        coordinator.connect();assertEquals(ContactsState.SYNCING,coordinator.state.value)
        coordinator.refresh();assertEquals(ContactsState.CONNECTED,coordinator.state.value)
        coordinator.disconnect();assertEquals(ContactsState.DISCONNECTED,coordinator.state.value)
        assertEquals(1,client.connects)
    }
    @Test fun tenantChangeDuringConsentDoesNotInstallGrant() = runBlocking {
        val client=Client();var current=session()
        val authorization=object: GoogleAuthorizationAcquirer {
            override suspend fun acquire(requestedScopes: Set<String>,forceConsent: Boolean): GoogleAuthorizationResult {
                current=session("other-tenant")
                return GoogleAuthorizationResult.Authorized(GoogleServerAuthorizationCode("synthetic-code"))
            }
        }
        val coordinator=ContactsCoordinator(client,authorization) { current }
        coordinator.connect();assertEquals(0,client.connects);assertEquals(ContactsState.DISCONNECTED,coordinator.state.value)
    }
    @Test fun cancellationDoesNotConnect() = runBlocking {
        val client=Client()
        val authorization=object: GoogleAuthorizationAcquirer {
            override suspend fun acquire(requestedScopes: Set<String>,forceConsent: Boolean)=GoogleAuthorizationResult.Cancelled
        }
        val coordinator=ContactsCoordinator(client,authorization) { session() }
        coordinator.connect();assertEquals(0,client.connects);assertEquals(ContactsState.DISCONNECTED,coordinator.state.value)
    }
}
