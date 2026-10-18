# Casting staging host benchmark

Run from the repository with the cached Android/Gradle toolchain:

```sh
python3 scripts/cast-profile/run.py  # fixed two-worker vs fair shared-worker
python3 scripts/cast-profile/run.py --algorithms legacy bounded fair
# Harness smoke check:
python3 scripts/cast-profile/run.py --quick --forks 1 --samples 1
```

The opt-in Gradle init script discovers the mobile ARM64 verification JVM test
runtime and Java installation. No APK is installed and no device is accessed.
The harness is not packaged into the app. Reports go into a unique directory under
`app/build/reports/cast-profile/`; `metadata.json` records source hashes, Git state,
host, Java runtime/classpath, configuration, and baseline revision. Raw samples,
per-process logs, generated baseline source, and median/min/max summaries remain
alongside it. Build output is captured in `build.log`.

## Comparison

The default `bounded` mode uses the preserved fixed-worker source in
`fixtures/FixedCastSegmentDownloader.java`, renamed only. The runner verifies its
original source hash against the first staging measurement. `fair` uses the current
production downloader, which lends all four workers to a lone asset and shares
assignments at segment boundaries. The fixed fixture is benchmark-only and never
packaged into the app.

For optional `legacy` comparisons, the runner extracts the actual `downloadSegments` and `downloadSegmentBytes`
methods from commit `0c0bcf9db9c4192ada0d37480d8a01d0618cadae`. Only dependencies are
adapted: aggregate metadata, destination directory, and a synthetic upstream call.
A harness lock models the original global lock around staging. The fixed side calls the preserved downloader with two workers per request; the
fair side calls production with the default four-worker ceiling.
This isolates staging and the old serialization cost; it does **not** execute
`CastRemuxCache`, HTTP response serving, playlist probes, or Android remuxing.

Both algorithms consume identical generated streams through OkHttp/Okio response
objects, without allocating fixture-sized input arrays in the benchmark JVM.
Each read supplies at most 8 KiB. Each segment has a distinct constant byte pattern.
The runner checks complete file length and SHA-256 against its own independent
fixture digest, then checks that staging files and active responses are gone.
The delay cases sleep once per segment; they model latency per request, not
network bandwidth, sockets, congestion, or a remote server.

Default cases:

- 8 MiB: 32 segments of 256 KiB, one request, no delay.
- 32 MiB: 128 segments of 256 KiB, one request, no delay.
- 8 MiB with 20 ms delay per segment, one request.
- Two simultaneous 8 MiB requests, each with 20 ms delay per segment.

Each case/algorithm has two fresh JVM forks, one excluded warmup per fork and three
measured samples. Fork order alternates. Each process uses `-Xms64m -Xmx512m` and
common-pool parallelism four. The legacy caller can also participate in parallel
streams, so observed legacy active calls can exceed four. Both bounded downloaders have four workers globally. The fixed baseline limits
each request to two; the fair implementation assigns available workers to the
asset with fewer active segments, rotating ties. Those differing concurrency policies
are intentional parts of the comparison; latency is not an equal-worker algorithm
microbenchmark. The host is not isolated and short timings can be noisy.

## Metrics and limits

- Elapsed wall time includes staging, caller dispatch, and waiting for all outputs;
  excludes digest verification, file deletion, explicit pre-sample GC and remuxing.
- Allocation is the sum of live-thread allocation-counter deltas. Persistent
  downloader, common-pool, and caller threads are included, as are synthetic
  transport, instrumentation and sampler allocations. It is not application-only
  allocation; unsupported counters fail the run. Threads remain alive during each
  sample; this method is unsuitable for workloads that retire threads mid-sample.
- Heap is absolute JVM used heap sampled approximately every millisecond, plus an
  end-of-operation sample. This can miss short peaks and excludes native memory,
  direct buffers, process RSS, operating-system page cache, and media components.
- Disk is the sum of logical staging-file lengths, sampled and checked at completion.
  It is not physical allocated blocks or a full-pipeline disk high-water mark. With
  two requests both final TS outputs are retained until measurement completes;
  neither side simulates deleting the first TS after a real remux. Both sides thus
  retain 16 MiB in that fixture; this does not prove equal production disk use.
- Active response count measures fixture concurrency, not live network connections.
- Byte patterns are not valid TS media. No media quality, compatibility, playback
  startup, cancellation latency, or end-to-end improvement is established here.
  Functional cancellation/coalescing/eviction coverage is in the existing JVM tests.

Read the initial results in `docs/optimization/cast-staging-measurements.md` and
the scheduler comparison in `docs/optimization/cast-fair-scheduling.md` before
using these numbers to choose a native conversion or change concurrency limits.

## Direct PC → internal TV DLNA probe

`pc_dlna_probe.py` uses Python's standard library and bypasses the phone app.
It selects **only the exact supplied UDN**, reads the device/service XML and
GetProtocolInfo, and optionally serves a local MP4 to that renderer. Its temporary
HTTP server accepts only the selected TV's IP and a random per-run media URL.
No APK is installed or launched. Playback tests replace current playback and
send Stop when the bounded run ends. Inspection without `--cast` is read-only.

```sh
python3 scripts/cast-profile/pc_dlna_probe.py \
  --interface 192.0.2.10 \
  --udn 00000000-0000-0000-0000-000000000001 \
  --cast libraries/playback-core/src/main/resources/renderer-probes/avc.mp4 \
  --seconds 30
```

Use the PC's current LAN IPv4 for `--interface`. Reports contain device XML,
JSON events, and an HTML playhead chart with HTTP requests and SOAP responses.
MP4 and the bundled DASH fixture are supported. For DASH, only sibling
`init-stream*.m4s` and `chunk-stream*.m4s` files are served; arbitrary external
media URLs are not proxied by this diagnostic. A byte count measures data written
by the PC; it does not independently prove decoding or sustained network speed.
Advertised wildcards are not evidence that every format works.

Measured on 2026-09-28: Coocaa `酷开电视P3D_D5A-DLNA`, 192.0.2.10,
Skyworth Media Renderer 1.0, DLNA server `Skyworth/1.0.4.18`. The direct device
XML exposes AVTransport, ConnectionManager, and RenderingControl. GetProtocolInfo
returns a broad MIME list, including video/mp4. The app's AVTransport-only
`getExclusiveServiceTypes()` filter hid the other services; their absence from
its registry was not a TV limitation.

The initial 20-second MP4 run served a 399,210-byte file (an initial partial
connection followed by `Range: bytes=0-`). The TV reported advancing position
through 11 seconds before the test sent Stop. The small sample and PC socket
write times are not a sustained-throughput benchmark. Raw evidence is under
`app/build/reports/pc-dlna/` (build artifacts, not committed).
