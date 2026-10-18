# Android playback ownership and 8K memory plan

## Scope and sequence

The shared Android playback implementation serves mobile, Leanback and Leanback Lite.
The existing activity, fragment, adapter, parser, QuickJS and bounded-cache refactors
remain the foundation. This pass audits the remaining service → player → overlay →
request ownership chain and verifies high-bitrate playback on device `7e34269d`.

1. Bound encoded sample allocation by the actual VM heap, preserving headroom for
   decoder/native surfaces, HTTP range windows, images and provider runtimes.
2. Invalidate asynchronous playback work on source replacement, stop and release.
   Coalesce decoder retries; ensure old track restores cannot mutate a new session,
   even when the media key is reused.
3. Give each danmaku load one cancellable request and delivery scope. Detach the
   screen overlay from the service without releasing background media playback.
4. Remove redundant browse-start resets and the unused generic loader interface;
   use the shared task scope and shared activity binding for both screen variants.
   Consolidate URL/file launch helpers and explicitly dispatch inherited static
   overloads to the flavor activity; static hiding cannot override a throwing base stub.
5. Run JVM regressions, compile all Android modes/ABIs, run device ownership/smoke
   tests, then play a known local 8K fixture through the real Media3 player and
   SurfaceView. Record startup, selected decoder, dropped frames and memory over
   repeated create/seek/release cycles.

## Decisions

- `SmoothnessLoadControl` limits its encoded-sample target to one fifth of the VM
  heap, between 8 and 96 MiB, aligned to Media3 allocation segments. Both local and
  network playback prioritize this byte target. Starting/resuming is allowed at
  the byte target, preventing a high-bitrate stream from waiting forever for an
  unreachable time watermark. The progressive adaptive watermarks still stop
  earlier when sufficient media is buffered.
- Remove the fixed 30-second back-buffer and keyframe retention. At 100 Mbps,
  thirty seconds alone corresponds to roughly 358 MiB of encoded video. Backward
  seeks re-read the source. The byte target is not a hard RSS or total-memory cap:
  sample/load granularity, native codecs, surfaces and transport prefetch are separate.
- Keep the existing hardware-first Media3 renderer, adaptive track selection and
  SurfaceView outputs. Do not force 8K on unsupported hardware, force software
  video decoding, globally increase download concurrency, enable decoder prewarming,
  or apply device-specific codec tweaks without measurements. Media3 already owns
  asynchronous codec selection and its platform workarounds.
- Decoder recovery and saved-track restoration belong to a playback generation.
  Scope closure removes queued work at release; background track queries hold only
  a weak manager reference. An old screen can clear only its own overlay.
- URL/file launch overloads now reach the concrete flavor activity. Mobile and TV
  use the same push/file parser, eliminating duplicated URI parsing and preventing
  the link dialog, file picker and push extractor from reaching an unsupported
  base implementation. Empty push input is ignored.
- Danmaku HTTP responses are closed after copying at most 8 MiB into independent
  parser data; oversized feeds and malformed/unsupported URIs are rejected through
  the checked failure path. Cancellation covers the call, worker, source and queued
  delivery. Parsing releases the data source; overlay release clears callbacks,
  player listeners and sync. Sync samples Media3 on main and publishes snapshots to
  the danmaku render thread.

References checked against the installed Media3 1.10.1 bytecode:
[DefaultLoadControl](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl),
[Surface types](https://developer.android.com/media/media3/ui/surface).

## Validation

Completed on 2026-09-05:

| Gate | Result |
| --- | --- |
| Mobile / Leanback / Leanback Lite JVM suites | 504 passed each; no failures or skips |
| QuickJS resource suite | 5 passed |
| Java compilation | All three modes, ARM64 and ARMv7 |
| Release lint and R8 | Mobile and Leanback ARM64 passed |
| Device smoke and resource suite | 34 passed on `7e34269d` |
| 8K hardware playback | Passed: HEVC Main, 7680×4320, 30 fps, 93.2 Mbps |
| Selected decoder | `OMX.qcom.video.decoder.hevc`; device advertises 8K30 support |
| Hardware duration | 155.821 s total, including a 90 s first cycle and nine shorter cycles |
| Rendered / dropped frames | 4,419 / 0; maximum consecutive drops 0 |
| First frame | 319–430 ms across ten sessions |
| Peak sampled process PSS | 229.13 MiB (includes test host); VM limit 512 MiB |
| Post-release Java heap | 129.54 MiB after warm-up; 109.03–110.55 MiB in the remaining cycles |
| Post-release native heap | 18.11–18.42 MiB |
| URL/file launch regression | Passed on final mobile build; inherited URL/file methods reach the concrete flavor activity |
| Real video-screen check | Passed on final build: normal shared-file intent → VideoActivity → PlaybackService, real 8K first frame; back/background recovery and forced-process cold restart |

Every hardware cycle verifies the real decoded size, advancing/playing state,
hardware decoder use, bounded decoder initialization, seeking and idempotent release.
The launch-helper change and normal video-screen check were completed before the
final 155.821-second player rerun; the same final build rendered 8K in both paths.

The stable heap trend is evidence against accumulation in these exercised paths;
it is not a whole-app retained-object/heap-dump proof. Android codec/surface memory
may also be attributed outside the application process.

The initial device run exposed three harness assumptions: querying an allocator
past Media3's `onReleased`, and two settings assertions before a fragment's OPEN
transition had reached RESUMED. The corrected checks still assert byte-budget
behavior, labels, clickability and settings persistence; they wait for the actual
lifecycle boundary. The subsequent 33-test gate passed.

Reproduce (requires an authorized ARM device, Android SDK, FFmpeg and Gradle):

```sh
./gradlew :app:verifyAndroidMemory :app:assembleMobileArm64_v8aDebug --console=plain
scripts/test-android-playback.sh --serial 7e34269d
# Or reuse the generated 8K30 HEVC file:
scripts/test-android-playback.sh --serial 7e34269d --fixture /path/to/8k-hevc.mp4
```

The runner selects mobile/TV and ARM64/ARMv7 from the device, uses `adb install -r`
for the isolated `.verification` app, and removes its temporary media fixture.
It does not uninstall apps or clear application data. The launch and malformed-feed
regressions are part of the repeatable smoke suite (34 total on mobile).

Local evidence is under `app/build/reports/android-playback-refactor/`, including
`final-build.log`, `unit-summary.json`, `7e34269d/smoke.txt`,
`7e34269d/hardware.txt`, `7e34269d/hardware.json`, and `hardware-summary.json`.
`7e34269d/hardware-playback.png` shows the test SurfaceView output;
`7e34269d/video-screen.png` shows normal VideoActivity playback with the
7680×4320 / H265 / hardware-decoding indicator.
`7e34269d/launch-regression.txt`, `background-recovery.txt` and `restart.txt`
record the final entry-point and recovery checks. These local test logs
and the generated media are intentionally not tracked in Git.
The synthetic 7680×4320 HEVC Main fixture is 12 seconds, 30 fps, approximately
93 Mbps, generated with FFmpeg testsrc2/libx265. It exercises decode, display,
seeking and repeated release; it does not establish HDR, AV1, network/provider
throughput or native 8K panel output capability.
