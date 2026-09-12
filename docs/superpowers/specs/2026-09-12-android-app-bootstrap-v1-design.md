# Android App Bootstrap V1 — Architectural Design

Status: **approved design, not implemented**  
Date: 2026-09-12  
Repository: `escossio/andy-android`  
Target functional frontier: `android-app-bootstrap-v1`

This document defines the first functional Android implementation in the Andy Android repository. It builds on the already-merged governance bootstrap and does not change the permanent platform authority model.

The permanent boundary remains:

`andy-android -> Kotlin SDK -> Client API -> attention-router`

The permanent authority chain remains:

`Human Identity -> Tenant Membership -> Active Tenant -> Device -> Session -> SDK/API -> Capabilities / Channels / Integrations`

This frontier proves only that the Android toolchain, repository boundaries, CI, independent certification workstation, and a real Android device can all reproduce the same candidate commit. It does not implement product behavior beyond a minimal Compose shell.

## 1. Goal

Create the smallest real Android application that is worth keeping:

- native Kotlin;
- Jetpack Compose;
- application ID `io.github.escossio.andy`;
- one launcher `MainActivity`;
- one screen containing only the text **Andy**, centered;
- one minimal unit test;
- deterministic Gradle wrapper/toolchain;
- CI build proof;
- independent workstation build proof using the same commit SHA;
- real-device installation and launch proof.

The frontier is complete only when one exact candidate SHA is traceable through repository governance, GitHub CI, workstation certification, APK digest, and a physical Android device.

## 2. Non-goals

This frontier must not implement or introduce:

- human login;
- Google Sign-In;
- email challenge;
- Client API networking;
- Kotlin SDK network transport;
- tenant selection;
- device enrollment;
- Android Keystore protocol work;
- location;
- WhatsApp;
- Home Assistant;
- notifications/push;
- Room or local persistence;
- background sync;
- voice or microphone;
- analytics;
- Firebase;
- Hilt/Dagger;
- Retrofit/OkHttp/Ktor client;
- WorkManager;
- provider SDKs;
- production signing;
- Play Store publishing;
- product navigation, theme system, logo, onboarding, or final UX.

The visible UI is intentionally disposable in appearance but permanent in architectural placement.

## 3. Prerequisite governance micro-change

The current governance workflow correctly enforces architecture, secret scanning, and governance tests, but it does not compile Android. The functional frontier is not allowed to edit `.github/workflows/**`, so Android build verification must be installed by a separate governance change before the feature branch is opened.

A dedicated governance PR must add an `android-build` check with these properties:

- separate from `architecture-guard` and `secret-scan`;
- minimal permissions;
- no repository or environment secrets;
- no signing credentials;
- no deployment permissions;
- no provider credentials;
- disposable GitHub-hosted runner;
- JDK 17;
- candidate execution is allowed only inside this non-privileged build job because compiling the application necessarily executes candidate Gradle/build logic;
- trusted security checks continue to execute from the base branch and continue to inspect the candidate as data only.

Before an Android project exists, the new check may succeed with an explicit, deterministic `ANDROID_PROJECT_NOT_PRESENT` bootstrap result. Once the functional frontier exists, the check must execute the pinned wrapper and run the approved build/test commands.

After the first real `android-build` check context is observed successfully, that exact context is added to `main` protection. Context names are discovered from GitHub; they are not guessed in branch protection configuration.

## 4. Trust separation in CI

The repository intentionally has two different trust models.

### 4.1 Trusted security jobs

`architecture-guard`, `secret-scan`, and governance-policy tests remain base-trusted. They may fetch candidate Git objects, paths, and blobs, but they do not execute candidate scripts, Gradle, Kotlin, or shell code.

### 4.2 Candidate build job

`android-build` is explicitly an unprivileged candidate-execution job. It may check out and execute the pull-request candidate solely to prove that the Android project builds and tests.

The candidate build job must have:

- `contents: read` at most;
- no secrets;
- no write token use;
- no deploy capability;
- no package publishing;
- no signing material;
- no access to external private infrastructure.

A successful candidate build does not replace security/governance checks. Merge eligibility requires all required checks independently.

## 5. Frozen Android baseline

The first functional frontier uses exactly:

- `minSdk = 28`;
- `targetSdk = 36`;
- `compileSdk = 36`;
- Android Gradle Plugin `9.4.0`;
- Gradle `9.6.0`;
- JDK `17`;
- Kotlin using the AGP 9.x integrated Kotlin support where applicable;
- stable Jetpack Compose only;
- Compose BOM `2026.08.00`;
- Gradle Kotlin DSL;
- Version Catalog.

No dependency is added merely because it is common in Android projects.

## 6. Exact frontier and file boundary

The trusted frontier already defines branch:

`feat/android-app-bootstrap-v1`

and the exact implementation allowlist:

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `gradle/libs.versions.toml`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradlew`
- `gradlew.bat`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/io/github/escossio/andy/MainActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/io/github/escossio/andy/BootstrapTest.kt`

The feature branch must not change its frontier manifest, architecture guard, workflows, `AGENTS.md`, security documents, or repository-wide governance.

If implementation needs any additional path or dependency, it stops with `FRONTIER_EXPANSION_REQUIRED`; governance changes are reviewed separately.

## 7. Gradle and module shape

This frontier creates exactly one Android application module: `app`.

The root build files exist only to establish plugin/version resolution and the wrapper. The app module owns packaging and the minimal Compose Activity.

The project does not create premature `core`, `sdk`, `features`, `capabilities`, `integrations`, `data`, or `sync` Gradle modules. Those repository zones already exist as architectural responsibilities, but physical Gradle modularization happens only when a later frontier has actual code that benefits from it.

The build must use the committed Gradle wrapper. A globally installed Gradle is neither required nor authoritative.

## 8. Minimal application behavior

`MainActivity` is the launcher Activity.

Its only product-visible behavior is:

- start successfully;
- render one Compose surface;
- display the text `Andy`;
- center that text in the available screen.

`MainActivity.kt` defines one package-visible immutable bootstrap constant named `BOOTSTRAP_TEXT` with value `"Andy"`, and the Compose content renders that constant. This constant exists solely to make the JVM unit test prove the exact visible bootstrap text without adding a test-only framework or extra production layer.

No splash experience, navigation graph, custom theme, network call, permission request, background worker, service, receiver, provider integration, or persistent state is introduced.

The app label in resources is `Andy`.

## 9. Unit-test contract

`BootstrapTest.kt` is a plain JVM unit test and asserts exactly that `BOOTSTRAP_TEXT == "Andy"`.

The test adds no instrumentation framework, emulator dependency, Robolectric, DI framework, or extra production abstraction. It exists to prove that the `app` unit-test pipeline is wired and that the exact bootstrap text rendered by the shell is stable.

The frontier succeeds only if `:app:testDebugUnitTest` passes.

## 10. GitHub Android build check

Once the governance prerequisite is merged, the candidate build job for `feat/android-app-bootstrap-v1` must run, at minimum:

`./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`

The build uses JDK 17 and API 36 tooling on the GitHub-hosted runner.

The job must fail if either unit tests or debug assembly fail.

The initial build job does not publish artifacts externally, sign production binaries, upload to Play, or use secrets.

If practical without expanding permissions or scope, the job may expose the debug APK as a short-lived GitHub Actions artifact. Artifact publication is optional for this frontier; reproducibility by commit SHA remains the authority.

## 11. Independent workstation certification

A separate operator-managed certification workstation is used as an additional proof point. It is deliberately not part of repository architecture and is not a required GitHub status check.

The workstation baseline is:

- x86_64 Linux;
- JDK 17;
- Android SDK Platform 36;
- Android Build-Tools 36.0.0;
- ADB/platform-tools capable of communicating with the test device;
- no requirement for globally installed Gradle.

The repository must never contain the workstation hostname, login, network address, local alias, SSH details, Android SDK filesystem path, or other lab inventory.

Certification uses an isolated clean checkout/worktree of the exact pull-request commit SHA, not merely the same branch name.

The workstation runs:

`./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`

and computes SHA-256 for the resulting debug APK.

The certification record is operational evidence, not committed private-infrastructure inventory.

## 12. Physical-device certification

A real Android device is part of the first certification pass.

The approved device class for this proof is:

- Android 16;
- API level 36;
- arm64-v8a;
- authorized over ADB.

No device serial, account data, personal identifiers, installed-app inventory, precise location, or other private device data is committed to Git.

The physical-device proof must use the APK produced from the same certified commit SHA. The operator may rebuild locally from that SHA, but the resulting APK digest must be recorded so the exact installed binary is identifiable.

Certification steps are:

1. verify device is connected and authorized;
2. install the debug APK for the candidate SHA;
3. launch package `io.github.escossio.andy`;
4. verify the Activity starts without immediate crash;
5. verify the visible screen contains only the intended bootstrap product text `Andy`;
6. record candidate commit SHA and APK SHA-256 in the execution report.

This is a development-device installation only. It is not a production deploy or Play release.

## 13. Reproducibility chain

The desired evidence chain is:

`PR candidate SHA -> trusted governance checks -> GitHub android-build PASS -> independent workstation build PASS -> APK SHA-256 -> physical API-36 device PASS`

Every stage must refer to the same Git commit SHA.

A statement such as “the branch was the same” or “similar code compiled” is insufficient certification.

## 14. Failure handling

The frontier fails closed for architectural or reproducibility problems.

Stop with `FRONTIER_EXPANSION_REQUIRED` if:

- an additional repository path is needed;
- a new dependency outside the approved baseline is required;
- a workflow/governance change is discovered while on the feature branch;
- the implementation needs a different module boundary.

Treat these as ordinary implementation failures, not frontier expansions:

- Gradle syntax error;
- Kotlin compile error;
- unit-test failure caused by candidate code;
- resource/manifest error within allowed files;
- build-tool invocation error caused by the implementation.

An unavailable external certification workstation or disconnected physical device blocks only the independent certification step; it must not be silently reported as PASS. GitHub remains the repository merge authority unless branch protection is explicitly changed through governance.

## 15. Merge and completion policy

The functional PR is not merged until:

- `architecture-guard` passes;
- `governance-tests` passes;
- `secret-scan` passes;
- `android-build` passes;
- changed paths exactly match the trusted frontier;
- forbidden dependency patterns are absent;
- the independent workstation builds/tests the exact candidate SHA;
- the debug APK SHA-256 is recorded;
- the real API-36 device installs and launches the candidate successfully;
- the screen proves the minimal `Andy` shell;
- no secret or real personal/infrastructure data is introduced.

The workstation/device certification remains complementary rather than a permanent required GitHub check in this first frontier. If later reliability and availability justify a self-hosted runner or external check integration, that is a separate governance design.

## 16. Successful frontier result

A successful `android-app-bootstrap-v1` leaves the repository with exactly one real Android application module and a deliberately tiny visible product surface.

The expected final statement is conceptually:

`candidate SHA X -> all required GitHub checks PASS -> workstation SHA X PASS -> APK SHA-256 Y -> real Android 16/API 36 device PASS -> screen = Andy`

The next frontier may then begin actual product capabilities, starting from a reproducible, governed Android foundation instead of an unverified prototype.
