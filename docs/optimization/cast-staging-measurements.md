# Casting staging: host measurements and decision

Date: 2026-09-11. The bounded Java downloader substantially reduces allocations in
these controlled fixtures. It does **not** establish faster renderer startup: the
single delayed-request case is approximately twice as slow with two workers per
preparation. No C/C++/Rust conversion is justified by this measurement.

## Method

For the original two algorithms, run `python3 scripts/cast-profile/run.py --algorithms legacy bounded`. See the [harness instructions](../../scripts/cast-profile/README.md)
for reproducibility and metric definitions. The [retained data](../../PUBLIC_PREPARATION.md)
contains all 48 measured samples, source hashes and summary statistics.
The complete local report, generated baseline source and logs are under
`app/build/reports/cast-profile/AUTHORIZED_DEVICE_SERIAL.342990Z/`.

- Linux x86-64 host with four logical CPUs; Java `21.0.10+7-b1163.110`.
- The Git baseline is `0c0bcf9db9c4192ada0d37480d8a01d0618cadae`. Its two download
  methods are extracted verbatim. An adapter supplies metadata and synthetic
  upstream responses; a harness lock reproduces serialization around staging.
- The current working-tree `CastSegmentDownloader` runs with two workers per
  request and a shared maximum of four. Current source hashes are retained because
  the implementation has not been committed. No application source changed during
  this measurement slice.
- Four fixtures, two algorithms, two fresh JVM forks each; one excluded warmup and
  three measured samples per fork. Algorithm order alternates. Both JVMs use
  `-Xms64m -Xmx512m`; legacy common-pool parallelism is fixed at four. The caller
  also participates in the legacy stream, producing up to five active responses.
- Each segment is 256 KiB, generated without retaining large fixture buffers in
  the JVM. Synthetic response reads supply at most 8 KiB. Delay fixtures wait
  20 ms once per segment. This is latency per request, not bandwidth throttling.
- Every output length and SHA-256 matched an independently generated expectation.
  All fixture responses closed and all generated TS outputs were removed.

## Results

Each row has six measured samples. Time is the median with observed minimum and
maximum; MiB means 1,048,576 bytes. For two requests, time is completion of **both**,
not first-response or per-renderer latency.

| Fixture | Implementation | Time ms, median [min–max] | Allocated MiB, median | Sampled peak used heap MiB, median | Logical TS disk MiB |
| --- | --- | ---: | ---: | ---: | ---: |
| 8 MiB, no delay | Legacy | 42.2 [22.1–88.6] | 31.864 | 29.8 | 8 |
| 8 MiB, no delay | Bounded | 29.8 [21.4–53.2] | 0.229 | 5.7 | 8 |
| 32 MiB, no delay | Legacy | 205.9 [125.7–281.2] | 127.423 | 73.5 | 32 |
| 32 MiB, no delay | Bounded | 162.9 [106.8–251.0] | 0.505 | 6.3 | 32 |
| 8 MiB, 20 ms/segment | Legacy | 185.2 [176.7–194.8] | 32.040 | 36.3 | 8 |
| 8 MiB, 20 ms/segment | Bounded | 363.5 [354.0–379.5] | 0.661 | 6.7 | 8 |
| Two 8 MiB requests, 20 ms/segment | Legacy | 378.0 [373.9–399.8] | 64.228 | 40.9 | 16 |
| Two 8 MiB requests, 20 ms/segment | Bounded | 367.3 [357.4–384.3] | 1.116 | 6.7 | 16 |

For 32 MiB, allocation dropped approximately **99.6%**. Absolute sampled heap
medians fell from 73.5 to 6.3 MiB. These are JVM fixture measurements, not process
RSS or Android peak memory. Allocation counters include workers, callers, synthetic
transport and instrumentation. The one-millisecond heap sampler can miss peaks.

No-delay timings have substantial overlapping ranges, so their median differences
are not sufficient evidence for a throughput claim. The delayed single-request
regression is clear in this fixture: 363.5 versus 185.2 ms. Limiting one asset to two
workers leaves spare capacity but roughly doubles its latency here. With two assets,
the bounded implementation uses four active workers and both finish in approximately
the same total time as the two serialized legacy preparations. This tests staging
only; it does not time the production cache coordinator or native remux.

The logical TS disk requirement did not fall. Both outputs are retained until the
two-request measurement ends, including in the legacy harness; real remuxing would
consume/delete the first TS according to its own lifecycle. Physical disk blocks,
MP4 outputs, media buffers, sockets, playlist probes, HTTP serving, process RSS and
page cache are outside the measurement. Peak full-pipeline disk usage and the cost
of two concurrent native remuxers remain unmeasured.

## Decision and next steps

1. Retain bounded Java staging and the existing native Android remux backend.
   Eliminating whole-playlist buffering addresses the measured allocation problem;
   another language would not remove synthetic upstream waits or the full-download
   requirement before MP4 serving.
2. Treat the two-workers-per-asset policy as a measurable tradeoff, not a demonstrated
   latency optimization. Next, compare a fair shared four-worker scheduler against
   fixed two-worker assignments, including one slow asset, late arrivals, saturation
   and cancellation. Do not simply give each asset four long-running workers: the
   first asset can then occupy the whole shared pool until its staging completes.
3. Keep Android media validation and real-network measurements as release gates.
   Record first-response time, simultaneous remux memory, MP4/TS disk high-water,
   range/HEAD playback, and cancellation teardown on an explicitly authorized device.
   No device was accessed, no APK installed, and no media-compatibility claim follows
   from generated byte patterns.
4. Consider incremental remux only after evidence shows full-file preparation is
   the remaining renderer bottleneck. A native rewrite remains deferred.

## Validation performed

The full benchmark completed with 48 matching measured outputs, eight scenario/
algorithm summaries and no fixture-file leftovers. The opt-in runtime task compiled
mobile ARM64 verification source and JVM tests. Python syntax, JSON consistency,
source-hash consistency and scoped whitespace checks passed. The functional app
suite was not rerun for this harness/documentation-only change; its prior result
remains 1,486 passed and two skipped, with leanback-lite ARMv7 compilation passed.

## Follow-up

The [fair scheduler implementation and comparison](cast-fair-scheduling.md) follows
this baseline: it recovers spare worker capacity for a lone asset while retaining
bounded staging and testing late arrivals and scoped cancellation. The original
results above remain historical measurements of the fixed two-worker policy.
