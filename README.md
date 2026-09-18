# Andy Android

Andy Android is the public Android client for the Andy product.

The authoritative platform/backend is [Attention Router](https://github.com/escossio/attention-router). This repository must communicate with that platform only through versioned client contracts / SDK boundaries. It must never depend on PostgreSQL, containers, AGT01, a specific VPS, local IP addresses, internal ingress, provider internals, or server implementation details.

## Status

Human Identity V0.3A is implemented and live-proven on a physical Android device.

The current flow reaches the public Attention Router Client API over HTTPS, completes real Google Human Identity validation, receives a short-lived `DEVICE_BOOTSTRAP` continuation grant, and keeps that credential in memory at this frontier.

The proof intentionally stopped before consuming the grant. No device bootstrap, tenant, membership, enrollment, or session was created.

See [`docs/checkpoints/V03A_LIVE_PROOF_20260917.md`](docs/checkpoints/V03A_LIVE_PROOF_20260917.md).

## Identity

- App name: Andy
- Application ID: `io.github.escossio.andy`
- Kotlin namespace: `io.github.escossio.andy`
- License: Apache-2.0

## Development rule

Functional work is allowed only inside a trusted architecture frontier. If an implementation requires a path or dependency outside its frontier, stop with `FRONTIER_EXPANSION_REQUIRED` and change governance separately.
