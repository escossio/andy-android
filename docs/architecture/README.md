# Andy Android Architecture

Permanent boundary:

`andy-android -> Kotlin SDK -> Client API -> attention-router`

Permanent authority chain:

`Human Identity -> Tenant Membership -> Active Tenant -> Device -> Session -> SDK/API -> Capabilities / Channels / Integrations`

The client is offline-tolerant but not authoritative for identity, membership, approvals, policy, or sensitive external execution. Android permissions, device capabilities, tenant authorization, and execution authority remain distinct.

The initial repository zones are `app`, `core`, `sdk`, `features`, `capabilities`, `integrations`, `data`, and `sync`. Functional work is admitted only by machine-readable frontiers under `.github/architecture/frontiers/`.

The first planned feature frontier is `android-app-bootstrap-v1`; it proves only the Android toolchain and a trivial app shell.

Reserved future baseline: minSdk 28; targetSdk 36; compileSdk 36; Android Gradle Plugin 9.4.0; Gradle 9.6.0; JDK 17; stable Compose BOM 2026.08.00; Gradle Kotlin DSL; Version Catalog. No functional Android build is installed by this bootstrap.
