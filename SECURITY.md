# Security Policy

Do not commit real credentials, API keys, OAuth tokens, signing keys, conversations, phone numbers, precise locations, tenant/device identifiers, provider payloads, or infrastructure inventories.

All examples and fixtures must be synthetic.

Persistent provider credentials belong to backend/integration secret boundaries, not to this Android repository. Android signing material must never be committed.

If a secret is exposed, rotate/revoke it first, then remove it from active history and investigate the affected boundary.
