# Casting: bounded playlist segment probes

Date: 2026-09-11.

The aggregate-length path previously used a parallel stream on the common pool.
Concurrent callers could add probing work outside the staging budget; an unsizable
segment did not stop sibling probes. Caller interruption did not own/cancel the
active HEAD and range calls. Aggregate length addition could overflow a signed long.

## Change

`CastSegmentProbe` uses a dedicated four-worker scheduler with at most two admitted
playlist jobs. A lone playlist can use four workers; concurrent playlists receive
new assignments according to active segment counts. Probing shares the fair
scheduler implementation with staging (`CastWorkScheduler`), but uses a separate
instance and no payload copy buffers. Probes cannot occupy staging workers.

A sizing failure cancels sibling calls and stops new segment assignments. Caller
interruption retains the caller's interrupt flag, cancels its own calls and stops
HEAD-to-GET fallback. An upstream operation that ignores cancellation keeps its
admission slot until it returns. The worker alone closes its response; the caller
never races it to close a response body. `CastCallScope` is shared by staging and
probing so they follow the same ownership rule. Their scopes remain independent.

Admission saturation, an unsizable segment, or an invalid/overflowing size result
uses the existing unknown-length/chunked TS fallback. Only completely sized,
positive, overflow-safe arrays are eligible for aggregate metadata publication.

## Protocol and input boundaries

- Keep the existing HEAD-first behavior. A successful response with a positive
  body content length supplies the size. An unsupported, failed, zero-length or
  unknown-length HEAD falls back to GET with `Range: bytes=0-0`.
- A 206 response must describe `bytes 0-0/<positive total>`. Unknown totals,
  overflow, malformed units, unrelated ranges and expanded ranges are rejected.
  The old parser accepted any suffix after the final slash; this tightening can
  send nonconforming CDN responses to chunked fallback instead of advertising
  potentially incorrect aggregate offsets.
- A server ignoring Range may still supply a positive full body length in a 200
  response. No application-level payload read is needed for sizing. HTTP-client
  cleanup may perform its own bounded draining; this is not a zero-network-byte
  guarantee.
- One sizing batch accepts 1–10,000 segment descriptors, matching the existing
  staging count ceiling. Larger playlists skip aggregate sizing. This check is
  after playlist fetching/parsing; it does not bound playlist text memory.
- Authentication and request headers use the same `upstreamCall` builder and the
  existing immutable URL-and-header cache identity. No cloud-provider save,
  cleanup, quality-selection, or routing contracts are changed.

The probe budget is separate from four staging workers, two remux preparations,
playlist fetching and progressive playback. It is not a cap on all app/relay
network activity. Four probe daemon threads are created on first use and remain
until process exit, as do the staging workers. Added idle thread-stack cost needs
Android process measurement. Duplicate concurrent probes for the same uncached
playlist are still possible within the two-job bound; this change does not add
in-flight probe coalescing or alter metadata cache freshness.

## Validation

`CastSegmentProbeTest` covers ordered HEAD lengths without payload reads,
HEAD failure/zero/unsupported fallback, ignored-range full length, strict
Content-Range parsing, segment-count and aggregate-overflow rejection, early
sibling cancellation, interrupted callers and late responses, admission limits,
and cancellation isolation between active jobs. Existing twelve staging tests
continue to exercise fair scheduling, late arrivals, saturation, cancellation,
byte ordering, size limits and file ownership after the shared-code extraction.

The complete mobile/leanback JVM suites passed **1,508 tests with two skipped**
(754 passed and one skipped per flavor), including eight probe tests per flavor.
Leanback-lite ARMv7 compilation passed:

```sh
./gradlew --offline --max-workers=2 :app:testKeyLogic \
  :app:compileLeanback_liteArmeabi_v7aVerificationJavaWithJavac --console=plain
```

The benchmark runtime metadata now includes the extracted scheduler and call-scope
source hashes, so future staging measurements identify their complete implementation.
A quick one-fork/one-sample run per algorithm completed with two matching outputs
in `app/build/reports/cast-profile/AUTHORIZED_DEVICE_SERIAL.840912Z/`; it verifies harness
integration only and is not a new performance comparison.


These are synthetic JVM fixtures, not actual CDN or device tests. No probe latency,
Android media compatibility, renderer startup, process RSS or physical socket
teardown improvement is claimed. The prior staging performance reports identify
their historical source hashes; this change does not retroactively remeasure them.

## Remaining work

Keep Android native remux/renderer validation and real-network cancellation,
process memory and temporary disk measurements as release gates. No device is
authorized for this optimization task, and none was accessed. Probe coalescing
and playlist-fetch limits can be considered separately if measurements justify
them. Native conversion remains deferred; the next independent managed-code
optimization in the task is search merging and ranking.
