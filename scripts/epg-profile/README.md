# EPG import host profiling

Run offline from the repository, without connecting to a device:

```sh
python3 scripts/epg-profile/run.py
```

For a harness smoke check:

```sh
python3 scripts/epg-profile/run.py --quick --forks 1 --warmups 1 --samples 1
```

The opt-in Gradle init script builds the mobile verification test runtime and
exports its classpath/JDK location. No production Gradle task or dependency is
added. The driver compiles the benchmark and a renamed, **test-only** baseline
importer from commit `0c0bcf9db` into an ignored build directory. That commit must
exist locally; no Git fetch, download, APK installation, or device operation occurs.
The baseline and current importer use the same current model classes and date
formatters, so the timestamp correction is held constant.

## Comparison

- Small: 1,000 programmes, 100 selected.
- Large sparse: 100,000 programmes, 1,000 selected.
- Large dense: 100,000 programmes, all selected.
- Plain XML; gzip with expanded-cache refresh inside the measurement; gzip with
  the legacy expanded sidecar already cached outside the measurement.
- Two independent JVM forks per algorithm/case, two excluded warmups and three
  measured samples per fork by default. Algorithm order alternates between forks.
- The actual old `readXml` method and current `readXml` method are called, including
  channel matching, time conversion, guide construction, and publication. Gzip
  expansion for the baseline is performed by the harness to avoid Android-only
  cache-directory access. Network downloads are excluded for both algorithms.
- Every fork uses `-Xms64m -Xmx512m`, UTC and the root locale, with a GC requested
  before each import. These are warm-JIT, OS-page-cache-friendly host measurements,
  not cold app starts. File-cache warmth is not forcibly controlled.
- Selected counts and SHA-256 fingerprints of titles, times, dates, and logo must
  match across algorithms, formats, samples, and forks for each fixture.

The fixtures use stable direct channel IDs, late metadata and Unicode titles.
They do not model network latency, fuzzy matching across thousands of channels,
all real-provider XML variants, or Android heap/GC behavior.

## Metrics and outputs

Reports and generated fixtures are under `app/build/reports/epg-profile/<UTC-run>/`:

- `metadata.json`: baseline revision, working-tree status, source hashes, host/JDK,
  and arguments.
- `samples.json`: raw per-import results, including output fingerprints.
- `summary.json`: median/min/max per case and algorithm; failed or mismatched runs
  do not produce a successful summary.
- Build and worker logs; generated baseline source and classes.

`allocatedBytes` uses HotSpot's per-thread allocation counter where supported;
it measures allocation churn on the importing thread, not peak live memory.
`sampledPeakHeapBytes` samples the **whole JVM heap** approximately every millisecond
and can miss short peaks. It includes JVM/library state, and is not Android PSS or
native memory. `postGcHeapBytes` keeps the final guide strongly reachable, but also
includes warmed library/class state. Neither heap metric is a retained-size analysis.
Do not subtract these figures and present the result as an exact app-memory saving.

Treat medians/ranges as local evidence, not a promised Android speedup. Preserve
the raw report when publishing results. Generated fixtures can be removed with
normal build-output cleanup; never point the output directory at private guide data.

`EpgXmlRuntimeInstrumentedTest` covers the platform SAX guard, Unicode, and gzip
limits for a future authorized Android run. Compiling that runner is not a device test.
