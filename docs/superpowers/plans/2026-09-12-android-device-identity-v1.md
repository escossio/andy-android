# Android Device Identity V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an installation-scoped, automatically provisioned, fail-closed Android device identity backed by a non-exportable P-256 Android Keystore key, with no visible UI change and no backend/network dependency.

**Architecture:** Add a Kotlin-only `core:device-identity` module for the public result contract, canonical fingerprinting, storage/crypto ports, and deterministic state-machine engine. Add an Android `data:device-identity` module implementing the ports with Android Keystore and `AtomicFile` under `noBackupFilesDir`. `app` invokes the manager at launch and logs bounded readiness/unavailable events only.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.4.0, Gradle 9.6.0, JDK 17, minSdk 28, targetSdk 36, compileSdk 37, AndroidX Test Core/Runner 1.7.0, AndroidX Test Ext JUnit 1.3.0, JUnit 4.13.2, Android Keystore, `SHA256withECDSA`, `AtomicFile`.

**Spec:** `docs/superpowers/specs/2026-09-12-android-device-identity-v1-design.md`

## Global Constraints

- Do not start until `docs/superpowers/plans/2026-09-12-android-device-identity-v1-governance-ci.md` is merged and `android-instrumentation` is required.
- Feature branch: `feat/android-device-identity-v1`.
- Modify only paths admitted by `.github/architecture/frontiers/android-device-identity-v1.json`.
- Reinstall creates a new identity. No identity backup/restore.
- First launch provisions automatically; later launches reuse the same key/fingerprint.
- Fingerprint is local `device_key_fingerprint`, never authoritative server `device_id`.
- Key: EC P-256 / `secp256r1`, Android Keystore, non-exportable private key.
- Signature: `SHA256withECDSA`.
- StrongBox is optional and not requested explicitly.
- Device key use does not require biometric/PIN.
- Fingerprint format: `sha256:` plus exactly 64 lowercase hex characters.
- Fingerprint input: X.509 SubjectPublicKeyInfo DER from `PublicKey.encoded`.
- Lifecycle: `ABSENT -> PROVISIONING -> READY`; only `PROVISIONING` may recover automatically.
- A `READY` identity is never silently regenerated, deleted, rotated, or replaced.
- Every valid `READY` launch recalculates the fingerprint and performs a fresh local sign/verify self-test.
- Metadata path: `noBackupFilesDir/device_identity_v1.json`, atomically replaced.
- No Room, DataStore, Hilt, Dagger, Retrofit, OkHttp, Ktor client, Firebase, Google Play Services, WorkManager, analytics, background workers, provider SDKs, HTTP, backend URLs, tenant logic, human identity, sessions, or enrollment.
- No `android.permission.INTERNET`.
- No public arbitrary signing API; only the infrastructure SPI method `signForSelfTest` exists.
- Visible UI remains the current centered `Andy` text.
- Logs never contain full fingerprint, public/private key material, signatures, challenge bytes, user data, location, tenant/server ids, tokens, or infrastructure details.
- Notebook and physical phone are not automatic gates.
- Do not merge automatically.

---

## Exact Feature File Set

**Modify:**
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/io/github/escossio/andy/MainActivity.kt`

**Create:**
- `core/device-identity/build.gradle.kts`
- `core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentity.kt`
- `core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt`
- `core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt`
- `core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceKeyFingerprintTest.kt`
- `data/device-identity/build.gradle.kts`
- `data/device-identity/src/main/AndroidManifest.xml`
- `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt`
- `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt`
- `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/NoBackupDeviceIdentityMetadataRepository.kt`
- `data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt`

No other path may change.

---

### Task 1: Wire the modules and stable test dependencies

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `core/device-identity/build.gradle.kts`
- Create: `data/device-identity/build.gradle.kts`
- Create: `data/device-identity/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces Gradle modules `:core:device-identity` and `:data:device-identity`.
- Data depends on core; app depends on both.

- [ ] **Step 1: Extend `gradle/libs.versions.toml` with exact stable entries**

Use:

```toml
[versions]
agp = "9.4.0"
kotlin = "2.3.21"
composeBom = "2026.08.00"
activityCompose = "1.13.0"
junit = "4.13.2"
androidxTestCore = "1.7.0"
androidxTestRunner = "1.7.0"
androidxTestExtJunit = "1.3.0"

[libraries]
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
junit = { module = "junit:junit", version.ref = "junit" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Update root plugin declarations**

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

- [ ] **Step 3: Include the modules**

Append to `settings.gradle.kts`:

```kotlin
include(":core:device-identity")
include(":data:device-identity")
```

Keep `include(":app")` and repository configuration unchanged.

- [ ] **Step 4: Create the core module build file**

`core/device-identity/build.gradle.kts`:

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

`data/device-identity/build.gradle.kts`:

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
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```

- [ ] **Step 6: Create the permission-free data manifest**

`data/device-identity/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 7: Add app module dependencies**

Add to `app/build.gradle.kts`:

```kotlin
implementation(project(":core:device-identity"))
implementation(project(":data:device-identity"))
```

- [ ] **Step 8: Verify module discovery and empty builds**

```bash
./gradlew --no-daemon projects
./gradlew --no-daemon \
  :core:device-identity:test \
  :data:device-identity:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:assembleDebug
```

Expected: both modules listed; all tasks PASS.

- [ ] **Step 9: Commit Task 1**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml \
  app/build.gradle.kts core/device-identity/build.gradle.kts \
  data/device-identity/build.gradle.kts data/device-identity/src/main/AndroidManifest.xml
git commit -m "build: add device identity modules"
```

---

### Task 2: Define the narrow public contract and canonical fingerprint

**Files:**
- Create: `core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentity.kt`
- Create: `core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceKeyFingerprintTest.kt`

**Interfaces:**
- `DeviceIdentityManager.ensureIdentity(): DeviceIdentityResult`
- `DeviceKeyFingerprint.fromSubjectPublicKeyInfo(ByteArray)`
- `DeviceKeyFingerprint.parse(String)`

- [ ] **Step 1: Write the failing fingerprint tests**

`DeviceKeyFingerprintTest.kt`:

```kotlin
package io.github.escossio.andy.core.deviceidentity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeyFingerprintTest {
    @Test
    fun fingerprintUsesCanonicalSha256LowercaseHex() {
        val value = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(byteArrayOf(1, 2, 3, 4)).value
        assertTrue(value.matches(Regex("^sha256:[0-9a-f]{64}$")))
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

- [ ] **Step 2: Run and confirm RED**

```bash
./gradlew --no-daemon :core:device-identity:test --tests '*DeviceKeyFingerprintTest'
```

Expected: compile failure because the type does not exist.

- [ ] **Step 3: Implement `DeviceIdentity.kt`**

```kotlin
package io.github.escossio.andy.core.deviceidentity

import java.security.MessageDigest

@JvmInline
value class DeviceKeyFingerprint private constructor(val value: String) {
    companion object {
        private val canonical = Regex("^sha256:[0-9a-f]{64}$")

        fun fromSubjectPublicKeyInfo(encoded: ByteArray): DeviceKeyFingerprint {
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
            val hex = digest.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
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

enum class StoredIdentityState { PROVISIONING, READY }

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

`DeviceIdentityCrypto` is infrastructure SPI only. The application-facing `DeviceIdentityManager` exposes no signing method.

- [ ] **Step 4: Run core tests and confirm GREEN**

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

### Task 3: Implement the state machine with explicit test alias injection

**Files:**
- Create: `core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt`
- Create: `core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt`

**Interfaces:**
- Production alias constant: `andy_device_identity_v1`.
- Constructor accepts `keyAlias` with the production constant as its default so instrumentation tests can use isolated synthetic aliases without changing the public manager contract.

- [ ] **Step 1: Write failing happy-path tests with simple fakes**

Create fakes for `DeviceIdentityMetadataRepository` and `DeviceIdentityCrypto`. Tests must assert generation count. Begin with:

```kotlin
@Test
fun absentStateProvisionsOnceAndBecomesReady() {
    val metadata = FakeMetadataRepository(MetadataLoadResult.Absent)
    val crypto = FakeCrypto(keyExists = false, publicKey = byteArrayOf(1, 3, 3, 7))
    val result = engine(metadata, crypto).ensureIdentity()
    assertTrue(result is DeviceIdentityResult.Ready)
    assertEquals(1, crypto.generateCount)
    assertEquals(StoredIdentityState.READY, metadata.current().state)
}

@Test
fun readyStateReusesExistingIdentityWithoutGeneration() {
    val publicKey = byteArrayOf(1, 3, 3, 7)
    val fingerprint = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(publicKey)
    val metadata = FakeMetadataRepository(
        MetadataLoadResult.Present(
            StoredIdentityMetadata(1, StoredIdentityState.READY, TEST_ALIAS, fingerprint),
        ),
    )
    val crypto = FakeCrypto(keyExists = true, publicKey = publicKey)
    val result = engine(metadata, crypto).ensureIdentity()
    assertEquals(DeviceIdentityResult.Ready(fingerprint), result)
    assertEquals(0, crypto.generateCount)
}
```

Use a test constant such as `private const val TEST_ALIAS = "andy_device_identity_test"`.

- [ ] **Step 2: Run and confirm RED**

```bash
./gradlew --no-daemon :core:device-identity:test --tests '*DeviceIdentityEngineTest'
```

Expected: compile failure because `DeviceIdentityEngine` does not exist.

- [ ] **Step 3: Implement `DeviceIdentityEngine.kt`**

```kotlin
package io.github.escossio.andy.core.deviceidentity

const val DEVICE_IDENTITY_SCHEMA_VERSION = 1
const val DEVICE_IDENTITY_KEY_ALIAS = "andy_device_identity_v1"

class DeviceIdentityEngine(
    private val metadataRepository: DeviceIdentityMetadataRepository,
    private val crypto: DeviceIdentityCrypto,
    private val challengeGenerator: () -> ByteArray,
    private val keyAlias: String = DEVICE_IDENTITY_KEY_ALIAS,
) : DeviceIdentityManager {
    override fun ensureIdentity(): DeviceIdentityResult = when (val loaded = metadataRepository.load()) {
        MetadataLoadResult.Absent -> provisionFresh()
        is MetadataLoadResult.Invalid -> DeviceIdentityResult.Unavailable(loaded.reason)
        is MetadataLoadResult.Present -> when (loaded.metadata.state) {
            StoredIdentityState.PROVISIONING -> resumeProvisioning(loaded.metadata)
            StoredIdentityState.READY -> validateReady(loaded.metadata)
        }
    }

    private fun provisionFresh(): DeviceIdentityResult = try {
        if (crypto.hasKey(keyAlias)) {
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.INCONSISTENT_FRESH_INSTALL)
        } else {
            metadataRepository.writeProvisioning(keyAlias)
            crypto.generateKey(keyAlias)
            completeProvisioning()
        }
    } catch (_: Exception) {
        DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.PROVISIONING_FAILED)
    }

    private fun resumeProvisioning(metadata: StoredIdentityMetadata): DeviceIdentityResult {
        if (metadata.keyAlias != keyAlias) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.METADATA_MALFORMED)
        }
        return try {
            if (!crypto.hasKey(keyAlias)) crypto.generateKey(keyAlias)
            completeProvisioning()
        } catch (_: Exception) {
            DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.PROVISIONING_FAILED)
        }
    }

    private fun completeProvisioning(): DeviceIdentityResult {
        val fingerprint = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(
            crypto.publicKeySubjectPublicKeyInfo(keyAlias),
        )
        if (!selfTest()) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.SELF_TEST_FAILED)
        }
        metadataRepository.writeReady(keyAlias, fingerprint)
        return DeviceIdentityResult.Ready(fingerprint)
    }

    private fun validateReady(metadata: StoredIdentityMetadata): DeviceIdentityResult {
        if (metadata.schemaVersion != DEVICE_IDENTITY_SCHEMA_VERSION ||
            metadata.keyAlias != keyAlias ||
            metadata.publicKeyFingerprint == null
        ) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityUnavailableReason.METADATA_MALFORMED)
        }
        return try {
            if (!crypto.hasKey(keyAlias)) {
                return DeviceIdentityResult.Unavailable(
                    DeviceIdentityUnavailableReason.ESTABLISHED_KEY_MISSING,
                )
            }
            val actual = DeviceKeyFingerprint.fromSubjectPublicKeyInfo(
                crypto.publicKeySubjectPublicKeyInfo(keyAlias),
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
        val signature = crypto.signForSelfTest(keyAlias, challenge)
        return crypto.verifySelfTest(keyAlias, challenge, signature)
    }
}
```

- [ ] **Step 4: Add the complete fail-closed test matrix**

Add these exact test cases using the fakes:

```text
provisioningWithExistingKeyCompletesWithoutGeneratingAgain
provisioningWithoutKeyGeneratesAndCompletes
readyWithMissingKeyIsUnavailableAndNeverGenerates
readyWithFingerprintMismatchIsUnavailableAndNeverGenerates
readyWithFailedSelfTestIsUnavailableAndNeverGenerates
invalidMetadataReturnsUnavailableWithoutKeyMutation
absentMetadataWithUnexpectedKeyIsUnavailableAndNeverGenerates
establishedKeyExceptionIsUnavailableAndNeverGenerates
```

Every `READY` failure test must assert `generateCount == 0`.

- [ ] **Step 5: Run all core tests**

```bash
./gradlew --no-daemon :core:device-identity:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt \
  core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt
git commit -m "feat: add fail-closed device identity engine"
```

---

### Task 4: Implement atomic no-backup metadata storage

**Files:**
- Create: `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/NoBackupDeviceIdentityMetadataRepository.kt`
- Create initially, then extend: `data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt`

**Interfaces:**
- `NoBackupDeviceIdentityMetadataRepository(context, fileName = "device_identity_v1.json")`.
- Test-only file isolation uses a synthetic `fileName` passed to the internal constructor.

- [ ] **Step 1: Write failing metadata instrumentation tests**

Start `AndroidDeviceIdentityInstrumentationTest.kt` with:

```kotlin
@RunWith(AndroidJUnit4::class)
class AndroidDeviceIdentityInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testFileName = "device_identity_test_${System.nanoTime()}.json"
    private val metadataFile get() = File(context.noBackupFilesDir, testFileName)

    @After
    fun cleanupMetadata() {
        metadataFile.delete()
        File(metadataFile.path + ".bak").delete()
    }

    @Test
    fun metadataLivesUnderNoBackupFilesDirAndRoundTripsProvisioning() {
        val repository = NoBackupDeviceIdentityMetadataRepository(context, testFileName)
        repository.writeProvisioning("synthetic_alias")
        assertTrue(metadataFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        val loaded = repository.load() as MetadataLoadResult.Present
        assertEquals(StoredIdentityState.PROVISIONING, loaded.metadata.state)
    }

    @Test
    fun malformedMetadataFailsClosed() {
        metadataFile.writeText("not-json")
        val loaded = NoBackupDeviceIdentityMetadataRepository(context, testFileName).load()
        assertEquals(
            MetadataLoadResult.Invalid(DeviceIdentityUnavailableReason.METADATA_MALFORMED),
            loaded,
        )
    }
}
```

Use imports from AndroidX Test Core, Ext JUnit, core device identity types, JUnit, and `java.io.File`.

- [ ] **Step 2: Run and confirm RED**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: compile failure because the repository class does not exist.

- [ ] **Step 3: Implement exact atomic storage**

Create `NoBackupDeviceIdentityMetadataRepository.kt`:

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

internal class NoBackupDeviceIdentityMetadataRepository(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : DeviceIdentityMetadataRepository {
    private val file = File(context.noBackupFilesDir, fileName)
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
            } else null
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
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "device_identity_v1.json"
    }
}
```

- [ ] **Step 4: Add unsupported-schema and READY round-trip tests**

Add one test that writes JSON with `schema_version=2` and expects `METADATA_UNSUPPORTED`, and one that calls `writeReady()` with a canonical fingerprint and verifies exact round-trip.

- [ ] **Step 5: Run instrumentation tests**

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

### Task 5: Implement the Android Keystore adapter

**Files:**
- Create: `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt`
- Extend: `data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt`

**Interfaces:**
- Implements `DeviceIdentityCrypto` with AndroidKeyStore, P-256, `SHA256withECDSA`.

- [ ] **Step 1: Add failing Keystore tests with unique alias**

Add:

```kotlin
private val testAlias = "andy_device_identity_test_${System.nanoTime()}"

@After
fun cleanupKey() {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(testAlias)
}

@Test
fun createsNonExportableP256KeyWithStablePublicEncoding() {
    val crypto = AndroidKeystoreDeviceIdentityCrypto()
    crypto.generateKey(testAlias)
    assertTrue(crypto.hasKey(testAlias))

    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val privateKey = keyStore.getKey(testAlias, null) as PrivateKey
    assertEquals(null, privateKey.encoded)

    val publicKey = keyStore.getCertificate(testAlias).publicKey as ECPublicKey
    assertEquals(256, publicKey.params.curve.field.fieldSize)

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

- [ ] **Step 2: Run and confirm RED**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: compile failure because `AndroidKeystoreDeviceIdentityCrypto` does not exist.

- [ ] **Step 3: Implement the adapter**

`AndroidKeystoreDeviceIdentityCrypto.kt`:

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
            .publicKey.encoded

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

Do not call `setIsStrongBoxBacked(true)`.

- [ ] **Step 4: Run instrumentation tests and confirm GREEN**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: all metadata and Keystore tests PASS.

- [ ] **Step 5: Commit Task 5**

```bash
git add data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt \
  data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt
git commit -m "feat: add Android Keystore device identity"
```

---

### Task 6: Compose the production manager and prove recovery/fail-closed behavior

**Files:**
- Create: `data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt`
- Extend: `data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt`

**Interfaces:**
- `AndroidDeviceIdentityFactory.create(context): DeviceIdentityManager` uses production alias/file name.
- Instrumentation tests construct `DeviceIdentityEngine` directly with synthetic alias and synthetic metadata filename using the explicit injection points already defined.

- [ ] **Step 1: Add failing end-to-end instrumentation tests**

Add these three tests:

```text
firstEnsureCreatesIdentityAndSecondManagerReturnsSameFingerprint
provisioningMetadataWithExistingKeyRecoversToReady
establishedReadyIdentityWithDeletedKeyFailsClosedWithoutReplacement
```

For all three tests, construct:

```kotlin
val alias = "andy_device_identity_e2e_${System.nanoTime()}"
val fileName = "device_identity_e2e_${System.nanoTime()}.json"
val repository = NoBackupDeviceIdentityMetadataRepository(context, fileName)
val crypto = AndroidKeystoreDeviceIdentityCrypto()
val engine = DeviceIdentityEngine(
    metadataRepository = repository,
    crypto = crypto,
    challengeGenerator = { ByteArray(32) { 7 } },
    keyAlias = alias,
)
```

For the first test, call `engine.ensureIdentity()`, then create a second `DeviceIdentityEngine` with the same repository, crypto, alias and a different deterministic challenge and assert the same `Ready(fingerprint)`.

For provisioning recovery, call `repository.writeProvisioning(alias)`, then `crypto.generateKey(alias)`, then assert `ensureIdentity()` returns `Ready` without a second generation attempt.

For key loss, provision to `Ready`, delete `alias` directly from `AndroidKeyStore`, call `ensureIdentity()` again, assert `Unavailable(ESTABLISHED_KEY_MISSING)`, and assert `crypto.hasKey(alias)` remains false.

Delete the synthetic alias and metadata file at the end of each test with `try/finally`.

- [ ] **Step 2: Run instrumentation and confirm current tests execute**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

The end-to-end tests may compile before the factory exists because they use explicit construction. Keep them RED until the assertions are satisfied; do not weaken the assertions.

- [ ] **Step 3: Implement the production factory**

`AndroidDeviceIdentityFactory.kt`:

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
            challengeGenerator = { ByteArray(32).also(random::nextBytes) },
        )
    }
}
```

- [ ] **Step 4: Run all core/data tests**

```bash
./gradlew --no-daemon \
  :core:device-identity:test \
  :data:device-identity:testDebugUnitTest \
  :data:device-identity:connectedDebugAndroidTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 6**

```bash
git add data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt \
  data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt
git commit -m "feat: compose Android device identity manager"
```

---

### Task 7: Initialize identity on launch without changing the visible screen

**Files:**
- Modify: `app/src/main/java/io/github/escossio/andy/MainActivity.kt`

**Interfaces:**
- Consumes `AndroidDeviceIdentityFactory` and typed `DeviceIdentityResult`.
- Emits only `DEVICE_IDENTITY_READY` or `DEVICE_IDENTITY_UNAVAILABLE:<reason>`.

- [ ] **Step 1: Run the existing bootstrap UI unit test before editing**

```bash
./gradlew --no-daemon :app:testDebugUnitTest
```

Expected: `BootstrapTest.bootstrapTextIsAndy` PASS.

- [ ] **Step 2: Modify `MainActivity.kt` exactly as follows**

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

Do not log the fingerprint.

- [ ] **Step 3: Verify app unit test and APK build**

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Verify no network or sensitive logging**

```bash
python3 scripts/security/scan_secrets.py .
grep -RInE 'android\.permission\.INTERNET|https?://|fingerprint\.value' \
  app core/device-identity data/device-identity || true
```

Expected: `SECRET_SCAN_PASS`; no network permission/URL; no fingerprint logging.

- [ ] **Step 5: Commit Task 7**

```bash
git add app/src/main/java/io/github/escossio/andy/MainActivity.kt
git commit -m "feat: initialize device identity on launch"
```

---

### Task 8: Run the exact-frontier completion suite

**Files:**
- Verify only.

**Interfaces:**
- Produces exact candidate SHA ready for PR CI.

- [ ] **Step 1: Architecture guard**

```bash
python3 scripts/architecture/check_guardrails.py \
  --base-ref "$(git rev-parse origin/main)" \
  --head-ref "$(git rev-parse HEAD)" \
  --branch feat/android-device-identity-v1
```

Expected: `ARCH_PASS`.

- [ ] **Step 2: JVM tests**

```bash
./gradlew --no-daemon \
  :core:device-identity:test \
  :data:device-identity:testDebugUnitTest \
  :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Real Android instrumentation**

```bash
./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest
```

Expected: PASS on an approved Android emulator/device environment. Notebook is not required.

- [ ] **Step 4: Assemble APK**

```bash
./gradlew --no-daemon :app:assembleDebug
```

Expected: `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Repository safeguards**

```bash
python3 scripts/security/scan_secrets.py .
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD | sort
git status --short --branch
```

Expected: `SECRET_SCAN_PASS`, zero diff errors, only frontier-approved paths, clean worktree.

- [ ] **Step 6: Verify no arbitrary signing API**

```bash
grep -RInE 'fun[[:space:]]+sign\(|sign\(bytes|sign\(payload' \
  app core/device-identity data/device-identity || true
```

Expected: no general-purpose application signing method. `signForSelfTest` is the only signing capability name.

- [ ] **Step 7: Verify unchanged visual contract**

Confirm `MainActivity.kt` still contains exactly:

```text
BOOTSTRAP_TEXT = "Andy"
Modifier.fillMaxSize()
contentAlignment = Alignment.Center
BasicText(BOOTSTRAP_TEXT)
```

No buttons, status labels, login UI, recovery UI, navigation, or Material components are added.

---

### Task 9: Push the PR and obtain exact-SHA CI proof

**Files:**
- No further edits.

**Interfaces:**
- Produces reviewable feature PR and APK artifact.

- [ ] **Step 1: Push branch**

```bash
git push -u origin feat/android-device-identity-v1
```

- [ ] **Step 2: Open PR**

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

- [ ] **Step 3: Wait for all five required checks on the exact candidate SHA**

```text
architecture-guard = success
governance-tests = success
secret-scan = success
android-build = success
android-instrumentation = success
```

`android-build` must show `DEVICE_IDENTITY_MODULE_PRESENT` and execute core/data JVM tests plus app unit test/assembly. `android-instrumentation` must show `DEVICE_IDENTITY_MODULE_PRESENT` and execute `:data:device-identity:connectedDebugAndroidTest`.

- [ ] **Step 4: Verify the APK artifact**

Confirm exactly one `andy-debug-apk` artifact containing `app-debug.apk`, tied to the exact candidate SHA. Capture run id, artifact id, artifact size, expiry, and optionally APK SHA-256 after download.

- [ ] **Step 5: Security review before merge**

Confirm all are absent:

```text
network/backend/provider code
INTERNET permission
full fingerprint logging
private-key export
StrongBox requirement
biometric requirement
READY auto-regeneration
governance mutation in feature PR
visual UI change
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

### Task 10: Merge after approval and verify main

**Files:**
- No edits.

**Interfaces:**
- Consumes explicit merge approval and unchanged reviewed head SHA.
- Produces green `main` carrying Device Identity V1.

- [ ] **Step 1: Freshly verify PR state, mergeability, unchanged head, and all five checks**

Expected: open, mergeable, exact reviewed SHA, all success.

- [ ] **Step 2: Merge with expected head SHA and no bypass**

Use the repository's existing merge method.

- [ ] **Step 3: Wait for all five post-merge checks on the new main SHA**

Expected all success.

- [ ] **Step 4: Verify post-merge `andy-debug-apk` publication**

Expected: artifact exists for the new main SHA.

- [ ] **Step 5: Final report**

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
