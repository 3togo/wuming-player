# Range progress allocation

Date: 2026-09-11. `ParallelRangeDataSource.Part` previously allocated a new
`CompletableFuture` after every buffered read, even when nobody requested progress.
It now allocates only when a caller requests a pending signal. Waiters share that
signal until progress detaches it; completion remains outside the monitor. Failure
and close complete any existing signal, and late observers see failure/closure.
The byte buffers, read sizes, provider profiles, concurrency, fallback, hedging
criteria and transport contracts are unchanged.

Four focused regressions cover 1,024 unobserved reads, shared waiters and rearming,
failure, and close with/without observers. All 21 search-core and 1,520 app tests
passed (two app skips); leanback-lite ARMv7 compiled. These are local JVM checks,
not provider/device playback or seek validation.

## Measurement

The harness compiles the actual HEAD version under a test-only renamed class and
compares it with the current Part. Each sample fills 16 × 128 KiB buffers through
a synthetic DataSource without progress observers. Two fresh JVM forks per
algorithm/chunk size, two warmups and four samples per fork: 32 matching checksums.
Timing includes construction, filling and checksum; thread allocation includes
buffers and proxy overhead. No sockets, Android decoder or provider is exercised.

| Read chunk | Algorithm | Median ms (min–max) | Median allocated bytes |
| ---: | --- | ---: | ---: |
| 64 | legacy | 14.98 (12.16–78.75) | 3937280 |
| 64 | lazy | 19.98 (8.85–30.99) | 3150464 |
| 8192 | legacy | 12.79 (2.53–75.32) | 2129408 |
| 8192 | lazy | 9.75 (2.57–58.44) | 2122880 |

Retain as a narrow allocation improvement: 786,816 bytes saved for fragmented
64-byte reads, 6,528 bytes for 8 KiB reads. The fragmented-read median time got
worse and ranges overlap widely, so no throughput gain is established. Allocation
savings shrink when progress is actively observed. Buffer retention is unchanged.
No native transport rewrite is justified by this isolated allocation result.

[Raw evidence](../../PUBLIC_PREPARATION.md) and
[reproduction](../../scripts/range-profile/README.md).

Remaining: controlled real-server latency/wait/GC separation, cancellation during
seek, and authorized Android provider comparisons. Preserve the separate Ali
adaptive range policy and the provider lessons during that work.
