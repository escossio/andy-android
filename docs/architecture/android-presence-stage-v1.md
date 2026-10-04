# Presence stage V1 frontier

Governance only. This change creates no Android module, renderer, asset or feature.
The single manifest `android-presence-stage-v1.json` admits only the exact future
branch `feat/android-presence-stage-v1`, after this policy is merged into main.
Baseline: `977976e8435288d26790a711c4ce654a864e2d57`.

## Boundary and scope

Compose continues to own Home and all operational UI. One new physical Android
library, `:features:presence`, isolates the replaceable stage from onboarding's
existing SDK dependencies. It has **no project dependencies**. Onboarding may
consume the stage; the stage must never consume onboarding, app, core identity,
SDK, data, capabilities, integrations, sync, approval or command modules.

`PresenceStage.kt` owns the renderer-neutral contract and stage lifecycle/status.
Inputs are immutable visual values: explicit replay time/seed, pose, blink, gaze,
gesture/cancellation and clothing variant. No session object, credential, token,
client SDK, service locator, operational callback, approval, execution authority
or Attention Router access may cross this boundary, including via closures,
opaque objects, reflection or dependency injection. Renderer status describes
visual availability only; it cannot authorize or execute anything. Android/Compose
lifecycle handles stay internal to the adapter, never in the public visual model.

`PresenceReplay.kt` computes deterministic visual state from synthetic input.
`SceneViewPresenceRenderer.kt` is the only adapter to SceneView/Filament; its types
must not escape into the neutral contract or Home. Public rendering entry points
may accept Compose layout configuration, never operational objects or callbacks.
Compose rendering/lifecycle lambdas internal to the adapter are not execution
callbacks. Home selects the existing `AndyPresenceAvatar()` when disabled,
unavailable, asset loading fails, or recoverable initialization/rendering fails.
Do not catch fatal VM/native crashes as if recovery were guaranteed. Release the
renderer's resources when switching away. Preserve the Canvas implementation and
its current test unchanged; neither is writable in this frontier.

The experiment is one representative synthetic character, preferably seated and
framed at half-body, with eyes/head/jaw/torso rig, two rest poses, blink, synthetic
gaze target, one interruptible gesture, and **one clothing swap** (two variants
inside the same asset). Jaw animation uses synthetic replay only. No hair system,
asset store, remote download, material compiler, editor or additional modules.
The single self-contained `andy.glb` embeds meshes, rig, textures and animations;
`andy.LICENSE.txt` records redistribution license and provenance. No real-person
scan or personal data. No external glTF resource URLs. Built-in lighting suffices.

## Exact path budget

The manifest is the exhaustive literal path list (13 files). Each path has one purpose:

| Path or file in the manifest | Permitted change |
| --- | --- |
| `settings.gradle.kts` | Add only `include(":features:presence")`. |
| `gradle/libs.versions.toml` | Add only `sceneview = "2.3.0"` to versions and `sceneview = { module = "io.github.sceneview:sceneview", version.ref = "sceneview" }` to libraries. Preserve all existing entries. |
| `features/onboarding/build.gradle.kts` | Add only `implementation(project(":features:presence"))`. Preserve existing dependencies. |
| `OnboardingScreen.kt` | Wire the visual stage, local experiment enable/disable and fallback in the presence card; keep Home/operational behavior in Compose. Existing camera affordance may be hidden in that card; never pass permission state or request callback to the stage, or make camera permission an experiment prerequisite. |
| `features/presence/build.gradle.kts` | Android library using existing AGP/Compose plugins, compileSdk 37, build tools 36.0.0, minSdk 28, Java 17; only dependencies below. |
| `features/presence/src/main/AndroidManifest.xml` | Empty library manifest, matching the repository style; no permissions, components, features or providers. |
| `PresenceStage.kt`, `PresenceReplay.kt`, `SceneViewPresenceRenderer.kt` | Contract/lifecycle, deterministic visual state and isolated adapter as described above, under the exact presence package. |
| `andy.glb`, `andy.LICENSE.txt` | One packaged synthetic character and its redistribution evidence. |
| `PresenceReplayTest.kt`, `PresenceStageTest.kt` | Determinism, rig/pose state, cancellation, clothing variant, fallback selection and lifecycle regression tests, with synthetic fixtures. |

All 13 paths are required artifacts so the feature cannot omit its boundary,
asset/license or tests. No app manifest, MainActivity, session logic, root build,
CI, governance, `AGENTS.md` or `STATUS.md` is admitted. Existing camera permission
outside the stage is not new authorization for capture; changing that app-level
permission requires separate governance.

## Dependency authorization

Only one new direct runtime coordinate is authorized:
`io.github.sceneview:sceneview:2.3.0`, through the version catalog. Its upstream
[versioned dependency declaration](https://github.com/SceneView/sceneview-android/blob/v2.3.0/sceneview/build.gradle)
uses Filament `filament-android`, `gltfio-android`, `filament-utils-android` at
1.56.0, kotlin-math 1.5.3 and AndroidX UI support. These rendering/UI transitives
are permitted; this is not authorization for other SceneView artifacts or plugins.
No direct Filament override, ARSceneView, ARCore, XR or dynamic version is allowed.

Upstream also declares Fuel HTTP modules. They are **not authorized**. The adapter
must use local assets/buffers and declare the catalog dependency exactly as:

```kotlin
implementation(libs.sceneview) {
    exclude(group = "com.github.kittinunf.fuel")
}
```

The only other module dependencies permitted are existing
`implementation(platform(libs.androidx.compose.bom))`,
`implementation(libs.androidx.compose.foundation)`,
`implementation(libs.kotlinx.coroutines.core)` and `testImplementation(libs.junit)`.
No project, file/JAR, plugin, repository or additional dependency is authorized.
Existing OkHttp in the shared catalog remains SDK-only; it is not admitted into
Presence. Verify the resolved Presence runtime dependency graph and local asset
loading in the future feature's unprivileged CI. If Fuel exclusion is incompatible,
or toolchain/native compatibility needs other versions/files, stop with
`FRONTIER_EXPANSION_REQUIRED`; do not restore networking or silently replace pins.
This governance pass does not prove Android/native runtime compatibility.

## Exclusions and review gates

Camera capture, CameraX, ML Kit, MediaPipe, face recognition/tracking, perception,
voice/microphone, RECORD_AUDIO, speech recognition and local/remote TTS are outside
V1. A virtual 3D scene camera is allowed; an Android capture camera is not.
No new backend, operational SDK, Firebase, persistence/database, direct HTTP,
WebSocket, sockets, WebView, provider API or authority decisions. Existing bans on
Retrofit, Ktor, OkHttp use, DI frameworks, Room, DataStore and WorkManager remain.
No runtime network access, including transitives or remote assets, is authorized.

The existing schema/guard remains unchanged: literal paths, required artifacts,
case-insensitive forbidden-content regexes, trusted-base execution. Scoped regexes
separate the new presence package/build from existing onboarding authority and the
shared catalog's existing OkHttp declaration. Tests cover accepted baseline text,
synthetic allowed rendering and rejected dependencies/authority/capture inputs.
These are lexical checks, not a Kotlin/Gradle sandbox or dependency resolver.
Review must verify the exact config deltas, package/namespace, neutral API (including
closure capture), dependency graph and embedded GLB resources/license. Binary GLB
content is outside the guard/secret scanner's text coverage. Reject obfuscated or
indirect equivalents of prohibited behavior even when regexes cannot detect them.

Feature validation must demonstrate replay determinism, gesture interruption,
clothing switching, both idle poses and fallback on disable/load failure, then
rendering/lifecycle behavior on a CI worker/device. Heavy Android validation stays
off the AGT control plane. Any extra file, dependency or authority requires separate
governance with `FRONTIER_EXPANSION_REQUIRED`.
