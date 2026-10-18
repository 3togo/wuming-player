# APP-012–APP-015 implementation and validation

This record covers the four follow-ups to [the indexed app review](app-improvements-2026-09-25.md).
Changes are in the working tree. Device authorization is limited to `7e34269d`.

## APP-012 — UI-safe performance statistics

UI repository reads now consult memory in every readiness state, including cache
misses and list reads. DAO hydration runs on IO. The tracker publishes a live record
immediately and adds the stored baseline to observations collected while loading,
using shared `source-core` aggregate logic. It cannot persist an unhydrated record
when the baseline read fails. Identity checks reject late hydration after clearing;
force-save now dispatches to IO as well. The write-only persistence port remains intact.

Regression coverage includes ready/unready cache hits and misses with forbidden DAO
callbacks, background cache fill, stored-plus-pending counters and speed averages,
failed hydration without overwriting storage, and clear during hydration.

## APP-013 — Local artwork and visible error fallback

`ImgUtil` preserves resource/content/file/data models before generic URL conversion;
assets become local Android asset models. HTTP(S) alone use GlideUrl/header parsing.
The failed-artwork listener now unwraps Glide's ViewTarget to find the ImageView and
show its text fallback. It previously tested the wrapper as though it were a view.

The Android test decodes a real packaged resource, test-owned PNG through file and
FileProvider content URIs, and a data URI. It verifies HTTP headers and the actual
visible ImageView fallback for missing artwork. Temporary files and Glide targets
are cleaned up.

## APP-014 — Playback diagnostics and bounded observation

`playback-core` now owns monotonic playback diagnostic aggregation. It separates
buffer starvation hints, buffered decoder/render stalls and player generations;
counts rebuffering and rendered frames; and rejects position jumps as watched time.
A sliding live window is not treated as a player restart.

Android instrumentation captures player/decoder/buffer samples, load-error class
categories and discontinuity reasons. It checkpoints JSON during startup and each
observation, then writes a final result even on assertion failure. No media URLs,
headers, credentials or exception messages enter this diagnostic artifact. The
host saves it beside exact instrumentation results; missing telemetry cannot count
as a successful diagnostic group. `--live-seconds 15..300` extends the explicit host
deadline without automatic retries or changing playback defaults.

A loopback HLS fixture deliberately withholds a segment after video starts, checks
transport-starvation diagnostics, then replaces the media item and checks a distinct
reset. Shared deterministic tests also cover buffered decoder stalls, seeks and live
window shifts. Real-provider diagnosis remains evidence-based; one successful run
does not retroactively resolve an earlier intermittent failure.

## APP-015 — Disposable persistence and recovery smoke

`--persistence` adds eight methods to production smoke (19 total): portable restore
preserves cloud ownership/audit; portable-table round trip; failed restore rolls
back without applying preferences; all exported schemas 35–45 migrate to 46;
cloud ownership/indexes survive upgrades; recovery export; artwork; and controlled
playback diagnostics. No cloud-account cleanup or actual-library restore is run.

Migrations use unique test-owned database names and close/delete their fixtures.
Backup tests use in-memory Room databases and unique temporary preference keys.
The recovery screen delegates raw export to shared `DatabaseRecoveryArchive`.
A verification-only, non-exported activity binds the real screen to a private cache
fixture. The test supplies the SAF result, exercises Export and Retry, checks that
unsupported originals remain byte-identical, and reopens the exported database with
its real WAL/SHM to recover both committed rows. It does not automate an OEM document
picker or certify physical-TV focus.

Reproduce on an authorized device:

```sh
python3 scripts/run-smoke-tests.py --tier production --mode mobile \
  --abi arm64_v8a --serial AUTHORIZED_SERIAL --offline --persistence
python3 scripts/run-smoke-tests.py --tier production --mode mobile \
  --abi arm64_v8a --serial AUTHORIZED_SERIAL --offline \
  --live-channel 'Pet Club TV' --live-seconds 120
```

## Follow-up attempt ledger

| Attempt | Result | Evidence |
| --- | --- | --- |
| Persistence 1 | 16 methods passed; recovery fixture used execSQL for a result-returning PRAGMA; corrected to rawQuery | [Summary](../../PUBLIC_PREPARATION.md) |
| Persistence 2 | 17 methods passed, including recovery export; artwork failure exposed file conversion and incorrect fallback-test expectations; fixed production routing/listener and tested the visible fallback | [Summary](../../PUBLIC_PREPARATION.md) |
| Persistence 3 | 18 methods passed, including artwork; controlled playback failed before rendering because the Qualcomm decoder rejected the 160×96 fixture. Replaced only this new test's media with synthetic 640×360 H.264 | [Summary](../../PUBLIC_PREPARATION.md), [diagnostic](../../PUBLIC_PREPARATION.md) |
| Persistence 4 | 18 methods passed; controlled stream rendered and stalled with 49ms buffered, exposing an exact-zero assumption. Diagnostic starvation now allows a <=250ms edge remainder, covered by a unit regression | [Summary](../../PUBLIC_PREPARATION.md), [diagnostic](../../PUBLIC_PREPARATION.md) |
| Persistence 5 | 18 methods passed; controlled starvation/reset reached final diagnostic export, which exposed Android JSONObject requiring string keys. Changed numeric discontinuity reason keys to strings | [Summary](../../PUBLIC_PREPARATION.md) |
| Persistence 6 | **19/19 passed**, plus 4/4 launch checks. Controlled fixture rendered 640×360 video, detected transport starvation and one media reset, and saved the final diagnostic successfully | [Summary](../../PUBLIC_PREPARATION.md), [diagnostic](../../PUBLIC_PREPARATION.md) |
| Live observation 1 | **11/12 passed; provider test failed after 27.182s**, before its requested 120s window completed. Failure retained, no automatic retry | [Summary](../../PUBLIC_PREPARATION.md), [diagnostic](../../PUBLIC_PREPARATION.md) |

## APP-014 measured provider result

Pet Club TV rendered 848×480 video: first-frame startup 5.890s, 160 rendered
frames and 5.107s of observed progress. It then buffered with only 195ms remaining,
recorded a 13.334s stall, and surfaced `ERROR_CODE_IO_UNSPECIFIED`. There was no
media-generation reset before the failure. The latest ExoPlayer error at
2026-09-24T19:22:13.807Z identifies
`HlsPlaylistTracker$PlaylistStuckException`; the
[sanitized exception evidence](../../PUBLIC_PREPARATION.md)
contains only its class and timestamp/process identifiers.

This run failed because playlist progress stopped as observed by the HLS tracker.
The evidence does not distinguish an upstream playlist problem from caching or
network behavior, and does not establish the cause of the earlier SMOKE-002 stall.
The diagnostic improvement is implemented and fixture-tested; multi-minute real
provider stability is **not validated**. Playback buffer/retry defaults were not
changed to conceal the failure.

Wi-Fi was restored to `0`, airplane mode stayed `1`, and stay-awake returned to
`15`; see the [settings record](../../PUBLIC_PREPARATION.md).
The installed application remains the mobile `無名(testing)` build.

## Final offline acceptance

The full `unitAcceptanceBaseline`, all three `assembleCriticalTests` APK selections,
and mobile ARM64 / Leanback ARM64 / Leanback ARM32 verification lint passed in one
sequential build invocation (3m52s). This includes the three architecture-boundary
gates, 100 Python tests, Android variant unit tests, desktop tests and shared-core
tests. Available JUnit report counts and the exact command are in the
[validation summary](../../PUBLIC_PREPARATION.md), with the
[complete successful build log](../../PUBLIC_PREPARATION.md).
An earlier invocation had 24 class-loading failures while builds overlapped;
it is not counted as passing. The complete invocation was rerun without that overlap.

The Leanback static remote/label check and `git diff --check` passed. No physical-TV
certification, OEM document-picker interaction or signed-release device validation
is claimed. APP-012–APP-015 implementation is complete in the working tree; the
provider failure above remains a separately recorded runtime limitation.

## Subsequent investigation

[APP-016/APP-017](adaptive-cache-2026-09-25.md) subsequently found and fixed runtime
manifest caching, reproduced its stale-read behavior with a local fixture, and
completed a new 120-second provider observation successfully. The failed APP-014
run above remains historical evidence; its original artifact has not been replaced.
