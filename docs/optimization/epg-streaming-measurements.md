# EPG streaming measurements — 2026-09-11

## Decision

Keep the managed streaming importer and defer a Rust/C/C++ prototype. This host
comparison supports the structural optimization: far fewer allocations when most
programmes are irrelevant, and a smaller benefit when the entire guide is needed.
It does not establish Android startup speed, PSS, native memory, or real-feed parity.

## Method and reproducibility

- Command: `python3 scripts/epg-profile/run.py`.
- [Harness and metric definitions](../../scripts/epg-profile/README.md).
- Baseline importer: commit `0c0bcf9db9c4192ada0d37480d8a01d0618cadae`, renamed and
  compiled only into the benchmark output. Current importer: the uncommitted
  streaming implementation based on the same HEAD. Source hashes are in metadata
  and were checked against the working tree after the run.
- Local Linux x86_64 host; benchmark JDK `21.0.10+7-b1163.110`; identical
  `-Xms64m -Xmx512m`, UTC/root locale, and shared model/formatter classes.
- Two fresh JVM forks per algorithm/scenario, two excluded warmups per fork,
  three measured imports per fork: six samples per cell, 108 measured samples total.
  Importer order alternates across forks. GC is requested before each import.
- Network downloads are excluded. Gzip-refresh includes legacy decompression to
  disk in each measured import; gzip-cache gives the legacy importer a pre-expanded
  file. Streaming always decompresses twice and never creates that sidecar.
- All 108 samples passed selected-count and output-fingerprint checks. Fingerprints
  cover titles, start/end instants, displayed times, dates, and logo.

The small fixture has 1,000 programmes/100 selected; large sparse has
100,000/1,000; large dense has 100,000/100,000. All use direct channel IDs,
late metadata, Unicode titles and ignored descriptions. Separate regression tests
cover aliases and malformed input; the benchmark is not a fuzzy-matching workload.

## Results

Times are median milliseconds with observed min–max in parentheses. Allocation
is median MiB allocated on the import thread. Peak is median of the sampled whole
JVM heap peaks in MiB, **not** exact live-object size, Android PSS or native memory.

| Fixture | Input/cache | Legacy ms (range) | Streaming ms (range) | Allocated MiB legacy → streaming | Sampled peak heap MiB legacy → streaming |
| --- | --- | --- | --- | --- | --- |
| small | xml | 63.5 (49.0–76.6) | 60.0 (42.2–71.3) | 6.9 → 0.8 | 16.4 → 9.3 |
| small | gzip-refresh | 78.9 (57.3–103.2) | 46.7 (24.2–72.2) | 6.8 → 0.8 | 15.5 → 10.2 |
| small | gzip-cache | 54.1 (38.5–87.2) | 42.4 (32.0–54.7) | 6.8 → 0.8 | 15.5 → 9.3 |
| large-sparse | xml | 1056.1 (860.5–1233.6) | 638.0 (557.5–732.3) | 591.2 → 36.8 | 140.6 → 46.3 |
| large-sparse | gzip-refresh | 1112.5 (981.2–1298.4) | 553.5 (521.5–583.8) | 591.5 → 36.5 | 131.5 → 45.7 |
| large-sparse | gzip-cache | 1607.5 (1309.2–2294.8) | 761.4 (581.3–973.0) | 593.2 → 36.5 | 135.6 → 45.4 |
| large-dense | xml | 1556.0 (1333.5–2044.2) | 1272.3 (966.4–2138.5) | 821.0 → 275.5 | 160.4 → 124.9 |
| large-dense | gzip-refresh | 1533.9 (1373.8–2075.7) | 1044.4 (977.3–1163.9) | 817.5 → 275.2 | 171.1 → 122.5 |
| large-dense | gzip-cache | 1404.6 (1121.5–1500.6) | 997.4 (948.5–1106.4) | 827.4 → 275.2 | 172.0 → 125.4 |

For sparse plain XML, allocation fell about 94% and sampled peak heap about 67%.
For dense plain XML, allocation fell about 66%, while sampled peak heap fell about
22%: the final 100,000-programme guide still has to exist. These are scenario-specific
host measurements, not forecasts for all users or low-memory Android devices.

The timing ranges overlap in some cases, particularly small and dense XML imports.
Host scheduling, JVM warmup and GC introduce visible variation. Do not interpret
cross-scenario differences (such as a cached case taking longer than refresh) as
proof that caching itself is slower. The allocation reduction is the clearer signal.

The millisecond heap sampler can miss short peaks and adds a small amount of
measurement work. GC-assisted runs do not reproduce continuous Android usage.
An exact retention analysis and Android measurements remain outstanding.

## DTD compatibility

External DTD declarations (e.g. `<!DOCTYPE tv SYSTEM "xmltv.dtd">`) are now
accepted. The parser does not fetch the referenced DTD file: the
`load-external-dtd` feature is disabled where available (JVM/Xerces), and
`resolveEntity` returns an empty InputSource for the DTD system identifier.
Android's ExpatReader does not support that feature but does not auto-load
external DTDs by default, so the guard is safe on both platforms.

Pure internal DTD declarations (e.g. `<!DOCTYPE tv [<!ENTITY x 'expanded'>]>`)
are still rejected in `startDTD` when neither `systemId` nor `publicId` is
present. Mixed declarations (external system ID with an internal subset) pass
`startDTD`, but `startEntity` rejects any entity reference that is not one of
the five predefined XML entities (`amp`, `lt`, `gt`, `quot`, `apos`). This
blocks both internal entity expansion and XXE-style external entity references
even when the parser suppresses `resolveEntity` callbacks under
`load-external-dtd=false`.

Android's published ExpatReader supports the lexical handler property but not a
declaration-handler property; its lexical handler also does not report
start/end entity callbacks. The `startEntity` guard is therefore the portable
fallback when declaration-handler callbacks are unavailable. See the
[AOSP ExpatReader implementation](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/org/apache/harmony/xml/ExpatReader.java).

Before release, test actual feeds that carry external DTD declarations to
confirm the empty-InputSource path does not cause the platform parser to emit
spurious validation errors.

`EpgXmlRuntimeInstrumentedTest` adds UTF-8/UTF-16, CDATA/predefined-entity,
DTD-guard and gzip-expansion-limit checks for an authorized device run.
Compilation alone does not verify Android runtime behavior.

Local validation passed: 16 EPG-core tests, 10 focused importer/formatter JVM tests,
and compilation of mobile and leanback ARM64 verification instrumentation code.
No device was accessed and no package was installed.

## Evidence files

Raw results are in the ignored local build directory
`app/build/reports/epg-profile/AUTHORIZED_DEVICE_SERIAL.760411Z/`: `metadata.json`,
`samples.json`, `summary.json`, generated fixtures, and worker/build logs.
This document preserves the aggregated results if build outputs are later cleaned.
The old single-cold-sample figures in the task log are historical and superseded
by this repeated, complete-import comparison.
