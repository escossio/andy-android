# Android Device Identity V1 — Design

Date: 2026-09-12
Repository: `escossio/andy-android`
Status: Approved design, implementation not yet started

## Purpose

Define the first post-bootstrap Android security frontier for Andy: create and validate a local cryptographic device identity that is stable for one installation, automatically provisioned on first launch, non-exportable, non-restorable across reinstall, and fail-closed after becoming established.

This frontier intentionally stops before Google sign-in, email challenge, backend enrollment, tenant binding, session establishment, network transport, or arbitrary signing APIs.

## Architectural position

The permanent identity chain remains:

`Human Identity -> Tenant Membership -> Active Tenant -> Device -> Session -> SDK/API -> Capabilities / Channels / Integrations`

This frontier implements only the local foundation for the `Device` step. It does not create an authoritative server `device_id`.

The local installation is represented by a cryptographic fingerprint derived from the public key. A future backend enrollment frontier will create the authoritative `device_id` and bind it to the public key.

## Approved decisions

1. Reinstalling the application creates a new device identity.
2. Device identity is provisioned automatically on the first application launch.
3. Provisioning is idempotent on later launches.
4. Local identity uses `device_key_fingerprint`; authoritative `device_id` is created only by the backend in a future enrollment flow.
5. The keypair uses ECDSA P-256 in Android Keystore.
6. The private key is non-exportable.
7. StrongBox is not mandatory. Hardware-backed protection may be used when available, but absence of StrongBox must not prevent a valid supported device from running the app.
8. A previously established identity is never silently regenerated if its key becomes unavailable or invalid.
9. The UI remains visually identical to the bootstrap screen: centered `Andy` text only.
10. Identity metadata and identity-presence markers are never restored through Android backup.
11. The device identity key does not require biometric/PIN authorization for each use. It proves possession by the device, not human consent.
12. A `READY` identity must pass both fingerprint validation and a local sign/verify self-test on every application launch.
13. Metadata is stored in `noBackupFilesDir` with atomic writes; Room and DataStore are out of scope.
14. This frontier does not expose a public arbitrary `sign(bytes)` API. Signing is internal only for the self-test.
15. Android Keystore integration must be proven by an instrumentation test running in GitHub Actions.
16. Notebook and physical phone are not automatic CI gates for this frontier.

## Scope

### In scope

- Kotlin platform-neutral contract for device identity state.
- Android implementation backed by Android Keystore.
- Automatic first-launch provisioning.
- P-256 key generation for signing.
- Public-key fingerprinting.
- Atomic local metadata persistence under `noBackupFilesDir`.
- Crash-safe provisioning recovery before identity reaches `READY`.
- Fail-closed handling after identity reaches `READY`.
- Local cryptographic self-test on every launch.
- Structured, redacted observability.
- JVM unit tests for state-machine logic and deterministic fingerprint handling.
- Android instrumentation tests for real Keystore behavior.
- GitHub Actions gating for unit tests, instrumentation tests, APK assembly, architecture guard, governance tests, and secret scan.

### Explicitly out of scope

- Google authentication.
- Email challenge.
- Human identity establishment.
- Backend enrollment.
- Authoritative server `device_id`.
- Tenant or membership logic.
- Sessions or access tokens.
- HTTP clients, URLs, API transport, SDK/API calls, provider calls, or network access.
- External proof-of-possession protocol.
- Arbitrary payload signing API.
- Key rotation after `READY`.
- Key recovery or migration across reinstall.
- Android attestation as an authority mechanism.
- StrongBox as a mandatory requirement.
- Biometric/PIN prompt for device-identity key use.
- Room, DataStore, DI frameworks, background workers, analytics, Firebase, or unrelated platform additions.
- Any visual change from the current centered `Andy` bootstrap screen.

## Module and ownership design

The implementation is split into three responsibilities.

### `app`

Owns lifecycle and composition only.

On startup it triggers an `ensureIdentity()`-style application operation before continuing normal composition. It must not know Android Keystore details, metadata file paths, cryptographic algorithms, fingerprint encoding details, or backend concepts.

The visible screen remains unchanged.

### `core/device-identity`

A Kotlin-only, platform-neutral module/boundary.

Responsibilities:

- public identity result model;
- state-machine semantics;
- abstract contracts required by the orchestration logic;
- no Android framework imports;
- no provider SDKs;
- no raw HTTP;
- no concrete persistence technology.

The public contract is deliberately narrow. Consumers can ask for the current usable device identity and receive either:

- `Ready(fingerprint)`; or
- `Unavailable(reason)`.

No public arbitrary signing primitive exists in V1.

### `data/device-identity`

Android implementation boundary.

Responsibilities:

- Android Keystore access;
- key generation and lookup;
- local metadata storage;
- public-key canonicalization;
- SHA-256 fingerprint calculation;
- local sign/verify self-test;
- atomic metadata writes;
- translation of Android/Keystore failures into the platform-neutral unavailable model.

The implementation must not know tenant, human identity, server `device_id`, Google auth, backend URLs, or API transport.

## Cryptographic design

### Key type

- Algorithm: EC.
- Curve: NIST P-256 / `secp256r1`.
- Purpose: signing and local verification.
- Signature algorithm: `SHA256withECDSA`.
- Key storage: Android Keystore.
- Private key: non-exportable.
- StrongBox: optional, never required for correctness.
- Per-use user authentication: not required.

### Stable alias

The V1 alias is versioned and contains no personal or server identity data.

Conceptual value:

`andy_device_identity_v1`

The implementation must not derive the alias from email, phone number, tenant, server IDs, physical device identifiers, or location.

### Fingerprint

Fingerprint input is the canonical public-key SubjectPublicKeyInfo DER/X.509 encoding.

Fingerprint algorithm:

`SHA-256(publicKey.encoded)`

Canonical display/storage format:

`sha256:<64 lowercase hexadecimal characters>`

The fingerprint is a local cryptographic identifier only. It does not grant authority.

## Persistent metadata

Metadata lives in app-private `noBackupFilesDir` so Android Auto Backup cannot restore the identity marker into a fresh installation.

Conceptual file:

`noBackupFilesDir/device_identity_v1.json`

The file contains no secrets and no user/tenant/backend data.

V1 fields:

- `schema_version` — integer `1`;
- `state` — `PROVISIONING` or `READY`;
- `key_alias` — expected V1 alias;
- `public_key_fingerprint` — required only for `READY`.

No private key, public key blob, email, account identifier, phone number, tenant identifier, location, token, or server `device_id` is stored here.

Writes must be atomic: write a complete replacement to a temporary file in the same private directory, fsync/close as appropriate, then atomically replace the target. A partially written JSON file must never be treated as a valid state.

## State model

The logical lifecycle is:

`ABSENT -> PROVISIONING -> READY`

`UNAVAILABLE` is an externally reported fail-closed condition, not a silently repairable terminal file state.

### `ABSENT`

No metadata exists and no V1 key alias exists.

Action:

1. persist `PROVISIONING` metadata;
2. generate the keypair;
3. read the public key;
4. calculate canonical fingerprint;
5. run local cryptographic self-test;
6. atomically persist `READY` metadata with fingerprint;
7. return `Ready(fingerprint)`.

### `PROVISIONING`

Provisioning started but did not complete.

Automatic recovery is permitted because the identity was never established as `READY`.

If the expected key exists:

- validate it;
- calculate fingerprint;
- run self-test;
- complete `READY` atomically.

If the expected key does not exist:

- generate the expected V1 key once;
- validate it;
- complete `READY` atomically.

### `READY`

A prior identity has been established.

Every launch must:

1. load and validate metadata schema;
2. require the exact expected alias;
3. require the key to exist and be accessible;
4. calculate the public-key fingerprint;
5. compare it exactly with persisted fingerprint;
6. perform the sign/verify self-test;
7. return `Ready(fingerprint)` only if all checks succeed.

No failure in this path may trigger silent key deletion, replacement, rotation, or regeneration.

### `UNAVAILABLE`

Returned when a previously established identity cannot be trusted or used.

Examples include:

- `READY` metadata but missing key;
- inaccessible/invalidated key;
- fingerprint mismatch;
- sign/verify self-test failure;
- corrupt metadata;
- unknown metadata schema version;
- wrong alias in `READY` metadata;
- metadata absent while the expected V1 key alias unexpectedly exists.

The app remains fail-closed for device identity. Recovery/re-enrollment belongs to a future explicit frontier.

## Startup behavior

At each application launch:

1. composition root invokes the device-identity manager;
2. the manager resolves the state and either provisions, validates, or returns unavailable;
3. identity processing performs no network I/O;
4. the app still renders the same minimal `Andy` screen.

V1 does not introduce a user-facing recovery screen. An unavailable state is observable through structured internal logging/tests and will be consumed by a later enrollment/recovery frontier.

## Cryptographic self-test

A `READY` identity is not accepted merely because an alias is present.

On every launch:

1. generate fresh random challenge bytes locally;
2. sign them with the private key;
3. verify the signature using the corresponding public key;
4. discard the challenge and signature immediately.

Nothing from this self-test is persisted or sent anywhere.

If signing or verification fails, return `Unavailable` and do not regenerate the key.

## Backup and reinstall semantics

The identity is installation-scoped.

Required behavior:

- app uninstall removes the installation's app-private state and Keystore association;
- reinstall starts from a new `ABSENT` installation and creates a new keypair/fingerprint;
- Android backup/restore must not restore the V1 metadata marker;
- no application-level attempt is made to restore the identity from Google account, external storage, cloud backup, server state, or physical device identifiers.

A future backend can recognize the reinstall only as a new device enrollment attempt.

## Error handling

V1 follows fail-closed behavior after `READY`.

The platform-neutral unavailable reason model should distinguish operationally useful categories without exposing sensitive internals. At minimum it must differentiate:

- metadata malformed/unsupported;
- established key missing;
- established key inaccessible/invalid;
- fingerprint mismatch;
- self-test failure;
- inconsistent fresh-install state.

Exceptions from Android/Keystore/storage are caught at the Android implementation boundary and converted to these bounded categories. Raw stack traces, key material, public keys, fingerprints, filesystem paths, and provider payloads must not be emitted as normal application telemetry.

## Observability

Allowed structured events include concepts such as:

- `DEVICE_IDENTITY_PROVISIONING_STARTED`;
- `DEVICE_IDENTITY_PROVISIONING_RECOVERED`;
- `DEVICE_IDENTITY_READY`;
- `DEVICE_IDENTITY_UNAVAILABLE:<bounded_reason>`.

Normal logs must not contain:

- complete fingerprint;
- public key bytes;
- private key material;
- raw signatures;
- challenge bytes;
- tenant/device backend IDs;
- real user data;
- infrastructure details.

The fingerprint may be returned through the typed in-process V1 contract but is not written to normal logs.

## Testing strategy

### JVM unit tests

Tests must cover the state machine with deterministic doubles for key and metadata dependencies.

Minimum cases:

1. `ABSENT` provisions once and becomes `READY`;
2. repeated ensure on `READY` returns the same fingerprint;
3. `PROVISIONING` with an existing valid key completes safely;
4. `PROVISIONING` without a key creates one and completes safely;
5. `READY` with missing key returns unavailable and never generates a new key;
6. `READY` with fingerprint mismatch returns unavailable;
7. `READY` with failed self-test returns unavailable;
8. malformed metadata returns unavailable;
9. unsupported schema version returns unavailable;
10. metadata absent with unexpected existing alias returns unavailable;
11. fingerprint formatting is exactly `sha256:` plus 64 lowercase hex characters;
12. no state transition after `READY` invokes key generation as a repair path.

### Android instrumentation tests

Instrumentation must exercise the real Android Keystore, not a mocked JVM replacement.

Minimum cases:

1. creates a P-256 key in Android Keystore;
2. private key is not exportable as encoded private material;
3. public-key fingerprint is deterministic for the created key;
4. a second manager instance/process lifecycle reuse returns the same fingerprint for the same installation state;
5. `SHA256withECDSA` sign/verify succeeds;
6. metadata is written under `noBackupFilesDir`;
7. `PROVISIONING` recovery works against a real existing Keystore alias;
8. a simulated established-state/key-loss inconsistency returns unavailable and does not silently create a replacement identity.

Tests must use synthetic aliases/fixtures isolated from any real user identity.

## CI and governance sequencing

Implementation requires two separately reviewed changes.

### Phase 1 — governance/CI preparation

Before feature code is allowed, create a trusted frontier for `android-device-identity-v1` and expand GitHub Actions so Android instrumentation tests are an obligatory gate when the frontier requires them.

Preferred CI architecture:

- GitHub-hosted Ubuntu runner;
- official Android SDK tools already available on the runner where possible;
- install the required Android API 36 emulator system image through `sdkmanager` if absent;
- create an AVD;
- run emulator headless;
- wait for boot completion;
- execute `connectedDebugAndroidTest`;
- keep existing JVM test, `assembleDebug`, architecture guard, governance tests, secret scan, and APK artifact publication gates.

No notebook dependency.
No physical-phone automatic dependency.
No third-party emulator action unless a separately reviewed investigation proves official tooling insufficient.

The governance change is reviewed and merged separately from feature code.

### Phase 2 — feature implementation

Only after the trusted frontier and instrumentation gate exist may the feature PR add the approved modules/files.

Feature code must not modify its own frontier policy, CI workflow, root/scoped `AGENTS.md`, or security/governance rules.

If implementation discovers that an unapproved path, dependency, SDK, or boundary is necessary, it must stop with `FRONTIER_EXPANSION_REQUIRED` and report the exact need.

## CI definition of done

For the feature candidate SHA, all of the following must pass:

- `architecture-guard`;
- `governance-tests`;
- `secret-scan`;
- JVM unit tests;
- Android instrumentation tests on emulator;
- `assembleDebug`;
- debug APK artifact publication.

The physical notebook and user's phone are not required automatic gates for this non-visual frontier.

## Acceptance criteria

The frontier is complete only when all are proven:

1. first launch automatically creates one P-256 Android Keystore identity;
2. repeated launches reuse the exact same identity/fingerprint;
3. fingerprint equals SHA-256 over the canonical X.509 SubjectPublicKeyInfo encoding and uses `sha256:<lowercase-hex>` format;
4. sign/verify self-test succeeds on every valid launch;
5. interrupted pre-READY provisioning is recoverable;
6. a broken established `READY` identity is never silently regenerated;
7. metadata is in `noBackupFilesDir` and not part of restoreable app backup state;
8. private key remains non-exportable;
9. no network/backend/provider traffic occurs;
10. no authoritative server `device_id` is created locally;
11. no arbitrary signing API is exposed;
12. no user, tenant, phone, location, token, or real device/server identifiers are committed or logged;
13. visible UI remains the same centered `Andy` bootstrap screen;
14. all mandatory CI gates pass on the exact candidate SHA;
15. post-merge `main` passes the same mandatory gates.

## Security invariants

- Local possession is not server authority.
- Fingerprint is not `device_id`.
- Device identity is not human authentication.
- Device identity is not tenant membership.
- Device identity key is not a general-purpose application signing oracle.
- Private key never leaves Android Keystore.
- `READY` identity failure never causes implicit replacement.
- Fresh-install provisioning recovery is permitted only before `READY`.
- Backup/restore never transports the installation identity.
- App UI remains ignorant of cryptographic implementation details.

## Future boundary

The next frontier may introduce human authentication and backend enrollment. It may consume `Ready(fingerprint)` and the public-key material through a deliberately designed proof-of-possession/enrollment protocol, at which point the backend can create and bind the authoritative `device_id`.

That future frontier must define the signed message format, nonce/challenge semantics, replay protection, server validation, human-auth linkage, and enrollment recovery before any external signing capability is exposed.
