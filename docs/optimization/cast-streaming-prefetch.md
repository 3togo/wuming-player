# Bounded HLS cast streaming prefetch

The aggregated TS compatibility response formerly opened and drained each HLS
segment sequentially. Every segment boundary exposed another network wait. Its
known-length path also skipped failed segments and padded missing bytes, hiding
corruption behind a successful HTTP body length.

`OrderedPrefetchStream` in playback-core now owns ordered look-ahead, bounded
buffers and cancellation. `CastSegmentStreams` is the Android HTTP port, replacing
both old segment stream implementations. The same sender relay serves DLNA and
paired desktop clients; no Android policy was copied into desktop code.

- Up to three segments download concurrently; first bytes are delivered without
  waiting for a whole segment. Only three segment requests are scheduled ahead.
- Each accelerated stream has sixteen reusable 64 KiB arrays (1 MiB payload
  capacity), independent of playlist/segment size. This excludes HTTP buffers,
  thread stacks, consumer buffers and object overhead.
- At most two streams acquire the shared prefetch budget (six worker threads).
  Additional streams fetch sequentially on their request thread. Closed workers
  retain admission until blocked upstream work actually exits.
- Seeking requests only the relevant segment slices. Ignored ranges fall back
  to exact prefix skipping; wrong Content-Range, changed lengths, premature EOF
  and HTTP failures abort instead of supplying substituted media bytes.
- Closing a renderer response cancels outstanding HTTP calls and queued work.
  Source credentials are preserved; the requested slice overrides stale Range
  headers. There is no full-episode buffer or temporary staging file.

The existing small-file TS-to-MP4 remux/staging path, provider quality selection,
normal renderer-managed HLS, and progressive range policies remain as before.
Whole-playlist size probes still precede the seekable compatibility response.

## Validation

149 playback-core tests and the focused Android casting/provider suites passed.
Android production compilation, desktop Kotlin compilation, Java retention and
all three shared-library boundary checks passed. The focused Android compilation
excluded the same three unrelated upstream tests still blocked by constructor/API
changes: BaiduOpenDriveProviderTest, OpenDriveFailuresTest and
RecommendationEngineTest. No device was accessed.

Regression tests cover out-of-order completion, first-byte delivery before a
segment finishes, bounded read-ahead, interrupted producers, cancellation of
blocked opens, retained admission for abandoned workers, sequential saturation
fallback, exact HTTP ranges, authentication, ignored ranges and truncated bodies.

## Synthetic measurement

The host harness is `scripts/cast-profile/CastStreamingBenchmark.java`. Run it
with the compiled playback-core and Kotlin runtime on the Java classpath:

```sh
java --class-path "<playback-core runtime classpath>" scripts/cast-profile/CastStreamingBenchmark.java
```

Both modes use the same new stream engine: budget zero selects its sequential
fallback and budget one enables prefetch. Eighteen generated 256 KiB segments
have a 40 ms open delay and a 2 ms delay per bulk read. One warmup pair is excluded;
three pairs alternate execution order. Every transfer's length and SHA-256 are
verified, as are request-count limits and response closure.

Median elapsed time was **874.862 ms sequential** and **293.694 ms prefetched**
(2.98x in this latency model). First-byte samples stayed around 42–44 ms.
This isolates overlapped segment waits, not a production-before/after benchmark.
It does not measure CDN throughput, socket behavior, playlist probes, remuxing,
Android memory/CPU, decoding or TV playback. Raw samples and source hashes are in
[the measurement record](../../PUBLIC_PREPARATION.md).
