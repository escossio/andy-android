# Architecture Guard

Trusted frontier policy is read from the base/default branch. Candidate pull-request content is data only and must never be imported, sourced, or executed by the guard.

Feature branches must exactly match a trusted frontier's `branch` field. Governance branches use the prefix `chore/architecture-governance/` and may change only governance paths. Documentation branches may use `docs/` and may change only documentation/security text paths; they cannot touch functional implementation or policy code.

Stable outcomes include `ARCH_PASS`, `ARCH_GOVERNANCE_MUTATION`, `ARCH_PATH_NOT_ALLOWED`, `ARCH_DEPENDENCY_FORBIDDEN`, `ARCH_BOUNDARY_CROSSING`, `ARCH_REQUIRED_ARTIFACT_MISSING`, `ARCH_FRONTIER_UNKNOWN`, `ARCH_POLICY_INVALID`, and `ARCH_CONTENT_FORBIDDEN`.

If a feature needs scope outside its manifest, stop with `FRONTIER_EXPANSION_REQUIRED`; do not edit the manifest from the feature branch.
