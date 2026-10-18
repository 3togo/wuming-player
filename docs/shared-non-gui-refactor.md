# App-wide shared non-GUI refactor

Scope authorized on 2026-10-12: all reusable non-GUI logic in the app.
Mobile, leanback and leanback_lite share Android ports; desktop supplies JVM
ports. Algorithms, protocol parsing, policy, request budgets, caches and
workflows belong in `libraries/*-core`, even before desktop has a corresponding
screen. Android/Room/Media3/JNI/WebView bindings and lifecycle dispatch stay in
platform modules. Frozen vendor and registered Java facade contracts still apply.

## Extracted in this change

| Shared module | Behavior moved from app/platform code |
| --- | --- |
| base-core | Hex, AES envelope, formatting, bean conversions, bounded HTTP text, cancellable atomic download transfer |
| cache-core | Bounded file-tree measurement/deletion without following symlinks |
| catalog-core | Episode number matching, detail admission, opaque companion catalog handles |
| cloud-core | Provider registry/account orchestration, failure classification, external episode decoding/materialization/cache, persisted handle promotion, protocol helpers, complete 123 Netdisk provider protocol/workflow |
| media-core | Direct HTTP input/source resolution, cloud source resolution, lazy cloud URI refresh/failover, playback route selection |
| playback-core | Complete HLS ad pipeline, shared probe cache/budget/HTTP transport, TS/AVC dimensions, HLS reference traversal, buffer budget, adaptive windows, health monitoring, network rules, bounded local URL waiting, native task ownership/session cleanup, offline header metadata, bounded stream reachability |
| search-core | Chinese title normalization, alias repository/learning, collection merge/filter/sort policy, source selection storage and grouped search coordinator |
| source-core | Depot prune policy, DEX provider artifact cache/recovery, cloud site selection |
| text-core | Simplified/traditional Chinese conversion tables and algorithm |
| upnp-core | QR pairing lifecycle, DLNA request policy, cast deduplication/stale-stop protection, remote pairing/token authorization workflow |
| user-library-core | Playlist filtering and initial episode selection |
| design-core | Concurrent bounded update-feed lookup and mirror selection workflow |

Old app-facing APIs remain facades/adapters where existing callers need them.
The Android and desktop HLS paths consume the same shared pipeline. Moving other
behavior into core makes it available to desktop; this does not claim every
existing desktop feature has already been wired to every new workflow.

## Remaining work — this refactor is not complete

The following are concrete areas still mixing portable behavior with platform
bindings. They must be reviewed and separated rather than counted as ports just
because they import Android or app types:

- Other `cloud/opendrive/*OpenDriveProvider.java` (123 Netdisk now extracted): provider API protocols, original/
  smart routes, saved-copy handling, login/session workflows and proxy streams;
  HTTP, credentials/cookies, tracked-copy persistence and relay addresses need ports.
- `CloudStorageManager`, `OpenDriveCacheCleaner`, account backup state and token
  management: separate storage access from orchestration and safety policy.
- `VideoRankManager`, `SearchCache`, search history/suggestions and repository
  workflows: move ranking/cache/storage policy while retaining bean/Room mapping.
- `BaseConfig`, `VodConfig`, `LiveConfig`, `DepotManager`, `RemoteDepotSync`:
  split source/config/depot workflows from resource and framework dispatch.
- `LiveParser` and player extractors: shared protocol/directive parsing and
  lifecycle workflows must be separated from player/JNI/resource bindings.
- Settings, update mirror download chains and package-verification policy:
  shared preference and workflow ports, Android package inspection/installation.
- `CastRemuxCacheEngine`, relay/server handlers and request workflows: separate
  parsing, session/cache decisions and scheduling from NanoHTTPD/player ports.
- Non-GUI workflows embedded in activities/controllers/viewmodels: inspect their
  decision logic individually; moving only standalone utility classes is insufficient.

Preserve the documented Ali, UC and Quark routing/cleanup contracts throughout
provider extraction. Do not replace provider-specific behavior with a common
fallback merely to reduce class count. Language migration requires retention
inventory and behavioral evidence; no retention marker may be removed to finish.

## Validation record

The full desktop and mobile verification unit suites and eleven affected core
module suites passed after the search, stream-check, authorization and cache-tree
extractions. `verifyJavaRetention`, `verifyGenericLibraryBoundaries`,
`verifyDesktopBoundaries` and `verifyCodeLifecycleIsolation` also passed.
The subsequent full run passed all three Android mode unit suites: mobile 1,112
tests, leanback 1,119, and lite 1,112 (one optional fixture skipped in each).
Desktop ran 322 tests with nine optional skips and no failures. All six mode/ABI
verification APKs and the mobile test runner built successfully.

After the final payload bridge correction, all six APKs rebuilt, the 77 cloud-core
tests and 15 selected Android provider/payload regressions passed, and all four
boundary/retention checks passed again. The bridge reuses the original decoded
payload instead of creating a second parser/cache.

The rebuilt mobile app and runner were installed with data preserved on the
explicitly authorized tablet `AUTHORIZED_TABLET_SERIAL`. Both HLS instrumentation
methods passed together in 2.240 seconds: unique per-preparation probes and exact
removal of the captured 15-segment insertion. The fixture was removed afterward;
original stay-awake value 15 and disabled-lock state true were restored.
These checks establish this extraction batch, not completion of the remaining
app-wide work or device playback on a TV. Local evidence is ignored under
`artifacts/shared-non-gui-refactor/2026-10-12/`.
