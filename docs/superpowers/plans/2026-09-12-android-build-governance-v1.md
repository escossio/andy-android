# Android Build Governance V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` for this plan. The user explicitly chose inline execution for speed; do not use subagent-driven development unless a later human instruction overrides that decision. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a base-trusted, unprivileged `android-build` GitHub check before any functional Android code is admitted, then make that real observed check required on protected `main`.

**Architecture:** Existing security jobs remain base-trusted and candidate-as-data. The new `android-build` job is intentionally different: its workflow definition comes from trusted `main`, but it checks out and executes the exact PR candidate SHA in a disposable GitHub-hosted runner with read-only permissions, no secrets, no signing, and no private-infrastructure access. Before the Android project exists, it reports `ANDROID_PROJECT_NOT_PRESENT` and succeeds deterministically.

**Tech Stack:** GitHub Actions, GitHub-hosted Ubuntu runner, `actions/checkout@v4`, `actions/setup-java@v4`, JDK 17, Android SDK command-line tools supplied by the runner, GitHub REST/CLI for observed check contexts and branch-protection context addition.

**Spec:** `docs/superpowers/specs/2026-09-12-android-app-bootstrap-v1-design.md`

## Global Constraints

- Work only in `escossio/andy-android`.
- Governance branch is exactly `chore/architecture-governance/android-build-ci-v1`.
- Modify only `.github/workflows/governance.yml` in the governance PR unless a blocker requires `FRONTIER_EXPANSION_REQUIRED`.
- Do not modify the Android frontier manifest, guard implementation, scanner implementation, scoped/root `AGENTS.md`, or functional Android paths.
- Preserve `architecture-guard`, `governance-tests`, and `secret-scan` behavior unchanged.
- Trusted security jobs continue to use base code and inspect candidates as data only.
- `android-build` may execute candidate Gradle/Kotlin only in its own unprivileged job.
- `android-build` has no secrets, no write permission, no signing material, no deployment/package publishing, and no private-infrastructure access.
- Candidate checkout uses the exact PR head SHA and `persist-credentials: false`.
- JDK is 17.
- Android compilation target reserved for the feature is `compileSdk 37`; SDK Build Tools are `36.0.0`.
- Do not add `android-build` to branch protection until a real successful `android-build` context has run on `main`.
- Do not merge the governance PR without explicit human authorization.
- No runtime, Attention Router, database, WhatsApp, provider, notebook, or physical-device mutation belongs to this plan.

---

### Task 1: Preflight and Isolated Governance Worktree

**Files:**
- Read: `AGENTS.md`
- Read: `.github/workflows/governance.yml`
- Read: `.github/architecture/README.md`
- Read: spec from planning branch
- Create local worktree only: `/tmp/andy-android-android-build-ci-v1`

**Interfaces:**
- Consumes: protected `main` and approved spec.
- Produces: isolated governance branch based on current `origin/main`.

- [ ] **Step 1: Synchronize and prove the base**

Run from `/srv/projetos/andy-android`:

```bash
git fetch origin --prune
git status --short --branch
git rev-parse origin/main
gh repo view escossio/andy-android --json visibility,defaultBranchRef,url
```

Expected: repository is public, default branch is `main`, and no local mutation is required.

- [ ] **Step 2: Read trusted instructions and design**

Run:

```bash
git show origin/main:AGENTS.md
git show origin/main:.github/workflows/governance.yml
git show origin/main:.github/architecture/README.md
gh api 'repos/escossio/andy-android/contents/docs/superpowers/specs/2026-09-12-android-app-bootstrap-v1-design.md?ref=docs/android-app-bootstrap-v1-design-20260912' -H 'Accept: application/vnd.github.raw+json'
```

Expected: all content is readable. If the approved spec is missing or the workflow no longer matches the base-trusted design, stop and report.

- [ ] **Step 3: Create isolated worktree**

Run:

```bash
git worktree add -b chore/architecture-governance/android-build-ci-v1 /tmp/andy-android-android-build-ci-v1 origin/main
cd /tmp/andy-android-android-build-ci-v1
git status --short --branch
```

Expected: clean branch exactly `chore/architecture-governance/android-build-ci-v1`.

---

### Task 2: Add the Unprivileged Candidate Android Build Job

**Files:**
- Modify: `.github/workflows/governance.yml`

**Interfaces:**
- Consumes: existing trusted workflow triggers and three security/governance jobs.
- Produces: fourth job/check named exactly `android-build`.

- [ ] **Step 1: Preserve the existing workflow and append one job**

Do not rewrite the existing three jobs except for formatting that is strictly necessary. Add this job under `jobs:`:

```yaml
  android-build:
    name: android-build
    runs-on: ubuntu-latest
    timeout-minutes: 25
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

      - name: Checkout exact PR candidate for unprivileged build
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

      - name: Detect Android project
        id: android_project
        shell: bash
        run: |
          set -euo pipefail
          if [[ -x ./gradlew && -f ./settings.gradle.kts && -f ./app/build.gradle.kts ]]; then
            echo 'present=true' >> "$GITHUB_OUTPUT"
            echo 'ANDROID_PROJECT_PRESENT'
          else
            echo 'present=false' >> "$GITHUB_OUTPUT"
            echo 'ANDROID_PROJECT_NOT_PRESENT'
          fi

      - name: Install compile SDK and build tools
        if: steps.android_project.outputs.present == 'true'
        shell: bash
        run: |
          set -euo pipefail
          command -v sdkmanager
          sdkmanager 'platforms;android-37' 'build-tools;36.0.0'

      - name: Build and test exact candidate
        if: steps.android_project.outputs.present == 'true'
        shell: bash
        run: |
          set -euo pipefail
          ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Security requirements for this exact job:

- do not add `secrets.*`;
- do not add `permissions: write-all` or any write permission;
- do not set `GH_TOKEN`, signing variables, provider credentials, or deployment credentials;
- do not call `note`, SSH, the notebook, AGT-private services, or Attention Router;
- do not execute repository code before the explicit candidate build steps;
- do not alter the existing base-trusted security jobs to checkout candidate code.

- [ ] **Step 2: Verify the trust split mechanically**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
p = Path('.github/workflows/governance.yml')
s = p.read_text()
assert 'name: android-build' in s
assert "java-version: '17'" in s
assert "platforms;android-37" in s
assert "build-tools;36.0.0" in s
assert 'persist-credentials: false' in s
assert './gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug' in s
assert 'secrets.' not in s
print('ANDROID_BUILD_WORKFLOW_STATIC_CHECK=PASS')
PY

python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 scripts/architecture/check_guardrails.py --policy-self-check
python3 scripts/security/scan_secrets.py .
git diff --check
git diff -- .github/workflows/governance.yml
```

Expected: static check PASS, existing 26 governance/security tests PASS, `ARCH_PASS`, `SECRET_SCAN_PASS`, no whitespace errors, and diff contains only the intended workflow change.

- [ ] **Step 3: Commit the governance change**

Run:

```bash
git add .github/workflows/governance.yml
git commit -m 'ci: add unprivileged Android build check'
git status --short --branch
```

Expected: clean worktree after commit.

---

### Task 3: Publish the Governance PR and Stop at the Merge Gate

**Files:**
- Remote branch/PR only.

**Interfaces:**
- Consumes: governance commit from Task 2.
- Produces: reviewed PR whose existing trusted checks must pass; no merge yet.

- [ ] **Step 1: Push the branch and open PR**

Run:

```bash
git push -u origin chore/architecture-governance/android-build-ci-v1

gh pr create \
  --repo escossio/andy-android \
  --base main \
  --head chore/architecture-governance/android-build-ci-v1 \
  --title 'ci: add Android build certification check' \
  --body 'Adds a base-defined, unprivileged android-build job. Existing architecture/security jobs remain base-trusted. No Android application code is introduced.'
```

- [ ] **Step 2: Verify PR paths and checks**

Run:

```bash
gh pr diff --repo escossio/andy-android chore/architecture-governance/android-build-ci-v1 --name-only
gh pr checks --repo escossio/andy-android chore/architecture-governance/android-build-ci-v1 --watch
```

Expected changed path: only `.github/workflows/governance.yml`.

Expected on this PR: the currently trusted required checks pass. The new `android-build` context is not required to exist on this PR because `pull_request_target` uses the workflow from base `main`; it becomes active only after this governance workflow is merged to `main`.

- [ ] **Step 3: Stop for explicit human merge authorization**

Report:

```text
ANDROID_BUILD_GOVERNANCE_PR_READY=YES
PR_URL=<actual URL>
HEAD_SHA=<actual SHA>
CHANGED_PATHS=.github/workflows/governance.yml
EXISTING_REQUIRED_CHECKS=PASS
MERGED=NO
```

Do not merge until the human explicitly authorizes it.

---

### Task 4: Post-Merge Activation and Required-Check Registration

**Files:**
- GitHub branch-protection required-check contexts only.

**Interfaces:**
- Consumes: human-authorized merged governance PR.
- Produces: real successful `android-build` run on `main`, then required `android-build` branch-protection context without replacing the existing three contexts.

- [ ] **Step 1: After explicit authorization, merge safely**

Refresh the PR head SHA first, then merge with the repository's accepted merge method and expected head protection. Do not bypass checks or branch protection.

- [ ] **Step 2: Observe the real `android-build` check on merged `main`**

Run:

```bash
git -C /srv/projetos/andy-android fetch origin --prune
MAIN_SHA=$(git -C /srv/projetos/andy-android rev-parse origin/main)
gh api "repos/escossio/andy-android/commits/$MAIN_SHA/check-runs" \
  --jq '.check_runs[] | [.name,.status,.conclusion] | @tsv'
```

Expected: `android-build` exists and is `completed success`; because no Android project exists yet, its log must include `ANDROID_PROJECT_NOT_PRESENT`.

Do not continue if the real context is absent, pending, cancelled, or failed.

- [ ] **Step 3: Add only the observed context to branch protection**

Read current contexts:

```bash
gh api repos/escossio/andy-android/branches/main/protection/required_status_checks/contexts
```

Expected before mutation: existing contexts include `architecture-guard`, `governance-tests`, and `secret-scan`.

Add the one new context without replacing existing contexts:

```bash
printf '%s\n' '{"contexts":["android-build"]}' >/tmp/andy-add-android-build-context.json
gh api --method POST \
  repos/escossio/andy-android/branches/main/protection/required_status_checks/contexts \
  --input /tmp/andy-add-android-build-context.json
rm -f /tmp/andy-add-android-build-context.json
```

- [ ] **Step 4: Read back required contexts**

Run:

```bash
gh api repos/escossio/andy-android/branches/main/protection/required_status_checks/contexts
```

Expected exact set contains all four:

```text
architecture-guard
android-build
governance-tests
secret-scan
```

Do not weaken PR requirement, admin enforcement, force-push/deletion restrictions, or conversation-resolution settings.

- [ ] **Step 5: Final report**

Report:

```text
ANDROID_BUILD_GOVERNANCE_FINAL_STATUS=PASS/FAIL
MAIN_SHA=
ANDROID_BUILD_CONTEXT_OBSERVED=YES/NO
ANDROID_BUILD_MAIN_RESULT=PASS/FAIL
ANDROID_PROJECT_NOT_PRESENT_RESULT=PASS/FAIL
REQUIRED_CONTEXTS=
EXISTING_SECURITY_JOBS_CHANGED=NO
SECRETS_USED=NO
WRITE_PERMISSION_GRANTED_TO_BUILD_JOB=NO
PRIVATE_INFRA_ACCESSED=NO
FRONTIER_EXPANSION_REQUIRED=NO/YES
```

The functional `feat/android-app-bootstrap-v1` must not start until this report is PASS and `android-build` is required on `main`.
