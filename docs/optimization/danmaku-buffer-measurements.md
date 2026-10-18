# Danmaku bounded-input copy reduction

Date: 2026-09-11. The loader now transfers its private completed buffer when its
capacity exactly equals the data length. Otherwise it makes the existing exact-size
copy. The temporary buffer has one owner, does not escape before completion, and
is never written after transfer. This saves a copy without retaining unused capacity.
The 8 MiB input bound, cancellation checks, failed-load publication, HTTP ownership,
parser behavior and rendering are unchanged.

## Evidence

The harness compiles the actual HEAD loader under a test-only renamed class and
invokes the old/current bounded read methods. Two fresh JVM forks per algorithm
and size, three warmups and five measured samples per fork: 60 samples, each checked
byte-for-byte against its fixture. Timing excludes fixture creation and equality
checks. Allocations are caller-thread JVM allocations, not peak process memory.
Retained payload counts only the returned byte array, not the parsed object graph.

| Input bytes | Algorithm | Median ms (min–max) | Allocated bytes, median | Retained payload bytes |
| ---: | --- | ---: | ---: | ---: |
| 8192 | legacy | 0.066 (0.046–0.080) | 24720 | 8192 |
| 8192 | owned | 0.056 (0.043–0.085) | 16512 | 8192 |
| 1048577 | legacy | 0.819 (0.688–0.988) | 5243160 | 1048577 |
| 1048577 | owned | 0.803 (0.667–1.679) | 5243160 | 1048577 |
| 8388608 | legacy | 7.777 (5.942–19.384) | 25166128 | 8388608 |
| 8388608 | owned | 4.663 (4.207–7.929) | 16777504 | 8388608 |

At the maximum accepted size, allocation falls by 8,388,624 bytes (one payload
array plus its JVM header). The partial-capacity fixture deliberately shows no
allocation improvement. Exact-fit opportunities depend on input length and read
chunking; this is not a universal saving. Host timing is noisy and does not measure
Android parsing or rendering performance. Keep this small managed change; it
provides no reason to introduce a native parser.

Tests cover empty/small/exact/partial buffers, every output byte through the maximum
size, oversized unknown-length rejection with old-source preservation, cancellation
while reading, response closure before deferred parsing and replacement release.
Mobile and leanback JVM suites each passed 763 tests with one skip (1,526 passed,
two skipped total). Leanback-lite ARMv7 compilation passed. Mobile and leanback ARM64 instrumentation suites also compiled successfully; they
were not executed. No device was accessed.

The existing Android suites cover range ordering, concurrent reads, close races,
slow-range replacement, sibling cleanup and unsafe-range fallback, plus danmaku
HTTP/parser ownership and cancellation. Their device execution remains pending;
JVM policy tests do not substitute for those runtime checks.

[Raw samples and environment](../../PUBLIC_PREPARATION.md).
[Reproduction](../../scripts/danmaku-profile/README.md).
