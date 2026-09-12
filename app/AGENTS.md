# App shell boundary

`app/` owns Android lifecycle, composition root, navigation/theme wiring, and final packaging. It does not construct Client API URLs, attach auth headers, own transport credentials, call provider APIs directly, or make server authority decisions.
