# Human Identity V0.3A live proof — 2026-09-17

## Code boundary

The live proof used Android source:

`a0f4ce7196b7f9fd40bbbb66ce9fcbc3c9f9b8d4`

This source includes the continuation flow:

`POST /api/v1/auth/google/challenges/{challenge_id}/verify-and-continue`

and parses the returned Human Identity reference plus the short-lived `DEVICE_BOOTSTRAP` continuation grant.

## Public path

The Android client used the public Attention Router Client API over valid HTTPS:

`https://api.escossio.com`

The proof used the same Google Web Client ID expected by the backend verifier. No credential value is recorded here.

## Physical live proof

A physical Android device completed the real Google credential flow.

Observed outcome:

- Human Auth challenge returned HTTP 201;
- the user selected a real Google account through Android's credential UI;
- `/verify-and-continue` returned HTTP 200;
- the app reached the visible state `Human identity validated.`;
- the backend transaction finished `VERIFIED`;
- one `DEVICE_BOOTSTRAP` continuation grant was issued.

## Client credential handling

At the V0.3A boundary:

- the continuation grant is retained only in memory by the onboarding coordinator;
- the grant's string representation does not reveal the opaque token;
- a post-proof search of the app's private persisted files found no credential matching the `hcg_` continuation-token format.

## Explicit stop boundary

The live proof stopped before:

- consuming the continuation grant;
- device bootstrap;
- tenant creation;
- membership creation;
- enrollment creation;
- session creation.

The backend grant remained `ACTIVE` and unconsumed at the end of the proof.

## Resume rule

The next frontier must consume or exchange the already-proven continuation authority through a separately governed bootstrap contract.

Do not repeat the V0.3A Human Identity implementation or treat the public HTTPS/Google flow as unproven unless new evidence invalidates this checkpoint.
