package io.github.escossio.andy.features.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.escossio.andy.core.humanidentity.HumanIdentityState

@Composable
fun OnboardingScreen(
    state: HumanIdentityState,
    bootstrapState: DeviceBootstrapState,
    sessionState: ClientSessionState,
    locationState: ClientLocationState,
    onContinue: () -> Unit,
    onContinueSession: () -> Unit,
    onRetryHuman: () -> Unit,
    onRetryBootstrap: () -> Unit,
    onRetrySession: () -> Unit,
    onShareLocation: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        if (sessionState !is ClientSessionState.Idle) {
            SessionContent(
                sessionState = sessionState,
                locationState = locationState,
                onRetrySession = onRetrySession,
                onRestartHuman = onRetryHuman,
                onShareLocation = onShareLocation,
            )
            return@Column
        }

        when (state) {
            HumanIdentityState.DeviceIdentityUnavailable ->
                BasicText("Device identity is not ready.")
            HumanIdentityState.Unauthenticated -> {
                Action("Connect existing device", onContinueSession)
                Action("Continue with Google", onContinue)
            }
            HumanIdentityState.RequestingChallenge,
            HumanIdentityState.AcquiringProviderCredential,
            HumanIdentityState.ValidatingBackend ->
                BasicText("Validating human identity…")
            is HumanIdentityState.Failure -> {
                BasicText("Human identity could not be validated: ${state.reason.name}")
                Action("Try again", onRetryHuman)
            }
            is HumanIdentityState.Validated -> BootstrapContent(
                bootstrapState = bootstrapState,
                onRetryBootstrap = onRetryBootstrap,
                onRestartHuman = onRetryHuman,
            )
        }
    }
}

@Composable
private fun BootstrapContent(
    bootstrapState: DeviceBootstrapState,
    onRetryBootstrap: () -> Unit,
    onRestartHuman: () -> Unit,
) {
    when (bootstrapState) {
        DeviceBootstrapState.Idle -> BasicText("Human identity validated.")
        DeviceBootstrapState.Establishing ->
            BasicText("Establishing device and tenant…")
        is DeviceBootstrapState.Established -> {
            val authority = bootstrapState.authority
            BasicText("Device bootstrap established.")
            BasicText(
                "Tenant: ${authority.initialTenantId ?: "selection required"}",
                Modifier.padding(top = 8.dp),
            )
            BasicText(
                "Memberships: ${authority.memberships.size}",
                Modifier.padding(top = 8.dp),
            )
            BasicText(
                "Device: ${authority.device.deviceId}",
                Modifier.padding(top = 8.dp),
            )
        }
        is DeviceBootstrapState.Failure -> {
            BasicText("Device bootstrap failed: ${bootstrapState.reason.name}")
            Action("Retry device bootstrap", onRetryBootstrap)
            Action("Restart sign-in", onRestartHuman)
        }
    }
}

@Composable
private fun SessionContent(
    sessionState: ClientSessionState,
    locationState: ClientLocationState,
    onRetrySession: () -> Unit,
    onRestartHuman: () -> Unit,
    onShareLocation: () -> Unit,
) {
    when (sessionState) {
        ClientSessionState.Idle -> Unit
        ClientSessionState.Restoring ->
            BasicText("Restoring secure client session…")
        ClientSessionState.Establishing ->
            BasicText("Establishing secure client session…")
        ClientSessionState.LoadingBootstrap ->
            BasicText("Loading authenticated Andy state…")
        is ClientSessionState.Connected -> {
            val bootstrap = sessionState.bootstrap
            BasicText("Andy connected.")
            BasicText(
                "Tenant: ${bootstrap.activeTenantId}",
                Modifier.padding(top = 8.dp),
            )
            BasicText(
                "Memberships: ${bootstrap.memberships.size}",
                Modifier.padding(top = 8.dp),
            )
            BasicText(
                "Device: ${bootstrap.device.deviceId}",
                Modifier.padding(top = 8.dp),
            )
            BasicText(
                "Session expires: ${bootstrap.sessionExpiresAt}",
                Modifier.padding(top = 8.dp),
            )
            Action("Share current location", onShareLocation)
            when (locationState) {
                ClientLocationState.Idle -> Unit
                ClientLocationState.Acquiring ->
                    BasicText("Acquiring current location…", Modifier.padding(top = 8.dp))
                ClientLocationState.Sharing ->
                    BasicText("Sharing current location…", Modifier.padding(top = 8.dp))
                is ClientLocationState.Shared -> {
                    BasicText("Location shared.", Modifier.padding(top = 8.dp))
                    BasicText(
                        "Accuracy: ${locationState.accuracyM.toInt()} m",
                        Modifier.padding(top = 8.dp),
                    )
                    BasicText(
                        "Captured: ${locationState.capturedAt}",
                        Modifier.padding(top = 8.dp),
                    )
                }
                is ClientLocationState.Failure ->
                    BasicText(
                        "Location sharing failed: ${locationState.reason.name}",
                        Modifier.padding(top = 8.dp),
                    )
            }
        }
        is ClientSessionState.Failure -> {
            BasicText("Secure connection failed: ${sessionState.reason.name}")
            Action("Retry secure connection", onRetrySession)
            Action("Start over with Google", onRestartHuman)
        }
    }
}

@Composable
private fun Action(text: String, click: () -> Unit) {
    BasicText(
        text,
        Modifier.padding(top = 16.dp).clickable(onClick = click),
    )
}
