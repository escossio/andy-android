# Android Device Identity V1 Governance and CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the trusted `android-device-identity-v1` feature frontier and a mandatory unprivileged GitHub Actions instrumentation gate before any device-identity feature code is admitted.

**Architecture:** Preserve the existing `pull_request_target` trust model: governance, secret scanning, and architecture checks execute only trusted base code, while candidate Android code executes only in secret-free read-only jobs. Add a separate `android-instrumentation` job that checks out the exact candidate SHA and, when the device-identity module exists, provisions an API 36 emulator with official Android SDK tools and runs `connectedDebugAndroidTest`. Keep the existing `android-build` job and extend it only to run the future core/data JVM tests when those modules exist.

**Tech Stack:** GitHub Actions, Python 3.13 governance tests, Android SDK command-line tools 12.0, Android Emulator API 36 x86_64 Google APIs image, JDK 17, Gradle 9.6.0.

**Spec:** `docs/superpowers/specs/2026-09-12-android-device-identity-v1-design.md`

## Global Constraints

- Repository: `escossio/andy-android`.
- Governance branch: `chore/architecture-governance/android-device-identity-v1`.
- Functional branch admitted by this governance change: `feat/android-device-identity-v1`.
- Preserve required checks `architecture-guard`, `governance-tests`, `secret-scan`, `android-build`.
- Add required check `android-instrumentation` only after its governance PR is merged and post-merge main is green.
- Candidate Android jobs use `permissions: contents: read`, exact PR head SHA, `fetch-depth: 1`, and `persist-credentials: false`.
- No secrets, write permissions, OIDC, deployment, signing, publishing, SSH, self-hosted runners, notebook, AGT01, VPS, or infrastructure coupling.
- No third-party emulator action.
- No `sudo`, `apt-get`, global PATH mutation, or hardcoded Android SDK root.
- Resolve SDK root from `${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}`.
- Compile platform: `platforms;android-37.0`.
- Build tools: `build-tools;36.0.0`.
- Emulator image: `system-images;android-36;google_apis;x86_64`.
- Do not add or change Android feature source in this governance PR.
- Do not merge automatically.

---

## Exact Governance File Set

- Create: `.github/architecture/frontiers/android-device-identity-v1.json`
- Modify: `.github/workflows/governance.yml`
- Modify: `tests/architecture/test_guardrails_policy.py`

No other repository path may change in this plan.

---

### Task 1: Define the exact feature frontier

**Files:**
- Create: `.github/architecture/frontiers/android-device-identity-v1.json`
- Modify: `tests/architecture/test_guardrails_policy.py`

**Interfaces:**
- Consumes: `validate_manifest()` and `evaluate_frontier()`.
- Produces: trusted frontier `android-device-identity-v1` for branch `feat/android-device-identity-v1`.

- [ ] **Step 1: Add the failing manifest test**

Add `import json` and the following expected path set to `tests/architecture/test_guardrails_policy.py`:

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
        'whatsapp', 'android\\.permission\\.INTERNET',
    ):
        self.assertIn(forbidden, manifest['forbidden_content_patterns'])
    self.assertEqual(validate_manifest(manifest), [])
```

- [ ] **Step 2: Run the test and confirm RED**

```bash
python3 -m unittest tests.architecture.test_guardrails_policy.GuardPolicyTests.test_device_identity_frontier_is_exact -v
```

Expected: FAIL because the manifest does not exist.

- [ ] **Step 3: Create the frontier manifest**

Create `.github/architecture/frontiers/android-device-identity-v1.json`:

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
    "android\\.permission\\.INTERNET"
  ]
}
```

- [ ] **Step 4: Add the out-of-frontier negative test**

```python
def test_device_identity_frontier_rejects_unlisted_paths(self):
    manifest = device_identity_manifest()
    errors = evaluate_frontier(manifest, ['README.md'], {})
    self.assertIn('ARCH_GOVERNANCE_MUTATION:README.md', errors)
```

- [ ] **Step 5: Run both tests and confirm GREEN**

```bash
python3 -m unittest \
  tests.architecture.test_guardrails_policy.GuardPolicyTests.test_device_identity_frontier_is_exact \
  tests.architecture.test_guardrails_policy.GuardPolicyTests.test_device_identity_frontier_rejects_unlisted_paths -v
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add .github/architecture/frontiers/android-device-identity-v1.json tests/architecture/test_guardrails_policy.py
git commit -m "chore: define Android device identity frontier"
```

---

### Task 2: Add the read-only `android-instrumentation` job

**Files:**
- Modify: `.github/workflows/governance.yml`
- Modify: `tests/architecture/test_guardrails_policy.py`

**Interfaces:**
- Produces GitHub check context `android-instrumentation`.
- Runs candidate instrumentation only if `data/device-identity/build.gradle.kts` exists.

- [ ] **Step 1: Add the failing job policy helper**

Add:

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
        "'platforms;android-37.0'",
        "'build-tools;36.0.0'",
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

- [ ] **Step 2: Run and confirm RED**

```bash
python3 -m unittest tests.architecture.test_guardrails_policy.GuardPolicyTests.test_android_instrumentation_has_separate_unprivileged_boundary -v
```

Expected: FAIL.

- [ ] **Step 3: Add the job to `.github/workflows/governance.yml`**

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

      - name: Install Android emulator dependencies
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
            'platforms;android-37.0' \
            'build-tools;36.0.0' \
            'platform-tools' \
            'emulator' \
            'system-images;android-36;google_apis;x86_64'
          test -d "$SDK_ROOT/platforms/android-37.0"
          test -d "$SDK_ROOT/build-tools/36.0.0"
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

- [ ] **Step 4: Add job-scoped negative mutations**

Add:

```python
def test_android_instrumentation_rejects_privilege_and_emulator_mutations(self):
    text = self.workflow()
    self.assert_android_instrumentation_job(text)
    prefix, job = text.split('\n  android-instrumentation:', 1)
    mutations = [
        job.replace('contents: read', 'contents: write'),
        job.replace('persist-credentials: false', 'persist-credentials: true', 1),
        job.replace('github.event.pull_request.head.sha', 'github.head_ref'),
        job.replace("'system-images;android-36;google_apis;x86_64'",
                    "'system-images;android-35;google_apis;x86_64'"),
        job.replace(':data:device-identity:connectedDebugAndroidTest', ':app:assembleDebug'),
        job.replace('SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"',
                    'SDK_ROOT="/usr/local/lib/android/sdk"', 1),
        job + '\n      - run: sudo true\n',
        job + '\n      - run: ssh synthetic.invalid\n',
    ]
    for index, mutation in enumerate(mutations):
        candidate = prefix + '\n  android-instrumentation:' + mutation
        with self.subTest(mutation=index), self.assertRaises(AssertionError):
            self.assert_android_instrumentation_job(candidate)
```

- [ ] **Step 5: Run the instrumentation policy tests and confirm GREEN**

```bash
python3 -m unittest \
  tests.architecture.test_guardrails_policy.GuardPolicyTests.test_android_instrumentation_has_separate_unprivileged_boundary \
  tests.architecture.test_guardrails_policy.GuardPolicyTests.test_android_instrumentation_rejects_privilege_and_emulator_mutations -v
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add .github/workflows/governance.yml tests/architecture/test_guardrails_policy.py
git commit -m "ci: add Android instrumentation gate"
```

---

### Task 3: Gate future core/data JVM tests in `android-build`

**Files:**
- Modify: `.github/workflows/governance.yml`
- Modify: `tests/architecture/test_guardrails_policy.py`

**Interfaces:**
- Produces conditional execution of `:core:device-identity:test` and `:data:device-identity:testDebugUnitTest`.

- [ ] **Step 1: Add failing assertions to `assert_android_build_job()`**

```python
for required in (
    'DEVICE_IDENTITY_MODULE_PRESENT',
    ':core:device-identity:test',
    ':data:device-identity:testDebugUnitTest',
):
    self.assertIn(required, job)
```

- [ ] **Step 2: Run and confirm RED**

```bash
python3 -m unittest tests.architecture.test_guardrails_policy.GuardPolicyTests.test_android_build_has_separate_unprivileged_boundary -v
```

Expected: FAIL.

- [ ] **Step 3: Add module detection after `Detect Android project`**

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

- [ ] **Step 4: Add conditional JVM tests after the existing app build/test step**

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

Keep APK upload after this step.

- [ ] **Step 5: Run the full governance suite**

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 scripts/architecture/check_guardrails.py --policy-self-check
python3 scripts/security/scan_secrets.py .
git diff --check
```

Expected: all tests PASS, `ARCH_PASS`, `SECRET_SCAN_PASS`, zero diff-check errors.

- [ ] **Step 6: Commit Task 3**

```bash
git add .github/workflows/governance.yml tests/architecture/test_guardrails_policy.py
git commit -m "ci: gate device identity JVM tests"
```

---

### Task 4: Open the governance PR and stop at the review gate

**Files:**
- No further edits.

**Interfaces:**
- Produces a governance-only PR.

- [ ] **Step 1: Verify exact changed paths**

```bash
git diff --name-only origin/main...HEAD | sort
```

Expected exactly:

```text
.github/architecture/frontiers/android-device-identity-v1.json
.github/workflows/governance.yml
tests/architecture/test_guardrails_policy.py
```

- [ ] **Step 2: Run final local verification**

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 scripts/architecture/check_guardrails.py --policy-self-check
python3 scripts/security/scan_secrets.py .
git diff --check origin/main...HEAD
git status --short --branch
```

Expected: PASS, `ARCH_PASS`, `SECRET_SCAN_PASS`, clean worktree.

- [ ] **Step 3: Push and open PR**

```bash
git push -u origin chore/architecture-governance/android-device-identity-v1
```

Title:

```text
ci: add Android device identity frontier and instrumentation gate
```

Body:

```text
Governance-only preparation for android-device-identity-v1.

Adds the exact trusted feature frontier, a read-only unprivileged android-instrumentation job using official Android SDK tools and an API 36 headless emulator, and conditional JVM gates for the future core/data device-identity modules.

No Android feature implementation is included. No secrets, write permissions, third-party emulator action, notebook dependency, production signing, backend/network integration, or physical-device gate is added.

Do not merge automatically.
```

- [ ] **Step 4: Wait for five check contexts**

Expected:

```text
architecture-guard = success
governance-tests = success
secret-scan = success
android-build = success
android-instrumentation = success
```

On this governance PR the module does not exist, so `android-instrumentation` must report `DEVICE_IDENTITY_MODULE_NOT_PRESENT` and finish successfully without candidate instrumentation execution.

- [ ] **Step 5: Stop for explicit merge approval**

Report PR URL, head SHA, changed paths, local test totals, and all five check conclusions. Do not merge.

---

### Task 5: Merge after approval and require `android-instrumentation`

**Files:**
- No repository edits.

**Interfaces:**
- Consumes explicit human approval.
- Produces green main plus a five-check required-status policy.

- [ ] **Step 1: Freshly re-read PR state, head SHA, mergeability, and five checks**

Expected: open, mergeable, unchanged reviewed head, all checks success.

- [ ] **Step 2: Merge with expected head SHA and no bypass**

Use the repository's existing merge method.

- [ ] **Step 3: Wait for the post-merge main checks**

Expected all five checks success on the merge SHA.

- [ ] **Step 4: Read current branch protection/ruleset before mutation**

Preserve strict mode, PR requirements, admin enforcement, force-push protection, deletion protection, and existing required contexts.

- [ ] **Step 5: Append exactly `android-instrumentation` to required checks**

The resulting required contexts must include:

```text
architecture-guard
governance-tests
secret-scan
android-build
android-instrumentation
```

- [ ] **Step 6: Read back and verify no policy weakening**

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
