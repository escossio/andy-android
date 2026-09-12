# Architecture Guard

Trusted frontier policy is read from the base/default branch. Candidate pull-request content is data only and must never be imported, sourced, or executed by the guard.

Feature branches must exactly match a trusted frontier's `branch` field. Governance branches use the prefix `chore/architecture-governance/` and may change only governance paths. Documentation branches may use `docs/` and may change only documentation/security text paths; they cannot touch functional implementation or policy code.

Stable outcomes include `ARCH_PASS`, `ARCH_GOVERNANCE_MUTATION`, `ARCH_PATH_NOT_ALLOWED`, `ARCH_DEPENDENCY_FORBIDDEN`, `ARCH_BOUNDARY_CROSSING`, `ARCH_REQUIRED_ARTIFACT_MISSING`, `ARCH_FRONTIER_UNKNOWN`, `ARCH_POLICY_INVALID`, and `ARCH_CONTENT_FORBIDDEN`.

If a feature needs scope outside its manifest, stop with `FRONTIER_EXPANSION_REQUIRED`; do not edit the manifest from the feature branch.

## Trusted execution

PR checks use `pull_request_target` with read-only contents permission, explicitly check out the base SHA without persisted credentials, and fetch the candidate as Git objects only. Both the architecture checker and secret scanner execute from that trusted base. Governance tests also run from the base; candidate tests are not a security authority. Main pushes validate the current trusted policy with `--policy-self-check`.

The standalone secret scanner accepts `--git-ref` to inspect candidate blobs without checking them out. It detects common credential patterns, rejects symlink/path indirection, and never prints matched values. Binary/build files and text above 2 MiB are outside its documented detection coverage. It is a baseline detector, not a guarantee that arbitrary personal data or every secret format can be recognized. GitHub native secret scanning and push protection complement it.

Run bootstrap verification with Python 3 standard library only:

```sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 scripts/architecture/check_guardrails.py --policy-self-check
python3 scripts/security/scan_secrets.py .
```

The empty test package markers support recursive discovery on Python 3.13. No Android source or build is required. After the governance-only first push, protect `main` using the actual successful check contexts. All later functional changes require a branch and PR.
