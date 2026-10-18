# Kotlin implementation migration — 2026-09-25

## Status: in progress

68 production Java files (9,267 original lines) have been converted to Kotlin.
**129 unregistered Java implementation files remain in `app/`; the requested
migration is not complete.** The starting review queue contained 197 files after
excluding the 93 registered Java boundaries. Tests and frozen vendor snapshots
are outside that queue.

The retention policy in [../language-boundaries.md](../language-boundaries.md)
and [../java-retention.json](../java-retention.json) remains unchanged. All 93
registered Java files remain Java, including both Spider API shells and the 90
Android host shims. No compatibility hold was released by this migration.

## Converted implementation

| Module | Files | Scope |
| --- | ---: | --- |
| `app` | 32 | QR pairing, cast-session tracking, credential host matching, deferred cloud episodes, source resolvers, adaptive media factories, download service, cast notifications, credential stores, provider contract, cloud cleanup/stream checks, cast workers, offline downloads, database lifecycle and device model |
| `catvod` | 10 | Networking, cookies, proxy model and utilities |
| `chaquo` | 2 | Python platform and provider bridge |
| `desktop` | 2 | mpv cast player and UPnP cast service |
| `guarded-catvod-host` | 12 | Process host, diagnostics, crypto forwarding and WebView engines |
| `guarded-spider-helper` | 2 | Activity and provider service |
| `guarded-webview-helper` | 3 | Activity, service and protocol |
| `libraries/runtime-core` | 2 | Provider proxy server and native worker entry point |
| `quickjs` | 3 | Provider runtime, globals and HTTP bridge |

Kotlin stays in each owning module and preserves its existing package. The JVM
host now applies the Kotlin JVM plugin. The Android modules already use AGP's
built-in Kotlin support. No Rust code or JNI boundaries were changed.

## Compatibility work

- Preserved Java static entry points, public utility constructors, checked
  exceptions and overridable implementation methods. Compared public/protected
  descriptors and generic signatures against class files from the pre-migration
  working tree. Kotlin adds companion/metadata members and final flags on some
  methods of already-final classes; this is not complete reflection equivalence.
- Preserved boxed `Boolean`/`Integer` signatures and `JSMethod` annotations used
  by the QuickJS reflection bridge. Added a Java reflection regression test.
- Retained nullable raw Gson fields, immutable empty proxy defaults, cookie
  serialization and provider/domain scoping. Added Java consumer regression tests
  for utility constructors/static methods, proxy JSON state and null matchers.
- Preserved the existing cancellation and native-handle cleanup paths. QuickJS
  callback tests now pass a real, unexecuted `Call` instead of null, matching
  OkHttp's non-null callback contract; their cleanup assertions remain intact.
- Preserved record accessors for Java callers. Kotlin desktop callers use record
  properties. Pairing retains one-use challenges, monotonic expiry and peer grants.
- Reviewed converter output for lost delegation qualifiers, boxed types,
  null/default behavior, Java collection mutability and default-locale operations.
  Generated drafts alone are not accepted migrations.

## Remaining work

The remaining 129 app files include Room models/database code, configuration and
provider loading, cloud providers, playback/data sources, server orchestration,
and mobile/Leanback UI. Their absence from the retention register does not prove
that automatic conversion preserves their contracts. Continue with coherent
slices and their behavior tests; do not move them into `java-required` to make
the queue appear complete.

Use `python3 scripts/check-java-retention.py --inventory` for the current count.
Validation is local/offline only; no device testing or provider-account actions
were performed for this migration.
