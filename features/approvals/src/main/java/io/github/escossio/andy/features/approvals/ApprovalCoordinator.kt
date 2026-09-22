package io.github.escossio.andy.features.approvals

import io.github.escossio.andy.sdk.clientapi.ClientApproval
import io.github.escossio.andy.sdk.clientapi.ClientApprovalClient
import io.github.escossio.andy.sdk.clientapi.ClientApprovalDecision
import io.github.escossio.andy.sdk.clientapi.ClientApprovalErrorCode
import io.github.escossio.andy.sdk.clientapi.ClientApprovalListResult
import io.github.escossio.andy.sdk.clientapi.ClientApprovalResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ApprovalFailure {
    NETWORK_FAILURE,
    CLIENT_APPROVAL_DISABLED,
    CLIENT_APPROVAL_NOT_FOUND,
    CLIENT_APPROVAL_AUTHORITY_REJECTED,
    CLIENT_APPROVAL_CONFLICT,
    CLIENT_APPROVAL_EXPIRED,
    CLIENT_APPROVAL_UNAVAILABLE,
    CLIENT_SESSION_UNAUTHENTICATED,
    CLIENT_SESSION_AUTHORITY_REJECTED,
    CLIENT_SESSION_UNAVAILABLE,
    UNEXPECTED_RESPONSE,
}

sealed interface ApprovalState {
    data object Idle : ApprovalState
    data class Loading(val previous: List<ClientApproval>) : ApprovalState
    data class Ready(val approvals: List<ClientApproval>) : ApprovalState
    data class Deciding(
        val approvals: List<ClientApproval>,
        val approvalId: String,
        val decision: ClientApprovalDecision,
    ) : ApprovalState
    data class Failure(
        val approvals: List<ClientApproval>,
        val reason: ApprovalFailure,
    ) : ApprovalState
}

class ApprovalCoordinator(
    private val client: ClientApprovalClient,
    private val sessionProvider: suspend () -> ClientSessionCredential?,
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<ApprovalState>(ApprovalState.Idle)
    val state: StateFlow<ApprovalState> = mutable.asStateFlow()

    fun reset() {
        mutable.value = ApprovalState.Idle
    }

    suspend fun refresh() {
        mutex.withLock {
            val previous = approvalsOf(mutable.value)
            val session = sessionProvider()
            if (session == null) {
                mutable.value = ApprovalState.Failure(
                    previous,
                    ApprovalFailure.CLIENT_SESSION_UNAUTHENTICATED,
                )
                return@withLock
            }

            mutable.value = ApprovalState.Loading(previous)
            applyList(client.listPending(session), previous)
        }
    }

    suspend fun decide(
        approvalId: String,
        decision: ClientApprovalDecision,
    ) {
        mutex.withLock {
            val current = approvalsOf(mutable.value)
            val approval = current.firstOrNull { it.approvalId == approvalId }
            if (approval == null || approval.state != "PENDING_HUMAN_APPROVAL") {
                mutable.value = ApprovalState.Failure(
                    current,
                    ApprovalFailure.CLIENT_APPROVAL_CONFLICT,
                )
                return@withLock
            }

            val session = sessionProvider()
            if (session == null) {
                mutable.value = ApprovalState.Failure(
                    current,
                    ApprovalFailure.CLIENT_SESSION_UNAUTHENTICATED,
                )
                return@withLock
            }

            mutable.value = ApprovalState.Deciding(
                current,
                approvalId,
                decision,
            )
            when (
                val result = client.decide(
                    session,
                    approvalId,
                    decision,
                )
            ) {
                is ClientApprovalResult.Failure -> {
                    mutable.value = ApprovalState.Failure(
                        current,
                        result.error.failure(),
                    )
                }
                is ClientApprovalResult.Success -> {
                    val expectedState = when (decision) {
                        ClientApprovalDecision.APPROVE -> "APPROVED"
                        ClientApprovalDecision.DENY -> "DENIED"
                    }
                    if (result.approval.state != expectedState) {
                        mutable.value = ApprovalState.Failure(
                            current,
                            ApprovalFailure.UNEXPECTED_RESPONSE,
                        )
                        return@withLock
                    }

                    val remaining = current.filterNot {
                        it.approvalId == approvalId
                    }
                    applyList(
                        client.listPending(session),
                        remaining,
                    )
                }
            }
        }
    }

    private fun applyList(
        result: ClientApprovalListResult,
        fallback: List<ClientApproval>,
    ) {
        mutable.value = when (result) {
            is ClientApprovalListResult.Success -> ApprovalState.Ready(
                result.approvals.filter {
                    it.state == "PENDING_HUMAN_APPROVAL"
                },
            )
            is ClientApprovalListResult.Failure -> ApprovalState.Failure(
                fallback,
                result.error.failure(),
            )
        }
    }

    private fun approvalsOf(state: ApprovalState): List<ClientApproval> =
        when (state) {
            ApprovalState.Idle -> emptyList()
            is ApprovalState.Loading -> state.previous
            is ApprovalState.Ready -> state.approvals
            is ApprovalState.Deciding -> state.approvals
            is ApprovalState.Failure -> state.approvals
        }

    private fun ClientApprovalErrorCode.failure(): ApprovalFailure =
        when (this) {
            ClientApprovalErrorCode.NETWORK_FAILURE ->
                ApprovalFailure.NETWORK_FAILURE
            ClientApprovalErrorCode.CLIENT_APPROVAL_DISABLED ->
                ApprovalFailure.CLIENT_APPROVAL_DISABLED
            ClientApprovalErrorCode.CLIENT_APPROVAL_NOT_FOUND ->
                ApprovalFailure.CLIENT_APPROVAL_NOT_FOUND
            ClientApprovalErrorCode.CLIENT_APPROVAL_AUTHORITY_REJECTED ->
                ApprovalFailure.CLIENT_APPROVAL_AUTHORITY_REJECTED
            ClientApprovalErrorCode.CLIENT_APPROVAL_CONFLICT ->
                ApprovalFailure.CLIENT_APPROVAL_CONFLICT
            ClientApprovalErrorCode.CLIENT_APPROVAL_EXPIRED ->
                ApprovalFailure.CLIENT_APPROVAL_EXPIRED
            ClientApprovalErrorCode.CLIENT_APPROVAL_UNAVAILABLE ->
                ApprovalFailure.CLIENT_APPROVAL_UNAVAILABLE
            ClientApprovalErrorCode.CLIENT_SESSION_UNAUTHENTICATED ->
                ApprovalFailure.CLIENT_SESSION_UNAUTHENTICATED
            ClientApprovalErrorCode.CLIENT_SESSION_AUTHORITY_REJECTED ->
                ApprovalFailure.CLIENT_SESSION_AUTHORITY_REJECTED
            ClientApprovalErrorCode.CLIENT_SESSION_UNAVAILABLE ->
                ApprovalFailure.CLIENT_SESSION_UNAVAILABLE
            ClientApprovalErrorCode.INVALID_APPROVAL_ID,
            ClientApprovalErrorCode.UNEXPECTED_RESPONSE ->
                ApprovalFailure.UNEXPECTED_RESPONSE
        }
}
