# Removed SDK cache policy

The public distribution no longer includes JianPian or Thunder engines, native
sessions, downloads, or engine-specific cache ownership. Their former cache-policy
code and tests were removed with the engines. Settings now clears app-internal
cache children through the existing symlink-safe CacheTree utility; no active
removed engine can own those directories. Cache-size reporting is unchanged.
No external user files or account data are removed by this change.

See [vendor-sdk-removal.md](vendor-sdk-removal.md).
