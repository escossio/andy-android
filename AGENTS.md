# Contributor automation instructions

Read `README.md`, `SECURITY.md`, `docs/architecture/README.md`, and the scoped `AGENTS.md` for every area you change.

This repository is greenfield and fail-closed: functional work requires an exact trusted frontier. Directory existence is not permission to create files.

Do not commit secrets, real conversations, real phone numbers, real precise locations, tenant/device identifiers, provider payloads, signing material, or infrastructure inventories. Use synthetic fixtures only.

`attention-router` is the authoritative backend. Do not couple this client to PostgreSQL, containers, AGT01, any specific VPS, local IP addresses, internal ingress, provider internals, or backend implementation details.

A feature frontier may not modify its own architecture policy, manifest, CI workflow, root/scoped `AGENTS.md`, or security/governance documents.

A generic status-file convention does not expand a trusted frontier. Do not create or modify `STATUS.md` unless a trusted frontier explicitly allows that exact path.

If a required path, dependency, SDK, or authority boundary is outside the trusted frontier, stop with `FRONTIER_EXPANSION_REQUIRED` and report the exact path/dependency and reason. Governance changes are reviewed separately.

Candidate code must never be executed merely to validate architecture policy.
