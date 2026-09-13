# Human Identity + Device Permissions V1 Design

Date: 2026-09-12
Status: Design approved in chat; implementation not yet authorized
Primary client repository: `escossio/andy-android`
Required backend companion work: `escossio/attention-router`

## 1. Purpose

The next Andy Android version advances from local installation identity to the first real human onboarding boundary.

The version must provide:

1. a visible and functional **Continue with Google** flow;
2. backend validation of the Google identity before the Android client treats the human identity as validated;
3. a device-permission onboarding surface for **foreground location, notifications, camera, and microphone**;
4. a stable Android signing identity suitable for Google OAuth registration and future in-place updates;
5. preservation of the already-approved security rule that Android permission, Google authentication, device identity, tenant authority, and server authorization are distinct concepts.

This version intentionally stops before tenant membership, active tenant selection, final application session issuance, WhatsApp enrollment, Home Assistant, background location, FCM, or provider integrations.

## 2. Permanent authority chain

This design continues the permanent architecture:

`Human Identity -> Tenant Membership -> Active Tenant -> Device -> Session -> SDK/API -> Capabilities / Channels / Integrations`

This version implements only the first human-identity step and local Android permission/capability observation.

The local device identity from Device Identity V1 remains installation-scoped. It is **not yet enrolled or bound to the backend** in this version.

## 3. User-visible flow

### 3.1 Startup

The app starts by ensuring the existing Device Identity is `READY`.

If Device Identity is unavailable, the app fails closed for human onboarding and shows a bounded recoverable error state. It must not silently replace an established device key.

### 3.2 Continue with Google

When Device Identity is ready and no backend-validated human identity is active in the current app flow, the user sees a real **Continue with Google** action.

The Android client uses the modern Credential Manager / Sign in with Google path. Provider-specific Google code must remain outside platform-neutral `core/`.

### 3.3 Backend validation

The client must not promote a Google result directly to trusted Human Identity.

The intended trust flow is:

`Andy -> request backend challenge -> backend returns single-use nonce/challenge -> Credential Manager requests Google credential using nonce -> Google returns ID token -> Andy sends ID token + challenge reference through SDK -> backend validates token and nonce -> backend returns validated Human Identity result`

The backend is authoritative for Human Identity validation.

### 3.4 Permission onboarding

After Human Identity is validated, the app shows four independent permission/capability cards:

- Location
- Notifications
- Camera
- Microphone

The app does **not** immediately fire four Android permission dialogs in sequence. Each native permission prompt appears only after the user explicitly taps the corresponding card/action.

Denying any permission does not invalidate Google authentication or Human Identity.

## 4. Human Identity trust model

### 4.1 Google credential semantics

The Android client obtains a Google ID token only for immediate backend validation. The token is transient and must not become long-lived local application authority.

The backend validates at minimum:

- token signature against the Google trust chain;
- issuer;
- audience for the approved server/client configuration;
- token expiry/time validity;
- nonce associated with the backend challenge;
- single-use challenge semantics.

Replay of a consumed challenge must fail closed.

### 4.2 Identity key

The authoritative Google-backed Human Identity key is based on the stable Google subject identifier (`sub`) under provider `google`.

Email address must not be used as the primary identity key because email can change independently of the provider subject.

The backend may return an opaque Human Identity reference after successful validation. That reference does not grant tenant, device, channel, or execution authority.

### 4.3 No partial promotion

If the user cancels Google sign-in, networking fails, Credential Manager fails, the backend rejects the token, nonce validation fails, or the challenge expires, the flow remains unauthenticated.

No partial Human Identity state becomes authoritative merely because a provider credential was locally acquired.

## 5. Backend companion boundary

`attention-router` currently has authenticated integration admission but does not yet have the human-login boundary required by this version. A companion backend frontier is therefore required.

The minimum backend behavior for this version is:

1. issue a short-lived, single-use human-auth challenge with an unpredictable nonce;
2. accept a Google ID token plus the challenge reference;
3. validate Google token properties and challenge/nonce binding;
4. recognize or create the provider-backed Human Identity keyed by `google:sub`;
5. return a bounded `HUMAN_IDENTITY_VALIDATED` result with an opaque server identity reference;
6. reject replay, expiry, malformed credentials, wrong audience/issuer, and invalid nonce fail-closed.

This backend work must **not** create tenant membership, active tenant, final user session, refresh-token architecture, device enrollment, or integration credentials in this version.

The Android app must reach this backend only through the approved SDK/client boundary. UI and feature code must not construct raw Client API URLs, authentication headers, or ad-hoc protocol JSON.

## 6. Android component boundaries

### 6.1 `core/`

Platform-neutral Human Identity models and state transitions live in `core/`.

`core/` may represent states such as:

- `UNAUTHENTICATED`
- `GOOGLE_CREDENTIAL_ACQUIRED` as a transient process state
- `BACKEND_VALIDATING`
- `HUMAN_IDENTITY_VALIDATED`
- bounded failure/cancelled outcomes

`core/` must not depend on Android APIs, Credential Manager, Google provider libraries, raw HTTP clients, or concrete backend infrastructure.

### 6.2 `integrations/`

Google-specific mobile handoff belongs under `integrations/`.

This layer owns Credential Manager / Sign in with Google integration and converts the provider result into a transient credential representation suitable for the application flow.

It must not create Human Identity authority, tenant authority, or persist long-lived Google credentials as application authority.

### 6.3 `sdk/`

`sdk/` is the only normal Android boundary to the Attention Router Client API contracts and transport.

For this version the SDK surface needs the minimum challenge + validate operations required by Human Identity V1.

### 6.4 `features/`

The user-facing onboarding flow belongs under `features/`.

It coordinates:

- Continue with Google;
- loading/cancel/error states;
- backend validation progress/result;
- permission onboarding cards;
- bounded success state for this version.

Feature code must consume approved interfaces rather than directly invoking provider internals or raw transport.

### 6.5 `capabilities/`

Typed Android-native capabilities own permission/support/operational observation for:

- foreground location;
- notifications;
- camera;
- microphone.

Each capability must keep at least these concepts separate:

1. **support state** — whether the OS/device exposes the relevant capability;
2. **Android permission state** — `NOT_REQUESTED`, `GRANTED`, or `DENIED` as applicable;
3. **operational state** — whether the capability is currently usable after platform constraints are considered.

Android permission never becomes server/tenant authority.

### 6.6 `data/`

`data/` may persist only local onboarding/capability UX state that is necessary for a coherent app experience, such as whether a rationale has already been shown.

It must not persist the Google ID token as long-lived authority and must not promote cached local Human Identity state over server authority.

## 7. Permission requirements

### 7.1 Location

This version requests **foreground location only**.

The user may grant approximate or precise location according to Android behavior. Background location is explicitly out of scope.

No continuous tracking service is introduced in this version.

### 7.2 Notifications

Notification permission is requested only where the Android API level requires runtime consent. The capability layer still exposes a normalized permission/operational state across supported versions.

FCM/push delivery is not introduced in this version.

### 7.3 Camera

Camera permission is requested only after the user taps the camera permission card/action.

No QR scanner or image-recognition feature is introduced yet.

### 7.4 Microphone

Microphone permission is requested only after the user taps the microphone permission card/action.

No audio recording, transcription, or TTS feature is introduced yet.

## 8. Stable Android signing and Google OAuth registration

Google Android OAuth registration depends on the application package name and the signing-certificate fingerprint. Future in-place updates also require consistent signing.

Therefore this version establishes a **stable development signing identity** before the real Google acceptance flow is considered complete.

Rules:

- signing material must never be committed to the public repository;
- signing secrets are available only to trusted build contexts, not untrusted PR candidate execution;
- PR CI may continue to use ephemeral debug signing for build/test validation;
- trusted `main` packaging intended for physical acceptance uses the stable development signing identity;
- the Google Android OAuth client is registered for `io.github.escossio.andy` and the stable signing certificate fingerprint;
- the server-side Google client/audience configuration required for backend ID-token validation is treated as configuration, not as Android authority.

If the currently installed physical build is signed by a different certificate, one controlled reinstall is permitted to establish the stable signing baseline. After that baseline is established, subsequent acceptance builds should update in place so Device Identity continuity can be tested.

## 9. Error handling

The design is fail-closed at authority boundaries and permissive at optional capability boundaries.

Human Identity failures:

- user cancellation -> return to unauthenticated UI without partial promotion;
- provider error -> bounded retry state;
- network/backend unavailable -> bounded retry state;
- expired/consumed challenge -> restart challenge flow;
- token/nonce/audience/issuer validation failure -> remain unauthenticated;
- malformed server response -> remain unauthenticated.

Permission failures/denials:

- denial affects only that capability;
- login remains valid;
- other permission cards remain independently actionable;
- app must not loop native permission dialogs automatically after denial.

## 10. Security and privacy constraints

- No real OAuth secrets or signing keys in repository history.
- No Google ID tokens in logs.
- No full opaque Human Identity identifiers in ordinary logs.
- No precise location values logged as part of permission onboarding.
- No tenant IDs because tenant work is out of scope.
- No provider credentials retained as durable application authority.
- Structured logs may record bounded events such as `GOOGLE_SIGN_IN_STARTED`, `HUMAN_IDENTITY_VALIDATED`, `HUMAN_IDENTITY_VALIDATION_FAILED:<reason-code>`, and normalized capability-state transitions without personal values.

## 11. Testing strategy

### 11.1 Attention Router tests

The companion backend frontier must test at minimum:

- challenge issuance and expiry;
- challenge single-use/replay rejection;
- nonce mismatch rejection;
- malformed ID token rejection;
- invalid signature/issuer/audience/expiry rejection through appropriate verifier boundaries;
- valid synthetic verifier result -> Human Identity recognized/created by `google:sub`;
- repeated validation for the same provider subject resolves to the same Human Identity;
- no tenant/session/device enrollment side effects.

CI must not require a personal Google account.

### 11.2 Android JVM/unit tests

Test at minimum:

- Human Identity state transitions;
- user cancellation;
- provider error;
- backend/network failure;
- backend rejection;
- successful backend validation;
- no local promotion before backend validation;
- permission states remain independent;
- denial of one permission does not alter Human Identity or other capabilities.

### 11.3 Android instrumentation tests

Use real Android runtime tests where platform behavior matters, especially permission-state normalization and existing Device Identity continuity.

Do not require a real personal Google account in CI. Provider and backend boundaries should be replaceable by controlled test doubles for deterministic CI.

### 11.4 Physical acceptance

The real-phone acceptance sequence is:

1. install the stable-signed acceptance build;
2. verify existing Device Identity behavior reaches `DEVICE_IDENTITY_READY`;
3. tap **Continue with Google**;
4. select a real Google account;
5. verify backend result reaches `HUMAN_IDENTITY_VALIDATED`;
6. exercise Location, Notifications, Camera, and Microphone cards individually;
7. verify denial/grant combinations do not invalidate Human Identity;
8. install the next stable-signed build **over** the existing app;
9. verify Device Identity remains the same installation identity after update.

## 12. Explicitly out of scope

This version does not implement:

- email/new-device challenge;
- tenant creation or membership;
- active tenant switching;
- authoritative server `device_id` enrollment;
- device-to-human binding on the backend;
- final application session or refresh-token architecture;
- FCM;
- WhatsApp QR or channel installation;
- Home Assistant;
- SMS;
- contacts;
- calendar access;
- file/storage permissions;
- Bluetooth/Nearby Devices;
- background location;
- QR scanner behavior;
- camera capture product behavior;
- audio recording/transcription product behavior;
- arbitrary remote commands;
- conversation UI.

## 13. Completion criteria

Human Identity + Device Permissions V1 is complete only when all of the following are true:

1. Device Identity V1 remains green and reaches `READY` on real Android.
2. Continue with Google is visible and functional on the physical acceptance build.
3. Google credential is not treated as trusted Human Identity until backend validation succeeds.
4. Backend challenge/nonce validation is single-use and fail-closed.
5. The authoritative provider identity key is `google:sub`, not email.
6. Human Identity validation causes no tenant/session/device-enrollment side effect.
7. Location, Notifications, Camera, and Microphone are independently requestable through user action.
8. Permission denial never invalidates Human Identity.
9. No background location is requested.
10. Stable development signing is established without committing signing material.
11. The real Google acceptance flow works on the physical phone.
12. Required CI/governance checks for both repositories pass at exact candidate SHAs before merge.

## 14. Implementation sequencing constraint

Implementation should be split into independently reviewable backend and Android frontiers, with governance/frontier expansion reviewed before functional code that depends on it.

The implementation plan must preserve existing Device Identity behavior and must not expand into tenant/session/device enrollment merely because the Human Identity flow exists.