# Casting: fair segment scheduling

Date: 2026-09-11. The fixed two-worker policy is replaced with a shared four-worker
scheduler. A single asset can use all four workers. When another asset arrives,
new segment assignments favor the eligible asset with fewer active downloads;
equal counts rotate between assets. Assignments change at segment boundaries.

## Implementation and boundaries

`CastSegmentDownloader` admits at most two jobs, including abandoned jobs whose
upstream reads have not returned. Each of four lazily created daemon workers owns
one reusable 64 KiB buffer and claims one segment at a time. There is no task queue
proportional to playlist length. The dispatcher holds its lock only for admission,
assignment and completion bookkeeping; calls, copying and cancellation happen
outside that lock. The production relay no longer imposes a two-worker per-job cap.

A new asset cannot preempt an in-progress upstream read. If the first asset has
four blocked reads, the second waits for one to finish or fail. Once a worker is
free, the new asset is preferred over another segment of the busier asset. This
improves use of spare capacity but does not guarantee a time bound for late arrivals.

Cancellation closes only the job's active calls and shared output handle. It stops
future assignments for that job and removes its owned partial TS file; late workers
cannot reopen the path. Active abandoned workers keep their admission slot until
they actually return. A cancelled queued job starts no calls. Workers clear any
upstream interrupt state before servicing another asset. Cache coalescing, response
leases, output admission and native remux behavior are unchanged by this slice.

The four worker threads and their 256 KiB of reusable buffers persist after first
use until process exit. The preceding executor retired idle threads after 30 seconds.
This small retained-buffer/idle-thread cost avoids per-job buffer allocation, but
Android idle-process memory and thread-stack costs remain to be measured. The
256 KiB figure is copy-buffer capacity, not a process-memory bound.

## Regression coverage

The downloader tests cover existing byte order, size limits, read failures and
cleanup behavior, plus:

- A lone asset occupies all four workers; a late asset receives the next released
  worker and completes while earlier segments remain blocked.
- Two admitted jobs saturate capacity even when the first caller has abandoned
  reads that ignore cancellation; a third is rejected before making calls.
- Cancelling the queued second job starts no calls and removes its partial file.
- Cancelling one active asset leaves the other asset's calls running and its output
  byte-exact.
- An explicitly capped two-worker request still leaves room for another request.

Local validation passed: **1,492 tests passed and two skipped** across the complete
mobile/leanback JVM suites (746 passed and one skipped each), including twelve
downloader tests per flavor. Leanback-lite ARMv7 compilation also passed. These
are not Android device or renderer tests.

```sh
./gradlew --offline --max-workers=2 :app:testKeyLogic \
  :app:compileLeanback_liteArmeabi_v7aVerificationJavaWithJavac --console=plain
```

## Host comparison

Run `python3 scripts/cast-profile/run.py`. The default modes now compare `bounded`
(the preserved fixed two-worker implementation) with `fair` (the production shared
scheduler). The benchmark-only source snapshot is verified against the original
source SHA-256 `7d6976ba8d9f728973b921885205ee0a54bcef6a881492fc24144f8625d2ccbd`.
It is never packaged into the application. Optional `--algorithms legacy bounded
fair` also includes the original whole-playlist-buffering implementation.

The comparison uses identical generated streams, two fresh JVM forks per case and
algorithm, one excluded warmup and three measured samples per fork. The four cases
are 8 MiB and 32 MiB without delay, 8 MiB with 20 ms delay per segment, and two
simultaneous delayed 8 MiB requests. Java heap settings are `-Xms64m -Xmx512m` on
the same four-logical-CPU Linux x86-64 host. The host is not isolated. See the
[harness method](../../scripts/cast-profile/README.md) for allocation and sampling
limits. The timing for two requests is completion of both, not first-response time.

All **48 measured outputs** matched their independently generated length and
SHA-256 expectations, with no remaining fixture files or active responses. The
[retained data](../../PUBLIC_PREPARATION.md) records raw samples,
summary ranges, host/JVM details and source hashes. Full logs and generated classes
are in `app/build/reports/cast-profile/AUTHORIZED_DEVICE_SERIAL.862606Z/`.

Each row has six samples. Allocations include fixture/instrumentation work; peak
heap is absolute sampled JVM used heap, not process RSS or Android native memory.

| Fixture | Policy | Time ms, median [min–max] | Allocated MiB, median | Sampled peak heap MiB, median |
| --- | --- | ---: | ---: | ---: |
| 8 MiB, no delay | Fixed two-worker | 115.0 [58.6–130.7] | 0.277 | 5.7 |
| 8 MiB, no delay | Fair shared-worker | 83.8 [62.5–100.0] | 0.136 | 5.9 |
| 32 MiB, no delay | Fixed two-worker | 219.3 [115.2–379.8] | 0.575 | 6.3 |
| 32 MiB, no delay | Fair shared-worker | 257.2 [158.6–337.7] | 0.425 | 5.9 |
| 8 MiB, 20 ms/segment | Fixed two-worker | 410.7 [391.5–433.9] | 0.577 | 6.7 |
| 8 MiB, 20 ms/segment | Fair shared-worker | 220.4 [205.2–272.6] | 0.259 | 5.9 |
| Two 8 MiB requests, 20 ms/segment | Fixed two-worker | 396.6 [365.1–408.8] | 1.054 | 6.7 |
| Two 8 MiB requests, 20 ms/segment | Fair shared-worker | 397.9 [378.2–410.9] | 0.761 | 6.9 |

The delayed single-asset median fell from **410.7 to 220.4 ms**, approximately
46% lower. Its observed time ranges did not overlap in this run. Two simultaneous
delayed requests remained near 398 ms. Peak active responses were four for fair
scheduling, versus two for the fixed single-request baseline and four for its
concurrent case. This is evidence for recovering idle capacity under these delays,
not a claim about real-network throughput or renderer startup.

The 32 MiB no-delay median increased from 219.3 to 257.2 ms; broad overlapping
ranges prevent a confident throughput conclusion. Four-way I/O and per-segment
scheduling are not guaranteed to help when there is no upstream waiting. Keep this
case in future comparisons rather than treating the delayed result as universal.

Logical TS disk peaks stayed at 8, 32, 8 and 16 MiB for the four fixtures. Both
outputs are retained through the concurrent measurement, even for a serialized
baseline; this does not measure production TS/MP4 overlap. At the time of this measurement, playlist probes used their common-pool path
outside the four staging workers; the subsequent [probe change](cast-probes.md)
bounds them independently. The worker
limit is not a cap on all relay network activity.

## Decision and remaining work

Retain bounded managed staging and the existing Android native media backend.
The scheduler targets unused download capacity; a C/C++/Rust conversion would not
remove upstream waits. No new native module is warranted by this comparison.

Continue with authorized-device measurements of actual upstream behavior, first
response, range/HEAD playback, native remux overlap, cancellation teardown, process
RSS and total TS/MP4 temporary disk usage. Neither generated bytes nor host timings
establish media compatibility or Android playback startup. No device was accessed
and no APK was installed.

The later probe change extracts this scheduling algorithm and call-cancellation
scope into shared classes. Its local regression results are documented separately;
the timing table above retains its original measured source hashes.
