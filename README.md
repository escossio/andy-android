# Andy Android

Andy Android is the public Android client for the Andy product.

The authoritative platform/backend is [Attention Router](https://github.com/escossio/attention-router). This repository must communicate with that platform only through versioned client contracts / SDK boundaries. It must never depend on PostgreSQL, containers, AGT01, a specific VPS, local IP addresses, internal ingress, provider internals, or server implementation details.

## Status

Governance bootstrap only. No functional Android application is implemented yet.

## Identity

- App name: Andy
- Application ID: `io.github.escossio.andy`
- Kotlin namespace: `io.github.escossio.andy`
- License: Apache-2.0

## Development rule

Functional work is allowed only inside a trusted architecture frontier. If an implementation requires a path or dependency outside its frontier, stop with `FRONTIER_EXPANSION_REQUIRED` and change governance separately.
