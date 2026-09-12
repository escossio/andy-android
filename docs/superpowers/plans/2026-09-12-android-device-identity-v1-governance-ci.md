# Android Device Identity V1 Governance and CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the trusted `android-device-identity-v1` feature frontier and a mandatory unprivileged GitHub Actions instrumentation gate before any device-identity feature code is admitted.

**Architecture:** Preserve the existing `pull_request_target` trust model: governance, secret scanning, and architecture checks execute only trusted base code, while candidate Android code executes only in read-only, secret-free jobs. Add a separate `android-instrumentation` job that checks out the exact candidate SHA, detects the future device-identity module, and runs a headless API 36 emulator plus `connectedDebugAndroidTest` when that module exists. The governance PR itself changes only trusted policy/CI files and the frontier manifest.

**Tech Stack:** GitHub Actions, Python 3.13 governance tests, Android SDK command-line tools 12.0, Android Emulator API 36 x86_64 Google APIs image, JDK 17, Gradle 9.6.0.

**Spec:** `docs/superpowers/specs/2026-09-12-android-device-identity-v1-design.md`

## Global Constraints

- Repository: `escossio/andy-android`.
- Base at plan authoring time: `bf50e67c4191e0ce90596f74e325eb94f206a30a`; execution must fetch and use the then-current `origin/main` without silently rebasing unrelated work.
- Functional feature branch admitted by this governance change: `feat/android-device-identity-v1`.
- Governance branch should be `chore/architecture-governance/android-device-identity-v1`.
- Existing required checks remain: `architecture-guard`, `governance-tests`, `secret-scan`, `android-build`.
- New required check after governance merge: `android-instrumentation`.
- Candidate Android jobs remain `permissions: contents: read`, use exact PR head SHA, `fetch-depth: 1`, and `persist-credentials: false`.
- No secrets, write permissions, OIDC, deployment, signing, publishing, SSH, self-hosted runner, notebook, AGT01, VPS, or infrastructure coupling.
- No third-party Android emulator action. Use SDK tools directly.
- Do not use `sudo`, `apt-get`, global PATH mutation, or hardcoded `/usr/local/lib/android/sdk`.
- Resolve SDK root from `${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}` and tools from that root.
- Emulator API level: 36. System image package: `system-images;android-36;google_apis;x86_64`.
- Compile platform remains `platforms;android-37.0`; build tools remain `build-tools;36.0.0`.
- Do not alter feature code in this governance PR.
- Do not merge automatically. Stop for explicit human approval at the PR boundary.

---

## File Structure

**Governance PR changes exactly these paths:**

- Create: `.github/architecture/frontiers/android-device-identity-v1.json` — exact allowlist and forbidden dependency patterns for the feature PR.
- Modify: `.github/workflows/governance.yml` — add the unprivileged `android-instrumentation` job; keep the existing four jobs semantically unchanged except for policy-tested coexistence.
- Modify: `tests/architecture/test_guardrails_policy.py` — TDD assertions and negative mutations for the new frontier and instrumentation job.

No Android application/module source file is changed by this plan.

---

### Task 1: Define the exact trusted feature frontier

**Files:**
- Create: `.github/architecture/frontiers/android-device-identity-v1.json`
- Modify: `tests/architecture/test_guardrails_policy.py`

**Interfaces:**
- Consumes: `validate_manifest()` and `evaluate_frontier()` from `scripts/architecture/check_guardrails.py`.
- Produces: trusted frontier id `android-device-identity-v1` for branch `feat/android-device-identity-v1` with an exact path allowlist.

- [ ] **Step 1: Add a failing governance test for the new manifest**

Add a helper to load the new manifest and a test that asserts the exact branch, exact allowed paths, exact required artifacts, and required forbidden patterns. Use this exact expected path set:

```python
DEVICE_IDENTITY_ALLOWED_PATHS = {
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle/libs.versions.toml',
    'app/build.gradle.kts',
    'app/src/main/java/io/github/escossio/andy/MainActivity.kt',
    'core/device-identity/build.gradle.kts',
    'core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentity.kt',
    'core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt',
    'core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt',
    'core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceKeyFingerprintTest.kt',
    'data/device-identity/build.gradle.kts',
    'data/device-identity/src/main/AndroidManifest.xml',
    'data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt',
    'data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt',
    'data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/NoBackupDeviceIdentityMetadataRepository.kt',
    'data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt',
}


def device_identity_manifest():
    path = Path(__file__).resolve().parents[2] / '.github/architecture/frontiers/android-device-identity-v1.json'
    return json.loads(path.read_text())


def test_device_identity_frontier_is_exact(self):
    manifest = device_identity_manifest()
    self.assertEqual(manifest['schema_version'], 1)
    self.assertEqual(manifest['frontier_id'], 'android-device-identity-v1')
    self.assertEqual(manifest['branch'], 'feat/android-device-identity-v1')
    self.assertEqual(set(manifest['allowed_paths']), DEVICE_IDENTITY_ALLOWED_PATHS)
    self.assertEqual(set(manifest['required_artifacts']), DEVICE_IDENTITY_ALLOWED_PATHS)
    for forbidden in (
        'retrofit', 'okhttp', 'ktor-client', 'dagger', 'hilt',
        'androidx\\.room', 'firebase', 'com\\.google\\.android\\.gms',
        'androidx\\.work', 'play-services-location', 'home.?assistant',
        'whatsapp', 'INTERNET', 'device_id',
    ):
        self.assertIn(forbidden, manifest['forbidden_content_patterns'])
    self.assertEqual(validate_manifest(manifest), [])
```

Also add `import json` at the top.

- [ ] **Step 2: Run the targeted test and verify RED**

Run:

```bash
python3 -m unittest tests.architecture.test_guardrails_policy.GuardPolicyTests.test_device_identity_frontier_is_exact -v
```

Expected: FAIL because `.github/architecture/frontiers/android-device-identity-v1.json` does not exist.

- [ ] **Step 3: Create the exact frontier manifest**

Create `.github/architecture/frontiers/android-device-identity-v1.json` with this content:

```json
{
  "schema_version": 1,
  "frontier_id": "android-device-identity-v1",
  "branch": "feat/android-device-identity-v1",
  "allowed_paths": [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle/libs.versions.toml",
    "app/build.gradle.kts",
    "app/src/main/java/io/github/escossio/andy/MainActivity.kt",
    "core/device-identity/build.gradle.kts",
    "core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentity.kt",
    "core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt",
    "core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt",
    "core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceKeyFingerprintTest.kt",
    "data/device-identity/build.gradle.kts",
    "data/device-identity/src/main/AndroidManifest.xml",
    "data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt",
    "data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt",
    "data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/NoBackupDeviceIdentityMetadataRepository.kt",
    "data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt"
  ],
  "required_artifacts": [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle/libs.versions.toml",
    "app/build.gradle.kts",
    "app/src/main/java/io/github/escossio/andy/MainActivity.kt",
    "core/device-identity/build.gradle.kts",
    "core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentity.kt",
    "core/device-identity/src/main/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngine.kt",
    "core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceIdentityEngineTest.kt",
    "core/device-identity/src/test/kotlin/io/github/escossio/andy/core/deviceidentity/DeviceKeyFingerprintTest.kt",
    "data/device-identity/build.gradle.kts",
    "data/device-identity/src/main/AndroidManifest.xml",
    "data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityFactory.kt",
    "data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/AndroidKeystoreDeviceIdentityCrypto.kt",
    "data/device-identity/src/main/java/io/github/escossio/andy/data/deviceidentity/NoBackupDeviceIdentityMetadataRepository.kt",
    "data/device-identity/src/androidTest/java/io/github/escossio/andy/data/deviceidentity/AndroidDeviceIdentityInstrumentationTest.kt"
  ],
  "forbidden_content_patterns": [
    "retrofit",
    "okhttp",
    "ktor-client",
    "dagger",
    "hilt",
    "androidx\\.room",
    "firebase",
    "com\\.google\\.android\\.gms",
    "androidx\\.work",
    "play-services-location",
    "home.?assistant",
    "whatsapp",
    "android.permission.INTERNET",
    "device_id"
  ]
}
```

- [ ] **Step 4: Run the targeted test and verify GREEN**

Run:

```bash
python3 -m unittest tests.architecture.test_guardrails_policy.GuardPolicyTests.test_device_identity_frontier_is_exact -v
```

Expected: PASS.

- [ ] **Step 5: Add a negative allowlist test**

Add:

```python
def test_device_identity_frontier_rejects_unlisted_paths(self):
    manifest = device_identity_manifest()
    errors = evaluate_frontier(manifest, ['README.md'], {})
    self.assertIn('ARCH_GOVERNANCE_MUTATION:README.md', errors)
```

- [ ] **Step 6: Run the two frontier tests**

Run:

```bash
python3 -m unittest \
  tests.architecture.test_guardrails_policy.GuardPolicyTests.test_device_identity_frontier_is_exact \
  tests.architecture.test_guardrails_policy.GuardPolicyTests.test_device_identity_frontier_rejects_unlisted_paths -v
```

Expected: 2 tests PASS.

- [ ] **Step 7: Commit Task 1**

```bash
git add .github/architecture/frontiers/android-device-identity-v1.json tests/architecture/test_guardrails_policy.py
git commit -m "chore: define Android device identity frontier"
```

---

### Task 2: Add the unprivileged Android instrumentation CI job

**Files:**
- Modify: `.github/workflows/governance.yml`
- Modify: `tests/architecture/test_guardrails_policy.py`

**Interfaces:**
- Consumes: exact candidate SHA for PRs; trusted main SHA for pushes; Android SDK root variables supplied by GitHub-hosted Ubuntu.
- Produces: required check context `android-instrumentation`.

- [ ] **Step 1: Add a failing test helper for the instrumentation job**

Add this helper next to `assert_android_build_job`:

```python
def assert_android_instrumentation_job(self, text):
    jobs = self.jobs(text)
    self.assertIn('android-instrumentation', jobs)
    job = jobs['android-instrumentation']
    self.assertIn('name: android-instrumentation', job)
    self.assertIn('runs-on: ubuntu-latest', job)
    self.assertIn('timeout-minutes: 35', job)
    permissions = re.search(r'^    permissions:\n(.*?)^    steps:', job,
                            flags=re.MULTILINE | re.DOTALL)
    self.assertIsNotNone(permissions)
    self.assertEqual(permissions[1].strip(), 'contents: read')
    checkouts = self.checkouts(job)
    self.assertEqual(len(checkouts), 2)
    self.assertIn('ref: ${{ github.sha }}', checkouts[0])
    self.assertIn('ref: ${{ github.event.pull_request.head.sha }}', checkouts[1])
    for checkout in checkouts:
        self.assertEqual(checkout.count('persist-credentials: false'), 1)
    for required in (
        'data/device-identity/build.gradle.kts',
        'DEVICE_IDENTITY_MODULE_PRESENT',
        'SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"',
        'cmdline-tools/latest/bin/sdkmanager',
        'cmdline-tools/latest/bin/avdmanager',
        "'system-images;android-36;google_apis;x86_64'",
        'andy-ci-api36',
        'sys.boot_completed',
        ':data:device-identity:connectedDebugAndroidTest',
        'emulator -kill',
    ):
        self.assertIn(required, job)
    for forbidden in (
        'secrets.', 'contents: write', 'id-token:', 'sudo ', 'apt-get ',
        'setup-android', 'reactivecircus', 'self-hosted', 'ssh ', 'note',
        '/usr/local/lib/android/sdk', 'GITHUB_PATH', 'export PATH=',
        'continue-on-error:', 'deploy', 'publish', 'signing',
    ):
        self.assertNotIn(forbidden, job)
```

Add:

```python
def test_android_instrumentation_has_separate_unprivileged_boundary(self):
    self.assert_android_instrumentation_job(self.workflow())
```

- [ ] **Step 2: Run the targeted test and verify RED**

Run:

```bash
python3 -m unittest tests.architecture.test_guardrails_policy.GuardPolicyTests.test_android_instrumentation_has_separate_unprivileged_boundary -v
```

Expected: FAIL because `android-instrumentation` does not exist.

- [ ] **Step 3: Add the new job to `.github/workflows/governance.yml`**

Append this job under `jobs:` at the same indentation level as `android-build`:

```yaml
  android-instrumentation:
    name: android-instrumentation
    runs-on: ubuntu-latest
    timeout-minutes: 35
    permissions:
      contents: read
    steps:
      - name: Checkout trusted main on push
        if: github.event_name == 'push'
        uses: actions/checkout@v4
        with:
          ref: ${{ github.sha }}
          fetch-depth: 1
          persist-credentials: false

      - name: Checkout exact PR candidate for unprivileged instrumentation
        if: github.event_name == 'pull_request_target'
        uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.head.sha }}
          fetch-depth: 1
          persist-credentials: false

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Detect device identity module
        id: device_identity
        shell: bash
        run: |
          set -euo pipefail
          if [[ -f data/device-identity/build.gradle.kts ]]; then
            echo 'present=true' >> "$GITHUB_OUTPUT"
            echo 'DEVICE_IDENTITY_MODULE_PRESENT'
          else
            echo 'present=false' >> "$GITHUB_OUTPUT"
            echo 'DEVICE_IDENTITY_MODULE_NOT_PRESENT'
          fi

      - name: Install emulator tooling and API 36 image
        if: steps.device_identity.outputs.present == 'true'
        shell: bash
        run: |
          set -euo pipefail
          SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
          test -n "$SDK_ROOT"
          SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
          AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
          test -x "$SDKMANAGER"
          test -x "$AVDMANAGER"
          "$SDKMANAGER" --version
          "$SDKMANAGER" \
            'platform-tools' \
            'emulator' \
            'system-images;android-36;google_apis;x86_64'
          test -x "$SDK_ROOT/platform-tools/adb"
          test -x "$SDK_ROOT/emulator/emulator"

      - name: Create and boot API 36 emulator
        if: steps.device_identity.outputs.present == 'true'
        shell: bash
        run: |
          set -euo pipefail
          SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
          AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
          ADB="$SDK_ROOT/platform-tools/adb"
          EMULATOR="$SDK_ROOT/emulator/emulator"
          echo no | "$AVDMANAGER" create avd \
            --force \
            --name andy-ci-api36 \
            --package 'system-images;android-36;google_apis;x86_64'
          "$EMULATOR" \
            -avd andy-ci-api36 \
            -no-window \
            -no-audio \
            -no-boot-anim \
            -no-snapshot \
            -wipe-data \
            -gpu swiftshader_indirect \
            > "$RUNNER_TEMP/andy-emulator.log" 2>&1 &
          "$ADB" wait-for-device
          booted=''
          for attempt in $(seq 1 120); do
            booted="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
            if [[ "$booted" == '1' ]]; then
              break
            fi
            sleep 2
          done
          if [[ "$booted" != '1' ]]; then
            cat "$RUNNER_TEMP/andy-emulator.log"
            exit 1
          fi

      - name: Run real Android Keystore instrumentation tests
        if: steps.device_identity.outputs.present == 'true'
        shell: bash
        run: |
          set -euo pipefail
          ./gradlew --no-daemon :data:device-identity:connectedDebugAndroidTest

      - name: Stop emulator
        if: always() && steps.device_identity.outputs.present == 'true'
        shell: bash
        run: |
          set -u
          SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
          "$SDK_ROOT/platform-tools/adb" emulator -kill || true
```

- [ ] **Step 4: Run the targeted job test and verify GREEN**

Run:

```bash
python3 -m unittest tests.architecture.test_guardrails_policy.GuardPolicyTests.test_android_instrumentation_has_separate_unprivileged_boundary -v
```

Expected: PASS.

- [ ] **Step 5: Add negative security mutations for the instrumentation job**

Add:

```python
def test_android_instrumentation_rejects_privilege_and_emulator_mutations(self):
    text = self.workflow()
    self.assert_android_instrumentation_job(text)
    mutations = [
        text.replace('contents: read', 'contents: write', 1),
        text.replace('persist-credentials: false', 'persist-credentials: true', 1),
        text.replace('github.event.pull_request.head.sha', 'github.head_ref', 1),
        text.replace("'system-images;android-36;google_apis;x86_64'",
                     "'system-images;android-35;google_apis;x86_64'"),
        text.replace(':data:device-identity:connectedDebugAndroidTest', ':app:assembleDebug', 1),
        text.replace('SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"',
                     'SDK_ROOT="/usr/local/lib/android/sdk"', 1),
        text + '\n      - run: sudo true\n',
        text + '\n      - run: ssh synthetic.invalid\n',
    ]
    for index, mutation in enumerate(mutations):
        with self.subTest(mutation=index), self.assertRaises(AssertionError):
            self.assert_android_instrumentation_job(mutation)
```

- [ ] **Step 6: Run all architecture policy tests**

Run:

```bash
python3 -m unittest tests.architecture.test_guardrails_policy -v
```

Expected: all tests PASS.

- [ ] **Step 7: Commit Task 2**

```bash
git add .github/workflows/governance.yml tests/architecture/test_guardrails_policy.py
git commit -m "ci: add Android instrumentation gate"
```

---

### Task 3: Require the device-identity JVM tests when the feature module exists

**Files:**
- Modify: `.github/workflows/governance.yml`
- Modify: `tests/architecture/test_guardrails_policy.py`

**Interfaces:**
- Consumes: `core/device-identity/build.gradle.kts` and `data/device-identity/build.gradle.kts` when the future feature branch contains them.
- Produces: JVM gate for `:core:device-identity:test` and `:data:device-identity:testDebugUnitTest` inside the existing read-only `android-build` job.

- [ ] **Step 1: Add a failing policy assertion**

Extend `assert_android_build_job()` with:

```python
for required in (
    'DEVICE_IDENTITY_MODULE_PRESENT',
    ':core:device-identity:test',
    ':data:device-identity:testDebugUnitTest',
):
    self.assertIn(required, job)
```

- [ ] **Step 2: Run the android-build policy test and verify RED**

Run:

```bash
python3 -m unittest tests.architecture.test_guardrails_policy.GuardPolicyTests.test_android_build_has_separate_unprivileged_boundary -v
```

Expected: FAIL because the module-specific JVM tests are not wired.

- [ ] **Step 3: Add module detection and conditional JVM tests to `android-build`**

Immediately after `Detect Android project`, add:

```yaml
      - name: Detect device identity module
        id: device_identity
        if: steps.android_project.outputs.present == 'true'
        shell: bash
        run: |
          set -euo pipefail
          if [[ -f core/device-identity/build.gradle.kts && -f data/device-identity/build.gradle.kts ]]; then
            echo 'present=true' >> "$GITHUB_OUTPUT"
            echo 'DEVICE_IDENTITY_MODULE_PRESENT'
          else
            echo 'present=false' >> "$GITHUB_OUTPUT"
            echo 'DEVICE_IDENTITY_MODULE_NOT_PRESENT'
          fi
```

After the existing `Build and test exact candidate` step, add:

```yaml
      - name: Run device identity JVM tests
        if: steps.device_identity.outputs.present == 'true'
        shell: bash
        run: |
          set -euo pipefail
          ./gradlew --no-daemon \
            :core:device-identity:test \
            :data:device-identity:testDebugUnitTest
```

Keep APK upload after this step so artifact publication occurs only after all JVM tests succeed.

- [ ] **Step 4: Run the policy test and verify GREEN**

Run:

```bash
python3 -m unittest tests.architecture.test_guardrails_policy.GuardPolicyTests.test_android_build_has_separate_unprivileged_boundary -v
```

Expected: PASS.

- [ ] **Step 5: Run the complete governance suite**

Run:

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 scripts/architecture/check_guardrails.py --policy-self-check
python3 scripts/security/scan_secrets.py .
git diff --check
```

Expected:
- all tests PASS;
- `ARCH_PASS`;
- `SECRET_SCAN_PASS`;
- `git diff --check` exits 0.

- [ ] **Step 6: Commit Task 3**

```bash
git add .github/workflows/governance.yml tests/architecture/test_guardrails_policy.py
git commit -m "ci: gate device identity JVM tests"
```

---

### Task 4: Verify the governance candidate and open the PR

**Files:**
- Verify only; no additional file changes expected.

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: reviewable governance PR with no feature code.

- [ ] **Step 1: Verify the exact changed path set**

Run:

```bash
git diff --name-only origin/main...HEAD | sort
```

Expected exactly:

```text
.github/architecture/frontiers/android-device-identity-v1.json
.github/workflows/governance.yml
tests/architecture/test_guardrails_policy.py
```

If any other path appears, stop and remove the unrelated change before continuing.

- [ ] **Step 2: Run final local verification**

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 scripts/architecture/check_guardrails.py --policy-self-check
python3 scripts/security/scan_secrets.py .
git diff --check origin/main...HEAD
git status --short --branch
```

Expected: tests PASS, `ARCH_PASS`, `SECRET_SCAN_PASS`, clean worktree except branch tracking metadata.

- [ ] **Step 3: Push the governance branch**

```bash
git push -u origin chore/architecture-governance/android-device-identity-v1
```

- [ ] **Step 4: Open the governance PR**

Use title:

```text
ci: add Android device identity frontier and instrumentation gate
```

PR body must state:

```text
Governance-only preparation for android-device-identity-v1.

Adds the exact trusted feature frontier, a read-only unprivileged android-instrumentation job using official Android SDK tools and an API 36 headless emulator, and conditional JVM gates for the future core/data device-identity modules.

No Android feature implementation is included. No secrets, write permissions, third-party emulator action, notebook dependency, production signing, backend/network integration, or physical-device gate is added.

Do not merge automatically.
```

- [ ] **Step 5: Wait for all current required checks plus `android-instrumentation`**

Expected on the governance PR:

```text
architecture-guard = success
governance-tests = success
secret-scan = success
android-build = success
android-instrumentation = success
```

Because the feature module does not exist yet, `android-instrumentation` must report `DEVICE_IDENTITY_MODULE_NOT_PRESENT` and exit successfully without executing candidate instrumentation tests. The check context must still exist.

- [ ] **Step 6: Stop for explicit human merge approval**

Do not merge in this task. Report PR URL, head SHA, changed paths, local test totals, and five remote check conclusions.

---

### Task 5: Post-approval merge and branch-protection gate

**Files:**
- No repository file edits.
- GitHub branch protection/ruleset configuration only.

**Interfaces:**
- Consumes: explicit human authorization after Task 4.
- Produces: `main` containing the trusted frontier and CI job, with `android-instrumentation` required for future PRs.

- [ ] **Step 1: Freshly verify the PR before merge**

Confirm:

```text
PR state = open
mergeable = true
head SHA unchanged from reviewed candidate
all five checks = success
```

- [ ] **Step 2: Merge with expected head SHA**

Use the repository's existing merge method and an expected-head guard. Do not bypass branch protection.

- [ ] **Step 3: Verify the post-merge main checks**

On the new `main` SHA, wait for:

```text
architecture-guard = success
governance-tests = success
secret-scan = success
android-build = success
android-instrumentation = success
```

- [ ] **Step 4: Add `android-instrumentation` to required status checks without removing existing contexts**

Read the current branch protection/ruleset first. Preserve strictness and these existing contexts:

```text
architecture-guard
governance-tests
secret-scan
android-build
```

Append exactly:

```text
android-instrumentation
```

Do not disable strict mode, PR requirements, admin enforcement, force-push protection, deletion protection, or any existing rule.

- [ ] **Step 5: Read back the protection/ruleset and verify**

Expected required contexts exactly include all five checks. Report no weakening of protection.

- [ ] **Step 6: Final governance completion report**

Report:

```text
DEVICE_IDENTITY_GOVERNANCE_STATUS=PASS/FAIL
MAIN_SHA=
FRONTIER_ID=android-device-identity-v1
FEATURE_BRANCH=feat/android-device-identity-v1
REQUIRED_CHECK_ARCHITECTURE_GUARD=YES/NO
REQUIRED_CHECK_GOVERNANCE_TESTS=YES/NO
REQUIRED_CHECK_SECRET_SCAN=YES/NO
REQUIRED_CHECK_ANDROID_BUILD=YES/NO
REQUIRED_CHECK_ANDROID_INSTRUMENTATION=YES/NO
FEATURE_CODE_CHANGED=NO
NOTEBOOK_DEPENDENCY=NO
PHYSICAL_DEVICE_GATE=NO
THIRD_PARTY_EMULATOR_ACTION=NO
FRONTIER_EXPANSION_REQUIRED=NO/YES
```
