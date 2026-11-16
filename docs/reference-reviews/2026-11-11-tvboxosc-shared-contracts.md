# TVboxOSC shared contracts — 2026-11-11

## Scope and provenance

Reference: `/home/eli/git/temp/TVboxOSC`, revision
`0130b94a345347b40bba832189e8c1a832ca0b08`. No reference update was requested or
performed. This is a bounded adaptation, not an exhaustive repository review.

Reviewed reference paths:

- `app/src/main/java/com/kukuqi/tvbox/osc/util/LivePlaylistMetadata.java`
- `app/src/main/java/com/kukuqi/tvbox/osc/util/SourceDescriptor.java`
- `app/src/test/java/com/kukuqi/tvbox/osc/util/LivePlaylistMetadataTest.java`
- `engine/core/src/main/java/com/kukuqi/tvbox/engine/player/parse/JsonParseResult.java`
- `app/src/test/java/com/kukuqi/tvbox/osc/util/JsonParseProtocolTest.java`
- `app/src/main/java/com/kukuqi/tvbox/osc/core/SearchDispatcher.java`

The reference's root licence is AGPL-3.0. These changes independently implement
observed contracts in Kotlin and reuse Wuming's existing modules and adapters;
no reference source files, assets, runtime dependencies or build modules are
transplanted.

## Implemented behavior, in requested order

### 1. M3U grammar and guide addresses

`live-core/M3uDirectives` owns quote-aware display-name separation, double/single
quoted and unquoted attributes, attribute-key boundaries, and guide URL
resolution. Attribute parsing stops before an EXTINF display name, so text in a
name cannot overwrite metadata. Header guide lists retain every URL, including
unquoted lists. Relative EPG template braces survive resolution.

`LivePlaylistParser` and Android `LiveParser` consume those rules. EXTGRP overrides
the current entry's group before its stream is published. Android defers channel
creation until the first stream to avoid leaving an empty original group behind;
catchup, DRM, player settings and channel merging remain in the Android adapter.
Desktop passes the selected playlist candidate as the resolution base, including
mirror fallback. The shared M3U grammar runs before the legacy native parser, so
native availability cannot silently select older grammar. TXT native behavior is
unchanged.

### 2. JSON playback response contract

`playback-core/JsonPlaybackResponse` decodes the observed TVBox JSON parser dialect:
root URL before `data.url`; root or nested header objects or JSON-encoded objects;
fallback headers before nested headers before root headers; root aliases after
its header object. Names override case-insensitively; UA, Referer, Cookie and Origin
receive canonical names. Empty/malformed optional headers do not erase inherited
authentication, and invalid HTTP header shapes are omitted. Returned headers are
an immutable snapshot; the separately exposed merge helper returns an independent
mutable map for existing adapter callers.

Scheme-relative URLs use an HTTP parser endpoint's scheme, defaulting to HTTPS
when no endpoint is available. Invalid JSON and invalid URLs have distinct typed
failures. Executable/document schemes are rejected. Hosts may supply their
transport allowlist; Android preserves its extensible native-protocol boundary
and downstream edition/player admission. Legacy concatenated segment URLs remain
accepted on that boundary.

Android `ParseJob` uses the decoder before its existing completion/cleanup path.
The common `CatVodCatalogCodec`, used by desktop provider runtimes, uses it after
preserving CatVod array/object URL selection. Desktop still admits only HTTP(S)
and retains its browser-sniffing rejection for interactive pages.

### 3. Search lifecycle

`search-core/SearchLifecycle` owns request epochs, planning-future ownership,
source-key deduplication, bounded provider dispatch (four per batch by default),
per-dispatch deadlines, cancellation and queued-delivery invalidation. Terminal
outcomes distinguish success (including empty), provider failure, timeout,
cancellation and executor rejection. A late provider completion cannot publish a
second terminal result. Timed-out work is cancelled before notifying observers;
an observer failure cannot prevent cancellation or queue progress. Cancelled
queued tasks are purged when the supplied executor exposes its queue.

Executors remain host-owned. Android supplies a bounded dedicated search pool
with rejection rather than caller-thread execution, keeping blocking provider
code off shared IO/timer threads. `SiteViewModel`, shared by mobile and both TV
variants, retains alias expansion, caching, result publication and progress;
progress counts the same unique source keys as dispatch. Model teardown and result
publication share the model monitor and recheck the shared request epoch.

Desktop's existing `CatalogSourceCoordinator` delegates search to this lifecycle,
preserving its existing total search wait budgets and partial results. Its home
loading path, including progressive late home updates, stays intact. Search
request tokens are allocated before Swing workers run, so navigation can invalidate
work that has not started. Replacement wakes the old collector, whose finalizer
cannot cancel its successor. CatalogPanel invalidates search on navigation,
gateway replacement and close, and guards both result and failure publication.

Interruption remains cooperative: providers that ignore it can retain a physical
worker. Host worker limits and queue limits still apply; this is no claim of
forcible termination, unlimited recovery capacity or improved throughput.

## Compatibility coverage

| Observed contract | Mobile | Leanback / Lite | Desktop |
| --- | --- | --- | --- |
| Reviewed M3U quote styles, names with commas, EXTGRP | Covered by shared lexical rules and adapter regression | Same production adapter; compiled | Covered by shared parser and loader regression |
| Relative/multiple header EPG URLs and templates | Covered; retains the existing comma-list representation | Same production adapter; compiled | Covered; playlist base supplied by loader |
| Reviewed TVBox JSON URL/header dialect | Covered in ParseJob | Same production adapter; compiled | Covered in CatVod codec, including real provider adapter test |
| External configured JSON parser fetching / browser sniffing | Existing Android runtime | Existing Android runtime | Partial/product scope: this change does not add Android's external parser or browser runtime |
| Non-HTTP playback transports | Adapter/edition dependent | Adapter/edition dependent | Not covered by desktop CatVod playback; HTTP(S) admission retained |
| Bounded search lifecycle and cancellation | Shared lifecycle plus ViewModel regressions | Same ViewModel; compiled | Shared lifecycle plus collector and Swing regressions |
| Android DRM/catchup player directives | Preserved and regression-tested | Same adapter; compiled | Not added by this change |

This dialect has no formal version identifier in the reviewed reference. Offline
fixtures establish these concrete contracts, not all M3U or TVBox conformance.

## Validation

- Core suites: live-core 59 tests; playback-core 310 tests (one existing optional
  downloaded-media replay skipped); search-core 52 tests; catalog-core 117 tests.
  No failures or errors.
- Selected desktop suites: 43 tests, no failures/skips. Covers playlist loading,
  the real JVM-spider adapter, search replacement through CatalogPanel, existing
  catalogue pagination/provider-status and playback UI behavior.
- Android mobile ARM64 verification unit tests: 23 tests, no failures/skips,
  covering LiveParser, ParseJob and SiteViewModel. Mobile, leanback and
  leanback-lite ARM64 verification Kotlin/Java compilation passed.
- `verifyGenericLibraryBoundaries`, `verifyDesktopBoundaries`,
  `verifyCodeLifecycleIsolation` and `verifyJavaRetention` passed. The retention
  inventory still verifies the existing 94 protected files. No language conversion
  or retention-register change was needed.
- The ordinary Android signing check rejected the local identity because it does
  not match `updates.json`. Verification used the existing documented
  `-PwumingEphemeralSigning=true` flag; no signing configuration/key was changed.
- Removed-fix controls: restoring last-comma channel-name extraction failed two
  M3U regressions; dropping fallback-header merging failed the authentication
  regression; removing the queued-delivery epoch guard failed the stale-result
  regression. All controls failed on assertions, not compilation. Original fixes
  were restored and the suites passed again.

Physical-device playback, Android HTTP transport with a live parser endpoint,
and live provider services were not exercised. Shared offline fixtures, Android
adapter tests, desktop provider execution and platform compilation are distinct
pieces of evidence; none establishes live-service or physical-device playback.

## Controlled scheduling measurements

A temporary Java harness compared eager submission with submission-time deadlines
(the previous Android scheduling shape) against SearchLifecycle. Both used eight
physical workers and one timer thread. Each batch had 24 independent sources,
20–50 ms simulated IO plus 16 KiB of work per successful source. Three runs of
each case were made on this host; the table reports medians. These are synthetic
policy measurements, not a live-provider benchmark or a general performance claim.

| Workload / policy | Batch ms | Success / timeout | Peak physically running | Last source started, ms | Worker CPU, ms | Worker allocation, KiB |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| Mixed, eager | 132 | 24 / 0 | 8 | 81 | 3.37 | 385.8 |
| Mixed, bounded | 239 | 24 / 0 | 4 | 188 | 5.04 | 390.5 |
| One interruption-ignoring stall, eager | 81 | 13 / 11 | 8 | 81 | 2.91 | 240.6 |
| One interruption-ignoring stall, bounded | 251 | 23 / 1 | 5 | 201 | 4.37 | 391.1 |

The ordinary workload slows from about 182 to 100 completed sources/second when
logical concurrency drops from eight to four. Later sources start later, in
input order. With an 80 ms timeout and one stalled source, dispatch-time deadlines
let the remaining 23 sources finish rather than expiring queued work before it
gets a useful execution window. The stalled worker was released after terminal
outcomes and recovery completed. The physical peak of five in that case is the
four current slots plus the expired provider that ignored interruption; Android's
separate four-thread physical pool imposes its own stricter limit. Allocation is
worker-thread allocated bytes, not retained heap; the stalled comparison completes
different amounts of useful work and does not demonstrate lower memory use.

## Remaining scope

Other reference features, runtime ABI coverage, subscription capability summaries,
additional M3U directives and new desktop external-parser/browser capabilities
remain outside this adaptation. Reuse the reviewed fixtures when expanding those
contracts; do not infer full compatibility from this bounded result.


## Review method and resumption

Used the [tv-reference-abstraction skill](/home/eli/.codex/skills/tv-reference-abstraction/SKILL.md),
with the user's explicit reference and product paths overriding its historical
workspace defaults.

Example continuation: “Use tv-reference-abstraction to review another bounded
TVboxOSC compatibility domain for Wuming. Read this report and status.json first,
verify these adaptations still exist, and propose only newly supported gaps.
Do not update the reference or publish changes.”


## Final-diff follow-up audit

The second review examined accepted input families and actual UI dispatch rather
than repeating only the earlier tests. It found and corrected three gaps:

1. A quote inside an **unquoted** M3U logo URL was incorrectly treated as the start
   of a quoted attribute. Delimiter scanning now uses the same attribute matcher
   as metadata extraction, so apostrophes/query-string quotes remain literal URL
   characters. Core, Android and desktop loader regressions cover this case.
2. Existing JSON header maps accepted numeric and boolean primitives by converting
   them to their wire strings. The initial decoder accidentally accepted only
   strings. Header conversion now retains primitive values while URL decoding
   remains string-only, preserving invalid-header rejection. Tests cover the core,
   Android ParseJob bridge and desktop CatVod codec.
3. Android checked its search epoch before `LiveData.postValue`, but LiveData could
   publish that queued value after cancellation. A controlled asynchronous main
   executor reproduced the stale result. SiteViewModel now supplies Android's
   main executor to SearchLifecycle and sets LiveData directly inside the guarded
   delivery. Cached results use the same `deliverIfCurrent` path. A further epoch
   check after observer notification prevents a reentrant observer's new search
   from receiving completion progress belonging to the old search.

All three primary reproducers failed on assertions before their fixes. The new
Android tests retain a genuinely queued main-executor case for both provider and
cached results; the existing synchronous executor alone could not establish this
property.

### Production caller audit

- Mobile collection/fast-search screens and leanback CollectActivity/fast-search
  screens call the same `app/src/main` SiteViewModel multi-source overload.
  BaseVideoActivity's alternate-source search also uses that overload.
- Android LiveApi/LiveConfig call the same LiveParser; both platform video
  activities use PlayerManager, whose JSON parser path creates the same ParseJob.
  `leanback_lite` explicitly includes leanback sources/resources and also consumes
  all `main` sources.
- DesktopLivePlaylistLoader supplies the selected playlist base to the shared
  parser for validation and final loading. Desktop JVM, JavaScript, Python and
  guarded CatVod executors call CatVodCatalogCodec, which consumes the shared JSON
  decoder. CatalogPanel delegates search to CatalogSourceCoordinator, which uses
  SearchLifecycle. Its request is allocated before background execution and
  invalidated on navigation, gateway replacement and close.
- Android single-source pagination uses the existing generic request task, and
  the MediaBrowser service has its own existing search workflow. They were not
  converted by this bounded main-UI batch abstraction. Likewise, typed companion
  responses are not raw TVBox JSON parser responses. These distinctions limit the
  claim that every search/transport entry point uses the new batch/JSON decoder.

### Follow-up validation

- live-core: 60 tests; playback-core: 311 tests (one existing optional fixture
  skipped); search-core: 53 tests; catalog-core: 118 tests.
- Selected desktop caller/UI suites: 41 tests, no failures/skips.
- Selected mobile ARM64 verification tests: 28; selected leanback ARM64
  verification tests: 28. Both passed without failures/skips. Leanback-lite
  ARM64 verification Kotlin/Java compilation passed.
- The three architecture checks and Java-retention verification passed. The
  existing ephemeral verification signing flag was used, without key/config
  changes. No physical-device or live-service validation was added.

The scheduling measurements above concern provider dispatch and are unchanged by
moving Android result publication to its main executor.


## Single-source paging and MediaBrowser extension

The previously separate Android search paths now consume the shared lifecycle.

### Paging contract

SiteViewModel uses a separate one-source SearchLifecycle for pages, with the
existing 30-second VOD timeout and main-thread guarded delivery. Site, literal
query, quick flag, page string and returned Result metadata are unchanged. Pages
do not use the all-source alias/cache/strict-filter workflow or its progress
channel. Page replacement, stop and model teardown invalidate queued delivery.

Home/category/detail and paging continue to supersede each other on the result
channel. Existing generic result tasks retain their Guava execution path, but
publication rechecks that channel's identity on main, preventing an already-queued
old detail/category result from overwriting a replacement page (and vice versa).
Paging itself does not cancel an independent in-progress all-source batch.
A CancellationException thrown by a provider is unwrapped as a cancellation
outcome rather than becoming a visible paging error.

### MediaBrowser contract

`search-core/MediaBrowserSearch` owns browser search policy and publication. Its
ports supply planning/configuration access, provider calls, existing shared
merging/ranking adapters and platform media metadata conversion. The shared core
now owns alias iteration, partial provider success, canonical-key/strict-mode
cache identity, cache publication/clear/trim, and immutable result snapshots.
Android retains the same memory-scaled cache capacity and five-minute TTL.

`SearchLifecycle.collectOrdered` is distinct from its bounded dispatch mode:
providers are submitted eagerly on the host's original IO executor and collected
in source order. Each get receives its own five-second budget when that source's
turn arrives; this is not a five-second deadline from submission. Collection
stops after 50 per-source-merged raw items, then applies the existing global merge,
ranking and final 50-item cap. Timed-out/failed providers do not discard other
results. Early return, interruption, clear and replacement cancel unused work;
providers that ignore interruption still require physical executor containment.

PlaybackService allocates the browser request before queueing work, registers its
outer future before dispatch and checks ownership again in its posted session
notification. Clear and replacement wake blocked collectors and prevent old
planning/conversion/provider work from repopulating the cache. The existing
immediate onSearch acceptance response and onGetSearchResult full-result behavior
(including the existing global last-result scope) remain unchanged. The generic
workflow uses latest-request ownership for that scope; no new per-controller
paging protocol was introduced.

### Extension validation

- search-core: 63 tests passed; catalog-core: 118 tests passed.
- Selected desktop search/provider/pagination suites: seven tests passed.
- Selected mobile and leanback ARM64 verification suites: 35 tests each passed.
  They cover exact page arguments and metadata, page/page and page/detail
  replacement, queued result ownership in both directions, independent batch
  progress, useful errors, silent cancellation, stop/clear, and the actual
  VodBrowse adapter's cache/cap/clear forwarding.
- Leanback-lite ARM64 verification Kotlin/Java compilation passed. An initial
  concurrent Gradle run hit KSP's `zip file is empty`; no empty generated JAR
  remained afterwards, and sequential verification completed successfully.
- The three architecture checks and Java retention verification passed.
- New core coverage includes canonical/strict cache separation, TTL expiry,
  immutable capped snapshots, source ordering, a wait budget starting at each
  collection turn, alias/provider partial failure, interruption and cancellation
  of tracked caller work, and clear during planning/conversion.
- Removing page cancellation from stopSearch makes the queued-page regression
  fail. Removing ordered collection cleanup makes the timed-out-provider cleanup
  regression fail. Both controls failed on assertions and were restored before
  final successful verification. The provider-cancellation outcome regression
  also failed on the earlier FAILED classification before correction.
- Tests live in the ordinary module test source sets. The repository's
  unitAcceptanceBaseline includes app:test and every generic core test task;
  app:testKeyLogic selects both mobile and leanback verification test tasks.
  No opt-in hardware fixture or private-only diagnostic is required for these
  regressions.

### Ordered-collection compatibility measurement

A temporary Java harness compared the original eager-submit/ordered-get loop and
the shared ordered collector using four physical workers, 24 sources with
10–30 ms simulated IO, and a 50-raw-item threshold. Each source supplied three
items, so both collected 51 before final trimming. The harness asserted identical
selected values in every run. Three runs per case gave these medians:

| Case / collector | Response ms | Raw collected | Providers started | Peak running | Worker CPU ms | Worker allocated KiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Mixed / original | 91 | 51 | 24 | 4 | 3.67 | 104.6 |
| Mixed / shared | 91 | 51 | 22 | 4 | 2.10 | 84.4 |
| First source stalls / original | 150 | 51 | 24 | 4 | 1.95 | 104.6 |
| First source stalls / shared | 150 | 51 | 24 | 4 | 2.16 | 96.7 |

The diagnostic scaled the per-turn timeout to 150 ms; production remains five
seconds. Stalled work was released after response and worker recovery was
verified. Source-order fairness and the physical concurrency limit were retained.
The shared collector cancelled remaining tasks at the cap, so some queued tasks
never began. Allocation measures worker-thread allocated bytes, not retained heap;
these small synthetic measurements establish neither live-provider performance
nor a general CPU/memory improvement.

No physical Android device or real MediaLibrarySession/browser client was used.
The evidence is maintained core/adapter tests, actual source-set compilation and
production caller tracing; device playback and live service interoperability
remain unverified.


### Paging and browser cancellation audit

The follow-up audit reproduced and fixed three browser cancellation defects:
provider-thrown cancellation/interruption on a later alias discarded earlier
successes; an abandoned provider that consumed its interrupt could start another
alias; and direct cancellation of the returned browser task could still publish
converted results and repopulate the cache. Each new regression failed on an
assertion before the fix. Source tasks now expose their own cancellation state,
active requests retain alias partial successes, and explicit task cancellation
invalidates publication under the cache lock. Cancellation preserves
`mayInterruptIfRunning`; the new false-flag regression also caught an initial
implementation that interrupted child work and passed after correction.

Additional regressions cover completed/old future cancellation, old conversion
finishing after a replacement, queued paging failure after successful replacement,
source order when later providers finish first, timed-out results remaining
excluded, LRU recency without TTL extension, the exact expiry boundary, and the
existing empty-result cache behavior when every source fails. No additional
paging production change was required. Collection still eagerly submits providers,
waits up to five seconds from each source's collection turn, applies the raw cap
in source order, then performs the existing merge/rank/final cap.

Final verification used isolated report directories to avoid concurrent work
replacing the evidence: 74 search-core, 119 catalog-core and seven selected desktop
tests passed. Mobile and leanback selected adapter suites passed with 37 tests
each. Leanback-lite ARM64 Kotlin/Java compilation and all three architecture
checks plus Java retention verification passed. Paging/browser production and regression source hashes were unchanged across
the final verification. Concurrent desktop UI edits were followed by another
successful run of the seven selected desktop tests. Physical-device and actual
MediaLibrarySession/client interoperability remain unverified.

### Android service instrumentation coverage (2026-11-12)

`PlaybackServiceSearchInstrumentedTest` connects a real Media3 `MediaBrowser` to
`PlaybackService` using its service `SessionToken`. Search acceptance, asynchronous
`onSearchResultChanged` notifications, parameter extras, and `getSearchResult`
results use the real Android service/session implementation with an in-app
browser client; this does not establish cross-process marshalling. The shared
`MediaBrowserSearch`, `BrowseTree` and `VodBrowse` adapters remain in the path.
A test-only provider/metadata host supplies deterministic results without external
providers, persisted configuration mutation or alias-network requests. This does not test
live provider transport, production title ranking or cross-application permissions.

The seven methods cover acceptance before provider completion; replacement while
provider and cache-hit notifications are queued; configuration refresh and service destruction
before queued notification delivery; cache reuse, strict-mode separation and exact
five-minute expiry without sliding renewal; a fresh production five-second timeout
at each ordered collection turn, excluding a first source that finishes late;
and the raw source-order cap when later futures finish first. Retrieval also
asserts the service's existing full-list behavior for nonzero page requests.

The only production seam is the Android notification executor, defaulting to
`App.mainThreadExecutor`. The queued tests hold this port, then drain the actual
guarded callback on Android's main thread. Normal-path tests use its production
executor. No portable search policy was added to the service.

All seven methods are selected for both modes by the maintained deep-smoke
`media-browser-search` group. Mobile ARM64 and leanback ARM32 application and
instrumentation APKs compiled; the smoke manifest's 27 host tests, remote/label
check, architecture checks and Java retention check passed. Device results are recorded below. Verification
labels remain `無名(testing)` and `無名(test runner)`.


The first tablet attempts exposed fixture interference: the smoke bootstrap's
Home activity asynchronously emitted a VOD configuration refresh during the test.
Diagnostic callbacks recorded `config:vod=true` alongside the cleared result.
The service correctly invalidated the request. The new group now requests the
existing inert MemoryTestActivity during bootstrap; every other group keeps its
Home default. The clear regression deliberately sends `ConfigEvent.vod()` through
the real service event subscription. The bootstrap selection and rejection of an
unknown host have maintained Python regressions.

For the negative control, removing only the notification's delivery-time request
check made the tablet browser receive `provider` after replacement and fail on
`Stale notification reached the actual browser`. The intact TV-box control passed.
The source guard was restored before final APK creation. The installed tablet
control APK hash independently matched the recorded mutated build.

An exploratory request for 50 items with pageSize=1 also produced Media3's
`Invalid size=50, pageSize=1` validation log even though this in-app client received
the callback value. The historical full-list service callback remains unchanged;
the maintained ordering regression uses a sufficient page size and checks a
nonzero page separately. Small-page interoperability is an existing limitation,
not established as correct by these tests.


Final validation at the local changes on top of `95ff45d`:

| Selection | Result |
| --- | --- |
| JVM unit suites, all production modules and six Android verification variants | 8,636 passed, 17 declared skips, zero failures |
| Python host tests | 225 passed |
| Tablet T1202A256GB24516760216, mobile ARM64, API 34 | 39/39 deep-smoke methods passed in run 20261112T001725Z |
| Q601B 192.168.123.94:5555, leanback ARM32, API 29 | 40/44 methods passed in that run; state group interrupted before any method started |
| Q601B state-group retry, 20261112T002413Z | 4/4 passed |
| New media-browser service suite | 7/7 passed on each device |

The TV result is a full run with an interruption plus a separate successful
retry, not one uninterrupted all-green 44-method run. ActivityManager recorded
firmware force-stopping the fresh instrumentation process during startup; there
was no application exception or test assertion in that interrupted group.
Cold start, restart and background return passed on both devices. Stay-awake was
restored to 0 on both, with original screen timeouts 1800000 ms on the tablet and
240000 ms on the box; cleanup verification passed after the full run and retry.
Source hashes remained unchanged across final APK execution. Raw runs and unit
XML were retained under `~/git/temp/wuming-media-browser-e2e/`.

`unitAcceptanceBaseline` is not green: its test suites passed, but the independent
`desktop:verifyPublicRuntimeLicenses` gate rejects the already-present desktop
coordinate `net.java.dev.jna:jna:5.18.1` as absent from the reviewed inventory.
The check and dependency were left unchanged. GUI/hardware/optional-media unit
assumptions remain skips, not passes. No live-provider or cross-application
permission coverage is claimed by the synthetic service fixtures.


### JNA provenance and Media3 paging correction (2026-11-12)

This follow-up resolves the JNA baseline blocker and small-page limitation recorded
above. The existing JNA 5.18.1 dependency is now in the reviewed Maven inventory,
scoped only to desktop runtime. Its JAR and POM matched the publisher's Maven
Central SHA-1 checksums; their SHA-256 digests are recorded in the inventory.
The JAR's license and the bundled desktop license match the exact
[upstream 5.18.1 license](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE).
The existing distribution selects Apache-2.0 from the publisher's dual-license
options. All three packaged license/notice files are hash-bound in the inventory
and supporting-artifact manifest. The already-reviewed desktop and search-core
build inputs also had stale manifest digests, which were refreshed after inspection.
The runtime graph gate and full public provenance checker now pass, with no bypass.

Media3's [callback contract](https://developer.android.com/reference/androidx/media3/session/MediaLibraryService.MediaLibrarySession.Callback)
uses zero-based pages and positive requested sizes. Both search retrieval and
library children now apply those exact bounds, returning empty lists beyond the
end instead of repeating the full result set. `base-core/ListPages` owns the
bounded, overflow-safe slice. Android's shared browse adapters and
`MediaBrowserSearch.currentPage` use it; desktop's active `CatalogResultPager`
uses it through `CatalogPagination`, retaining the UI's existing minimum size,
page clamping and independent result snapshots. Search notifications still carry
the total count, and paging does not refetch providers or mutate the cached list.
Timeouts, source collection order, ranking and the result cap remain unchanged.

The search ordering regression now checks one-item pages, each item across partial
pages, last/out-of-range pages, maximum integer arguments, parameter propagation
and cache reuse without additional provider calls. A new children regression
checks the actual ROOT folders with one-item pages and bounds. Shared unit tests
cover invalid arguments, overflow, cached snapshot lifetime and desktop clamping.
The service group now contains eight methods, selected in both modes.

Before-fix controls established the failures on both devices: the old search
callback returned 50 for pageSize=1 and failed the new count assertion; the old
children callback returned two for pageSize=1 and crashed on Media3's application
thread with `Invalid size=2, pageSize=1`. These were retained separately from the
final successes. The new JNA regression also failed on the absent inventory entry
before repair. No invalid-result exception was suppressed to make a test pass.

Final validation:

| Selection | Result |
| --- | --- |
| Full unitAcceptanceBaseline | BUILD SUCCESSFUL |
| JVM unit tests, including desktop and all six Android verification variants | 8,641 passed, 17 declared skips, zero failures |
| Python unit tests | 227 passed |
| Public provenance and runtime dependency license gates | passed |
| Tablet T1202A256GB24516760216, mobile ARM64, API 34 | 10/10 affected smoke tests passed |
| Q601B 192.168.123.94:5555, leanback ARM32, API 29 | 10/10 affected smoke tests passed |

The affected smoke selection was player wiring (two methods) plus the complete
media-browser service group (eight), in run `20261112T005725Z`. Both service-test
process logs were checked after the run: no size-validation errors or fatal
exceptions were present. Final source hashes matched the build inputs. Both
application and runner APKs compiled, labels remain distinct, and architecture,
retention and remote/label checks passed. Both devices stayed awake and unlocked;
stay-awake and screen timeouts were restored and independently verified. Raw
negative controls, final unit XML and device results are retained under
`~/git/temp/wuming-jna-media-pages/`. The fixtures use an in-app browser and
deterministic providers; live providers and cross-process permissions remain
outside this validation scope.


### Final JNA and paging diff audit (2026-11-12)

No new production regression was found. The review traced mobile and leanback
through the same main-source PlaybackService/BrowseTree methods to
`MediaBrowserSearch.currentPage` and `ListPages`; no flavor override replaces
those handlers. Android copies the bounded view to an immutable Media3 list.
Desktop CatalogPanel's active category path calls CatalogResultPager.loadThrough,
which calls load, CatalogPagination.slice and the same ListPages. Desktop keeps
its existing size normalization and page clamping before slicing, and copies
results before releasing its pager lock. The untouched recommendation path retains
its separate shared recommendation workflow.

The audit added four maintained regressions rather than relying only on the
preceding runs: a virtual maximum-sized list verifies the final partial page
without integer-end overflow or a huge allocation; the desktop wrapper verifies
its clamped final page at that boundary; the browser workflow verifies retained
immutable pages across request replacement, cache restoration, exact expiry and
trim; and a desktop classpath test verifies the resolved JNA JAR plus all three
packaged notices against reviewed publisher byte hashes without initializing JNA.

Page reads neither refetch providers nor renew cache expiry. Expiry/trim removes
cached lookup entries but preserves the last published service result until
replacement or explicit clear, matching the pre-paging current() behavior. Held
pages retain their old snapshot, cannot mutate the cached result, and do not
truncate the full cache entry when that query is restored.

Fresh verification passed: base-core 49 tests, catalog-core 121, search-core 76,
and desktop 402 (nine declared GUI/hardware skips). That is 639 passes, nine skips,
zero failures. Both JNA Python regressions, the runtime license graph, the full
public provenance check, all three architecture boundaries, Java retention and
diff whitespace checks also passed. Production and instrumentation source hashes
still match the preceding 10/10 affected smoke run on each Android device. This
audit changed tests and documentation only; no new device run was performed.
Isolated audit XML and logs are under `~/git/temp/wuming-jna-paging-audit/`.
