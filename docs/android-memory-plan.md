# Android memory ownership plan and results

Implemented for shared Android code, mobile, Leanback TV and Leanback Lite.
Leanback Lite inherits the TV sources. The plan follows resource ownership from
view creation through asynchronous work to teardown, rather than treating each
retained callback as a separate fix.

## Ownership strategy

1. Give each resource one owner: application/service, activity, fragment view,
   dialog, request, or JavaScript runtime. A longer-lived owner must not strongly
   retain a shorter-lived screen through callbacks, adapters, or player listeners.
2. Cancel work and invalidate deliveries before releasing views. A recreated
   fragment receives a new UI task scope; old queued work cannot touch its new
   binding. Use `BaseFragment.postUi` only from the main thread for view work;
   asynchronous producers still need request-generation/lifecycle guards.
3. Unregister observers and detach consumers explicitly. Temporary view detach
   suspends player observation; permanent cleanup also clears the player reference.
   The service remains responsible for releasing the player itself.
4. Bound caches, parser fan-out and native runtime resources. Cancelled or late
   responses must still close their owned streams/native handles.
5. Verify ownership transitions with repeatable regressions, compile every mode
   and ABI, then exercise the isolated verification app on devices. Keep device
   regression evidence separate from a production heap/long-playback audit.

## Completed work

| Owner | Findings and implementation |
| --- | --- |
| Fragment/dialog/activity views | Added shared `ViewCleanup` in the activity, fragment, bottom-sheet, side-sheet and alert-dialog bases. It detaches RecyclerView and pager adapters, clears scroll/page listeners, stops animations and detaches player views. The TV implementation also disconnects ItemBridgeAdapter from its ObjectAdapter, breaking the second observer link. View cleanup does not release the service-owned player. |
| Activity UI queue | Added `UiTaskScope`: destruction removes queued work and rejects subsequent posts. Migrated anonymous delayed callbacks in mobile/TV live playback, TV casting/search/home, mobile video/history. Existing named loops retain their explicit cancellation. |
| Configuration and dialog callbacks | Added `LifecycleCallback` and migrated UI configuration callbacks across home, live, settings, favorites, playlists and video. A request keeps its callback alive until completion or destruction, then drops the delegate and lifecycle observer. Backup/restore use repeated callbacks, explicitly closed on dialog dismissal, so multiple actions still work. |
| Search requests and history loading | `SearchWordClient` owns callbacks in a synchronized per-request map. Network callbacks weakly reference the client; cancellation clears callbacks immediately, including queued deliveries. RecordAdapter loads use weak references and attachment generations, with cancellation on detach. Mobile search cancels its delayed record update when its view ends. |
| Playback playlist preparation | Playlist preparation has an explicit cancellable job with a weak activity reference. New playback and activity destruction cancel the current HTTP call and future. All playback delivery and proxy-content publication happen on main, after checking the job is current. |
| Parser/WebViews | ParseJob owns its HTTP calls, limits parallel JSON parser workers to four, clears completion callbacks, and destroys WebViews on main. Each CustomWebView owns its child sniffers; stopped views reject further creation, child counts are bounded, and destruction is idempotent and detaches the native view. |
| QuickJS runtime | Global tracks timers, callback handles, calls and queued responses. Shutdown cancels I/O and timers, closes undelivered responses and releases functions on the runtime thread. Native argument, promise, HTTP header/array and proxy wrappers are released after use. Spider cancels pending promises, releases native objects on its executor, and no longer waits for a provider's destroy promise on the caller thread. JsLoader retires failed/duplicate/stale initializations, including races with configuration clearing. |
| Wallpaper and clocks | Wallpaper detach removes the inflated image hierarchy and releases GIF/player resources; reattachment does not accumulate children. Clock callback replacement and release drop the previous owner without accumulating ticker listeners. |
| Models, audio and caches | Model teardown invalidates task generations before cancellation, and search variants honor interruption. Audio-focus release works after transient loss and uses application context. Unconsumed browser playback results now have a 64-entry, five-minute cache with atomic consumption and configuration invalidation. Existing search caches remain bounded. |

## Existing owners audited and retained

- PlaybackService clears client/navigation callbacks, removes its player listener,
  releases its media session/player and clears the server's service reference.
  PlaybackActivity releases controller futures and service bindings. Background
  playback remains service-owned.
- Mobile DLNA discovery removes registry listeners and unbinds its application
  context. TV renderer teardown removes its playback callbacks/listener and
  position updater. DLNACast stops monitoring and clears its callbacks on release.
- CastDialogV2 stops discovery/scan work and clears its player/callback data.
  CloudStorageWebActivity cancels login/QR work and destroys its WebView.
- Process-wide network monitoring is intentionally application-owned, without a
  screen reference. Task queues and module/search caches already have bounds.
- JAR-provider cache eviction continues to avoid destroying providers underneath
  in-flight native calls. Arbitrary third-party provider code that ignores
  interruption cannot safely be force-terminated inside this process.

## Verification completed — 2026-09-04

| Check | Result |
| --- | --- |
| Mobile ARM64 verification unit suite | 500 passed |
| Leanback ARM64 verification unit suite | 500 passed |
| Leanback Lite ARM64 verification unit suite | 500 passed |
| QuickJS JVM resource regressions | 5 passed, including late and queued response closure during shutdown |
| Java compilation for mobile, TV and TV Lite, ARM64 and ARMv7 | Passed for all six combinations |
| Verification APKs | Mobile ARM64 and TV ARMv7 apps and test harnesses built |
| Mobile lifecycle/native-resource tests on physical ARM64 phone | 13 passed |
| Real mobile home/search/collection recreation and orientation tests | 3 passed |
| TV ARMv7 flavor on compatible physical phone | 16 passed, including Leanback observer teardown, category navigation and nested-folder recreation |
| Physical ARMv7 TV launch | Passed (`am start -W`, status `ok`) |
| Physical TV instrumentation | Device rejected harness installation with `INSTALL_FAILED_USER_RESTRICTED`; no device restriction was changed |
| Whitespace/error check | `git diff --check` passed |

The device regressions exercise ten fragment detach/attach cycles, ten wallpaper
reattachments, parser child teardown and late child rejection, search replacement
and cancellation, and audio-focus loss/release. Native QuickJS tests exercise 100
promises, 100 fired timers, 100 canceled timers, 20 synchronous HTTP responses and
20 asynchronous responses. Native wrapper counts return to their baseline.
During verification, the timer test exposed growth from 1 to 101 wrappers; fixing
argument ownership at the bridge removed that growth, and the regression passes.

The test-only MemoryTestActivity is in `src/verification`, not production builds.
All device installs target `com.fongmi.android.tv.verification` and its test
package. Production apps, account data and device security settings were not
changed. TV Lite shares the tested TV implementation but was not installed on a
device separately.

This is regression evidence for the audited ownership paths, not a claim that
arbitrary external providers or every possible runtime flow are leak-free. No
full production heap-dump analysis or extended playback soak was performed.

## Reproduction and evidence

Run the three `:app:test<Mode>Arm64_v8aVerificationUnitTest` tasks, where `<Mode>`
is `Mobile`, `Leanback` or `Leanback_lite`, plus `:quickjs:testDebugUnitTest`.
Compile both ABI variants with `compile<Mode><Abi>VerificationJavaWithJavac`.
Install verification APKs with `adb install -r`; do not use connected Gradle tasks
that uninstall an installed target, and do not clear production app data.

Focused device classes:

- `ResourceLifecycleInstrumentedTest`
- `SearchWordClientInstrumentedTest`
- `AudioFocusHelperInstrumentedTest`
- `QuickJsResourcesInstrumentedTest`
- `CustomWebViewOwnershipInstrumentedTest`
- TV-only `LeanbackAdapterLifecycleInstrumentedTest`

Local build/device logs are saved in `app/build/reports/android-memory/`.

## Follow-up audit — 2026-09-04

The existing refactor above was preserved. The follow-up closed these additional
ownership gaps:

- Added a per-view `UiTaskScope` to `BaseFragment`, closed before view teardown.
  Mobile search history/layout/dialog callbacks and TV category filter scrolling
  now use this scope; mobile episode scrolling uses the activity scope.
- `CustomRecyclerView` coalesces delayed focus requests and cancels them on detach.
- `CustomSeekView` registers its listener only while attached, restores observation
  on reattachment, and cancels ticking when its player changes. `ViewCleanup` and
  controller release explicitly clear its player; late scrub completion tolerates
  a cleared player.
- Subtitle search workers no longer capture their progress dialog strongly.
  Dialog dismissal cancels the search, activity destruction dismisses the dialog,
  and late completion checks the weak dialog before showing results.
- Added device regressions for ten fragment-view recreation cycles with queued
  callbacks and ten seek-view detach/attach cycles with explicit listener counts.

Follow-up verification against the updated sources:

| Check | Result |
| --- | --- |
| Mobile / TV / TV Lite ARM64 JVM suites | 500 passed per mode; no failures or skips |
| QuickJS JVM resource suite | 5 passed |
| Java compilation, all three modes and both ARM ABIs | Passed |
| Mobile ARM64 isolated device resource suite | 15 passed |
| TV ARMv7 isolated device resource suite on compatible ARM64 phone | 16 passed, including Leanback observer cleanup |
| Whitespace/error check | Passed |

Follow-up logs: `followup-final-build.log`, `followup-mobile-device.log`, and
`followup-tv-device.log` in `app/build/reports/android-memory/`.
The physical TV and TV Lite were not exercised again in this follow-up. Subtitle
search cancellation was reviewed and compiled, but not exercised against a live
subtitle provider. Production heap dumps and prolonged playback remain outside
the evidence above.

## Completion audit: network and plain-dialog ownership — 2026-09-04

This pass preserves the earlier lifecycle, adapter, player, cache, parser and
QuickJS changes and extends the same ownership rules to the remaining shared
network/dialog paths. These changes apply to mobile, TV and TV Lite.

| Resource | Gap closed |
| --- | --- |
| Depot update/validation workers | Resolve application context before submitting work. Calling `getApplicationContext()` inside the worker still captured the original Activity for the entire queued/network operation. Existing Settings callbacks already weakly reference their UI owners. |
| Plain-dialog lifecycle | `LifecycleCleanup` provides an explicitly removable destruction callback. Bilibili/OpenDrive login dialogs close with their Activity. History dialogs close with the fragment **view** or Activity that opened them, including while history is still loading. Completion/dismissal removes the lifecycle observer and callback. |
| Bilibili QR requests | Generation and polling own cancellable HTTP calls as well as futures. Refresh/dismiss cancels both. Workers and main-thread result deliveries hold weak dialog references; stale QR bitmaps are recycled. Activity destruction clears success/cancel delegates without invoking screen callbacks. |
| OpenDrive QR requests | Workers capture provider identifiers and the application-owned token manager, never the dialog/Activity. Per-generation polling queues close on refresh/dismiss. Destruction clears Activity, binding and completion callback references; late challenge bitmaps are recycled. Provider operations still use their existing protocol and cooperative interruption behavior. |
| Danmaku searches | Each dialog owns its call and delivery queue. Replacement/dismiss cancels both, clears the weak owner, suppresses stale delivery and closes late responses. Legacy API cancellation now tags POST requests too; cancelling a legacy search cannot cancel a dialog-owned search. |
| History results | Loading is cancellable and generation-checked. An empty result releases the dialog resources. An old result cannot open a window after its originating fragment view ends or replace a newer result. |
| Queued platform dialog events | Device tests exposed an `onShow` event arriving after dismissal. Both QR dialogs now reject show events for dismissed/obsolete windows before reading cleared fields. |

### Repeatable verification gate

```sh
./gradlew :app:verifyAndroidMemory
```

The gate runs all three ARM64 JVM suites and the QuickJS JVM suite, compiles all
three modes for ARM64 and ARMv7, and builds the isolated mobile ARM64 and TV ARMv7
application/test APKs. It does not install or uninstall an application. Run the
same command in CI when changing Android resource ownership.

The added `DialogMemoryInstrumentedTest` uses local HTTP fixtures and deliberately
stalled/late results. It covers ten QR refreshes, ten danmaku replacements,
Activity recreation, immediate dialog dismissal before queued `onShow`, fragment
view destruction during history loading, current QR display/recycling, late QR
recycling, late response closure, and independent legacy/dialog cancellation.
`LifecycleCleanupTest` checks destruction, early completion and already-destroyed
owners. The HTTP fixtures do not log into a real provider.

Final validation against the completed sources:

| Check | Result |
| --- | --- |
| `:app:verifyAndroidMemory` | Passed |
| Mobile / TV / TV Lite ARM64 JVM suites | 503 passed per mode; no failures, errors or skips |
| QuickJS JVM resource regressions | 5 passed |
| Java compilation for all three modes and both ARM ABIs | Passed for all six combinations |
| Mobile ARM64 isolated device memory suite | 23 passed on the connected physical ARM64 phone |
| TV ARMv7 isolated device memory suite | 24 passed on the same ARMv7-compatible phone, including Leanback observer cleanup |
| Whitespace/error check | Passed |

Evidence: `completion-gate.log`, `completion-mobile-device.log`, and
`completion-tv-device.log` in `app/build/reports/android-memory/`. The first device
run's discovered dialog crash is retained in
`completion-dialog-race-reproduction.log`; both final suites pass with its fix.

The physical TV was not re-tested in this pass because the earlier harness
installation was device-restricted. TV Lite shares the tested TV implementation
and passed compilation/JVM checks; it was not installed separately. OpenDrive
teardown and late bitmap disposal were tested without contacting a live provider.
Live account login, prolonged playback, and production heap-dump analysis remain
outside this regression evidence. These limitations do not require repeating the
implemented refactor; the gate and ownership rules above make future changes
reviewable and repeatable.

## Production heap profiling — 2026-09-04

Production-package heaps have now been captured on the physical Android 14 ARM64
phone and Android 9 ARMv7 Skyworth TV. This supersedes the earlier statements that
production heaps had not been analyzed; those statements describe the preceding
test-only passes. Full measurements, run provenance, root paths and limitations
are in [production-heap-profile.md](production-heap-profile.md).

The profiling pass fixed two additional ownership defects:

- A native-held non-static `PlaybackService.LocalBinder` retained a destroyed
  service and 404,321 bytes. Playback and TV renderer binders now weakly reference
  their service, clear it on destruction, and reject late/null client attachment.
  Released player fields are cleared too.
- The detail cache shared mutable Result/Flag/Episode objects across screens,
  causing the second playback of the same item to stall. Cache entries now own
  Parcel snapshots and each hit produces an independent graph.

The final phone completed ten actual playback/exit cycles with no detected leaks
and zero retained VideoActivity, PlaybackService, PlayerManager or ExoPlayer
instances after exit. The physical TV reveals firmware-owned retention through
`ActivityThread.skyPatch` and, in the original navigation build, Android 9's
`ViewLocationHolder` pool. These are recorded explicitly in the profiling report;
passing application regressions does not establish that the firmware is leak-free.

The complete memory build gate and both production APK builds pass. New cache
ownership and service-binder device regressions also pass. Capture, offline Shark
analysis, and PSS/class-count aggregation are repeatable with the tools under
`scripts/heap-profile/`. Raw heaps stay in the ignored local build report folder.

## Playback and 8K follow-up — 2026-09-05

The remaining playback-generation, service-to-danmaku-view, HTTP/parser and render-thread
ownership gaps are addressed in [the Android playback refactor plan](optimization/android-playback-refactor.md).
It records the heap-based encoded-sample budget, removed back-buffer, cancellation and
shared cleanup decisions, along with smoke and hardware results for `7e34269d`.
Use `scripts/test-android-playback.sh --serial SERIAL` to reproduce the isolated device
run with a generated 8K HEVC fixture, or supply `--fixture PATH` to reuse one.
