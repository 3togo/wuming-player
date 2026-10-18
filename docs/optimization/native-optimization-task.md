# Task: incremental app optimization and native-code evaluation

Created: 2026-09-11
Status: managed slices locally verified; initial 20-test Android correctness selection passed on Android 15 ARM64. Performance, real-provider and casting-media gates remain open; native additions deferred
Scope: optimize measured workloads incrementally; evaluate C/C++/Rust only where
an optimized managed implementation still leaves a material bottleneck.

## Objective and evidence

Reduce EPG import memory, casting preparation costs, and search computation, then
investigate range playback, danmaku, and QuickJS overhead. Preserve playback,
provider, UI, and shared-library contracts. A whole-module native rewrite is not
the default deliverable; a measured decision to retain Java/Kotlin is a valid outcome.

The initial review inspected the current working-tree source. It did not run
device benchmarks, establish speedup percentages, or change application code.
The findings below identify structural costs, not proven runtime bottlenecks.
Recheck each finding before implementation because other work may change these files.

| Area | Optimization priority | Native value | Initial decision |
| --- | --- | --- | --- |
| EPG/XMLTV import | High | Medium–high, conditional | Improve streaming/filtering; first candidate for a bounded Rust experiment |
| Casting download/remux | High | Medium, conditional | Bound buffering and improve coordination before considering a media library |
| Search merge/ranking | High for large result sets | Low initially | Improve algorithms and avoid repeated computation in Java/Kotlin |
| Parallel range playback | Medium–high investigation | Medium if CPU-bound | Profile allocations, waits, and throughput before changing transport |
| Danmaku | Medium when enabled | Conditional | Separate import cost from rendering cost |
| QuickJS integration | Medium | Low for wholesale conversion | Engine is already native-backed; examine bridge overhead |
| Cloud providers, UI, database/domain policies | Targeted only | Very low | Keep managed unless new measurements justify a narrow exception |

## Working rules

- Implement one numbered phase at a time in independently reviewable changes.
- Record the baseline, changes, checks, results, and remaining limitations below.
- Compare equivalent inputs and configurations; distinguish cold and warm runs.
  Record revision/working-tree state, device or host, ABI, build type, fixture size,
  repeated samples, and variability. Host timings do not prove Android improvements.
- Start with source review, deterministic fixtures, JVM checks, and local builds.
  No device is authorized for this optimization task by the earlier QR/DLNA-only
  authorization. Obtain task-specific authorization before any device access.
- Preserve unrelated working-tree changes. Do not install, publish, or deploy as
  part of documenting or locally validating this task.
- Before changing Quark, UC, or Ali playback/cache behavior, read the corresponding
  [Quark](../quark-smart-quality-lessons.md), [UC](../uc-playback-cache-lessons.md),
  and [Ali](../ali-playback-cache-lessons.md) lessons as applicable. Preserve accepted
  quality fallbacks, quota-only fallbacks, header boundaries, and scoped cleanup.
- Keep phone/TV behavior and launcher labels intact. If UI/focus/labels change,
  run `python3 scripts/check-leanback-remote.py` and preserve the AGENTS.md contracts.

## Phases and acceptance criteria

### 1. EPG: stream and retain only necessary guide data

Evidence: `EpgParser.parseXmlData()` deserializes the complete `Tv` object graph
before filtering programmes. `libraries/epg-core` has an incremental XML reader,
but its current return model still collects every programme.

- [x] Establish representative small/large XMLTV and gzip fixtures, including
  unrelated channels, alias matches, timezone variants, and malformed input.
- [x] Record baseline import time, allocations/peak memory where measurable, and
  output equivalence. Keep download time separate from parsing time.
- [x] Implement Android-compatible streaming with early filtering. Define the
  retained date window from existing navigation requirements before limiting it.
- [x] Bound expanded input/text/retained data with explicit failure behavior;
  preserve XML security, cancellation, channel aliases, logos, timezone semantics,
  and successful/failed channel-match caching.
- [ ] Avoid unnecessary timestamp and string conversions without changing results.
- [x] Run focused parser/guide regressions and affected Android/shared builds.
- [x] Record before/after results and decide whether a native experiment is warranted.

Acceptance: required guide output remains equivalent; irrelevant programmes are
not retained as a complete intermediate object graph; limits and failures are
tested. Device performance remains pending until measured on an authorized device.

### 2. Casting: bound downloads and preparation work

Evidence: `CastMediaRelay.downloadSegments()` retains all segment byte arrays for
playlists estimated at up to 64 MiB; `ByteArrayOutputStream.toByteArray()` adds
copies. The estimate is not an enforced actual-byte budget. `obtainRemuxedMp4()`
holds a global lock across download and full-file remuxing.

- [x] Compare baseline/current host staging time, JVM allocation/heap and logical
  TS disk use with controlled single/concurrent fixtures; [48 samples](cast-staging-measurements.md).
- [ ] Measure Android full preparation/RSS/temporary disk high-water and cancellation
  latency; generated-stream host fixtures do not cover native remux or sockets.
- [x] Implement and compare fair shared worker scheduling: [48 matching samples](cast-fair-scheduling.md);
  delayed single-asset median 410.7 → 220.4 ms, concurrent completion near 398 ms.
  No-delay timings remain noisy; this is not a universal throughput improvement.
- [x] Replace whole-playlist memory retention with bounded workers and disk-backed
  staging or another bounded pipeline; enforce actual-byte/disk limits.
- [x] Bound segment-size probes independently of staging, stop failed batches, and
  scope cancellation; reject invalid count/range metadata and overflowing totals.
  [Details and local validation](cast-probes.md).
- [x] Use per-asset preparation coordination while keeping shared cache accounting
  and eviction synchronized correctly; deduplicate identical in-flight work.
- [ ] Preserve headers, Range/HEAD behavior, byte order, fallback, and owned-file
  cleanup across cancellation, partial failure, and concurrent requests.
- [ ] Verify output media and regression tests; record memory/startup tradeoffs.
- [ ] Separately evaluate incremental remuxing only if preparation latency remains
  significant and renderer compatibility can be demonstrated.

Acceptance: downloads cannot buffer the entire playlist without an enforced
budget; unrelated preparation is not serialized behind one long global lock;
partial work cleans up correctly. Native remuxing is not assumed to reduce latency
if the architecture still requires a complete download before serving playback.

### 3. Search: remove repeated work while preserving identity semantics

Evidence: `SearchResultPolicy.merge()` scans accumulated results per candidate;
relevance comparisons recalculate normalization/scores. `FastSearchCoordinator`
looks up source ranks linearly inside its comparator.

- [x] Capture merge, alias, artwork/source aggregation, tie ordering, strict-match,
  and multilingual normalization behavior with representative large result sets.
- [x] Index canonical identities with a semantics-preserving fallback for ambiguous
  aliases; avoid assuming every identity relation maps to one simple hash key.
- [x] Cache merge normalization within each call and map source IDs directly to
  session ranks; [equivalence and work-count evidence](search-indexing.md).
- [x] Prepare normalized query/title values and relevance once per item/query
  within each sort, preserving persisted user-rank priority and stable ties.
- [x] Check shared-library tests and mobile/leanback callers; compare outputs and
  CPU/allocation costs across increasing result counts.

Acceptance: equivalent merge/ranking behavior with reduced repeated work; no
unbounded global normalization cache. Native edit distance remains deferred unless
optimized batched matching is still a measured hotspot.

Search results: [relevance preparation and 72 matching host samples](search-relevance-measurements.md).

### 4. Range playback: profile and tune the managed path

Evidence: `ParallelRangeDataSource.Part` allocates prefetch buffers, creates progress
futures during reads, and copies buffered data into Media3 destination arrays.
Provider throttling and waiting may dominate these costs.

- [ ] Separate upstream latency/throughput, worker waits, CPU, allocations, and GC.
- [ ] Evaluate buffer reuse, concurrent memory budgets, and progress-notification
  frequency only where measurements show a benefit.
- [ ] Preserve provider profiles, Content-Range validation, EOF handling, adaptive
  tuning, sequential fallback, seek cancellation, and response-body ownership.
- [ ] Run controlled range-server regressions and affected builds; defer provider
  device comparisons until authorized.

Acceptance: no correctness regression across seek/failure/fallback cases and a
measured benefit for each retained tuning change. Moving buffers native does not
make Media3's Java byte-array interface automatically zero-copy.

Completed narrow allocation slice: [lazy progress signals and 32 matching host samples](range-progress.md). Broader transport profiling below remains open.

### 5. Danmaku and QuickJS: isolate workload-specific costs

- [ ] Danmaku: measure parsing/retention separately from frame rendering. Evaluate
  comment limits/time-window loading with seek and synchronization compatibility.
- [ ] If frame jank remains, profile text measurement, layout, and drawing before
  proposing a rendering change; parser conversion cannot establish rendering gains.
- [ ] QuickJS: measure context/module creation, JSON/base64 conversions, bridge
  calls, and cleanup. Preserve native thread affinity and async object ownership.
- [ ] Remove redundant conversions only with provider-contract regression coverage.

Acceptance: improvements have workload-specific evidence; native/managed resource
ownership remains explicit. Replacing the already-native QuickJS engine is outside
the initial scope.

Source review completed: [danmaku/QuickJS findings and evidence gates](native-decision.md). Runtime measurements remain open.

### 6. Native decision gate and optional prototype

- [ ] Select at most one remaining measured bottleneck for the first prototype.
- [ ] Define a material end-to-end improvement target before implementing it.
- [ ] Prefer Rust for bounded parsing/data processing; prefer C/C++ when integrating
  an established media library. Do not reimplement codecs or cryptography for this task.
- [ ] Design coarse-grained JNI boundaries, cancellation, error propagation, and
  explicit ownership. Include conversion/copying costs in measurements.
- [ ] Compare against the optimized managed baseline, including total process/native
  memory, CPU, latency, APK size, startup, and maintenance/build costs.
- [ ] Validate ARMv7/ARM64, Android native page-size compatibility, shared JVM/desktop
  boundaries, malformed-input handling, and dependency licensing where applicable.
- [x] Record adopt/defer/reject with evidence; retain a practical rollback path.
  [Current decision: defer native additions](native-decision.md); no prototype selected.

Acceptance: adopt only if the complete user-visible path improves materially with
acceptable compatibility and maintenance costs. A microbenchmark win alone is
insufficient. Keep Media3, Android UI/lifecycle integration, cloud-provider routing,
and domain-policy libraries managed unless separately justified.

## Validation references

- [Production heap tools](../../scripts/heap-profile/README.md): managed retention
  analysis and process memory snapshots; not native allocator backtraces.
- [Existing hardening review](../crash-memory-hardening.md): ownership and input
  bounds already implemented; preserve these rather than duplicating work.
- [Android JNI guidance](https://developer.android.com/ndk/guides/jni-tips): minimize
  marshalling and keep native interfaces small.
- [Media3 formats/decoders](https://developer.android.com/media/media3/exoplayer/supported-formats):
  player orchestration is distinct from platform decoding.
- [Native page sizes](https://developer.android.com/guide/practices/page-sizes):
  check current requirements before adding or updating native binaries.

## Progress log

| Date | Phase | Result | Next action |
| --- | --- | --- | --- |
| 2026-09-11 | Planning | Source-review findings and phased task documented; no application changes or device tests | Begin phase 1 with EPG fixtures, compatibility review, and baseline |
| 2026-09-11 | Phase 1 implementation | Selective two-pass import, direct gzip, limits, atomic failure behavior and offset fix locally verified | Controlled memory/timing comparisons and real-feed/device compatibility; native work deferred |
| 2026-09-11 | Phase 1 host profiling | 108 measured imports with identical outputs; substantially lower allocations; [results](epg-streaming-measurements.md) | Keep managed implementation; phase 2 casting is next. Android/feed compatibility remains a release gate |
| 2026-09-11 | Phase 2 staging | Direct-to-disk bounded downloads, exact size checks, scoped cancellation and owned-file cleanup; 1,472 JVM tests passed/two skipped and leanback-lite ARMv7 compiled; [details](cast-staging.md) | Replace global remux preparation locking with per-asset coordination |
| 2026-09-11 | Phase 2 coordination | Bounded per-asset preparation, header-aware identity, waiter cancellation, and leased cache files; see [details](cast-staging.md) | Measure preparation/memory/disk tradeoffs and validate Android remux/renderer behavior |
| 2026-09-11 | Phase 2 host profiling | 48 matching outputs; 32 MiB allocation 127.423 → 0.505 MiB; delayed single-asset time 185.2 → 363.5 ms; [results](cast-staging-measurements.md) | Keep managed staging; investigate fair worker scheduling; no native conversion or device speedup claim |
| 2026-09-11 | Phase 2 fair scheduling | Four shared workers, two admitted jobs, segment-boundary fairness, scoped cancellation; 48 matching host outputs; [results](cast-fair-scheduling.md) | Delayed single-asset median reduced about 46%; retain managed staging; native remux/device release gates remain |
| 2026-09-11 | Phase 2 bounded probes | Dedicated four-worker/two-job budget, shared fair scheduler/call scope, HEAD/range fallback and overflow checks; 1,508 JVM tests passed/two skipped; [details](cast-probes.md) | Native/device release gates remain; search merging/ranking is the next independent offline phase |
| 2026-09-11 | Phase 3 merge/source ranks | Indexed canonical IDs/title groups; preserved first-match wildcard semantics against 6,400 generated inputs; 1,000 distinct-title normalization calls 999,000 → 1,000; 18 core and 1,510 app tests passed/two skipped; [details](search-indexing.md) | Relevance preparation and elapsed/allocation profiling remain next; native conversion deferred |

### Phase 1 implementation notes

- Added the shared `XmlTvStreamReader` SAX visitor and integrated file imports in
  Android `EpgParser`. The existing string/per-channel response API and full-model
  JVM StAX API are unchanged.
- Pass 1 retains bounded channel metadata; pass 2 selects programmes before
  collecting titles. The complete input programme object graph is no longer retained.
  Two passes preserve aliases/icons declared after programme records; they incur
  additional scanning/decompression work, so runtime results must be measured.
- Gzip is decoded directly on each pass. The importer no longer creates or reads
  an expanded `.xml` sidecar. Previously cached sidecars are left untouched.
- All historical/future dates remain available for existing catch-up/navigation.
  The adapter rejects imports above 50,000 channel records, 250,000 selected
  programmes, or 16 Mi retained metadata/title characters. The reader additionally
  enforces expanded-byte, record, field, alias and nesting limits (see epg-core README).
  These are failure limits, not truncation: existing guide data is kept on failure.
- DTD-bearing feeds now fail closed, including external `xmltv.dtd` declarations;
  external entities are never resolved. This is an explicit compatibility constraint
  to validate against real feeds before release, not a claim of universal XMLTV parity.
- Programme-only unknown IDs are not accumulated in an unbounded miss cache.
  Known metadata IDs retain successful/failed matching caches.
- Regression testing exposed the existing colon-offset formatter using `ZZZ`,
  which does not accept `+08:00`; it now uses `xxx`. Malformed timestamps retain
  the prior epoch fallback, and diagnostics use the JVM-safe app logging wrapper.
- Deterministic generated fixtures cover 30,000 programmes (300 matched), late
  channel metadata, aliases/logos, mixed offset forms, historical dates, gzip,
  truncation and failed-import publication. Reader tests cover cancellation,
  caller-owned streams, DTD/entity rejection, malformed XML, and limits.
- No device access, native-code addition, or changes to cloud-provider behavior.

### Initial host evidence (2026-09-11)

`EpgStreamingImportTest.largeMostlyUnrelatedFixtureMatchesLegacySelection` generates
a 3,678,899-byte XML file containing 30,000 programmes, with 300 matching a configured
channel. It compares the selected titles against the original Simple XML `Persister`
model. A focused run on the local Linux host (working tree based on `0c0bcf9db`)
reported 1,762 ms for legacy deserialization and 470 ms for the two-pass import.
These are single cold samples of different work boundaries (the streaming sample
also includes guide conversion/publication), not a controlled speedup estimate.
There is no measured peak-memory or Android performance result yet.

The structural result is that the new reader emits only 300 programme records to
the adapter for this fixture, instead of retaining a 30,000-programme input model.
Metadata and the selected final guide remain retained and are bounded separately.
Do not infer total process-memory reduction from these object counts.

Next performance work: controlled repeated small/large and gzip comparisons,
allocation/peak-memory measurements, and authorized Android feed compatibility and
runtime profiling. Native implementation remains deferred pending that evidence.

Update: the repeated host comparisons are now complete; see
[EPG streaming measurements](epg-streaming-measurements.md) and the
[reproducible harness](../../scripts/epg-profile/README.md). This supersedes the
single-sample figures above. No native experiment is justified by these results.
Android timing/PSS/native memory and real-feed compatibility are still pending.
The DTD restriction was reviewed against Android's SAX implementation and remains
explicitly documented. Platform regression tests have been added for a future
authorized run. Redundant cold timing output was removed from the ordinary JVM test.

Profiling follow-up validation passed: 16 EPG-core tests, 10 focused Android JVM
importer/formatter tests, and compilation of both mobile and leanback ARM64
verification instrumentation code. The two new platform tests were **compiled,
not executed**. Python syntax checks and the harness smoke/full runs passed.

### Local validation

Passed:

```sh
./gradlew --offline --max-workers=2 :libraries:epg-core:check :app:testKeyLogic \
  :app:compileLeanback_liteArmeabi_v7aVerificationJavaWithJavac --console=plain
```

- EPG core: 16 tests passed.
- Mobile ARM64: 727 tests passed, one skipped.
- Leanback ARM64: 727 tests passed, one skipped.
- Mobile/leanback compilation occurred through their JVM test tasks; the explicit
  leanback-lite ARMv7 compilation also passed.
- Focused importer/formatter tests and scoped whitespace validation passed.
- No APK installation or device run. Peak memory, Android SAX behavior, and native
  conversion benefits remain unverified. No native prototype is justified yet.

### Follow-up implementation and validation

Search relevance preparation now preserves per-object persisted rank priority and
stable ties while avoiding repeated scores. See [72 host samples](search-relevance-measurements.md).
Range progress signals allocate only for observers; see [32 host samples](range-progress.md).
Validation: 21 search-core tests plus 1,520 app tests passed, two app tests skipped,
and leanback-lite ARMv7 compilation passed. No APK installation or device testing.
Danmaku and QuickJS review is recorded in the [native decision](native-decision.md).
Remaining unchecked criteria are intentionally open; synthetic tests do not close
Android playback, rendering, native-runtime or real-provider validation gates.

### Danmaku input-buffer follow-up

Implemented exact-fit buffer ownership transfer without spare-capacity retention.
[60 matching host samples](danmaku-buffer-measurements.md) show allocation dropping
from about 24 MiB to 16 MiB at the 8 MiB input bound; partial-buffer allocation is
unchanged. Added byte/limit/cancellation/reload tests: 1,526 app tests passed, two
skipped, and leanback-lite ARMv7 compiled. Parser/rendering and QuickJS runtime
profiling remain open; no native conversion or device performance claim is made.

Mobile and leanback ARM64 Android test sources also compiled successfully after
the range/danmaku changes; instrumentation was not run.

### QuickJS conversion ownership follow-up

Fixed partial array/object conversion cleanup while preserving successful ownership,
signed bytes and caller-thread execution. Ten QuickJS JVM tests and ARMv7 app
compilation passed. [Details and remaining runtime gates](quickjs-conversion-ownership.md).
Added a repeated-failure Android wrapper regression; it has not been executed.
This is a resource-lifetime fix, not a measured bridge-throughput improvement.

### QuickJS nested ownership follow-up

Extended the ownership review to error-response construction and returned-object
`then` lookup. Fixed parent/returned-wrapper retention on failure and added five
focused JVM regressions. See [details](quickjs-conversion-ownership.md). Performance
profiling and Android native-runtime execution remain separate, open criteria.

### Android runtime handoff

Reviewed outstanding criteria and prepared an [explicit 20-test runtime pass](android-runtime-validation.md)
for SAX, range reads/cancellation, danmaku and QuickJS ownership. Tests use fixtures
or loopback HTTP; live-provider methods are excluded. APK preparation is local;
no device operation is authorized by earlier task-specific pairing permission.
Timestamp caching, rendering/bridge rewrites and native prototypes remain deferred
until relevant measurements justify their costs.

### First authorized Android run and offline follow-up

User authorized `172.16.30.109:42787`; Android 15 ARM64 mobile app and test runner
installed successfully. Both combined runs passed 19 tests then stalled in the
final QuickJS HTTP test; that test passed alone. OEM frozen-process state was
observed. See [results and offline mitigation](android-runtime-validation.md).
Device access stopped when the user removed the phone. Foreground test hosting
is prepared but unverified on-device. Do not mark the 20-test gate complete.

Reconnected device `172.16.30.109:38591`: 16 tests passed; foreground class setup
hung in synchronous activity launch. A bounded asynchronous replacement runner
built, but the installer aborted its update; no successful full rerun yet.
[Latest evidence](android-runtime-validation.md).

### Android correctness gate passed

All original 20 selected methods passed in one run on `172.16.30.109:38591`:
zero failures/skips, 10.324 seconds. ADB maintained wakefulness and app foreground;
the temporary power setting was restored. Removed unsuccessful activity-host test
hooks. [Verified device results](android-runtime-validation.md). This closes this
specific runtime selection only; performance/native-conversion and casting-media
acceptance criteria remain open.

### Casting native-runtime fixture preparation

Added generated H.264/AAC TS assets and two Android staging/remux regressions.
They check exact bytes/order/headers, platform remux tracks/sample timelines and
malformed input rejection. [Scope and remaining gaps](cast-native-runtime.md).
Device execution result must be recorded separately; no timing or renderer claim.
