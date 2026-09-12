# Feature boundary

`features/` contains user-facing product behavior. Features may depend on approved core/SDK/capability interfaces but must not perform raw HTTP, direct provider calls, persistence hacks, or authority decisions outside their frontier.
