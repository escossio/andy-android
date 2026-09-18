package io.github.escossio.andy.sdk.clientapi

/**
 * Dedicated persistence boundary for the short Android client-session credential.
 *
 * Implementations must never persist the raw bearer token in plaintext.
 */
interface ClientSessionStore {
    suspend fun load(): ClientSessionCredential?
    suspend fun save(session: ClientSessionCredential)
    suspend fun clear()
}

/**
 * Default used by pure/unit wiring that does not opt into durable continuity.
 */
object NoopClientSessionStore : ClientSessionStore {
    override suspend fun load(): ClientSessionCredential? = null
    override suspend fun save(session: ClientSessionCredential) = Unit
    override suspend fun clear() = Unit
}
