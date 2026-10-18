# Casting optimization: bounded segment staging

Date: 2026-09-11

Scope: phase 2 disk staging and per-asset remux coordination; local validation only.

## Change

The small-asset MP4 remux path now stages segments directly into one temporary TS
file. Each worker writes to the segment's verified offset using a shared file
channel. Completion order cannot change playlist order.

The preceding code collected every segment into `byte[][]`, using a growing
`ByteArrayOutputStream` and a further `toByteArray()` copy per segment, before
writing the combined file. Although that method contained a 64 MiB branch, its
production caller already restricted remux candidates to **32 MiB**. The previous
task review's 64 MiB figure described the internal branch, not the active caller gate.

The new downloader enforces:

- At most four download workers and two admitted staging jobs, including abandoned
  jobs whose upstream has not returned. A shared scheduler assigns one segment at
  a time to the eligible job with fewer active segments, rotating ties. It does not
  queue one task per segment. A lone job can use all four workers.
- One reusable 64 KiB copy buffer per worker: at most **256 KiB of copy
  buffers**, independent of playlist payload size. This excludes OkHttp/Okio, TLS,
  operating-system caches and remux allocations; it is not a measured process-memory
  figure or a claim of a 256 KiB total footprint.
- Positive segment sizes, at most 10,000 segments, and an overflow-safe sum within
  the existing 32 MiB remux-input budget before creating a temporary file or HTTP call.
- Actual response EOF must agree with each probed size. Missing Content-Length
  is supported. Changed declared sizes, early EOF, an extra byte, read failures or
  HTTP errors reject staging and use the existing TS fallback.
- Staged file length cannot exceed the validated input budget. No expanded
  per-segment files or concatenation copies are created. Remux output remains a
  separate file and is rejected before cache admission if it exceeds 64 MiB.

This is a bounded-memory and input-staging change. It does not make MP4 playback
start before the complete download/remux, and there is no measured renderer-startup
or device-memory improvement yet. The production path shares four workers between assets at segment boundaries;
see the [fixed-versus-fair comparison](cast-fair-scheduling.md).

## Ownership and cancellation

The helper creates and owns only its unique `cast-stage-*.ts` file. On success,
ownership transfers to the existing remux operation, whose `finally` removes the
TS file. On failure/caller interruption, the shared file handle closes and the
partial file is deleted. Workers never reopen the path, so a late worker cannot
recreate it after cancellation.

An operation-scoped registry cancels only its own active OkHttp calls. The reading
worker closes each response body; cancellation does not race another thread's
`ResponseBody.close()` against a read. A failed worker cancels sibling calls.
Workers check the selected job's scope before starting another segment.

The caller's interruption flag is preserved. If an upstream ignores cancellation,
the caller can still fail and remove its file; late workers see the cancelled scope
or closed channel before writing. The scheduler's fixed worker/admission budget
prevents repeated abandoned requests from creating unbounded threads or queued work.
Admission saturation rejects preparation through the existing fallback. The four
daemon workers and their 256 KiB of buffers are created lazily on first use and
remain until process exit; completed job references are cleared before waiting.
This replaces the old executor's 30-second idle retirement and should be included
in future idle-process memory measurements.

A late asset receives the next available worker according to active segment counts.
There is no preemption inside an upstream read: if all four workers are blocked,
the new asset waits for a segment to return or fail. This differs from reserving
two unused workers for a possible future request.

This responds to caller interruption and download failures. It does not add a new
cast-disconnect or renderer-socket-close signal to the remux operation.

## Preserved behavior and remaining work

Staging uses the same upstream request builder/client as existing relay fetches,
including authentication, cookies, user agent and request headers. Existing HLS
rewriting, progressive TS streaming, range response logic and sample remuxing stay
in their existing paths. No cloud-provider quality/save/cache rules were changed.

The global preparation lock has been replaced with `CastRemuxCache`. Immutable
URL-and-header keys identify both remux files and aggregate-size metadata. At most
two assets prepare concurrently; additional assets use the existing TS fallback.
Requests for the same asset share one preparation. Interrupting one waiter leaves
other waiters running; the last interrupted waiter cancels its preparation. A
builder that ignores cancellation retains its slot until it actually exits, and
its late result is rejected and deleted.

Cache admission is limited to 64 MiB and eight entries. Waiting requests reserve
published files until they obtain an open stream. Each HTTP response owns a lease;
closing the response releases it. Eviction skips reserved or open files and leaves
failed deletions charged to the budget. If pinned files prevent admission, the new
output is discarded and the request falls back to TS. No network or remux operation
runs under the cache lock.

Remuxing checks interruption and output size between samples, and checks again
after finalization. Native buffering/finalization can temporarily exceed the output
limit, so this is not a hard total-disk bound. Two preparations can also require
more temporary disk and remux memory than the former serialized path; host/device
measurements remain pending. These changes do not add new native code.

Playlist size probes now use a [separate bounded scheduler](cast-probes.md): four
workers and two admitted jobs. The shared `CastWorkScheduler` implements fairness
for both probing and staging, with independent instances and call scopes. These
limits do not include playlist fetching or progressive playback.

## Regression coverage

`CastSegmentDownloaderTest` uses generated input streams, not media-sized in-memory
fixture arrays. It covers a 16 MiB ordered output, explicit out-of-order completion,
worker/read-size bounds, invalid/overflowing budgets, absent/changed lengths,
short/long bodies, HTTP/read errors, sibling cancellation, caller interruption,
ignored cancellation and preservation of unrelated files. Existing relay tests
also cover remux sample flags and playlist behavior; a request-header regression
checks the shared call builder without executing a network call.

Android MediaExtractor/MediaMuxer execution, renderer compatibility, actual socket
teardown and peak process memory are not established by these JVM tests. No device
was accessed and no APK was installed.

Local validation passed:

```sh
./gradlew --offline --max-workers=2 :app:testKeyLogic \
  :app:compileLeanback_liteArmeabi_v7aVerificationJavaWithJavac --console=plain
```

Mobile and leanback each reported 754 passed tests and one skipped. This includes
twelve downloader tests, eight probe tests, six cache coordination tests and eight
relay tests per flavor. The shared source compiled
for both ARM64 test variants and leanback-lite ARMv7. Scoped whitespace checks passed.

`CastRemuxCacheTest` covers immutable credential-aware identity, response-close
lease release, eviction with active readers, output rejection, builder failure,
shared preparation with one interrupted waiter, independent concurrent assets,
capacity fallback, and late completion after abandonment. A downloader regression
verifies that an explicitly capped two-worker asset leaves capacity for another
asset. Additional regressions check a late arrival while a lone asset occupies all
four workers, prompt reassignment after one segment returns, admission saturation
with abandoned workers, and cancellation before a queued job starts any calls.

## Host performance follow-up

The [48-sample host comparison](cast-staging-measurements.md) confirms substantially
lower staging allocations, but finds a delayed single-asset latency regression
with two workers. Staging disk size is unchanged. The [fair scheduler](cast-fair-scheduling.md) addresses that policy in a subsequent
implementation and comparison; native conversion remains deferred. Android remux, renderer
startup, full-pipeline disk use and process memory remain unmeasured.
