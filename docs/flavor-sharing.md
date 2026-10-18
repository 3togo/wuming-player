# Shared behavior and flavor boundaries

`main` owns behavior that must stay consistent on mobile, leanback and leanback_lite.
TV Lite already includes `src/leanback/java` and `src/leanback/res` through the Gradle
source sets; it does not need another copy of TV controllers.

## Playback controls

- `BaseSpeedScaleController` owns rate changes, history updates, and temporary speed
  indicator cleanup. Flavor `SpeedScaleController` classes retain scale gestures and
  control-timeout behavior. TV keeps its quarter-step speed key bindings; mobile keeps
  touch zoom reset and control timeout refresh.
- `SkipMarkerController` owns marker selection, reset and TV one-second adjustments.
  It retains no view binding. Mobile supplies an interaction callback to preserve its
  control-timeout refresh; TV uses the constructor without that callback.

Keep common fixes in these shared implementations. Do not copy their action methods
back into individual flavors to change labels, focus, or gesture bindings.

## File browser

`BaseFileActivity` owns binding creation, adapter setup, permission completion,
initial-directory handling, navigation, result delivery and teardown. Both flavors
honor `FileChooser.EXTRA_INITIAL_DIRECTORY`. Permission completion weakly references
its owner and rejects a finished or destroyed activity.

Flavor activities supply toolbar/title presentation and scroll/focus positioning.
Mobile scrolls its RecyclerView; TV and TV Lite retain selected-position focus and
vertical spacing. Keep those hooks separate because they represent different input
methods, rather than adding runtime flavor checks to shared navigation.

## Verification

Run the same JVM tests against all three flavors, which also compiles each flavor's
binding and controller contracts:

```sh
./gradlew :app:testKeyLogic :app:testLeanback_liteArm64_v8aVerificationUnitTest
python3 scripts/check-leanback-remote.py
```

The static remote check does not replace device navigation tests. Shared marker tests
cover unset values, missing history and mobile callback ordering. Existing file-browser
tests cover initial-directory confinement and parent navigation.

## Playback activity responsibilities

`BaseVideoActivity` coordinates playback and renders screen state. Network subscription
ownership lives in `PlaybackNetworkObserver`: each start creates a distinct session;
stop removes queued refreshes and rejects late callbacks from that session. The Android
adapter uses application context, and registered callbacks weakly reference their owner.
Close releases the screen update callback and prevents a new subscription.

`VideoFavoritesController` owns favorite reads, toggles, metadata updates and request
identity. Its queued operations contain repository/input snapshots and a weak delivery
request, rather than an activity. User-requested writes may finish after close; close
releases the host and suppresses UI delivery. The activity supplies the current active
video key and renders the favorite icon/notification.

Both components expose package-local dependency seams for deterministic tests with
queued executors and fake subscriptions/stores. Keep framework callbacks and repository
operations in these components instead of adding them back to the playback activity.

`VideoHistoryLoader` owns history lookup, default episode restoration, cancellation
and pending UI delivery for every flavor. Both repository lookups run on the worker;
only the current request for the active video can update the screen. Cancellation
removes queued delivery, and close releases the host. Database failures still produce
a usable default history. `BaseVideoActivity` retains playback-state changes, history
writes and rendering, so final progress saving remains independent of read cancellation.

## Background results and media input

`UiRequest` owns one lifecycle-bound result, including cancellation, removable UI
delivery and cleanup of abandoned resources. File imports and the online subtitle
helper share it. Worker/cancel/discard callbacks capture data and services; UI
callbacks are detached on completion or owner destruction. Persistent user-library
writes retain their separate lifetime.

`ScanTask` owns a bounded group of discovery workers and cancels its own HTTP calls.
`HttpResponseText` applies actual-byte limits to discovery and playlist metadata.
See [the crash/memory review](crash-memory-hardening.md) for the audited
boundaries, regressions and runtime validation limits.

`DataCache` publishes completed list snapshots and serializes list mutations. Do not
mutate a published list or a list returned to readers. This protects collection
iteration and concurrent updates; it does not make mutable entity fields immutable.
