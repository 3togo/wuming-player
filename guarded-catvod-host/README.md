# Guarded CatVod host

This is a deliberately non-generic compatibility process for legacy CatVod providers which still
reference Android API types. It is launched as a disposable, memory-limited child JVM and is never
loaded into the desktop GUI class loader.

Only compatibility methods proven necessary by supported providers belong here. It must not become
a second Android application framework or a home for copied provider implementations.

Provider HTTP clients receive the desktop process's standard proxy settings. The host supplies a
private data directory and an in-memory Android preferences implementation; it does not access the
Android application's storage or classes.
