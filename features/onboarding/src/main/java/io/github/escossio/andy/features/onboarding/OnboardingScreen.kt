package io.github.escossio.andy.features.onboarding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.escossio.andy.core.humanidentity.HumanIdentityState
@Composable fun OnboardingScreen(state:HumanIdentityState,onContinue:()->Unit,onRetry:()->Unit){Column(Modifier.fillMaxSize().padding(24.dp)){when(state){HumanIdentityState.DeviceIdentityUnavailable->BasicText("Device identity is not ready.");HumanIdentityState.Unauthenticated->Action("Continue with Google",onContinue);HumanIdentityState.RequestingChallenge,HumanIdentityState.AcquiringProviderCredential,HumanIdentityState.ValidatingBackend->BasicText("Validating human identity…");is HumanIdentityState.Validated->BasicText("Human identity validated.");is HumanIdentityState.Failure->{BasicText("Human identity could not be validated: ${state.reason.name}");Action("Try again",onRetry)}}}}
@Composable private fun Action(text:String,click:()->Unit){BasicText(text,Modifier.padding(top=16.dp).clickable(onClick=click))}
