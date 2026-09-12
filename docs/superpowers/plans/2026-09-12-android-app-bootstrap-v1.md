# Android App Bootstrap V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` for this plan. The user explicitly chose inline execution for speed; do not use subagent-driven development unless a later human instruction overrides that decision. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first real Andy Android shell on the exact trusted frontier, prove it with required GitHub checks, independently rebuild the same candidate SHA on the certification workstation, and install/launch that APK on a real API-36 Android device.

**Architecture:** One `app` module only. AGP 9.4 uses built-in Kotlin; the Compose compiler plugin is pinned separately. The UI contains only centered text `Andy`. GitHub remains merge authority; the notebook/device chain is complementary certification of the same candidate SHA and must never become repository topology or a required private-infrastructure dependency.

**Tech Stack:** Android Gradle Plugin 9.4.0, Gradle 9.6.0, JDK 17, minSdk 28, targetSdk 36, compileSdk 37, SDK Build Tools 36.0.0, AGP built-in Kotlin, Compose Compiler Gradle plugin 2.3.21, Compose BOM 2026.08.00, Activity Compose 1.13.0, Compose Foundation, JUnit 4.13.2, Android SDK/ADB for independent certification.

**Spec:** `docs/superpowers/specs/2026-09-12-android-app-bootstrap-v1-design.md`

## Global Constraints

- Prerequisite: `android-build` must already exist as a successful real GitHub check on `main` and be a required status check alongside `architecture-guard`, `governance-tests`, and `secret-scan`.
- Feature branch is exactly `feat/android-app-bootstrap-v1`.
- The feature may create/modify only the exact paths already allowed by `.github/architecture/frontiers/android-app-bootstrap-v1.json`.
- Do not modify `.github/**`, `scripts/**`, any `AGENTS.md`, `README.md`, `SECURITY.md`, or governance/security implementation from the feature branch.
- Application ID and namespace are exactly `io.github.escossio.andy`.
- App display text and label are exactly `Andy`.
- `minSdk = 28`, `targetSdk = 36`, `compileSdk = 37`.
- AGP `9.4.0`, Gradle `9.6.0`, JDK `17`, Build Tools `36.0.0`.
- Use AGP built-in Kotlin. Do not apply `org.jetbrains.kotlin.android`.
- Compose Compiler Gradle plugin is `2.3.21`.
- Compose BOM is `2026.08.00`.
- Add no Material, navigation, ViewModel, DI, persistence, network, Firebase, analytics, WorkManager, provider, or production-signing dependency.
- Candidate runtime behavior is only one launcher Activity rendering centered `Andy`.
- No real user, tenant, device serial, phone, precise location, provider payload, credential, hostname, IP, SSH detail, notebook path, or infrastructure inventory may be committed.
- Certification workstation/device metadata remains operational evidence outside Git.
- Stop with `FRONTIER_EXPANSION_REQUIRED` if any repository path or dependency beyond the frozen frontier/baseline is needed.
- Do not merge the feature PR without explicit human authorization after all GitHub and physical certification gates pass.

## Locked Feature File Set

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.properties
gradle/wrapper/gradle-wrapper.jar
gradlew
gradlew.bat
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/io/github/escossio/andy/MainActivity.kt
app/src/main/res/values/strings.xml
app/src/test/java/io/github/escossio/andy/BootstrapTest.kt
```

No other feature path is authorized.

---

### Task 1: Prerequisite and Certification-Station Preflight

**Files:**
- Read only in repository.
- External workstation mutation allowed only for installing `platforms;android-37` if absent.

**Interfaces:**
- Consumes: protected main, required checks, already-prepared JDK 17 / Build Tools 36.0.0 / ADB workstation.
- Produces: proof that repository governance and certification station are ready before feature code begins.

- [ ] **Step 1: Verify required GitHub checks before creating the feature branch**

Run from `/srv/projetos/andy-android`:

```bash
git fetch origin --prune
gh api repos/escossio/andy-android/branches/main/protection/required_status_checks/contexts
git show origin/main:.github/architecture/frontiers/android-app-bootstrap-v1.json
git show origin/main:.github/workflows/governance.yml | grep -F 'name: android-build'
```

Expected required contexts include all four:

```text
architecture-guard
android-build
governance-tests
secret-scan
```

If `android-build` is not both present in trusted `main` and required by protection, stop. Do not create the feature branch.

- [ ] **Step 2: Verify current main is green**

Run:

```bash
MAIN_SHA=$(git rev-parse origin/main)
gh api "repos/escossio/andy-android/commits/$MAIN_SHA/check-runs" \
  --jq '.check_runs[] | [.name,.status,.conclusion] | @tsv'
```

Expected: all required checks on current main are completed successfully.

- [ ] **Step 3: Verify and minimally complete workstation compile SDK**

From AGT use the already-existing external connection wrapper operationally, without writing it into repository files. On the notebook:

```bash
sdkmanager --sdk_root=/root/Android --list_installed | grep -F 'platforms;android-37' || true
```

If `platforms;android-37` is absent, install only:

```bash
sdkmanager --sdk_root=/root/Android 'platforms;android-37'
```

Do not update JDK, ADB/platform-tools, Build Tools, or unrelated SDK packages. If a new license requires interactive acceptance, stop and report rather than mass-accepting licenses.

Then verify externally:

```bash
test -d /root/Android/platforms/android-37
test -d /root/Android/build-tools/36.0.0
java -version
/root/Android/platform-tools/adb devices -l
```

Expected: Platform 37 present, Build Tools 36.0.0 present, JDK 17, one authorized physical device available. Do not record its serial in Git or the final public PR.

---

### Task 2: Create the Exact Frontier Worktree

**Files:**
- Create isolated local worktree: `/tmp/andy-android-app-bootstrap-v1`

**Interfaces:**
- Consumes: current `origin/main`.
- Produces: exact trusted feature branch.

- [ ] **Step 1: Create the worktree/branch**

Run:

```bash
cd /srv/projetos/andy-android
git worktree add -b feat/android-app-bootstrap-v1 /tmp/andy-android-app-bootstrap-v1 origin/main
cd /tmp/andy-android-app-bootstrap-v1
git status --short --branch
```

Expected: clean branch exactly `feat/android-app-bootstrap-v1`.

- [ ] **Step 2: Read applicable instructions from trusted base**

Run:

```bash
cat AGENTS.md
cat app/AGENTS.md
cat .github/architecture/frontiers/android-app-bootstrap-v1.json
```

Expected: exact file allowlist matches the locked set in this plan. If not, stop and report instead of editing governance from the feature branch.

---

### Task 3: Create Deterministic Gradle Project and Wrapper

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradlew`
- Create: `gradlew.bat`

**Interfaces:**
- Produces: one-module Gradle foundation with pinned versions and official wrapper, no Android product code yet.

- [ ] **Step 1: Create `settings.gradle.kts`**

Write exactly:

```kotlin
import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndyAndroid"
include(":app")
```

- [ ] **Step 2: Create version catalog**

Write `gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.4.0"
kotlin = "2.3.21"
composeBom = "2026.08.00"
activityCompose = "1.13.0"
junit = "4.13.2"

[libraries]
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 3: Create root build script**

Write `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

Do not add `org.jetbrains.kotlin.android`.

- [ ] **Step 4: Create minimal Gradle properties**

Write `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
```

Do not disable AGP built-in Kotlin.

- [ ] **Step 5: Generate the official Gradle 9.6.0 wrapper outside the Android project**

Require `curl` and `unzip`; do not install packages automatically if missing.

Run:

```bash
command -v curl
command -v unzip
WRAP_TMP=$(mktemp -d)
DIST_TMP=$(mktemp -d)
cd "$DIST_TMP"
curl -fsSLO https://services.gradle.org/distributions/gradle-9.6.0-bin.zip
curl -fsSLO https://services.gradle.org/distributions/gradle-9.6.0-bin.zip.sha256
printf '%s  %s\n' "$(cat gradle-9.6.0-bin.zip.sha256)" gradle-9.6.0-bin.zip | sha256sum -c -
unzip -q gradle-9.6.0-bin.zip
printf '%s\n' 'rootProject.name = "wrapper-bootstrap"' > "$WRAP_TMP/settings.gradle.kts"
"$DIST_TMP/gradle-9.6.0/bin/gradle" -p "$WRAP_TMP" wrapper --gradle-version 9.6.0 --distribution-type bin
cd /tmp/andy-android-app-bootstrap-v1
mkdir -p gradle/wrapper
cp "$WRAP_TMP/gradlew" ./gradlew
cp "$WRAP_TMP/gradlew.bat" ./gradlew.bat
cp "$WRAP_TMP/gradle/wrapper/gradle-wrapper.jar" ./gradle/wrapper/gradle-wrapper.jar
cp "$WRAP_TMP/gradle/wrapper/gradle-wrapper.properties" ./gradle/wrapper/gradle-wrapper.properties
chmod 0755 gradlew
DIST_SHA=$(cat "$DIST_TMP/gradle-9.6.0-bin.zip.sha256")
printf '\ndistributionSha256Sum=%s\n' "$DIST_SHA" >> gradle/wrapper/gradle-wrapper.properties
rm -rf "$WRAP_TMP" "$DIST_TMP"
```

Verify:

```bash
grep -F 'gradle-9.6.0-bin.zip' gradle/wrapper/gradle-wrapper.properties
grep -E '^distributionSha256Sum=[0-9a-f]{64}$' gradle/wrapper/gradle-wrapper.properties
test -x gradlew
git status --short
```

Expected: wrapper pinned to 9.6.0 and distribution checksum present.

---

### Task 4: Implement the One-Screen Andy Shell and JVM Test

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/io/github/escossio/andy/MainActivity.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/io/github/escossio/andy/BootstrapTest.kt`

**Interfaces:**
- Produces: package `io.github.escossio.andy`, launcher Activity, centered `Andy`, one JUnit test asserting the exact bootstrap text.

- [ ] **Step 1: Create `app/build.gradle.kts`**

Write exactly:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.escossio.andy"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.escossio.andy"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-bootstrap"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)

    testImplementation(libs.junit)
}
```

- [ ] **Step 2: Create manifest and app label**

Write `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Write `app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Andy</string>
</resources>
```

- [ ] **Step 3: Write the minimal Compose Activity**

Write `app/src/main/java/io/github/escossio/andy/MainActivity.kt`:

```kotlin
package io.github.escossio.andy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

internal const val BOOTSTRAP_TEXT = "Andy"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndyBootstrap()
        }
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

No additional UI or behavior is permitted.

- [ ] **Step 4: Write the exact JVM unit test**

Write `app/src/test/java/io/github/escossio/andy/BootstrapTest.kt`:

```kotlin
package io.github.escossio.andy

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {
    @Test
    fun bootstrapTextIsAndy() {
        assertEquals("Andy", BOOTSTRAP_TEXT)
    }
}
```

- [ ] **Step 5: Prove the feature diff is exactly inside the trusted frontier**

Run:

```bash
python3 scripts/architecture/check_guardrails.py --policy-self-check
python3 scripts/security/scan_secrets.py .
git diff --check
git status --short
```

Then compare the changed/untracked path inventory against the locked feature file set. If any extra path appears, remove it if accidental; if it is genuinely required, stop with `FRONTIER_EXPANSION_REQUIRED`.

- [ ] **Step 6: Commit the complete candidate**

Run:

```bash
git add \
  settings.gradle.kts \
  build.gradle.kts \
  gradle.properties \
  gradle/libs.versions.toml \
  gradle/wrapper/gradle-wrapper.properties \
  gradle/wrapper/gradle-wrapper.jar \
  gradlew \
  gradlew.bat \
  app/build.gradle.kts \
  app/src/main/AndroidManifest.xml \
  app/src/main/java/io/github/escossio/andy/MainActivity.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/java/io/github/escossio/andy/BootstrapTest.kt

git commit -m 'feat: bootstrap Andy Android app shell'
```

Expected: no governance file changed.

---

### Task 5: Local Policy Proof, Push, and GitHub CI

**Files:**
- No new files.

**Interfaces:**
- Consumes: final feature commit.
- Produces: PR whose exact head SHA is certified by all four required GitHub checks.

- [ ] **Step 1: Evaluate final commit with trusted local guard**

Run:

```bash
HEAD_SHA=$(git rev-parse HEAD)
BASE_SHA=$(git rev-parse origin/main)
python3 scripts/architecture/check_guardrails.py \
  --base-ref "$BASE_SHA" \
  --head-ref "$HEAD_SHA" \
  --branch feat/android-app-bootstrap-v1
python3 scripts/security/scan_secrets.py --git-ref "$HEAD_SHA"
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD | sort
```

Expected: `ARCH_PASS`, `SECRET_SCAN_PASS`, and exactly the locked feature files.

- [ ] **Step 2: Push and create PR**

Run:

```bash
git push -u origin feat/android-app-bootstrap-v1

gh pr create \
  --repo escossio/andy-android \
  --base main \
  --head feat/android-app-bootstrap-v1 \
  --title 'feat: bootstrap Andy Android app shell' \
  --body 'First functional Android frontier: one Compose Activity rendering centered Andy. No API, login, integrations, persistence, background work, provider SDKs, or production signing.'
```

- [ ] **Step 3: Capture authoritative PR head SHA and watch checks**

Run:

```bash
PR_HEAD_SHA=$(gh pr view --repo escossio/andy-android feat/android-app-bootstrap-v1 --json headRefOid --jq .headRefOid)
test "$PR_HEAD_SHA" = "$(git rev-parse HEAD)"
gh pr checks --repo escossio/andy-android feat/android-app-bootstrap-v1 --watch
```

Expected all required checks PASS:

```text
architecture-guard
android-build
governance-tests
secret-scan
```

If GitHub `android-build` fails, inspect its logs and fix only ordinary implementation problems inside allowed feature paths. If resolution needs workflow/governance changes or a new dependency/path, stop with `FRONTIER_EXPANSION_REQUIRED`.

---

### Task 6: Independent Notebook Certification of the Exact PR SHA

**Files:**
- External temporary checkout only; no repository file changes.

**Interfaces:**
- Consumes: exact `PR_HEAD_SHA` whose GitHub checks passed.
- Produces: independent `testDebugUnitTest` + `assembleDebug`, APK SHA-256, and exact artifact path for device certification.

- [ ] **Step 1: Create a clean detached checkout on the notebook**

Use the existing external access wrapper operationally; do not write its alias, hostname, account, SDK path, or SSH details into Git.

On the notebook, set `PR_HEAD_SHA` to the exact value obtained from GitHub, then run:

```bash
set -euo pipefail
CERT_DIR="$(mktemp -d /tmp/andy-android-cert.XXXXXX)"
git clone --no-tags https://github.com/escossio/andy-android.git "$CERT_DIR"
cd "$CERT_DIR"
git fetch origin refs/heads/feat/android-app-bootstrap-v1:refs/remotes/origin/cert-candidate
ACTUAL_SHA=$(git rev-parse refs/remotes/origin/cert-candidate)
test "$ACTUAL_SHA" = "$PR_HEAD_SHA"
git checkout --detach "$PR_HEAD_SHA"
test "$(git rev-parse HEAD)" = "$PR_HEAD_SHA"
git status --short
```

Expected: clean detached checkout of the exact candidate SHA.

- [ ] **Step 2: Build/test with the approved workstation toolchain**

Run on the notebook:

```bash
export ANDROID_HOME=/root/Android
export ANDROID_SDK_ROOT=/root/Android
java -version
test -d "$ANDROID_SDK_ROOT/platforms/android-37"
test -d "$ANDROID_SDK_ROOT/build-tools/36.0.0"
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Expected: tests and debug assembly PASS without global Gradle.

- [ ] **Step 3: Record the candidate and APK digest outside Git**

Run:

```bash
APK="$CERT_DIR/app/build/outputs/apk/debug/app-debug.apk"
test -f "$APK"
APK_SHA256=$(sha256sum "$APK" | awk '{print $1}')
printf 'CERTIFIED_COMMIT_SHA=%s\nAPK_SHA256=%s\nAPK_PATH=%s\n' "$PR_HEAD_SHA" "$APK_SHA256" "$APK"
```

Do not commit this operational path or workstation inventory.

---

### Task 7: Physical Android 16 / API-36 Device Certification

**Files:**
- No repository changes.
- Development APK installation on the connected device is authorized.

**Interfaces:**
- Consumes: APK produced by Task 6 from exact `PR_HEAD_SHA`.
- Produces: proof of install, launch, no immediate crash, and visible `Andy` bootstrap surface.

- [ ] **Step 1: Verify the device class without recording its serial**

On the notebook:

```bash
ADB=/root/Android/platform-tools/adb
$ADB devices -l
DEVICE_API=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
DEVICE_ABI=$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')
test "$DEVICE_API" = '36'
test "$DEVICE_ABI" = 'arm64-v8a'
```

Do not include the device serial in the GitHub PR/body/comments or repository files.

- [ ] **Step 2: Install the exact certified APK**

Run:

```bash
$ADB install -r "$APK"
```

Expected: `Success`.

- [ ] **Step 3: Launch and prove Activity health**

Run:

```bash
$ADB shell am start -W -n io.github.escossio.andy/.MainActivity
$ADB shell pidof io.github.escossio.andy
```

Expected: Activity start reports success and package has a running PID.

- [ ] **Step 4: Prove the bootstrap text**

First attempt an accessibility/UI hierarchy proof without committing device output:

```bash
$ADB shell uiautomator dump /sdcard/andy-bootstrap-window.xml >/dev/null
$ADB shell cat /sdcard/andy-bootstrap-window.xml | grep -F 'text="Andy"'
$ADB shell rm -f /sdcard/andy-bootstrap-window.xml
```

If the Compose text is visible to UIAutomator, result is automated PASS.

If UIAutomator cannot expose the text but the Activity is healthy, capture a temporary screenshot outside Git and require direct human visual confirmation that the screen contains only the intended centered `Andy` product text before marking device certification PASS:

```bash
$ADB exec-out screencap -p > /tmp/andy-bootstrap-screen.png
```

Never commit the screenshot or device metadata unless a future explicit privacy-reviewed process authorizes it.

---

### Task 8: Final Same-SHA Gate and Merge Stop

**Files:**
- No new files.

**Interfaces:**
- Consumes: GitHub checks + workstation + physical-device evidence for one exact SHA.
- Produces: ready-to-merge report; no merge without explicit human approval.

- [ ] **Step 1: Re-read PR head and guarantee it did not move after certification**

Run on AGT:

```bash
CURRENT_PR_SHA=$(gh pr view --repo escossio/andy-android feat/android-app-bootstrap-v1 --json headRefOid --jq .headRefOid)
test "$CURRENT_PR_SHA" = "$PR_HEAD_SHA"
gh pr checks --repo escossio/andy-android feat/android-app-bootstrap-v1
```

If the PR head changed after workstation/device certification, certification is stale: repeat Tasks 6 and 7 for the new exact SHA.

- [ ] **Step 2: Verify final changed paths and prohibited dependencies**

Run:

```bash
git diff --name-only origin/main...HEAD | sort
python3 scripts/architecture/check_guardrails.py \
  --base-ref "$(git rev-parse origin/main)" \
  --head-ref "$(git rev-parse HEAD)" \
  --branch feat/android-app-bootstrap-v1
python3 scripts/security/scan_secrets.py --git-ref "$(git rev-parse HEAD)"
```

Expected: exact allowed paths, `ARCH_PASS`, `SECRET_SCAN_PASS`.

- [ ] **Step 3: Produce the ready-to-merge report**

Report concrete values:

```text
ANDROID_APP_BOOTSTRAP_V1_STATUS=PASS/FAIL
PR_URL=
CANDIDATE_SHA=
APPLICATION_ID=io.github.escossio.andy
MIN_SDK=28
TARGET_SDK=36
COMPILE_SDK=37
AGP=9.4.0
GRADLE=9.6.0
JDK=17
BUILD_TOOLS=36.0.0
COMPOSE_BOM=2026.08.00
GITHUB_ARCHITECTURE_GUARD=PASS/FAIL
GITHUB_GOVERNANCE_TESTS=PASS/FAIL
GITHUB_SECRET_SCAN=PASS/FAIL
GITHUB_ANDROID_BUILD=PASS/FAIL
NOTEBOOK_CERTIFIED_SHA=
NOTEBOOK_TESTS=PASS/FAIL
NOTEBOOK_ASSEMBLE_DEBUG=PASS/FAIL
APK_SHA256=
PHYSICAL_DEVICE_API=36
PHYSICAL_DEVICE_ABI=arm64-v8a
PHYSICAL_INSTALL=PASS/FAIL
PHYSICAL_LAUNCH=PASS/FAIL
SCREEN_ANDY=PASS/FAIL
REAL_DATA_COMMITTED=NO
GOVERNANCE_FILES_CHANGED=NO
FRONTIER_EXPANSION_REQUIRED=NO/YES
MERGED=NO
```

Stop here and wait for explicit human merge authorization.

---

## Plan Self-Review Record

- Spec coverage: exact frontier, compileSdk 37 correction, AGP/Gradle/JDK/Build Tools, built-in Kotlin, Compose compiler/BOM, minimal UI, unit test, four GitHub gates, clean same-SHA notebook build, APK digest, API-36 physical-device install/launch/text proof, and merge gate all have concrete tasks.
- Placeholder scan: dynamic SHA/APK values are derived by commands at execution time; no implementation TODO/TBD is left.
- Boundary check: no login, network SDK, location, WhatsApp, Home Assistant, persistence, sync, push, voice, provider SDK, analytics, production signing, Play deployment, or extra Gradle module is introduced.
- Trust check: security jobs remain base-trusted; candidate execution is confined to the intentionally unprivileged Android build job and independent certification environment.
- Same-SHA check: GitHub, notebook, APK digest, and device evidence are explicitly invalidated if PR head changes.
