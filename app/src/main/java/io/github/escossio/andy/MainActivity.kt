package io.github.escossio.andy

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapIdentity
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapPublicKeyResult
import io.github.escossio.andy.core.deviceidentity.DeviceBootstrapSignatureResult
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityResult
import io.github.escossio.andy.data.deviceidentity.AndroidDeviceIdentityFactory
import io.github.escossio.andy.features.onboarding.OnboardingConfiguration
import io.github.escossio.andy.features.onboarding.OnboardingCoordinator
import io.github.escossio.andy.features.onboarding.OnboardingScreen
import io.github.escossio.andy.integrations.googleidentity.AndroidGoogleCredentialAcquirer
import io.github.escossio.andy.sdk.clientapi.AttentionRouterDeviceBootstrapClient
import io.github.escossio.andy.sdk.clientapi.AttentionRouterHumanAuthClient
import kotlinx.coroutines.launch

internal const val BOOTSTRAP_TEXT = "Andy"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceReady = initializeDeviceIdentity()
        val bootstrapIdentity = AndroidDeviceIdentityFactory.createBootstrapIdentity()
        setContent {
            AndyBootstrap(deviceReady, bootstrapIdentity)
        }
    }

    private fun initializeDeviceIdentity(): Boolean {
        when (val result = AndroidDeviceIdentityFactory.create(applicationContext).ensureIdentity()) {
            is DeviceIdentityResult.Ready -> {
                Log.i(LOG_TAG, "DEVICE_IDENTITY_READY")
                return true
            }
            is DeviceIdentityResult.Unavailable -> {
                Log.w(LOG_TAG, "DEVICE_IDENTITY_UNAVAILABLE:${result.reason.name}")
                return false
            }
        }
    }

    private companion object {
        const val LOG_TAG = "AndyDeviceIdentity"
    }
}

@Composable
private fun AndyBootstrap(
    deviceReady: Boolean,
    bootstrapIdentity: DeviceBootstrapIdentity,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember {
        OnboardingCoordinator(
            ready = deviceReady,
            config = OnboardingConfiguration(
                clientApiBaseUrl = BuildConfig.ATTENTION_ROUTER_BASE_URL,
                googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
                canonicalDeviceName = Build.MODEL.take(160).ifBlank { "Android" },
            ),
            client = AttentionRouterHumanAuthClient(
                BuildConfig.ATTENTION_ROUTER_BASE_URL,
            ),
            provider = AndroidGoogleCredentialAcquirer(
                context,
                BuildConfig.GOOGLE_WEB_CLIENT_ID,
            ),
            bootstrapClient = AttentionRouterDeviceBootstrapClient(
                BuildConfig.ATTENTION_ROUTER_BASE_URL,
            ),
            devicePublicKeySpki = {
                when (val result = bootstrapIdentity.publicKey()) {
                    is DeviceBootstrapPublicKeyResult.Ready ->
                        result.publicKeySpkiB64Url
                    DeviceBootstrapPublicKeyResult.Unavailable -> null
                }
            },
            signDeviceChallenge = { challenge ->
                when (val result = bootstrapIdentity.signChallenge(challenge)) {
                    is DeviceBootstrapSignatureResult.Signed ->
                        result.signatureB64Url
                    DeviceBootstrapSignatureResult.Unavailable -> null
                }
            },
        )
    }
    val state by coordinator.state.collectAsState()
    val bootstrapState by coordinator.bootstrapState.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        OnboardingScreen(
            state = state,
            bootstrapState = bootstrapState,
            onContinue = {
                scope.launch { coordinator.continueWithGoogle() }
            },
            onRetryHuman = coordinator::restartAuthentication,
            onRetryBootstrap = {
                scope.launch { coordinator.retryDeviceBootstrap() }
            },
        )
    }
}
