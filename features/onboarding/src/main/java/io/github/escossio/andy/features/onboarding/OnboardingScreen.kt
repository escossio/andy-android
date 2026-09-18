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
    onContinue: () -> Unit,
    onRetryHuman: () -> Unit,
    onRetryBootstrap: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        when (state) {
            HumanIdentityState.DeviceIdentityUnavailable ->
                BasicText("Device identity is not ready.")
            HumanIdentityState.Unauthenticated ->
                Action("Continue with Google", onContinue)
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
private fun Action(text: String, click: () -> Unit) {
    BasicText(
        text,
        Modifier.padding(top = 16.dp).clickable(onClick = click),
    )
}
