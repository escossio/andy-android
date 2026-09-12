# Local data boundary

`data/` owns local persistence, encrypted caches, local models, and schema/migration responsibility. Local state is never authoritative for sensitive external effects and must remain tenant-scoped when tenant data is introduced.
