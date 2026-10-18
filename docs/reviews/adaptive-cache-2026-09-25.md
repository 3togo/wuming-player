# APP-016–APP-017: adaptive caching and playback failure evidence

The [stable findings index](app-improvements-2026-09-25.md#app-016) records the
source defects and implementation. Changes remain uncommitted in the working tree.

## APP-016 — Runtime manifest freshness

`AdaptiveSourceFactory` is an Android Media3 port. HLS selects its manifest factory
by data type, not URL suffix, so extensionless and signed manifests follow the same
path. DASH and Smooth Streaming use separate manifest and chunk factories. Existing
DRM and load-error configuration still applies. Platform-neutral transport eligibility
and desktop playback policy are unchanged; desktop does not use Media3's disk cache.

The temporary runtime cache remains available for segments. Existing stale manifest
entries are bypassed without deleting application data. Explicit offline downloads
still supply manifests from their separate read-only cache. Adaptive items with
external subtitles or clipping retain `DefaultMediaSourceFactory` and bypass runtime
caching for the entire item; this preserves the existing subtitle/clipping wrappers.

The Android regression uses private, unique cache directories and an isolated SQLite
index, closes both caches and deletes its own files. It proves old cached playlist
reads stay unchanged, fresh manifest reads advance, segment reads reuse bytes,
explicit downloaded manifests work after the HTTP server stops, and sidecar
subtitles still create a merged source. It does not need an external provider.

## APP-017 — Failure causes without private payloads

`FailureDiagnostics` in playback-core emits at most 16 exception class names with
identity-based cycle detection. The Android probe captures this in `onPlayerError`,
so failures outside the media load callback are retained as `playerErrorCauses`.
No exception messages, request headers or URLs are serialized. Instrumentation
checks the JSON with synthetic private strings, then restores the actual run report.
The raw URL/header debug line in the main media-source construction path is removed.

During the provider repeat, a first frame rendered before the player became ready.
The previous aggregator counted that startup wait in rebuffer duration despite a
zero rebuffer count. `PlaybackDiagnostics` now starts interruption accounting only
after actual playing begins, and resets that eligibility on media-generation changes.
A core regression covers pre-roll frames, genuine later buffering and reset startup.

## Reproduction

`--persistence` now runs 20 methods on mobile (the previous eight extra methods plus
one adaptive-cache regression and the 11-method production suite).

```sh
python3 scripts/run-smoke-tests.py --tier production --mode mobile \
  --abi arm64_v8a --serial AUTHORIZED_SERIAL --offline --persistence
python3 scripts/run-smoke-tests.py --tier production --mode mobile \
  --abi arm64_v8a --serial AUTHORIZED_SERIAL --offline \
  --live-channel 'Pet Club TV' --live-seconds 120
```

## Validation ledger

Results are appended below as runs complete. Prior failed provider runs remain in
[APP-014 evidence](followups-2026-09-25.md#app-014-measured-provider-result).

| Run | Result | Evidence |
| --- | --- | --- |
| Disposable persistence/cache smoke | **20/20 methods and 4/4 launch checks passed** on `7e34269d` (Android 14, ARM64 phone); power restored | [Summary](../../PUBLIC_PREPARATION.md), [cache regression](../../PUBLIC_PREPARATION.md) |
| Real provider, requested 120-second observation | **12/12 methods and 4/4 launch checks passed**; Pet Club TV rendered 1920×1080, 3,034 measured frames, 100.535s observed progress, zero playback errors/media resets; existing cache was retained | [Summary](../../PUBLIC_PREPARATION.md), [diagnostics](../../PUBLIC_PREPARATION.md), [settings restoration](../../PUBLIC_PREPARATION.md) |
| Main implementation acceptance | Full offline acceptance, three critical test APK builds and mobile ARM64 / Leanback ARM64 / Leanback ARM32 lint passed in 3m20s | [Build log](../../PUBLIC_PREPARATION.md) |
| Final startup-counter revision | Full offline baseline and mobile app/test APK builds passed again (1m3s), including 5 diagnostic accounting and 2 private-cause unit tests | [Final build log](../../PUBLIC_PREPARATION.md), [validation summary](../../PUBLIC_PREPARATION.md) |
| Final installed revision smoke | **20/20 methods and 4/4 launch checks passed**, with empty cleanup errors and original power value restored | [Summary](../../PUBLIC_PREPARATION.md), [controlled-playback diagnostic](../../PUBLIC_PREPARATION.md) |

The provider run used the cache-routing fix and new failure causes, before the final
startup-accounting correction. Its first-frame startup was 20.113s; the raw report's
4.877s buffering duration and 4.287s maximum stall include pre-play startup despite
zero rebuffer events. These original values are retained. The later counter fix is
validated separately; the live result is not presented as a run of that final counter
revision. This bounded observation supports the runtime-cache diagnosis without
claiming every provider or future network session will remain stable.

The Android loopback regression exercised HLS routing; DASH/Smooth Streaming factory
wiring compiled and passed offline checks but did not receive separate live-provider
runs. Physical TV hardware and signed-release builds were not tested in this turn.
Device Wi-Fi and power settings were restored, with airplane mode unchanged.

APP-016 and APP-017 are implemented and validated within these boundaries. The final
mobile verification app remains installed. `git diff --check` and the Leanback static
remote/label check passed. No commit or release publication was performed.
