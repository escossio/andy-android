# Human Identity + Device Permissions Android V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Andy Android V0.2 with visible Sign in with Google, backend-validated Human Identity, independently requestable foreground location/notifications/camera/microphone permissions, and a stable acceptance-signing path while preserving Device Identity V1.

**Architecture:** `app` remains the composition root. Platform-neutral Human Identity orchestration lives in `core/human-identity`; Google Credential Manager is isolated in `integrations/google-identity`; Client API HTTP lives in `sdk/client-api`; normalized native capability state lives in `capabilities/device-permissions`; and visible onboarding lives in `features/onboarding`. Human Identity is never promoted until Attention Router returns `HUMAN_IDENTITY_VALIDATED`, and Android permission is never treated as backend authority.

**Tech Stack:** Kotlin 2.3.21, AGP 9.4.0, Gradle 9.6.0, JDK 17, minSdk 28, targetSdk 36, compileSdk 37, Compose BOM 2026.08.00, AndroidX Credentials 1.6.0, Google `googleid` 1.2.0, OkHttp 5.5.0, kotlinx.serialization-json 1.11.0, kotlinx.coroutines 1.11.0, existing AndroidX Test/JUnit versions.

**Spec:** `docs/superpowers/specs/2026-09-12-human-identity-permissions-v1-design.md`

## Global Constraints

- Base implementation work on a freshly fetched `origin/main`; at plan-writing time Android `main` was `746c84f64940342022b25c098b884298dc313442`.
- Device Identity V1 is frozen behavior: first-launch provisioning, `READY` self-test, no silent regeneration, no backup restore, and existing real-Keystore instrumentation must stay green.
- Application ID stays exactly `io.github.escossio.andy`.
- Version becomes `versionCode = 2`, `versionName = "0.2.0-human-identity"`.
- Google provider UI uses Credential Manager / Sign in with Google. Do not use deprecated `GoogleSignInClient`.
- Android requests a backend challenge first and passes its nonce into the Google credential request.
- Google ID token is transient: never persist it, never log it, never place it in saved state.
- The Android client does not trust email, Google profile data, or a locally acquired token as Human Identity authority.
- Only the backend response `HUMAN_IDENTITY_VALIDATED` promotes the flow.
- No tenant, active tenant, server `device_id`, device enrollment/binding, final session/access token, refresh token, FCM, WhatsApp, Home Assistant, SMS, contacts, calendar, file/storage access, Bluetooth/Nearby, or conversation UI.
- Permissions in this version are foreground location, notifications, camera, and microphone only.
- No `ACCESS_BACKGROUND_LOCATION`.
- Permission prompts are user-triggered individually; do not auto-chain four native dialogs.
- Denying one permission does not change Human Identity state or another capability state.
- Stable signing material never enters Git, PR candidate execution, logs, artifacts other than signed APKs, or test fixtures.
- PR CI never receives stable signing secrets or a real Google account.
- A real Client API base URL and Google Web Client ID are acceptance configuration, not hardcoded product authority.
- Backend Human Identity V1 must be reviewed/merged before real physical end-to-end acceptance. Deployment remains a separate explicit authorization.

---

## File Structure

New modules and focused files:

- `core/human-identity/`
  - `build.gradle.kts`
  - `src/main/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentity.kt`
  - `src/main/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentityEngine.kt`
  - `src/test/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentityEngineTest.kt`
- `sdk/client-api/`
  - `build.gradle.kts`
  - `src/main/AndroidManifest.xml`
  - `src/main/java/io/github/escossio/andy/sdk/clientapi/AttentionRouterHumanAuthClient.kt`
  - `src/test/java/io/github/escossio/andy/sdk/clientapi/AttentionRouterHumanAuthClientTest.kt`
- `integrations/google-identity/`
  - `build.gradle.kts`
  - `src/main/AndroidManifest.xml`
  - `src/main/java/io/github/escossio/andy/integrations/googleidentity/GoogleCredentialAcquirer.kt`
  - `src/test/java/io/github/escossio/andy/integrations/googleidentity/GoogleCredentialAcquirerTest.kt`
- `capabilities/device-permissions/`
  - `build.gradle.kts`
  - `src/main/AndroidManifest.xml`
  - `src/main/java/io/github/escossio/andy/capabilities/devicepermissions/DeviceCapabilities.kt`
  - `src/main/java/io/github/escossio/andy/capabilities/devicepermissions/AndroidDeviceCapabilityInspector.kt`
  - `src/androidTest/java/io/github/escossio/andy/capabilities/devicepermissions/DeviceCapabilityInstrumentationTest.kt`
- `features/onboarding/`
  - `build.gradle.kts`
  - `src/main/AndroidManifest.xml`
  - `src/main/java/io/github/escossio/andy/features/onboarding/OnboardingCoordinator.kt`
  - `src/main/java/io/github/escossio/andy/features/onboarding/OnboardingScreen.kt`
  - `src/test/java/io/github/escossio/andy/features/onboarding/OnboardingCoordinatorTest.kt`
- Modify `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`.
- Modify `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/io/github/escossio/andy/MainActivity.kt`.

Governance changes are reviewed in a separate governance PR before feature code:
- Create `.github/architecture/frontiers/android-human-identity-permissions-v1.json`
- Modify `.github/workflows/governance.yml`
- Modify `tests/architecture/test_guardrails_policy.py`

---

### Task 1: Admit the new frontier and prepare trusted-main acceptance signing

**Files:**
- Create: `.github/architecture/frontiers/android-human-identity-permissions-v1.json`
- Modify: `.github/workflows/governance.yml`
- Modify: `tests/architecture/test_guardrails_policy.py`

**Interfaces:**
- Produces trusted feature branch name `feat/android-human-identity-permissions-v1`.
- Produces a main-push-only `android-acceptance-build` job gated by repository variable `ANDY_ACCEPTANCE_SIGNING_ENABLED == "true"`.
- The acceptance job may read signing secrets only on trusted `push` to `main`; it never runs for `pull_request_target`.

- [ ] **Step 1: Add failing architecture-governance tests**

Extend `tests/architecture/test_guardrails_policy.py` with tests that require:
1. a frontier named `android-human-identity-permissions-v1`;
2. exact branch `feat/android-human-identity-permissions-v1`;
3. exact allowed/required feature paths listed below;
4. forbidden content includes `android.permission.ACCESS_BACKGROUND_LOCATION`, `READ_CONTACTS`, `READ_SMS`, `RECEIVE_SMS`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `play-services-location`, `firebase`, `androidx.work`, `retrofit`, `ktor-client`, `dagger`, `hilt`, `home.?assistant`, `whatsapp`;
5. `android-acceptance-build` has `if: github.event_name == 'push' && vars.ANDY_ACCEPTANCE_SIGNING_ENABLED == 'true'`;
6. stable signing secrets appear only in that main-push-only job and nowhere in candidate build/instrumentation steps;
7. the acceptance job decodes the keystore under `$RUNNER_TEMP`, runs `assembleAcceptance`, verifies the APK with SDK `apksigner`, uploads only the APK, and removes the temporary keystore with an `if: always()` cleanup step.

Use exact secret names `ANDROID_SIGNING_KEYSTORE_B64`, `ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD`; repository variables `ANDY_ACCEPTANCE_SIGNING_ENABLED`, `GOOGLE_WEB_CLIENT_ID`, `CLIENT_API_BASE_URL`.

- [ ] **Step 2: Run governance tests and verify RED**

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
```

Expected: FAIL because frontier and acceptance job do not exist.

- [ ] **Step 3: Create the exact frontier manifest**

Create `.github/architecture/frontiers/android-human-identity-permissions-v1.json` with `allowed_paths` and `required_artifacts` equal to:

```text
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/io/github/escossio/andy/MainActivity.kt
core/human-identity/build.gradle.kts
core/human-identity/src/main/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentity.kt
core/human-identity/src/main/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentityEngine.kt
core/human-identity/src/test/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentityEngineTest.kt
sdk/client-api/build.gradle.kts
sdk/client-api/src/main/AndroidManifest.xml
sdk/client-api/src/main/java/io/github/escossio/andy/sdk/clientapi/AttentionRouterHumanAuthClient.kt
sdk/client-api/src/test/java/io/github/escossio/andy/sdk/clientapi/AttentionRouterHumanAuthClientTest.kt
integrations/google-identity/build.gradle.kts
integrations/google-identity/src/main/AndroidManifest.xml
integrations/google-identity/src/main/java/io/github/escossio/andy/integrations/googleidentity/GoogleCredentialAcquirer.kt
integrations/google-identity/src/test/java/io/github/escossio/andy/integrations/googleidentity/GoogleCredentialAcquirerTest.kt
capabilities/device-permissions/build.gradle.kts
capabilities/device-permissions/src/main/AndroidManifest.xml
capabilities/device-permissions/src/main/java/io/github/escossio/andy/capabilities/devicepermissions/DeviceCapabilities.kt
capabilities/device-permissions/src/main/java/io/github/escossio/andy/capabilities/devicepermissions/AndroidDeviceCapabilityInspector.kt
capabilities/device-permissions/src/androidTest/java/io/github/escossio/andy/capabilities/devicepermissions/DeviceCapabilityInstrumentationTest.kt
features/onboarding/build.gradle.kts
features/onboarding/src/main/AndroidManifest.xml
features/onboarding/src/main/java/io/github/escossio/andy/features/onboarding/OnboardingCoordinator.kt
features/onboarding/src/main/java/io/github/escossio/andy/features/onboarding/OnboardingScreen.kt
features/onboarding/src/test/java/io/github/escossio/andy/features/onboarding/OnboardingCoordinatorTest.kt
```

Permit `android.permission.INTERNET`, OkHttp, AndroidX Credentials, and `googleid`; continue forbidding unrelated frameworks/providers from Step 1.

- [ ] **Step 4: Add the main-only acceptance job**

Append:

```yaml
android-acceptance-build:
  name: android-acceptance-build
  if: github.event_name == 'push' && vars.ANDY_ACCEPTANCE_SIGNING_ENABLED == 'true'
  runs-on: ubuntu-latest
  timeout-minutes: 25
  permissions:
    contents: read
```

Steps must checkout `${{ github.sha }}` with `persist-credentials: false`; JDK 17; explicit SDK manager path; install Android 37.0/build-tools 36.0.0/platform-tools; decode signing keystore only under `$RUNNER_TEMP`; delete intermediate base64 immediately; export signing env only to Gradle step; export `GOOGLE_WEB_CLIENT_ID` and `CLIENT_API_BASE_URL` from repository variables; require both public vars non-empty; run `./gradlew --no-daemon :app:assembleAcceptance`; verify with `apksigner`; upload `andy-acceptance-apk`; cleanup keystore in `if: always()`. Never echo secrets/base64.

- [ ] **Step 5: Run governance checks GREEN**

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 -I scripts/architecture/check_guardrails.py --policy-self-check
python3 -I scripts/security/scan_secrets.py .
git diff --check origin/main...HEAD
```

Expected: PASS.

- [ ] **Step 6: Open governance PR and stop**

Suggested title: `ci: admit human identity Android frontier`.

Wait for five required checks. Keep `ANDY_ACCEPTANCE_SIGNING_ENABLED` false/unset. Do not merge without user authorization. After authorized merge, branch functional work from new `main`.

---

### Task 2: Add modules and pinned dependencies without behavior

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create build/manifest files for five new modules.

**Interfaces:**
- Adds `:core:human-identity`, `:sdk:client-api`, `:integrations:google-identity`, `:capabilities:device-permissions`, `:features:onboarding`.

- [ ] **Step 1: Verify current module graph is RED for the new frontier**

```bash
./gradlew projects
```

Expected before edits: the five new modules are absent.

- [ ] **Step 2: Pin library versions**

Add to `gradle/libs.versions.toml`:

```toml
credentials = "1.6.0"
googleId = "1.2.0"
okhttp = "5.5.0"
kotlinxSerialization = "1.11.0"
coroutines = "1.11.0"
```

Add aliases for `androidx.credentials:credentials`, `androidx.credentials:credentials-play-services-auth`, `com.google.android.libraries.identity.googleid:googleid`, `com.squareup.okhttp3:okhttp`, `com.squareup.okhttp3:mockwebserver`, `org.jetbrains.kotlinx:kotlinx-serialization-json`, `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `org.jetbrains.kotlinx:kotlinx-coroutines-android`, plus plugin `org.jetbrains.kotlin.plugin.serialization` at Kotlin version.

- [ ] **Step 3: Register plugin and modules**

Root `build.gradle.kts` adds `alias(libs.plugins.kotlin.serialization) apply false`. `settings.gradle.kts` includes the five modules.

- [ ] **Step 4: Create minimal module build files**

`core/human-identity`: Kotlin JVM, JDK17, JUnit, coroutines-core.

`sdk/client-api`: Android library + Kotlin serialization, namespace `io.github.escossio.andy.sdk.clientapi`, compileSdk37/minSdk28/Java17; depends on core human identity, OkHttp, serialization JSON, coroutines-core, JUnit, MockWebServer.

`integrations/google-identity`: Android library namespace `io.github.escossio.andy.integrations.googleidentity`; depends on core, Credentials 1.6.0, Play Services auth bridge 1.6.0, googleid 1.2.0, coroutines-core, JUnit.

`capabilities/device-permissions`: Android library namespace `io.github.escossio.andy.capabilities.devicepermissions`; compileSdk37/minSdk28/Java17; JUnit + current AndroidX test dependencies; `AndroidJUnitRunner`.

`features/onboarding`: Android library + Compose compiler, namespace `io.github.escossio.andy.features.onboarding`; Compose enabled; depends on core human identity, capabilities, Compose BOM/foundation/activity-compose, coroutines-android, JUnit.

Every new Android library manifest is an empty `<manifest>` document.

- [ ] **Step 5: Update app dependencies, version and acceptance build wiring**

App depends on all five modules. Set `versionCode = 2`, `versionName = "0.2.0-human-identity"`; enable `buildConfig`; add BuildConfig strings from environment `GOOGLE_WEB_CLIENT_ID` and `CLIENT_API_BASE_URL`, defaulting to empty strings for PR/debug builds.

Create a stable signing config and `acceptance` build type only when all four `ANDROID_SIGNING_*` environment variables are present. `acceptance` must `initWith(debug)`, have no applicationId suffix, and explicitly replace debug signing with the stable signing config. If any signing env is absent, do not create `acceptance`.

- [ ] **Step 6: Verify graph/build**

```bash
./gradlew projects
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS without signing secrets.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml app/build.gradle.kts core/human-identity sdk/client-api integrations/google-identity capabilities/device-permissions features/onboarding
git commit -m "build: add human identity onboarding modules"
```

---

### Task 3: Implement the platform-neutral Human Identity engine

**Files:**
- Create: `core/human-identity/src/main/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentity.kt`
- Create: `core/human-identity/src/main/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentityEngine.kt`
- Create: `core/human-identity/src/test/kotlin/io/github/escossio/andy/core/humanidentity/HumanIdentityEngineTest.kt`

**Interfaces:**
- `HumanAuthBackend.issueChallenge(): HumanAuthChallenge`
- `HumanAuthBackend.validateGoogle(challengeId, assertion): HumanIdentityValidation`
- `FederatedCredentialAcquirer.acquire(challenge): CredentialAcquisition`
- `HumanIdentityEngine.authenticate(): HumanIdentityOutcome`

- [ ] **Step 1: Write failing engine tests**

Cover challenge-before-provider ordering; nonce passed unchanged; cancellation never validates; provider failure never validates; backend rejection never returns Validated; success returns only opaque ID; `ProviderAssertion.toString()` redacts token.

- [ ] **Step 2: Run RED**

```bash
./gradlew :core:human-identity:test
```

Expected: FAIL.

- [ ] **Step 3: Implement core vocabulary**

```kotlin
package io.github.escossio.andy.core.humanidentity

data class HumanAuthChallenge(val challengeId: String, val nonce: String, val expiresAt: String)

class ProviderAssertion(val value: String) {
    override fun toString(): String = "ProviderAssertion(REDACTED)"
}

sealed interface CredentialAcquisition {
    data class Acquired(val assertion: ProviderAssertion) : CredentialAcquisition
    data object Cancelled : CredentialAcquisition
    data class Failed(val reason: HumanIdentityFailureReason) : CredentialAcquisition
}

data class HumanIdentityValidation(val humanIdentityId: String)

enum class HumanIdentityFailureReason {
    PROVIDER_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    BACKEND_REJECTED,
    CONFIGURATION_UNAVAILABLE,
    INVALID_RESPONSE,
}

sealed interface HumanIdentityOutcome {
    data class Validated(val humanIdentityId: String) : HumanIdentityOutcome
    data object Cancelled : HumanIdentityOutcome
    data class Failed(val reason: HumanIdentityFailureReason) : HumanIdentityOutcome
}

interface FederatedCredentialAcquirer {
    suspend fun acquire(challenge: HumanAuthChallenge): CredentialAcquisition
}

interface HumanAuthBackend {
    suspend fun issueChallenge(): HumanAuthChallenge
    suspend fun validateGoogle(challengeId: String, assertion: ProviderAssertion): HumanIdentityValidation
}

class HumanIdentityBackendRejected : Exception()
```

`HumanIdentityEngine.authenticate()` requests challenge, then credential, then backend validation. Catch backend rejection distinctly; never construct `Validated` from provider acquisition alone.

- [ ] **Step 4: Run GREEN**

```bash
./gradlew :core:human-identity:test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/human-identity
git commit -m "feat: add human identity engine"
```

---

### Task 4: Implement the Client API SDK adapter

**Files:**
- Create: `sdk/client-api/src/main/java/io/github/escossio/andy/sdk/clientapi/AttentionRouterHumanAuthClient.kt`
- Create: `sdk/client-api/src/test/java/io/github/escossio/andy/sdk/clientapi/AttentionRouterHumanAuthClientTest.kt`

**Interfaces:**
- Implements `HumanAuthBackend`.
- Base URL only through constructor; no auth header in this pre-session V1.

- [ ] **Step 1: Write MockWebServer RED tests**

Test exact challenge/validate paths, body field sets, no Authorization header, 201/200 parsing, bounded 401/409/410 rejection, malformed JSON and IO errors without token echo.

- [ ] **Step 2: Run RED**

```bash
./gradlew :sdk:client-api:testDebugUnitTest
```

Expected: FAIL.

- [ ] **Step 3: Implement DTOs/client**

Use private `@Serializable` DTOs matching `challenge_id`, `nonce`, `expires_at`, `id_token`, `status`, `human_identity_id`; `Json { ignoreUnknownKeys = false }`; OkHttp 5.5.0 and `withContext(Dispatchers.IO)`. Constructor rejects blank/non-HTTP(S) base URL as a bounded configuration exception. Set `Content-Type: application/json`; never add Authorization. Never include response bodies or provider assertion in thrown messages.

- [ ] **Step 4: Run GREEN**

```bash
./gradlew :sdk:client-api:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdk/client-api
git commit -m "feat: add Human Identity client API SDK"
```

---

### Task 5: Implement Google Credential Manager adapter

**Files:**
- Create: `integrations/google-identity/src/main/java/io/github/escossio/andy/integrations/googleidentity/GoogleCredentialAcquirer.kt`
- Create: `integrations/google-identity/src/test/java/io/github/escossio/andy/integrations/googleidentity/GoogleCredentialAcquirerTest.kt`

**Interfaces:**
- Implements `FederatedCredentialAcquirer` through an injected testable UI port.

- [ ] **Step 1: Write RED tests**

Assert blank Web Client ID gives configuration unavailable without UI; challenge nonce passed unchanged; token result becomes Acquired; cancellation maps Cancelled; provider failure maps PROVIDER_UNAVAILABLE; token never appears in result/exception stringification.

- [ ] **Step 2: Run RED**

```bash
./gradlew :integrations:google-identity:testDebugUnitTest
```

Expected: FAIL.

- [ ] **Step 3: Implement UI port and coordinator**

Define `GoogleCredentialUiResult` (`Token`, `Cancelled`, `Failed`) and `GoogleCredentialUiPort.requestIdToken(serverClientId, nonce)`. `GoogleCredentialAcquirer` maps this to core `CredentialAcquisition`.

- [ ] **Step 4: Implement real Credential Manager port**

Build `GetGoogleIdOption` with `setServerClientId`, `setNonce`, `setFilterByAuthorizedAccounts(false)`, `setAutoSelectEnabled(false)`; use `CredentialManager.create(context).getCredential(context, request)`; accept only Google ID-token custom credential; parse `GoogleIdTokenCredential`; return only `idToken`; map `GetCredentialCancellationException` separately; never log bundle/token.

- [ ] **Step 5: Run GREEN**

```bash
./gradlew :integrations:google-identity:testDebugUnitTest
```

Expected: PASS without a real Google account.

- [ ] **Step 6: Commit**

```bash
git add integrations/google-identity
git commit -m "feat: add Google Credential Manager integration"
```

---

### Task 6: Implement typed device permission/capability observation

**Files:**
- Create: `capabilities/device-permissions/src/main/java/io/github/escossio/andy/capabilities/devicepermissions/DeviceCapabilities.kt`
- Create: `capabilities/device-permissions/src/main/java/io/github/escossio/andy/capabilities/devicepermissions/AndroidDeviceCapabilityInspector.kt`
- Create: `capabilities/device-permissions/src/androidTest/java/io/github/escossio/andy/capabilities/devicepermissions/DeviceCapabilityInstrumentationTest.kt`

**Interfaces:**
- Kinds `LOCATION`, `NOTIFICATIONS`, `CAMERA`, `MICROPHONE`.
- Support `SUPPORTED/UNSUPPORTED`; permission `NOT_REQUESTED/GRANTED/DENIED`; operational `OPERATIONAL/UNAVAILABLE`.
- `DeviceCapabilityInspector.read(kind)` and `requiredPermissions(kind)`.

- [ ] **Step 1: Write instrumentation RED tests**

Assert support derives from platform features; location granted if coarse or fine; notification permission normalized by API level; location operational requires permission + LocationManager enabled; notification operational uses NotificationManager enabled; required LOCATION permissions coarse+fine; no background permission anywhere.

- [ ] **Step 2: Run RED**

```bash
./gradlew :capabilities:device-permissions:connectedDebugAndroidTest
```

Expected: FAIL.

- [ ] **Step 3: Implement normalized inspector**

Use platform APIs only. Required permission lists: LOCATION coarse+fine; NOTIFICATIONS POST_NOTIFICATIONS on API33+ else empty; CAMERA CAMERA; MICROPHONE RECORD_AUDIO. Distinguish NOT_REQUESTED vs DENIED using an injected/request-history predicate owned by onboarding; default ungranted without history to NOT_REQUESTED.

- [ ] **Step 4: Run GREEN**

```bash
./gradlew :capabilities:device-permissions:connectedDebugAndroidTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add capabilities/device-permissions
git commit -m "feat: model Android device permission capabilities"
```

---

### Task 7: Build onboarding coordinator and Compose UI

**Files:**
- Create: `features/onboarding/src/main/java/io/github/escossio/andy/features/onboarding/OnboardingCoordinator.kt`
- Create: `features/onboarding/src/main/java/io/github/escossio/andy/features/onboarding/OnboardingScreen.kt`
- Create: `features/onboarding/src/test/java/io/github/escossio/andy/features/onboarding/OnboardingCoordinatorTest.kt`

**Interfaces:**
- `signInWithGoogle()`, `refreshCapabilities()`.
- UI: unauthenticated/signing-in/validated/bounded-error; permission cards only after validation.

- [ ] **Step 1: Write coordinator RED tests**

Prove initial unauthenticated; success sets validated; cancel returns unauthenticated; failure is bounded; permission cards disabled before validation; denial of LOCATION preserves validated identity and other capability states.

- [ ] **Step 2: Run RED**

```bash
./gradlew :features:onboarding:testDebugUnitTest
```

Expected: FAIL.

- [ ] **Step 3: Implement coordinator**

Keep Human Identity reference only in memory in this no-session V1. Maintain an in-memory set of capability kinds that have been user-requested so ungranted state can become DENIED during the process. Never store Google assertion/token.

- [ ] **Step 4: Implement Compose screen**

Flow: `Andy` title; `Continuar com Google`; `Conectando…`; bounded failure + `Tentar novamente`; after validation show `Identidade validada` and cards `Localização`, `Notificações`, `Câmera`, `Microfone`. Each card has `Permitir` only when meaningful. Use one `rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())` and request only tapped card permissions. Never auto-launch next card. Foundation/Activity Compose only; no Material/navigation/ViewModel/DI.

- [ ] **Step 5: Run GREEN**

```bash
./gradlew :features:onboarding:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add features/onboarding
git commit -m "feat: add Google and permission onboarding UI"
```

---

### Task 8: Wire app composition root and manifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/io/github/escossio/andy/MainActivity.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Existing Device Identity remains first startup gate.
- App constructs SDK, Google integration, capability inspector, Human Identity engine, onboarding coordinator from BuildConfig public config.

- [ ] **Step 1: Add manifest RED assertion in permitted instrumentation test**

Require exactly these new permissions: INTERNET, ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS, CAMERA, RECORD_AUDIO. Assert BACKGROUND_LOCATION, contacts, SMS and Bluetooth/Nearby are absent.

- [ ] **Step 2: Run RED**

```bash
./gradlew :capabilities:device-permissions:connectedDebugAndroidTest
```

Expected: FAIL because app manifest lacks permissions.

- [ ] **Step 3: Modify app manifest**

Add only the six approved permissions before `<application>`. Do not change backup behavior, application ID, exported launcher semantics, or add provider services.

- [ ] **Step 4: Wire MainActivity**

Keep `AndroidDeviceIdentityFactory.create(applicationContext).ensureIdentity()` first. Ready logs only `DEVICE_IDENTITY_READY`, constructs `AttentionRouterHumanAuthClient(BuildConfig.CLIENT_API_BASE_URL)`, real Google acquirer with `BuildConfig.GOOGLE_WEB_CLIENT_ID`, `HumanIdentityEngine`, `AndroidDeviceCapabilityInspector`, `OnboardingCoordinator`, then renders `OnboardingScreen`. Device identity unavailable logs bounded reason and shows a fail-closed message without invoking Google/backend. Never log base URL, Google Client ID, Human Identity ID, token, or location.

- [ ] **Step 5: Run focused build/test set**

```bash
./gradlew --no-daemon :core:device-identity:test :core:human-identity:test :sdk:client-api:testDebugUnitTest :integrations:google-identity:testDebugUnitTest :features:onboarding:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Then, with emulator available:

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest :capabilities:device-permissions:connectedDebugAndroidTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/io/github/escossio/andy/MainActivity.kt
git commit -m "feat: wire Human Identity onboarding into Andy"
```

---

### Task 9: Full Android verification and functional PR gate

**Files:**
- No new production files unless verification exposes a defect.

- [ ] **Step 1: Verify exact frontier**

Run architecture guard using current repository instructions; functional PR must not change governance files.

- [ ] **Step 2: Run module tests/build**

```bash
./gradlew --no-daemon :core:device-identity:test :core:human-identity:test :sdk:client-api:testDebugUnitTest :integrations:google-identity:testDebugUnitTest :features:onboarding:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 3: Run real Android instrumentation**

Use existing KVM/AVD CI path and require both Device Identity and capability instrumentation. Expected: PASS.

- [ ] **Step 4: Run governance/secret checks**

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 -I scripts/architecture/check_guardrails.py --policy-self-check
python3 -I scripts/security/scan_secrets.py .
git diff --check origin/main...HEAD
```

Expected: PASS.

- [ ] **Step 5: Open functional PR; do not merge**

Suggested title: `feat: add Human Identity and device permission onboarding`.

PR states Device Identity preserved; Google not trusted before backend; no tenant/session/device enrollment; four permissions user-triggered; no background location; no real Google account in CI; stable signing main-only. Wait for all five required checks including `android-instrumentation`.

- [ ] **Step 6: Stop at exact-SHA review gate**

Record head SHA, check results, changed paths and review-thread state. `MERGED=NO` until explicit user authorization.

---

### Task 10: Establish stable signing + Google Auth configuration after code review

**Files:**
- No repository secret material.
- External configuration only.

**Interfaces:**
- Stable signing certificate for `io.github.escossio.andy`.
- Google Android OAuth client bound to package + stable SHA-1.
- Google Web OAuth Client ID shared by Android `setServerClientId` and backend `GOOGLE_OAUTH_CLIENT_ID`.

This begins only after governance/functional PRs are user-approved/merged and backend V1 has a reviewed deploy candidate.

- [ ] **Step 1: Generate stable development signing key outside Git**

```bash
mkdir -p "$HOME/.local/share/andy-signing"
keytool -genkeypair -keystore "$HOME/.local/share/andy-signing/andy-development.jks" -alias andy-development -keyalg RSA -keysize 3072 -validity 9125
```

Passwords entered interactively; never place in shell history/chat/Git/screenshots.

- [ ] **Step 2: Record only public certificate fingerprints**

```bash
keytool -list -v -keystore "$HOME/.local/share/andy-signing/andy-development.jks" -alias andy-development
```

Record SHA-1/SHA-256 only.

- [ ] **Step 3: Configure Google Auth Platform**

Create/confirm Android OAuth client for package `io.github.escossio.andy` + stable SHA-1, and a Web application OAuth client used as server audience. No client secret in Android.

- [ ] **Step 4: Configure GitHub trusted-main packaging**

Add four signing secrets and repository variables `GOOGLE_WEB_CLIENT_ID`, `CLIENT_API_BASE_URL`, `ANDY_ACCEPTANCE_SIGNING_ENABLED=true`. Never expose secrets to PR workflows.

- [ ] **Step 5: Configure Attention Router acceptance deployment**

Set backend `GOOGLE_OAUTH_CLIENT_ID` to the exact Web Client ID and `HUMAN_AUTH_CHALLENGE_TTL_SECONDS=300`. Deployment/migration/restart requires separate explicit user authorization.

---

### Task 11: Physical-phone acceptance and update continuity

**Prerequisites:** backend deployed at approved HTTPS endpoint; stable-signed Android acceptance build from trusted main; Google OAuth clients configured.

- [ ] **Step 1: Establish stable-signing baseline**

Current V0.1 physical build uses an ephemeral runner debug cert, so one controlled uninstall/install of stable-signed V0.2 is required. This intentionally creates the new long-lived Device Identity baseline.

- [ ] **Step 2: Confirm Device Identity**

```bash
adb logcat -c
adb shell am force-stop io.github.escossio.andy
adb shell am start -n io.github.escossio.andy/.MainActivity
sleep 2
adb logcat -d -s AndyDeviceIdentity:I '*:S'
```

Expected: `DEVICE_IDENTITY_READY`.

- [ ] **Step 3: Exercise real Google sign-in**

Tap `Continuar com Google`, select account, confirm `Identidade validada`. Backend audit/logs must not contain raw token/sub/email.

- [ ] **Step 4: Exercise permissions independently**

Tap Location, Notifications, Camera, Microphone cards separately. Grant/deny combinations must leave Human Identity validated and other cards actionable; no background-location prompt.

- [ ] **Step 5: Prove update continuity on next stable build**

Install a later APK with higher versionCode and same stable signing key over V0.2 without uninstalling; confirm Device Identity still reaches READY. Do not weaken privacy logging merely to expose fingerprint.

- [ ] **Step 6: Record acceptance result**

```text
HUMAN_IDENTITY_ANDROID_V1_STATUS=PASS/FAIL
STABLE_SIGNING_BASELINE=PASS/FAIL
GOOGLE_REAL_SIGN_IN=PASS/FAIL
BACKEND_HUMAN_IDENTITY_VALIDATED=PASS/FAIL
LOCATION_PERMISSION_FLOW=PASS/FAIL
NOTIFICATION_PERMISSION_FLOW=PASS/FAIL
CAMERA_PERMISSION_FLOW=PASS/FAIL
MICROPHONE_PERMISSION_FLOW=PASS/FAIL
BACKGROUND_LOCATION_REQUESTED=NO
DEVICE_IDENTITY_READY_AFTER_INSTALL=YES/NO
```
