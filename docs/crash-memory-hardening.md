# Crash and memory hardening review — 2026-09-10

This review covers shared UI ownership, playback/service callbacks, file imports,
LAN discovery, and subtitle/playlist input handling. It builds on the earlier
ViewModel, download, drive navigation, history and favorite refactors. It is a
source and local-build review, not a claim that all application crashes or leaks
are eliminated.

## Changes in this pass

| Boundary | Failure addressed | Ownership rule |
| --- | --- | --- |
| Shared database cache | Published lists change during reads; concurrent updates lose entries | List mutations are serialized and publish completed snapshots. Existing readers keep a structurally stable list. Config updates preserve item order. |
| File imports and online subtitle helper | Queued callbacks and abandoned files outlive the requesting UI | `UiRequest` owns the future, removable delivery, lifecycle registration, cancellation hook and abandoned result cleanup. Completion and close clear UI callbacks. |
| Task rejection | A canceled/rejected queued operation can leave progress UI waiting indefinitely | The request reports executor rejection/cancellation through its failure callback, unless its owner already closed. |
| LAN discovery | Bootstrap can enqueue probes after stop; global cancellation affects other scans; completed futures remain retained | A scan owns its calls and futures, registers before dispatch, removes completed work, and rejects stale delivery. At most eight probes run per scan. |
| Metadata and playlist text | Unbounded body buffering can exhaust memory | Discovery permits 64 KiB and playlist preprocessing 2 MiB. Limits check actual bytes, including responses with missing or incorrect Content-Length. Oversized playlists fall back to normal playback without preprocessing. |
| Playlist preprocessing | Cancellation cannot remove an already queued playlist result | The job owns a removable UI scope and registers its future before dispatch. Finishing/destroyed screens reject delivery. |
| Playback connection | Seek can dereference a controller before its asynchronous connection completes | Seek requires both the player and controller. |
| Online subtitle helper | Download outlives dialog/activity or changes a different playback session | Each operation owns its progress dialog and HTTP call. Playback generation is checked at UI delivery; abandoned downloads are removed. No global progress dialog is dismissed by old work. |
| Subtitle archives | Skipped ZIP entries bypass expansion limits; late errors leave earlier files; concurrent writes race | All entries count toward expansion limits; cancellation is checked while reading; failures remove only newly reserved files; filename reservation is atomic. |
| Subtitle settings dialog | Restored fragment lacks its original player view; a dismissed dialog retains that view | Missing player view dismisses safely; view teardown clears both references. |

The online subtitle helper currently has no direct production call site. Its
hardening is preventive; the file-import, discovery and playlist paths are used
by the application today.

## Shared ownership conventions

- Put single-result background UI work in `UiRequest`. Capture input values and
  services in worker/cancel/discard callbacks. Only success/failure callbacks may
  capture views; they are cleared on completion or cancellation.
- Use the fragment **view** lifecycle for work that updates a fragment binding.
- UI lifecycle cancellation and user-requested persistent writes have different
  lifetimes. Preserve intentional history/favorite saves after screen closure.
- Register a future/call before dispatching it. Cancel the owned call rather than
  a globally shared HTTP tag.
- Check request identity at actual UI delivery, including cached navigation and
  responses from providers that ignore interruption.
- Bound external input by actual bytes. Close responses and roll back partial
  output, preserving files that existed before the operation.

## Verification

```sh
./gradlew --no-daemon --max-workers=2 :app:verifyAndroidMemory --console=plain
python3 scripts/check-leanback-remote.py
git diff --check
```

The Gradle task runs the app JVM suite for mobile, leanback and leanback_lite,
QuickJS JVM tests, compiles all six mode/ABI combinations, and builds the isolated
mobile ARM64 and TV ARMv7 app/test-runner fixtures. It does not run device tests.
New JVM regressions exercise ignored interruption, lifecycle destruction, queued
results, executor cancellation, independent HTTP ownership, bounded responses,
archive rollback, concurrent file reservation, stable cache snapshots and concurrent cache writes. A subtitle-dialog teardown and
restoration instrumentation regression is also included for later device runs.

Cache snapshots protect collection structure; cached model objects retain their existing mutable semantics. This pass does not change asynchronous database write ordering or make cache reload a multi-table transaction.

Final local result: `verifyAndroidMemory` passed. The app ran 655 JVM tests per
flavor (1,965 total), and QuickJS passed five tests. All six mode/ABI builds and
the two app/test-runner fixture pairs succeeded. The static remote check passed
129 effective layouts, 286 explicit focus targets, 26 action contracts and three
app-label locales. `git diff --check` passed.

## Runtime validation still required

No device was accessed in this pass. Actual Activity recreation, Media3 service
connection/disconnection, WebView/native decoder release, heap retention after
repeated playback, low-memory process recreation and TV focus behavior require
an authorized device run. The existing memory instrumentation and device matrix
are the follow-up validation paths; local test counts cannot establish native
heap or decoder leak freedom.
