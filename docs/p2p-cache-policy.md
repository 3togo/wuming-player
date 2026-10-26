# Optional SDK cache policy

The public distribution does not package JianPian or Thunder engines. Its default
cache port clears app-internal cache children through the symlink-safe CacheTree
utility; no native SDK owns those directories in a public build.

The public core libraries own the portable P2P cache budget, link parsing, session
ownership and native runtime lease policies used by optional private Android
adapters. The private cache port retains active-owner protection, low-space stops,
deferred clearing and restart cleanup. Neither edition removes external user
files or account data through this operation. Cache-size reporting is unchanged.

See [shared editions](shared-editions.md) and the historical
[SDK removal record](vendor-sdk-removal.md).
