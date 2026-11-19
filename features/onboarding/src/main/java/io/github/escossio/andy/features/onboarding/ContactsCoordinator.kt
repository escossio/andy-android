package io.github.escossio.andy.features.onboarding

import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationAcquirer
import io.github.escossio.andy.integrations.googleauthorization.GoogleAuthorizationResult
import io.github.escossio.andy.sdk.clientapi.CONTACTS_READONLY_SCOPE
import io.github.escossio.andy.sdk.clientapi.ContactsClient
import io.github.escossio.andy.sdk.clientapi.ContactsResult
import io.github.escossio.andy.sdk.clientapi.ClientSessionCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ContactsState { DISCONNECTED, AUTHORIZING, CONNECTING, SYNCING, CONNECTED, ERROR }
class ContactsCoordinator(
    private val client: ContactsClient?,
    private val authorization: GoogleAuthorizationAcquirer?,
    private val session: () -> ClientSessionCredential?,
) {
    private val mutable = MutableStateFlow(ContactsState.DISCONNECTED)
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    fun reset() { mutable.value = ContactsState.DISCONNECTED }
    private fun sameOwner(before: ClientSessionCredential, after: ClientSessionCredential?) =
        after != null && before.tenantId == after.tenantId && before.humanIdentityId == after.humanIdentityId
    private fun apply(result: ContactsResult) {
        mutable.value = when (result) {
            is ContactsResult.Failure -> ContactsState.ERROR
            is ContactsResult.Success -> {
                val status = result.status
                val sync = result.syncStatus
                when {
                    status == "DISCONNECTED" -> ContactsState.DISCONNECTED
                    status == "AUTH_REQUIRED" || sync == "ERROR" -> ContactsState.ERROR
                    status in setOf("CONNECTED", "SCHEDULED") || sync in setOf("PENDING", "SYNCING") -> ContactsState.SYNCING
                    status == "ACTIVE" -> ContactsState.CONNECTED
                    else -> ContactsState.ERROR
                }
            }
        }
    }
    suspend fun refresh() = mutex.withLock {
        val before = session() ?: return@withLock reset()
        val result = client?.status(before) ?: return@withLock
        if (sameOwner(before, session())) apply(result) else reset()
    }
    suspend fun connect() = mutex.withLock {
        val before = session() ?: return@withLock reset()
        val api = client ?: return@withLock
        val provider = authorization ?: return@withLock
        mutable.value = ContactsState.AUTHORIZING
        when (val result = provider.acquire(setOf(CONTACTS_READONLY_SCOPE), forceConsent = true)) {
            GoogleAuthorizationResult.Cancelled -> reset()
            GoogleAuthorizationResult.Unavailable -> { mutable.value = ContactsState.ERROR }
            is GoogleAuthorizationResult.Authorized -> {
                val current = session()
                if (!sameOwner(before, current)) return@withLock reset()
                mutable.value = ContactsState.CONNECTING
                val response = result.code.withCode { api.connect(current!!, it) }
                if (sameOwner(before, session())) apply(response) else reset()
            }
        }
    }
    suspend fun disconnect() = mutex.withLock {
        val before = session() ?: return@withLock reset()
        val result = client?.disconnect(before) ?: return@withLock
        if (sameOwner(before, session())) apply(result) else reset()
    }
    suspend fun sync() = mutex.withLock {
        val before = session() ?: return@withLock reset()
        val result = client?.sync(before) ?: return@withLock
        if (sameOwner(before, session())) apply(result) else reset()
    }
}
