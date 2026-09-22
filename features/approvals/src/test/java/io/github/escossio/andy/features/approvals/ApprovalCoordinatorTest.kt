package io.github.escossio.andy.features.approvals

import io.github.escossio.andy.sdk.clientapi.ClientApproval
import io.github.escossio.andy.sdk.clientapi.ClientApprovalClient
import io.github.escossio.andy.sdk.clientapi.ClientApprovalDecision
import io.github.escossio.andy.sdk.clientapi.ClientApprovalErrorCode
import io.github.escossio.andy.sdk.clientapi.ClientApprovalListResult
import io.github.escossio.andy.sdk.clientapi.ClientApprovalResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ApprovalCoordinatorTest {
    private val session = ClientSessionCredential(
        token = "cst_" + "a".repeat(43),
        sessionId = "csn_" + "b".repeat(20),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        humanIdentityId = "hid_" + "c".repeat(20),
        deviceId = "cdev_" + "d".repeat(20),
        tenantId = "tenant-synthetic",
    )

    @Test
    fun refreshLoadsPendingApprovals() = runBlocking {
        val client = FakeClient()
        client.pending = listOf(pending())
        val coordinator = ApprovalCoordinator(client) { session }

        coordinator.refresh()

        val state = coordinator.state.value as ApprovalState.Ready
        assertEquals(listOf("apr_1"), state.approvals.map { it.approvalId })
    }

    @Test
    fun missingSessionFailsClosed() = runBlocking {
        val coordinator = ApprovalCoordinator(FakeClient()) { null }

        coordinator.refresh()

        assertEquals(
            ApprovalFailure.CLIENT_SESSION_UNAUTHENTICATED,
            (coordinator.state.value as ApprovalState.Failure).reason,
        )
    }

    @Test
    fun approveUsesServerResultThenRefreshesInbox() = runBlocking {
        val client = FakeClient()
        client.pending = listOf(pending())
        val coordinator = ApprovalCoordinator(client) { session }
        coordinator.refresh()

        coordinator.decide("apr_1", ClientApprovalDecision.APPROVE)

        assertEquals(
            listOf(ClientApprovalDecision.APPROVE),
            client.decisions.map { it.second },
        )
        assertTrue(
            (coordinator.state.value as ApprovalState.Ready).approvals.isEmpty(),
        )
    }

    @Test
    fun conflictingDecisionIsSurfacedWithoutDroppingCachedRequest() = runBlocking {
        val client = FakeClient()
        client.pending = listOf(pending())
        val coordinator = ApprovalCoordinator(client) { session }
        coordinator.refresh()
        client.decisionFailure = ClientApprovalErrorCode.CLIENT_APPROVAL_CONFLICT

        coordinator.decide("apr_1", ClientApprovalDecision.DENY)

        val state = coordinator.state.value as ApprovalState.Failure
        assertEquals(ApprovalFailure.CLIENT_APPROVAL_CONFLICT, state.reason)
        assertEquals(listOf("apr_1"), state.approvals.map { it.approvalId })
    }

    private fun pending() = ClientApproval(
        approvalId = "apr_1",
        state = "PENDING_HUMAN_APPROVAL",
        capability = "conversation.reply",
        operation = "conversation.reply",
        target = "synthetic-target",
        preview = "Mensagem proposta",
        issuedAt = Instant.parse("2026-09-22T06:45:00Z"),
        expiresAt = Instant.parse("2026-09-22T06:50:00Z"),
    )

    private class FakeClient : ClientApprovalClient {
        var pending: List<ClientApproval> = emptyList()
        var decisionFailure: ClientApprovalErrorCode? = null
        val decisions = mutableListOf<Pair<String, ClientApprovalDecision>>()

        override suspend fun listPending(
            session: ClientSessionCredential,
        ): ClientApprovalListResult =
            ClientApprovalListResult.Success(pending)

        override suspend fun get(
            session: ClientSessionCredential,
            approvalId: String,
        ): ClientApprovalResult =
            pending.firstOrNull { it.approvalId == approvalId }
                ?.let(ClientApprovalResult::Success)
                ?: ClientApprovalResult.Failure(
                    ClientApprovalErrorCode.CLIENT_APPROVAL_NOT_FOUND,
                )

        override suspend fun decide(
            session: ClientSessionCredential,
            approvalId: String,
            decision: ClientApprovalDecision,
        ): ClientApprovalResult {
            decisions += approvalId to decision
            decisionFailure?.let {
                return ClientApprovalResult.Failure(it)
            }
            val source = pending.first { it.approvalId == approvalId }
            pending = pending.filterNot { it.approvalId == approvalId }
            return ClientApprovalResult.Success(
                source.copy(
                    state = when (decision) {
                        ClientApprovalDecision.APPROVE -> "APPROVED"
                        ClientApprovalDecision.DENY -> "DENIED"
                    },
                ),
            )
        }
    }
}
