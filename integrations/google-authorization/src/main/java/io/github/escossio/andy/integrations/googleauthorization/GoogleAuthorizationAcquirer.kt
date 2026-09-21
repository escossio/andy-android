package io.github.escossio.andy.integrations.googleauthorization

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GoogleServerAuthorizationCode internal constructor(
    code: String,
) {
    private val credential = code

    init {
        require(code.length in 8..8192 && code.isNotBlank())
    }

    suspend fun <T> withCode(block: suspend (String) -> T): T = block(credential)

    override fun toString() = "GoogleServerAuthorizationCode(REDACTED)"
}

sealed interface GoogleAuthorizationResult {
    data class Authorized(
        val code: GoogleServerAuthorizationCode,
    ) : GoogleAuthorizationResult

    data object Cancelled : GoogleAuthorizationResult
    data object Unavailable : GoogleAuthorizationResult
}

interface GoogleAuthorizationAcquirer {
    suspend fun acquire(
        requestedScopes: Set<String>,
        forceConsent: Boolean = false,
    ): GoogleAuthorizationResult
}

class AndroidGoogleAuthorizationAcquirer(
    private val activity: ComponentActivity,
    private val serverClientId: String,
    private val client: AuthorizationClient = Identity.getAuthorizationClient(activity),
) : GoogleAuthorizationAcquirer, AutoCloseable {
    private val mutex = Mutex()
    private var pendingResult: CompletableDeferred<ActivityResult>? = null
    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            pendingResult?.complete(result)
        }

    override suspend fun acquire(
        requestedScopes: Set<String>,
        forceConsent: Boolean,
    ): GoogleAuthorizationResult = mutex.withLock {
        if (
            serverClientId.isBlank() ||
            requestedScopes.isEmpty() ||
            requestedScopes.size > 10 ||
            requestedScopes.any {
                it.length !in 1..256 ||
                    !it.startsWith("https://www.googleapis.com/auth/")
            }
        ) {
            return@withLock GoogleAuthorizationResult.Unavailable
        }

        try {
            val builder = AuthorizationRequest.builder()
                .setRequestedScopes(requestedScopes.sorted().map(::Scope))
                .setOptOutIncludingGrantedScopes(true)
                .requestOfflineAccess(serverClientId)

            if (forceConsent) {
                builder.setPrompt(AuthorizationRequest.Prompt.CONSENT)
            }

            val initial = client.authorize(builder.build()).await()
            val result = if (initial.hasResolution()) {
                when (val resolution = resolve(initial.pendingIntent)) {
                    ResolutionOutcome.Cancelled ->
                        return@withLock GoogleAuthorizationResult.Cancelled
                    ResolutionOutcome.Unavailable ->
                        return@withLock GoogleAuthorizationResult.Unavailable
                    is ResolutionOutcome.Success -> resolution.result
                }
            } else {
                initial
            }

            val code = result.serverAuthCode
                ?.takeIf { it.length in 8..8192 && it.isNotBlank() }
                ?: return@withLock GoogleAuthorizationResult.Unavailable

            GoogleAuthorizationResult.Authorized(
                GoogleServerAuthorizationCode(code),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GoogleAuthorizationResult.Unavailable
        }
    }

    override fun close() {
        pendingResult?.cancel()
        pendingResult = null
        launcher.unregister()
    }

    private suspend fun resolve(
        pendingIntent: PendingIntent?,
    ): ResolutionOutcome {
        val intent = pendingIntent ?: return ResolutionOutcome.Unavailable
        val deferred = CompletableDeferred<ActivityResult>()
        pendingResult = deferred
        return try {
            launcher.launch(
                IntentSenderRequest.Builder(intent.intentSender).build(),
            )
            val activityResult = deferred.await()
            if (activityResult.resultCode != Activity.RESULT_OK) {
                ResolutionOutcome.Cancelled
            } else {
                val data = activityResult.data
                    ?: return ResolutionOutcome.Unavailable
                ResolutionOutcome.Success(
                    client.getAuthorizationResultFromIntent(data),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ResolutionOutcome.Unavailable
        } finally {
            if (pendingResult === deferred) {
                pendingResult = null
            }
        }
    }

    private sealed interface ResolutionOutcome {
        data class Success(
            val result: AuthorizationResult,
        ) : ResolutionOutcome

        data object Cancelled : ResolutionOutcome
        data object Unavailable : ResolutionOutcome
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) {
                return@addOnCompleteListener
            }
            val error = task.exception
            if (task.isSuccessful && error == null) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(
                    error ?: IllegalStateException("GOOGLE_AUTHORIZATION_TASK_FAILED"),
                )
            }
        }
    }
