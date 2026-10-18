# Search host profiling

Run from the repository root: `python3 scripts/search-profile/run.py`.
Requires the configured JDK, offline Gradle dependencies, and Python 3.
Reports include source hashes, baseline revision, raw samples and summary ranges.
Generated classes and full logs remain under the module's `build/reports/search-profile`.
The harness compares legacy and current implementations and rejects output mismatches.

These are synthetic JVM measurements, not Android playback, process RSS, or device
CPU results. See [the task](../../docs/optimization/native-optimization-task.md)
and retained measurement JSON for interpretation. Do not run concurrent builds or
benchmarks when collecting comparison samples.
