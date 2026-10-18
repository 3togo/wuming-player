# Danmaku input-buffer host profiling

Run `python3 scripts/danmaku-profile/run.py` from the repository root with the
configured JDK, cached offline Gradle dependencies and Python 3. Do not run other
builds/benchmarks concurrently. Reports and generated legacy classes go under
`app/build/reports/danmaku-profile`; source hashes and baseline revision are recorded.

Compares actual legacy/current bounded input reads at 8 KiB, 1 MiB + 1 byte and
8 MiB. Checks every output byte; measures caller-thread allocation and elapsed time.
It does not run the Android parser, network, overlay or renderer. See
[results and limitations](../../docs/optimization/danmaku-buffer-measurements.md).

The legacy source is pinned to `0c0bcf9db9c4192ada0d37480d8a01d0618cadae`,
so committing the optimization does not silently replace the baseline with it.
