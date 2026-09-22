package io.github.escossio.andy

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import io.github.escossio.andy.features.approvals.ApprovalPanel
import io.github.escossio.andy.features.onboarding.ClientLocationFailure
import io.github.escossio.andy.features.onboarding.OnboardingCoordinator
import io.github.escossio.andy.features.onboarding.OnboardingScreen
import io.github.escossio.andy.sdk.clientapi.ClientApprovalDecision
import kotlinx.coroutines.launch

internal const val BOOTSTRAP_TEXT = "Andy"

class MainActivity : ComponentActivity() {
    private lateinit var sessionViewModel: AndySessionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionViewModel = ViewModelProvider(
            this,
            AndySessionViewModel.factory(applicationContext),
        )[AndySessionViewModel::class.java]
        sessionViewModel.attachActivity(this)

        setContent {
            AndyBootstrap(sessionViewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        sessionViewModel.refreshApprovalsIfConnected()
    }

    override fun onDestroy() {
        sessionViewModel.detachActivity(this)
        super.onDestroy()
    }
}

@Composable
private fun AndyBootstrap(
    viewModel: AndySessionViewModel,
) {
    val coordinator: OnboardingCoordinator = viewModel.coordinator
    val approvalCoordinator = viewModel.approvalCoordinator
    val scope = rememberCoroutineScope()
    val state by coordinator.state.collectAsState()
    val bootstrapState by coordinator.bootstrapState.collectAsState()
    val sessionState by coordinator.sessionState.collectAsState()
    val locationState by coordinator.locationState.collectAsState()
    val gmailState by coordinator.gmailState.collectAsState()
    val approvalState by approvalCoordinator.state.collectAsState()

    LaunchedEffect(sessionState) {
        if (sessionState is io.github.escossio.andy.features.onboarding.ClientSessionState.Connected) {
            coordinator.refreshGmailConnection()
            approvalCoordinator.refresh()
        } else {
            approvalCoordinator.reset()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            scope.launch { viewModel.shareCurrentLocation() }
        } else {
            coordinator.failLocation(ClientLocationFailure.PERMISSION_DENIED)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        OnboardingScreen(
            state = state,
            bootstrapState = bootstrapState,
            sessionState = sessionState,
            locationState = locationState,
            gmailState = gmailState,
            onContinue = {
                scope.launch { coordinator.continueWithGoogle() }
            },
            onContinueSession = {
                scope.launch { coordinator.continueWithExistingDevice() }
            },
            onRetryHuman = {
                scope.launch { coordinator.restartAuthentication() }
            },
            onRetryBootstrap = {
                scope.launch { coordinator.retryDeviceBootstrap() }
            },
            onRetrySession = {
                scope.launch { coordinator.retryClientSession() }
            },
            onShareLocation = {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            onConnectGmail = {
                scope.launch { coordinator.connectGmail() }
            },
            onDisconnectGmail = {
                scope.launch { coordinator.disconnectGmail() }
            },
            approvalContent = {
                ApprovalPanel(
                    state = approvalState,
                    onRefresh = {
                        scope.launch { approvalCoordinator.refresh() }
                    },
                    onApprove = { approvalId ->
                        scope.launch {
                            approvalCoordinator.decide(
                                approvalId,
                                ClientApprovalDecision.APPROVE,
                            )
                        }
                    },
                    onDeny = { approvalId ->
                        scope.launch {
                            approvalCoordinator.decide(
                                approvalId,
                                ClientApprovalDecision.DENY,
                            )
                        }
                    },
                )
            },
        )
    }
}
