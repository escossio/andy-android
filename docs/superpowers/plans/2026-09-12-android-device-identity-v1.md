# Android Device Identity V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an installation-scoped, automatically provisioned, fail-closed Android device identity backed by a non-exportable P-256 Android Keystore key, without changing the visible Andy bootstrap UI or contacting any backend.

**Architecture:** Add a Kotlin-only `core:device-identity` module containing the public result contract, fingerprint type, metadata abstractions, and deterministic state-machine engine. Add an Android `data:device-identity` module implementing those abstractions with Android Keystore, `AtomicFile`, and `noBackupFilesDir`. `app` only invokes the manager during startup and logs bounded readiness/unavailable events; it does not know cryptographic details.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.4.0, Gradle 9.6.0, JDK 17, minSdk 28, targetSdk 36, compileSdk 37, AndroidX Test Runner 1.7.0, AndroidX Test Ext JUnit 1.3.0, JUnit 4.13.2, Android Keystore, `SHA256withECDSA`, `AtomicFile`.

**Spec:** `docs/superpowers/specs/2026-09-12-android-device-identity-v1-design.md`

## Global Constraints

- Do not start until the governance/CI plan `docs/superpowers/plans/2026-09-12-android-device-identity-v1-governance-ci.md` is merged and `android-instrumentation` is a required check.
- Feature branch: `feat/android-device-identity-v1`.
- The feature PR may modify only paths listed by `.github/architecture/frontiers/android-device-identity-v1.json`.
- Reinstall means a new identity. No backup/restore of identity metadata.
- First launch provisions automatically; later launches reuse the same identity.
- Local fingerprint is not server `device_id` and grants no server authority.
- Key algorithm: EC P-256 / `secp256r1`.
- Signature algorithm: `SHA256withECDSA`.
- Private key remains non-exportable in Android Keystore.
- StrongBox is optional and must not be required.
- Per-use biometric/PIN authentication is not required for the device identity key.
- Fingerprint format is exactly `sha256:` followed by 64 lowercase hexadecimal characters.
- Fingerprint input is X.509 SubjectPublicKeyInfo DER (`PublicKey.encoded`).
- Lifecycle: `ABSENT -> PROVISIONING -> READY`; only `PROVISIONING` may self-recover.
- A `READY` identity is never silently regenerated, deleted, rotated, or replaced.
- Every valid launch recalculates the fingerprint and runs a fresh local sign/verify self-test.
- Metadata lives only in `noBackupFilesDir/device_identity_v1.json` and uses atomic replacement.
- No Room, DataStore, Hilt, Dagger, Retrofit, OkHttp, Ktor client, Firebase, Google Play Services, WorkManager, analytics, background workers, provider SDKs, network permission, HTTP, backend URL, tenant logic, human identity, session logic, or enrollment.
- No public arbitrary signing API.
- Visible UI must remain the current centered `Andy` text.
- Normal logs must never contain full fingerprint, public key bytes, private key material, signatures, challenges, tenant/device backend IDs, phone, location, token, or infrastructure details.
- Notebook and physical phone are not automatic gates for this frontier.
- Do not merge automatically. Stop for explicit human approval after exact-SHA CI evidence.

---

## File Structure

**Modify:**
- `settings.gradle.kts` — include the two new modules.
- `build.gradle.kts` — declare Android library and Kotlin JVM plugins with `apply false`.
- `gradle/libs.versions.toml` — add plugin aliases and stable AndroidX Test versions.
- `app/build.gradle.kts` — depend on core/data modules.
- `app/src/main/java/io/github/escossio/andy/MainActivity.kt` — invoke identity startup before unchanged Compose content.

**Create core module:**
- `core/device-identity/build.gradle.kts`
- `core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentity.kt`
- `core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt`
- `core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt`
- `core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceKeyFingerprintTest.kt`

**Create Android data module:**
- `data/device-identity/build.gradle.kts`
- `data/device-identity/src/main/AndroidManifest.xml`
- `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt`
- `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt`
- `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/NoBackupDeviceIdentityMetadataRepository.kt`
- `data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt`

---

### Task 1: Wire the new modules and test dependencies

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `core/device-identity/build.gradle.kts`
- Create: `data/device-identity/build.gradle.kts`
- Create: `data/device-identity/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces Gradle modules `:core:device-identity` and `:data:device-identity`.
- `:data:device-identity` depends on `:core:device-identity`.
- `:app` depends on both modules.

- [ ] **Step 1: Update the version catalog**

Add exact versions and aliases:

```toml
[versions]
agp = "9.4.0"
kotlin = "2.3.21"
composeBom = "2026.08.00"
activityCompose = "1.13.0"
junit = "4.13.2"
androidxTestRunner = "1.7.0"
androidxTestExtJunit = "1.3.0"

[libraries]
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
junit = { module = "junit:junit", version.ref = "junit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Declare the new root plugins**

Change root `build.gradle.kts` to:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

- [ ] **Step 3: Include both modules**

Append to `settings.gradle.kts`:

```kotlin
include(":core:device-identity")
include(":data:device-identity")
```

Keep existing repositories and `include(":app")` unchanged.

- [ ] **Step 4: Create the Kotlin-only core module build file**

Create `core/device-identity/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
```

- [ ] **Step 5: Create the Android data module build file**

Create `data/device-identity/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.escossio.andy.data.deviceidentity"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:device-identity"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```

- [ ] **Step 6: Create the data module manifest with no permissions**

Create `data/device-identity/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

This intentionally declares no `INTERNET` permission.

- [ ] **Step 7: Add module dependencies to the app**

Add to `app/build.gradle.kts` dependencies:

```kotlin
implementation(project(":core:device-identity"))
implementation(project(":data:device-identity"))
```

Keep current Compose dependencies and JUnit dependency unchanged.

- [ ] **Step 8: Run Gradle project discovery**

Run:

```bash
./gradlew --no-daemon projects
```

Expected output includes:

```text
Project ':app'
Project ':core:device-identity'
Project ':data:device-identity'
```

- [ ] **Step 9: Run empty-module compile tasks**

Run:

```bash
./gradlew --no-daemon \
  :core:device-identity:test \
  :data:device-identity:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 10: Commit Task 1**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml \
  app/build.gradle.kts core/device-identity/build.gradle.kts \
  data/device-identity/build.gradle.kts data/device-identity/src/main/AndroidManifest.xml
git commit -m "build: add device identity modules"
```

---

### Task 2: Implement the public contract and canonical fingerprint

**Files:**
- Create: `core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentity.kt`
- Create: `core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceKeyFingerprintTest.kt`

**Interfaces:**
- Produces `DeviceIdentityManager.ensureIdentity(): DeviceIdentityResult`.
- Produces `DeviceKeyFingerprint.fromSubjectPublicKeyInfo(bytes)` and `DeviceKeyFingerprint.parse(value)`.
- Produces bounded `DeviceIdentityUnavailableReason` values.

- [ ] **Step 1: Write failing fingerprint and contract tests**

Create `DeviceKeyFingerprintTest.kt`:

```kotlin
package io.github.escossio.andy.core.deviceidentity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeyFingerprintTest {
    @Test
    fun fingerprintUsesSha256PrefixAndLowercaseHex() {
        val fingerprint = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(byteArrayOf(1, 2, 3, 4))
        assertTrue(fingerprint.value.matches(Regex("^sha256:[0-9a-f]{64}$")))
    }

    @Test
    fun fingerprintIsDeterministic() {
        val bytes = byteArrayOf(9, 8, 7, 6)
        assertEquals(
            DeviceKeyFingerprint.fromSubjectPublicKeyInfo(bytes),
            DeviceKeyFingerprint.fromSubjectPublicKeyInfo(bytes),
        )
    }

    @Test
    fun parseRejectsNonCanonicalValues() {
        assertNull(DeviceKeyFingerprint.parse("SHA256:abc"))
        assertNull(DeviceKeyFingerprint.parse("sha256:ABCDEF"))
        assertNull(DeviceKeyFingerprint.parse("sha256:1234"))
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

```bash
./gradlew --no-daemon :core:device-identity:test --tests '*DeviceKeyFingerprintTest'
```

Expected: compile failure because `DeviceKeyFingerprint` does not exist.

- [ ] **Step 3: Implement `DeviceIdentity.kt`**

Create:

```kotlin
package io.github.escossio.andy.core.deviceidentity

import java.security.MessageDigest

@JvmInline
value class DeviceKeyFingerprint private constructor(val value: String) {
    companion object {
        private val canonical = Regex("^sha256:[0-9a-f]{64}$")

        fun fromSubjectPublicKeyInfo(encoded: ByteArray): DeviceKeyFingerprint {
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
            val hex = digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return DeviceKeyFingerprint("sha256:$hex")
        }

        fun parse(value: String): DeviceKeyFingerprint? =
            value.takeIf(canonical::matches)?.let(::DeviceKeyFingerprint)
    }
}

sealed interface DeviceIdentityResult {
    data class Ready(val fingerprint: DeviceKeyFingerprint) : DeviceIdentityResult
    data class Unavailable(val reason: DeviceIdentityUnavailableReason) : DeviceIdentityResult
}

enum class DeviceIdentityUnavailableReason {
    METADATA_MALFORMED,
    METADATA_UNSUPPORTED,
    ESTABLISHED_KEY_MISSING,
    ESTABLISHED_KEY_INACCESSIBLE,
    FINGERPRINT_MISMATCH,
    SELF_TEST_FAILED,
    INCONSISTENT_FRESH_INSTALL,
    PROVISIONING_FAILED,
}

interface DeviceIdentityManager {
    fun ensureIdentity(): DeviceIdentityResult
}

enum class StoredIdentityState {
    PROVISIONING,
    READY,
}

data class StoredIdentityMetadata(
    val schemaVersion: Int,
    val state: StoredIdentityState,
    val keyAlias: String,
    val publicKeyFingerprint: DeviceKeyFingerprint?,
)

sealed interface MetadataLoadResult {
    data object Absent : MetadataLoadResult
    data class Present(val metadata: StoredIdentityMetadata) : MetadataLoadResult
    data class Invalid(val reason: DeviceIdentityUnavailableReason) : MetadataLoadResult
}

interface DeviceIdentityMetadataRepository {
    fun load(): MetadataLoadResult
    fun writeProvisioning(keyAlias: String)
    fun writeReady(keyAlias: String, fingerprint: DeviceKeyFingerprint)
}

interface DeviceIdentityCrypto {
    fun hasKey(keyAlias: String): Boolean
    fun generateKey(keyAlias: String)
    fun publicKeySubjectPublicKeyInfo(keyAlias: String): ByteArray
    fun signForSelfTest(keyAlias: String, challenge: ByteArray): ByteArray
    fun verifySelfTest(keyAlias: String, challenge: ByteArray, signature: ByteArray): Boolean
}
```

`DeviceIdentityCrypto` is an infrastructure SPI used by the engine/data adapter; it is not the application-facing signing API. `DeviceIdentityManager` remains the only consumer-facing capability.

- [ ] **Step 4: Run the core tests and verify GREEN**

```bash
./gradlew --no-daemon :core:device-identity:test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentity.kt \
  core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceKeyFingerprintTest.kt
git commit -m "feat: define device identity contract"
```

---

### Task 3: Implement the fail-closed state-machine engine

**Files:**
- Create: `core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt`
- Create: `core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt`

**Interfaces:**
- Consumes: `DeviceIdentityMetadataRepository`, `DeviceIdentityCrypto`, `DeviceKeyFingerprint`.
- Produces: `DeviceIdentityEngine : DeviceIdentityManager`.
- Stable constants: schema version `1`; alias `andy_device_identity_v1`.

- [ ] **Step 1: Write the fake dependencies and failing happy-path tests**

Create `DeviceIdentityEngineTest.kt` with fakes that record generation count and signatures. Start with these tests:

```kotlin
package io.github.escossio.andy.core.deviceidentity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertIs
import org.junit.Test

class DeviceIdentityEngineTest {
    private val publicKey = byteArrayOf(1, 3, 3, 7)

    @Test
    fun absentStateProvisionsOnceAndBecomesReady() {
        val metadata = FakeMetadataRepository(MetadataLoadResult.Absent)
        val crypto = FakeCrypto(keyExists = false, publicKey = publicKey)
        val engine = engine(metadata, crypto)

        val result = engine.ensureIdentity()

        assertIs<DeviceIdentityResult.Ready>(result)
        assertEquals(1, crypto.generateCount)
        assertEquals(StoredIdentityState.READY, metadata.current().state)
    }

    @Test
    fun readyStateReusesExistingIdentityWithoutGeneration() {
        val fingerprint = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(publicKey)
        val metadata = FakeMetadataRepository(
            MetadataLoadResult.Present(
                StoredIdentityMetadata(1, StoredIdentityState.READY,
                    DEVICE_IDENTITY_KEY_ALIAS, fingerprint),
            ),
        )
        val crypto = FakeCrypto(keyExists = true, publicKey = publicKey)
        val result = engine(metadata, crypto).ensureIdentity()

        assertEquals(DeviceIdentityResult.Ready(fingerprint), result)
        assertEquals(0, crypto.generateCount)
    }
}
```

Use `org.junit.Assert.assertTrue(result is DeviceIdentityResult.Ready)` if the JUnit version does not provide `assertIs`; do not add another assertion library.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew --no-daemon :core:device-identity:test --tests '*DeviceIdentityEngineTest'
```

Expected: compile failure because `DeviceIdentityEngine` and constants do not exist.

- [ ] **Step 3: Implement the engine skeleton and happy paths**

Create `DeviceIdentityEngine.kt` with these exact public constants and constructor shape:

```kotlin
package io.github.escossio.andy.core.deviceidentity

const val DEVICE_IDENTITY_SCHEMA_VERSION = 1
const val DEVICE_IDENTITY_KEY_ALIAS = "andy_device_identity_v1"

class DeviceIdentityEngine(
    private val metadataRepository: DeviceIdentityMetadataRepository,
    private val crypto: DeviceIdentityCrypto,
    private val challengeGenerator: () -> ByteArray,
) : DeviceIdentityManager {
    override fun ensureIdentity(): DeviceIdentityResult = when (val loaded = metadataRepository.load()) {
        MetadataLoadResult.Absent -> provisionFresh()
        is MetadataLoadResult.Invalid -> DeviceIdentityResult.Unavailable(loaded.reason)
        is MetadataLoadResult.Present -> when (loaded.metadata.state) {
            StoredIdentityState.PROVISIONING -> resumeProvisioning(loaded.metadata)
            StoredIdentityState.READY -> validateReady(loaded.metadata)
        }
    }

    private fun provisionFresh(): DeviceIdentityResult {
        return try {
            if (crypto.hasKey(DEVICE_IDENTITY_KEY_ALIAS)) {
                return DeviceIdentityResult.Unavailable(
                    DeviceIdentityUnavailableReason.INCONSISTENT_FRESH_INSTALL,
                )
            }
            metadataRepository.writeProvisioning(DEVICE_IDENTITY_KEY_ALIAS)
            crypto.generateKey(DEVICE_IDENTITY_KEY_ALIAS)
            completeProvisioning()
        } catch (_: Exception) {
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.PROVISIONING_FAILED)
        }
    }

    private fun resumeProvisioning(metadata: StoredIdentityMetadata): DeviceIdentityResult {
        if (metadata.keyAlias != DEVICE_IDENTITY_KEY_ALIAS) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.METADATA_MALFORMED)
        }
        return try {
            if (!crypto.hasKey(DEVICE_IDENTITY_KEY_ALIAS)) {
                crypto.generateKey(DEVICE_IDENTITY_KEY_ALIAS)
            }
            completeProvisioning()
        } catch (_: Exception) {
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.PROVISIONING_FAILED)
        }
    }

    private fun completeProvisioning(): DeviceIdentityResult {
        val fingerprint = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(
            crypto.publicKeySubjectPublicKeyInfo(DEVICE_IDENTITY_KEY_ALIAS),
        )
        if (!selfTest()) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.SELF_TEST_FAILED)
        }
        metadataRepository.writeReady(DEVICE_IDENTITY_KEY_ALIAS, fingerprint)
        return DeviceIdentityResult.Ready(fingerprint)
    }

    private fun validateReady(metadata: StoredIdentityMetadata): DeviceIdentityResult {
        if (metadata.schemaVersion != DEVICE_IDENTITY_SCHEMA_VERSION ||
            metadata.keyAlias != DEVICE_IDENTITY_KEY_ALIAS ||
            metadata.publicKeyFingerprint == null
        ) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.METADATA_MALFORMED)
        }
        return try {
            if (!crypto.hasKey(DEVICE_IDENTITY_KEY_ALIAS)) {
                return DeviceIdentityResult.Unavailable(
                    DeviceIdentityUnavailableReason.ESTABLISHED_KEY_MISSING,
                )
            }
            val actual = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(
                crypto.publicKeySubjectPublicKeyInfo(DEVICE_IDENTITY_KEY_ALIAS),
            )
            if (actual != metadata.publicKeyFingerprint) {
                return DeviceIdentityResult.Unavailable(
                    DeviceIdentityUnavailableReason.FINGERPRINT_MISMATCH,
                )
            }
            if (!selfTest()) {
                return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.SELF_TEST_FAILED)
            }
            DeviceIdentityResult.Ready(actual)
        } catch (_: Exception) {
            DeviceIdentityResult.Unavailable(
                DeviceIdentityUnavailableReason.ESTABLISHED_KEY_INACCESSIBLE,
            )
        }
    }

    private fun selfTest(): Boolean {
        val challenge = challengeGenerator()
        val signature = crypto.signForSelfTest(DEVICE_IDENTITY_KEY_ALIAS, challenge)
        return crypto.verifySelfTest(DEVICE_IDENTITY_KEY_ALIAS, challenge, signature)
    }
}
```

- [ ] **Step 4: Make happy-path tests GREEN**

```bash
./gradlew --no-daemon :core:device-identity:test --tests '*DeviceIdentityEngineTest'
```

Expected: current happy-path tests PASS.

- [ ] **Step 5: Add the fail-closed test matrix**

Add tests with the fakes for all of these exact behaviors:

```kotlin
@Test fun provisioningWithExistingKeyCompletesWithoutGeneratingAgain()
@Test fun provisioningWithoutKeyGeneratesAndCompletes()
@Test fun readyWithMissingKeyIsUnavailableAndNeverGenerates()
@Test fun readyWithFingerprintMismatchIsUnavailableAndNeverGenerates()
@Test fun readyWithFailedSelfTestIsUnavailableAndNeverGenerates()
@Test fun metadataInvalidIsReturnedUnavailableWithoutKeyMutation()
@Test fun absentMetadataWithUnexpectedKeyIsUnavailableAndNeverDeletesOrGenerates()
@Test fun establishedKeyExceptionIsUnavailableAndNeverGenerates()
```

For every `READY` failure test, assert `crypto.generateCount == 0`.

- [ ] **Step 6: Run the complete core test suite**

```bash
./gradlew --no-daemon :core:device-identity:test
```

Expected: PASS with all state-machine and fingerprint cases.

- [ ] **Step 7: Commit Task 3**

```bash
git add core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt \
  core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt
git commit -m "feat: add fail-closed device identity engine"
```

---

### Task 4: Implement atomic no-backup metadata persistence

**Files:**
- Create: `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/NoBackupDeviceIdentityMetadataRepository.kt`
- Extend test: `data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt`

**Interfaces:**
- Implements `DeviceIdentityMetadataRepository`.
- File name: `device_identity_v1.json` inside `Context.noBackupFilesDir`.
- Uses `android.util.AtomicFile`.

- [ ] **Step 1: Create the instrumentation test file with metadata tests first**

Create `AndroidDeviceIdentityInstrumentationTest.kt` and begin with:

```kotlin
package io.github.escossio.andy.data.deviceidentity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityUnavailableReason
import io.github.escossio.andy.core.deviceidentity.MetadataLoadResult
import io.github.escossio.andy.core.deviceidentity.StoredIdentityState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidDeviceIdentityInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val metadataFile = File(context.noBackupFilesDir, "device_identity_v1.json")

    @After
    fun cleanupMetadata() {
        metadataFile.delete()
    }

    @Test
    fun metadataLivesUnderNoBackupFilesDirAndRoundTripsReady() {
        val repository = NoBackupDeviceIdentityMetadataRepository(context)
        repository.writeProvisioning("andy_device_identity_v1")
        assertTrue(metadataFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        val loaded = repository.load() as MetadataLoadResult.Present
        assertEquals(StoredIdentityState.PROVISIONING, loaded.metadata.state)
    }

    @Test
    fun malformedMetadataFailsClosed() {
        metadataFile.writeText("not-json")
        val loaded = NoBackupDeviceIdentityMetadataRepository(context).load()
        assertEquals(
            MetadataLoadResult.Invalid(DeviceIdentityUnavailableReason.METADATA_MALFORMED),
            loaded,
        )
    }
}
```

Add `androidx.test:core:1.7.0` if `ApplicationProvider` is not transitively available through runner; if needed, add an explicit `androidx-test-core` catalog entry at version `1.7.0` only if the compile error proves it is required. If that new dependency path is not allowed by the frontier, stop with `FRONTIER_EXPANSION_REQUIRED` rather than modifying governance inside the feature PR.

- [ ] **Step 2: Run instrumentation and verify RED**

With an Android emulator/device available through the approved CI/local environment:

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: compile failure because `NoBackupDeviceIdentityMetadataRepository` does not exist.

- [ ] **Step 3: Implement the repository with `AtomicFile`**

Create:

```kotlin
package io.github.escossio.andy.data.deviceidentity

import android.content.Context
import android.util.AtomicFile
import io.github.escossio.andy.core.deviceidentity.DEVICE_IDENTITY_SCHEMA_VERSION
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityMetadataRepository
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityUnavailableReason
import io.github.escossio.andy.core.deviceidentity.DeviceKeyFingerprint
import io.github.escossio.andy.core.deviceidentity.MetadataLoadResult
import io.github.escossio.andy.core.deviceidentity.StoredIdentityMetadata
import io.github.escossio.andy.core.deviceidentity.StoredIdentityState
import org.json.JSONObject
import java.io.File

internal class NoBackupDeviceIdentityMetadataRepository(context: Context) :
    DeviceIdentityMetadataRepository {

    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val atomicFile = AtomicFile(file)

    override fun load(): MetadataLoadResult {
        if (!file.exists()) return MetadataLoadResult.Absent
        return try {
            val json = JSONObject(atomicFile.openRead().bufferedReader().use { it.readText() })
            val schema = json.getInt("schema_version")
            if (schema != DEVICE_IDENTITY_SCHEMA_VERSION) {
                return MetadataLoadResult.Invalid(DeviceIdentityUnavailableReason.METADATA_UNSUPPORTED)
            }
            val state = StoredIdentityState.valueOf(json.getString("state"))
            val alias = json.getString("key_alias")
            val fingerprint = if (state == StoredIdentityState.READY) {
                DeviceKeyFingerprint.parse(json.getString("public_key_fingerprint"))
                    ?: return MetadataLoadResult.Invalid(
                        DeviceIdentityUnavailableReason.METADATA_MALFORMED,
                    )
            } else {
                null
            }
            MetadataLoadResult.Present(
                StoredIdentityMetadata(schema, state, alias, fingerprint),
            )
        } catch (_: Exception) {
            MetadataLoadResult.Invalid(DeviceIdentityUnavailableReason.METADATA_MALFORMED)
        }
    }

    override fun writeProvisioning(keyAlias: String) {
        write(
            JSONObject()
                .put("schema_version", DEVICE_IDENTITY_SCHEMA_VERSION)
                .put("state", StoredIdentityState.PROVISIONING.name)
                .put("key_alias", keyAlias),
        )
    }

    override fun writeReady(keyAlias: String, fingerprint: DeviceKeyFingerprint) {
        write(
            JSONObject()
                .put("schema_version", DEVICE_IDENTITY_SCHEMA_VERSION)
                .put("state", StoredIdentityState.READY.name)
                .put("key_alias", keyAlias)
                .put("public_key_fingerprint", fingerprint.value),
        )
    }

    private fun write(json: JSONObject) {
        val output = atomicFile.startWrite()
        try {
            output.writer(Charsets.UTF_8).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val FILE_NAME = "device_identity_v1.json"
    }
}
```

If `finishWrite()` is reached after `use` has already closed the stream on the targeted Android API implementation, adjust the minimal code to write bytes directly to the returned stream, flush, and let `AtomicFile.finishWrite(output)` own the final close. Preserve atomic replacement semantics; do not replace `AtomicFile` with ordinary `File.writeText`.

- [ ] **Step 4: Add unsupported-schema and READY round-trip tests**

Add tests that write schema `2` and expect `METADATA_UNSUPPORTED`, and that call `writeReady()` with a canonical fingerprint and verify exact round-trip.

- [ ] **Step 5: Run instrumentation tests and verify metadata GREEN**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: metadata tests PASS.

- [ ] **Step 6: Commit Task 4**

```bash
git add data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/NoBackupDeviceIdentityMetadataRepository.kt \
  data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt
git commit -m "feat: persist device identity metadata atomically"
```

---

### Task 5: Implement real Android Keystore P-256 identity operations

**Files:**
- Create: `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt`
- Extend: `data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt`

**Interfaces:**
- Implements `DeviceIdentityCrypto`.
- Android Keystore provider: `AndroidKeyStore`.
- Curve: `secp256r1`.
- Digest/signature: SHA-256 / `SHA256withECDSA`.

- [ ] **Step 1: Add failing Keystore instrumentation tests**

Add constants and cleanup:

```kotlin
private val testAlias = "andy_device_identity_test_${System.nanoTime()}"

@After
fun cleanupKey() {
    java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(testAlias)
}
```

Add these tests:

```kotlin
@Test
fun createsNonExportableP256KeyAndStablePublicFingerprint() {
    val crypto = AndroidKeystoreDeviceIdentityCrypto()
    crypto.generateKey(testAlias)
    assertTrue(crypto.hasKey(testAlias))

    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val privateKey = keyStore.getKey(testAlias, null) as java.security.PrivateKey
    assertEquals(null, privateKey.encoded)

    val first = crypto.publicKeySubjectPublicKeyInfo(testAlias)
    val second = crypto.publicKeySubjectPublicKeyInfo(testAlias)
    assertTrue(first.contentEquals(second))
}

@Test
fun sha256WithEcdsaSelfTestSucceeds() {
    val crypto = AndroidKeystoreDeviceIdentityCrypto()
    crypto.generateKey(testAlias)
    val challenge = ByteArray(32) { index -> index.toByte() }
    val signature = crypto.signForSelfTest(testAlias, challenge)
    assertTrue(crypto.verifySelfTest(testAlias, challenge, signature))
}
```

- [ ] **Step 2: Run instrumentation and verify RED**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: compile failure because `AndroidKeystoreDeviceIdentityCrypto` does not exist.

- [ ] **Step 3: Implement the Keystore adapter**

Create:

```kotlin
package io.github.escossio.andy.data.deviceidentity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityCrypto
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

internal class AndroidKeystoreDeviceIdentityCrypto : DeviceIdentityCrypto {
    override fun hasKey(keyAlias: String): Boolean = keyStore().containsAlias(keyAlias)

    override fun generateKey(keyAlias: String) {
        check(!hasKey(keyAlias)) { "Device identity key already exists" }
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKeyPair()
    }

    override fun publicKeySubjectPublicKeyInfo(keyAlias: String): ByteArray =
        requireNotNull(keyStore().getCertificate(keyAlias)) { "Device identity certificate missing" }
            .publicKey
            .encoded

    override fun signForSelfTest(keyAlias: String, challenge: ByteArray): ByteArray {
        val privateKey = requireNotNull(keyStore().getKey(keyAlias, null) as? java.security.PrivateKey) {
            "Device identity private key missing"
        }
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(challenge)
            sign()
        }
    }

    override fun verifySelfTest(
        keyAlias: String,
        challenge: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val publicKey = requireNotNull(keyStore().getCertificate(keyAlias)) {
            "Device identity certificate missing"
        }.publicKey
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(challenge)
            verify(signature)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
```

Do not call `setIsStrongBoxBacked(true)`. Do not request per-use user authentication.

- [ ] **Step 4: Add a curve assertion using the generated public EC key**

In the instrumentation test, cast the certificate public key to `java.security.interfaces.ECPublicKey` and assert:

```kotlin
val publicKey = keyStore.getCertificate(testAlias).publicKey as java.security.interfaces.ECPublicKey
assertEquals(256, publicKey.params.curve.field.fieldSize)
```

- [ ] **Step 5: Run instrumentation and verify GREEN**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: Keystore tests PASS.

- [ ] **Step 6: Commit Task 5**

```bash
git add data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt \
  data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt
git commit -m "feat: add Android Keystore device identity"
```

---

### Task 6: Compose the production manager and prove crash recovery/fail-closed behavior on Android

**Files:**
- Create: `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt`
- Extend: `data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt`

**Interfaces:**
- Produces `AndroidDeviceIdentityFactory.create(context): DeviceIdentityManager`.
- Uses secure random 32-byte challenges.

- [ ] **Step 1: Add failing end-to-end instrumentation tests**

Add tests with an isolated alias strategy exposed through an internal factory overload, or construct `DeviceIdentityEngine` directly from internal repository/crypto adapters for test aliases. Cover these exact behaviors:

```kotlin
@Test fun firstEnsureCreatesIdentityAndSecondManagerReturnsSameFingerprint()
@Test fun provisioningMetadataWithExistingKeyRecoversToReady()
@Test fun establishedReadyIdentityWithDeletedKeyFailsClosedWithoutReplacement()
```

For the key-loss test:

1. provision to `Ready`;
2. delete the test alias directly from `AndroidKeyStore`;
3. call `ensureIdentity()` again using the same metadata;
4. assert `Unavailable(ESTABLISHED_KEY_MISSING)`;
5. assert the alias remains absent after the call.

- [ ] **Step 2: Run instrumentation and verify RED for the production factory path**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: failure because `AndroidDeviceIdentityFactory` does not exist.

- [ ] **Step 3: Implement the production factory**

Create:

```kotlin
package io.github.escossio.andy.data.deviceidentity

import android.content.Context
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityEngine
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityManager
import java.security.SecureRandom

object AndroidDeviceIdentityFactory {
    fun create(context: Context): DeviceIdentityManager {
        val random = SecureRandom()
        return DeviceIdentityEngine(
            metadataRepository = NoBackupDeviceIdentityMetadataRepository(context.applicationContext),
            crypto = AndroidKeystoreDeviceIdentityCrypto(),
            challengeGenerator = {
                ByteArray(32).also(random::nextBytes)
            },
        )
    }
}
```

No network or backend dependency is introduced.

- [ ] **Step 4: Complete Android end-to-end instrumentation tests**

Use production classes with synthetic test aliases where isolation requires it. If the production alias constant cannot be safely overridden without adding a public arbitrary constructor, instantiate `DeviceIdentityEngine` directly in tests using the Android repository/crypto classes and a synthetic metadata file/alias variant introduced through package-internal constructor parameters. Any such constructor parameter must remain `internal` to the data module and must not expose a new application API.

- [ ] **Step 5: Run all instrumentation tests**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: PASS for metadata, Keystore, recovery, and fail-closed cases.

- [ ] **Step 6: Run core + data JVM tests**

```bash
./gradlew --no-daemon \
  :core:device-identity:test \
  :data:device-identity:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 7: Commit Task 6**

```bash
git add data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt \
  data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt
git commit -m "feat: compose Android device identity manager"
```

---

### Task 7: Initialize identity on app launch without changing the UI

**Files:**
- Modify: `app/src/main/java/io/github/escossio/andy/MainActivity.kt`

**Interfaces:**
- Consumes: `AndroidDeviceIdentityFactory.create(applicationContext)` and `DeviceIdentityResult`.
- Produces bounded startup log events only; retains exact `BOOTSTRAP_TEXT = "Andy"` and existing Compose layout.

- [ ] **Step 1: Preserve the current UI contract before editing**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
```

Expected: existing `BootstrapTest.bootstrapTextIsAndy` PASS.

- [ ] **Step 2: Add startup identity initialization**

Modify `MainActivity.kt` so `onCreate` performs identity initialization before `setContent`, but the Compose code is byte-for-byte semantically unchanged. Use bounded logs only:

```kotlin
package io.github.escossio.andy

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityResult
import io.github.escossio.andy.data.deviceidentity.AndroidDeviceIdentityFactory

internal const val BOOTSTRAP_TEXT = "Andy"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeDeviceIdentity()
        setContent {
            AndyBootstrap()
        }
    }

    private fun initializeDeviceIdentity() {
        when (val result = AndroidDeviceIdentityFactory.create(applicationContext).ensureIdentity()) {
            is DeviceIdentityResult.Ready -> Log.i(LOG_TAG, "DEVICE_IDENTITY_READY")
            is DeviceIdentityResult.Unavailable ->
                Log.w(LOG_TAG, "DEVICE_IDENTITY_UNAVAILABLE:${result.reason.name}")
        }
    }

    private companion object {
        const val LOG_TAG = "AndyDeviceIdentity"
    }
}

@Composable
private fun AndyBootstrap() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(BOOTSTRAP_TEXT)
    }
}
```

Do not log `result.fingerprint.value`.

- [ ] **Step 3: Run app unit test and assembly**

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Run secret and forbidden-content inspection**

```bash
python3 scripts/security/scan_secrets.py .
grep -RInE 'fingerprint\.value|android\.permission\.INTERNET|https?://' \
  app core/device-identity data/device-identity || true
```

Expected:
- `SECRET_SCAN_PASS`;
- no fingerprint logging;
- no network permission or URL introduced by the feature code.

- [ ] **Step 5: Commit Task 7**

```bash
git add app/src/main/java/io/github/escossio/andy/MainActivity.kt
git commit -m "feat: initialize device identity on launch"
```

---

### Task 8: Run the complete exact-frontier verification

**Files:**
- Verify only; no new files expected.

**Interfaces:**
- Consumes all previous tasks.
- Produces exact candidate SHA ready for GitHub CI.

- [ ] **Step 1: Verify the feature diff contains only allowed paths**

Run:

```bash
python3 scripts/architecture/check_guardrails.py \
  --base-ref "$(git rev-parse origin/main)" \
  --head-ref "$(git rev-parse HEAD)" \
  --branch feat/android-device-identity-v1
```

Expected: `ARCH_PASS`.

- [ ] **Step 2: Run all JVM tests**

```bash
./gradlew --no-daemon \
  :core:device-identity:test \
  :data:device-identity:testDebugUnitTest \
  :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Run real Android instrumentation tests**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: PASS on an approved Android emulator/device environment. Notebook is not required.

- [ ] **Step 4: Assemble the debug APK**

```bash
./gradlew --no-daemon :app:assembleDebug
```

Expected: `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Run repository safeguards**

```bash
python3 scripts/security/scan_secrets.py .
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD | sort
git status --short --branch
```

Expected: `SECRET_SCAN_PASS`, no whitespace errors, exact frontier paths only, clean worktree.

- [ ] **Step 6: Verify no public arbitrary signing API exists**

Run:

```bash
grep -RInE 'fun[[:space:]]+sign\(|sign\(bytes|sign\(payload' \
  app core/device-identity data/device-identity || true
```

Expected: only the infrastructure SPI method `signForSelfTest` and Android implementation of that exact method may appear; no `DeviceIdentityManager.sign(...)` or general-purpose signing API exists.

- [ ] **Step 7: Verify UI code remains unchanged except startup call/imports**

Inspect `MainActivity.kt` and confirm:

```text
BOOTSTRAP_TEXT = "Andy"
Box(fillMaxSize, contentAlignment = Alignment.Center)
BasicText(BOOTSTRAP_TEXT)
```

No Material components, navigation, buttons, login UI, status text, or recovery UI are added.

- [ ] **Step 8: Commit any test-only corrections made during final verification**

Only if a failing test required a legitimate in-frontier correction, commit it with a narrow message. Otherwise create no extra commit.

---

### Task 9: Push the feature PR and obtain exact-SHA CI proof

**Files:**
- No additional repository file changes expected.

**Interfaces:**
- Consumes the verified feature branch.
- Produces reviewable PR and GitHub artifact.

- [ ] **Step 1: Push the feature branch**

```bash
git push -u origin feat/android-device-identity-v1
```

- [ ] **Step 2: Open the PR**

Title:

```text
feat: add Android device identity foundation
```

Body:

```text
Implements android-device-identity-v1 only.

Adds a Kotlin-only device identity contract/state machine and an Android implementation backed by P-256 Android Keystore plus atomic no-backup metadata. First launch provisions automatically; READY identities validate fingerprint and sign/verify on every launch; established failures are fail-closed and never silently regenerated.

No Google auth, email challenge, backend enrollment, tenant/session logic, network permission, HTTP client, provider SDK, arbitrary signing API, visual UI change, notebook gate, or physical-device automatic gate.

Do not merge automatically.
```

- [ ] **Step 3: Wait for all five required GitHub checks on the exact candidate SHA**

Expected:

```text
architecture-guard = success
governance-tests = success
secret-scan = success
android-build = success
android-instrumentation = success
```

`android-build` must show the device-identity module detected and execute:

```text
:core:device-identity:test
:data:device-identity:testDebugUnitTest
:app:testDebugUnitTest
:app:assembleDebug
```

`android-instrumentation` must show:

```text
DEVICE_IDENTITY_MODULE_PRESENT
:data:device-identity:connectedDebugAndroidTest
```

- [ ] **Step 4: Verify artifact publication**

Confirm exactly one debug artifact named:

```text
andy-debug-apk
```

containing `app-debug.apk`, tied to the exact candidate SHA.

Capture:

```text
WORKFLOW_RUN_ID
ARTIFACT_ID
ARTIFACT_SIZE
ARTIFACT_EXPIRES_AT
```

Optionally download the artifact only to compute the APK SHA-256; do not install automatically on the user's phone.

- [ ] **Step 5: Review the PR diff for security invariants**

Confirm:

```text
network/backend/provider code = none
INTERNET permission = none
full fingerprint logging = none
private key export = none
StrongBox requirement = none
biometric requirement = none
READY auto-regeneration = none
UI change = none
feature PR governance mutation = none
```

- [ ] **Step 6: Stop for explicit human merge approval**

Report:

```text
ANDROID_DEVICE_IDENTITY_V1_STATUS=PASS/FAIL
PR_URL=
CANDIDATE_SHA=
BASE_SHA=
GITHUB_ARCHITECTURE_GUARD=
GITHUB_GOVERNANCE_TESTS=
GITHUB_SECRET_SCAN=
GITHUB_ANDROID_BUILD=
GITHUB_ANDROID_INSTRUMENTATION=
CORE_JVM_TESTS=
DATA_JVM_TESTS=
ANDROID_KEYSTORE_INSTRUMENTATION=
ASSEMBLE_DEBUG=
ARTIFACT_NAME=andy-debug-apk
ARTIFACT_ID=
WORKFLOW_RUN_ID=
APK_SHA256=
UI_CHANGED=NO
NETWORK_ADDED=NO
ARBITRARY_SIGNING_API=NO
READY_AUTO_REGENERATION=NO
NOTEBOOK_GATE=NO
PHYSICAL_DEVICE_GATE=NO
FRONTIER_EXPANSION_REQUIRED=NO/YES
MERGED=NO
```

---

### Task 10: Post-approval merge and post-merge main verification

**Files:**
- No new feature edits.

**Interfaces:**
- Consumes explicit human merge authorization and unchanged candidate SHA.
- Produces a green `main` carrying Device Identity V1.

- [ ] **Step 1: Freshly verify the PR before merge**

Confirm:

```text
state = open
mergeable = true
head SHA = reviewed candidate SHA
all five required checks = success
```

- [ ] **Step 2: Merge using expected head SHA**

Use the repository's existing merge method. Do not bypass required checks or branch protection.

- [ ] **Step 3: Wait for the new main SHA checks**

Required:

```text
architecture-guard = success
governance-tests = success
secret-scan = success
android-build = success
android-instrumentation = success
```

- [ ] **Step 4: Verify post-merge APK artifact**

Confirm the new main SHA also publishes `andy-debug-apk` successfully.

- [ ] **Step 5: Final completion report**

Report:

```text
ANDROID_DEVICE_IDENTITY_V1_STATUS=PASS/FAIL
MERGED=YES/NO
MAIN_SHA=
POST_MERGE_ARCHITECTURE_GUARD=
POST_MERGE_GOVERNANCE_TESTS=
POST_MERGE_SECRET_SCAN=
POST_MERGE_ANDROID_BUILD=
POST_MERGE_ANDROID_INSTRUMENTATION=
POST_MERGE_APK_ARTIFACT=
FIRST_LAUNCH_PROVISIONING_PROVEN=YES/NO
STABLE_FINGERPRINT_PROVEN=YES/NO
P256_KEY_PROVEN=YES/NO
PRIVATE_KEY_NON_EXPORTABLE_PROVEN=YES/NO
SELF_TEST_EVERY_READY_LAUNCH_PROVEN=YES/NO
PROVISIONING_RECOVERY_PROVEN=YES/NO
READY_FAIL_CLOSED_PROVEN=YES/NO
NO_BACKUP_METADATA_PROVEN=YES/NO
NETWORK_ADDED=NO
UI_CHANGED=NO
ARBITRARY_SIGNING_API=NO
```
