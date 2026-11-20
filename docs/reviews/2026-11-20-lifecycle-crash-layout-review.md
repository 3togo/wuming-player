# Wuming lifecycle, crash prevention, and layout review

20 November 2026 UTC. Reviewed the working tree based on
`4df6acea1065a1e59d17ee177b3d86afd7733cd3`, including the uncommitted source
compatibility changes. The review followed Android mobile and Leanback source
loading, providers, wallpaper and dialogs, and desktop configuration, playback
proxy, live TV and cloud-account paths. It includes a spot check of the shared
Python bridge and native resource ownership.

**Fix first:** bound Android configuration and parser reads, retire obsolete
provider runtimes safely, and make configuration publication atomic with
respect to request replacement. Two desktop layout defects and one playback
proxy lifetime defect reproduced with the actual components.

P1 indicates a process-wide memory or state risk. P2 indicates a broken feature
or a resource lifetime issue with narrower impact. P3 indicates a layout
condition that still needs device measurement.

## Memory and crash prevention

### P1. Android buffers complete network responses before applying decode rules

VOD and live configuration loads enter `Decoder`. Both its direct and relay
paths call `ResponseBody.string()` before validation. The warehouse picker
calls `bytes()` before selecting children, and the JSON playback parser reads
the entire response before passing it to the shared decoder. Closing the
response correctly releases the socket, but a large or compressed response can
exhaust heap before that happens. The 100-child warehouse limit bounds the
returned index only. Leanback's configuration Help also pretty-prints the
document and binds it as one selectable text block, adding another allocation
and rendering cost.

Use a shared bounded read of the decoded stream, with limits appropriate to
configuration, warehouse and parser payloads. Preserve the supported BOM,
charset and encoded-envelope behavior. Classify size rejection separately from
transport failure so an oversized response does not automatically trigger a
second download through the relay. Test an exact-limit response, one over the
limit, and a compressed response with a small wire size. Give the Help preview
its own display limit and retain the full accepted document for backup.

Source trace. No device was driven into heap exhaustion.
[Decoder](../../app/src/main/java/com/wuming/android/tv/api/Decoder.kt#L27),
[warehouse picker](../../app/src/main/java/com/wuming/android/tv/ui/dialog/DepotDialog.kt#L298),
[JSON playback parser](../../app/src/main/java/com/wuming/android/tv/player/ParseJob.java#L152),
[Leanback preview](../../app/src/leanback/java/com/wuming/android/tv/ui/dialog/LeanbackConfigDialog.kt#L324).

### P1. Android retains obsolete JS and Python provider instances across configurations

The process-wide `BaseLoader` owns JS and Python registries. The cache key
includes the provider URL and extension digest. Opening a source, changing its
extension, and reopening it creates a new entry while the old one stays
reachable. JS and Python have no entry budget or idle retirement. VOD
configuration clear deliberately keeps provider caches, and Android's memory
trim path clears result data without retiring these script instances. For
QuickJS, each initialized instance owns a native context and a dedicated
worker. Its `destroy` path releases the context on that worker and shuts the
executor down. A Python provider that has handled a call owns a bridge handle in
`_instances`, which survives until the close operation removes the handle and
clears its scope. A JVM garbage collection alone cannot remove that entry.

Put admission and retirement behind the provider owner. Lease an instance for
active calls and proxy playback, then destroy retired idle instances exactly
once. Bound retained inactive instances and cover configuration replacement,
failed initialization, a slow active call, and an old playback proxy still in
use. Keep active playback and the documented platform-specific provider
isolation decisions intact. Measure retained handle count and native heap
across repeated switches on a low-memory device.

Source trace. The persistent references are visible in these paths. The memory
retained by an individual provider depends on its implementation.
[BaseLoader](../../app/src/main/java/com/wuming/android/tv/api/loader/BaseLoader.java#L28),
[JS registry](../../app/src/main/java/com/wuming/android/tv/api/loader/JsLoader.kt#L26),
[Python registry](../../app/src/main/java/com/wuming/android/tv/api/loader/PyLoader.kt#L15),
[cache identity](../../app/src/main/java/com/wuming/android/tv/api/loader/JarLoader.java#L259),
[VOD clear](../../app/src/main/java/com/wuming/android/tv/api/config/VodConfig.java#L88),
[memory trim](../../app/src/main/java/com/wuming/android/tv/App.java#L307),
[QuickJS teardown](../../quickjs/src/main/java/com/wuming/quickjs/crawler/Spider.kt#L156),
[Python handle ownership](../../libraries/python-runtime/src/main/python/wuming_bridge.py#L136).

### P1. An obsolete Android config load can still mutate the active configuration

`BaseConfig.load` interrupts the previous future and advances a task ID.
`loadConfig` checks that ID around the call to `load`. Its shared executor has
multiple workers. VOD applies shared HTTP
rules, replaces its site list, and selects a home and parser *inside* that call.
If request A is already parsing when B starts, A can still publish those
changes. That can leave A's sites or HTTP rules active under B's configuration.
The queued success/error callback can also run after a new request starts,
because the callback does not recheck the task ID when delivered.

Parse into a request-owned result. Validate the current request when committing
the site list, parser, HTTP policy and active configuration, then recheck it
when delivering UI notifications. Regression-test A blocked before commit
while B succeeds, A completing after B fails, and a queued A callback delivered
after B starts. Cover both VOD and live entry points.

Source trace. Cancellation and generation checks are present, but the state
writes and queued callback delivery are outside their protection.
[Load and delivery](../../app/src/main/java/com/wuming/android/tv/api/config/BaseConfig.java#L113),
[worker pool](../../app/src/main/java/com/wuming/android/tv/utils/Task.kt#L23),
[VOD publication](../../app/src/main/java/com/wuming/android/tv/api/config/VodConfig.java#L153),
[site and parser state](../../app/src/main/java/com/wuming/android/tv/api/config/VodConfig.java#L184),
[live load](../../app/src/main/java/com/wuming/android/tv/api/config/LiveConfig.java#L125).

### P2. Android follows nested warehouse indexes without a depth or cycle limit

The Android VOD and live loaders recognize `urls` through `BaseConfig.checkJson`.
`parseDepot` resolves the first child by recursively calling the same loader and
assigns `this.config` before that child succeeds. A self-reference or two-index
cycle can repeatedly fetch and allocate until interruption, stack exhaustion or
another failure. The outer worker catches failures, but the old active
configuration is already displaced. The bounded picker path is separate.

Resolve a bounded chain with normalized visited identities before publishing
the leaf. Preserve first-child behavior for direct entry. Verify self-reference,
a two-node loop, a deep chain, a malformed index, and a working nested child.
Failure should leave the previously working configuration in place.

Source trace. [Warehouse recursion](../../app/src/main/java/com/wuming/android/tv/api/config/BaseConfig.java#L189),
[VOD entry](../../app/src/main/java/com/wuming/android/tv/api/config/VodConfig.java#L127),
[live entry](../../app/src/main/java/com/wuming/android/tv/api/config/LiveConfig.java#L125).

### P2. Wallpaper snapshots violate Glide ownership and bypass failure cleanup

`WallConfig.setSnapshot` calls `submit().get()` on a request owned by the
application-context request manager. It discards the target and manually
recycles Glide's bitmap. The manager has no explicit clear on success,
interruption or write failure. A request that fails after acquiring its bitmap
can retain it until the request is released. Directly recycling a Glide-owned
bitmap also risks invalidating a resource the engine still tracks.

Keep the future target and clear it with the request manager in a finally block.
Publish the cached JPEG only after encoding succeeds, and exercise successful
replacement, a failed write and an interrupted decode. Separately measure how
many video-wallpaper players survive while navigating a deep back stack.
`CustomWallView` pauses a hidden wallpaper player and releases it at detach or
destroy, which can leave stopped or paused players allocated in back-stack
activities.

Source trace. No bitmap-pool exception or decoder exhaustion was observed.
[Snapshot request](../../app/src/main/java/com/wuming/android/tv/api/config/WallConfig.kt#L76),
[wallpaper lifecycle](../../app/src/main/java/com/wuming/android/tv/ui/custom/CustomWallView.kt#L154).

### P2. Retiring a desktop JS provider removes the replacement's playback URL

The desktop catalogue retires old executors asynchronously. A configuration
reload can create a replacement registration for the same source key before
retirement completes. `Registration.close` removes all URLs matching the
source key. On loopback, old, replacement and unrelated registrations initially
returned HTTP 200. Closing the old registration changed the responses to
`404, 404, 200`. The replacement's valid URL should still return 200.

Own routes by registration identity and serialize route creation with that
registration's close. Exercise both retirement orders and keep the unrelated
source as a control. The same proxy uses a cached thread pool and synchronized
provider invocation. Route quotas bound saved URLs, so a single slow provider
can still occupy multiple worker threads. Treat bounded in-flight admission
and prompt release as a separate load test.

Reproduced with the production loopback server and inert response handlers.
[Route ownership](../../desktop/src/main/kotlin/com/wuming/tv/desktop/js/DesktopJsProxyServer.kt#L35),
[registration creation](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/CatalogPanel.kt#L838),
[asynchronous retirement](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/CatalogPanel.kt#L1072).

### P2. Desktop Live TV drops stale results but leaves affected work running

Changing the playlist advances `generation`. Closing the panel advances the
playlist and playback generations. Those checks suppress old completion
callbacks, but the playlist and EPG SwingWorkers are anonymous and have no
cancellation owner. Rapid source changes or closing during slow guide loads
can leave obsolete reads and parsers holding the panel and their data until
they finish. This is bounded by completion behavior rather than by a panel
resource lifetime. It can also delay work in the shared SwingWorker queue.

Track playback preparation, playlist and guide ownership separately. Cancel
the superseded operation, propagate cancellation down to the HTTP call, and
retain the UI delivery checks for races. Test rapid A/B/A selection and window
close against a held-open playlist and guide response. Assert cancellation
reaches the transport and the winning guide remains visible.

Source trace. [Playlist worker](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/LiveTvPanel.kt#L259),
[guide worker](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/LiveTvPanel.kt#L363),
[panel close](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/LiveTvPanel.kt#L502).

### P2. Desktop QR generation can fail outside the recovery handler

`beginLogin` wraps only `get()` in `runCatching`. The `onSuccess` body then
calls `CloudQrImage.render` on the Swing event thread. That renderer can throw
if the provider payload exceeds its limit or cannot fit the requested QR
version. The Bilibili adapter admits HTTPS QR content without a length check
at that point. If generation throws, the EDT action aborts before starting the
poll timer or re-enabling controls. This is an uncaught UI exception and a stuck
login attempt. JVM termination was not observed.

Render and validate the QR in the worker's guarded operation, then publish its
image and challenge together. Confirm a too-large payload gives a recoverable
message and a valid retry succeeds. Parse the browser URI inside the existing
guard in `openChallenge` as well.

Source trace. [Login completion](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/CloudAccountsPanel.kt#L121),
[QR rendering](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/CloudQrImage.kt#L9),
[provider response mapping](../../libraries/cloud-login-runtime/src/main/kotlin/com/wuming/tv/core/runtime/cloudlogin/QrCloudAccountProviders.kt#L51),
[open challenge](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/CloudAccountsPanel.kt#L281).

## Layout consistency

### P2. Desktop Settings hides five actions at the supported minimum width

At an 800 px frame width, the responsive rail takes 176 px. Frame padding and
the rail gap leave 566 px for content before tab insets. At a 550 px settings
panel width, the toolbar row is 526 × 60 px. `FlowLayout` moves **Edit source
URL**, **Import package**, **Roll back package**, **Check source**, and **Review
source trust** to y=60 or y=114, leaving all five with zero visible height.
The 1320 px panel control kept every toolbar button inside the row.

Use the existing width-aware `DesktopWrapLayout` in the Sources settings
toolbar. Add a populated narrow-and-wide layout test that checks each action's
bounds and keyboard reachability after resizing in both directions.

Reproduced with the actual Settings panel and application theme in a headless
Swing geometry probe. [Toolbar](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/ConfigurationPanel.kt#L122),
[window minimum](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/DesktopWindow.kt#L142),
[existing wrap layout](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/DesktopWrapLayout.kt#L7).

### P2. Desktop Live TV's URL field collapses at the same compact width

The playlist row reserves its label at the west edge and four buttons at the
east edge. At a 566 px panel width, `BorderLayout` assigns the center editor
a width of −36 px. It has 398 px of width at the 1000 px control. The supported
compact window therefore cannot show the manual playlist URL field.

Use a responsive field-and-action layout that gives the editor a usable minimum
width. Place the actions on a wrapping second row when needed. Test the
unlocked editor, focus traversal and a resize back to wide.

Reproduced with the actual Live TV panel and application theme in a headless
Swing geometry probe. [Playlist source row](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/LiveTvPanel.kt#L127).

### P3. Android typography scales inside two fixed-height containers

Mobile's landscape VOD toolbar hard-codes a 40 dp height, while its title uses
the shared headline role and scales with tablet size and system font size.
Under linear SP scaling, a 20 sp headline, 1.25 tablet multiplier and 2.0 font
scale produce a 50 dp text size before line metrics or padding. On Android 14+
the nonlinear converter changes the actual measurement. Leanback's config
dialog also assigns a fixed responsive height to its form column, although its
help text, two editors and four actions measure from scaled content. Its
spacer can shrink to zero and the form column has no scroll container.

Measure both actual screens before prescribing dimensions. For mobile, check
title and menu access at narrow landscape widths and enlarged text. For
Leanback, check an unlocked populated config dialog at enlarged text and use the
D-pad to reach each editor and action. Include English, Simplified Chinese and
Traditional Chinese. Preserve the language-specific mobile rail sizing rules.

Source-based layout risk. Android rendering at these settings was not measured.
[Landscape toolbar](../../app/src/mobile/res/layout-land/fragment_vod.xml#L17),
[text scaling](../../app/src/main/java/com/wuming/android/tv/utils/TextScale.kt#L75),
[Leanback form](../../app/src/leanback/res/layout/dialog_config.xml#L16),
[Leanback runtime sizing](../../app/src/leanback/java/com/wuming/android/tv/ui/dialog/LeanbackConfigDialog.kt#L146).

## Verification and refactor order

- Seven selected desktop layout, wrap, renderer and embedded-player tests
  passed. Two additional probes found the clipping and the proxy ownership
  defect. The narrow and wide geometry runs use the same application theme,
  real component constructors and isolated settings.
- The Leanback checker passed across 124 effective layouts, 303 explicit focus
  targets, 26 action contracts and 162 listener checks. Design tokens,
  dimension, typography, dialog and UI theme checks passed. These inspect
  policy and focus contracts. The headless probe measures geometry, while the
  Android qualifier and D-pad cases still need rendered-device checks.
- The earlier 17-test Leanback ARMv7 launch smoke is recorded separately. This
  audit did not run a device heap soak, reproduce an Android process crash,
  test live providers or profile native residency.

Start with bounded reads and a request-owned Android config commit. Follow with
lease-aware provider retirement and a low-memory soak. Fix desktop proxy
ownership and the two confirmed layout problems in independently testable
changes. A release acceptance suite and a long-running heap regression answer
different questions. Track live provider instances and native heap after each
retirement cycle, and compare actual in-window controls after resizing and at
supported font scales.

## Implementation and validation

- Android configuration reads enforce decoded-byte limits before buffering, and
  the Leanback Help preview has a separate display limit. Warehouse traversal
  follows the first child with a cycle and depth limit. Candidate loads prepare
  privately and publish under the current request ID. The same ID gates
  callback delivery and superseded requests cancel their own HTTP calls.
  Failed warehouse loads preserve the active source and saved index.
- Android JS and Python calls lease provider instances. The cache bounds idle
  instances, keeps the most recent playback instance for its proxy callbacks,
  and defers destruction of in-use resources. Wallpaper JPEG generation owns
  and clears its Glide target, stages files before publication, and releases
  wallpaper media when its activity stops. The landscape VOD toolbar measures
  its content, and the Leanback source dialog can scroll to its actions.
- Desktop proxy capabilities belong to individual registrations, with a
  bounded request executor. Live TV cancels obsolete playlist and EPG HTTP
  calls and interrupts pending playback preparation. Desktop QR rendering
  fails through the login's recovery path. Settings and Live TV headers
  measure their action rows at the assigned width.
- Desktop smoke exposed an mpv startup ordering regression. An invalid
  embedded renderer can fail before IPC connects while mpv stays alive in idle
  mode. The adapter now recognizes the specific renderer diagnostic and
  shares the existing one-time fallback guard across IPC, process exit and
  diagnostic output. The native test confirmed that two launches occur
  without a premature failure or close callback.

The final Gradle `unitAcceptanceBaseline` passed. Its recorded JVM reports
contain 8,676 cases with zero failures and 17 skips. The 227 tooling tests
and 13 separately run Python bridge tests passed. Desktop CLI smoke and 12
native desktop smoke tests passed. The Leanback focus contract, typography,
dialog theme, UI theme and dimension checks passed. The signing backup
regression now compares exact saved bytes, including when paths inside the
home directory are stored with a tilde.

The final Leanback ARMv7 verification app and instrumentation APK assembled.
Their package identities and certificates matched the saved evidence from
the earlier device run. The 53-case deep and D-pad smoke selection was prepared.
The TV did not complete an ADB handshake on that attempt.

## Device smoke retry

On 20 November 2026 UTC the Q601B connected over ADB
and reported Android 10 (API 29) and ARMv7. The public run
`20261120T154355454238Z-deep-leanback` passed 16 of its 53 selected tests before
ActivityManager force-stopped the target during the Settings test. The device
log shows the firmware's delayed background stop and the instrumentation
report shows where execution ended.

The private verification APK included the public working-tree changes and
the private extensions. The 71-method selection included deep, persistence,
and D-pad checks. Run `20261120T155221413348Z-deep-leanback` recorded 64 passes,
two fixture failures, and five skips. All five private SDK, edition, and
routing checks passed. The preview fixture now waits for asynchronous detail
to make the preview visible and laid out before asserting focus. The Settings
D-pad fixture now opens its own task because the box force-stopped the AndroidX
for-result host and destroyed its Settings child.

The two repaired checks passed in `20261120T160207057598Z-deep-leanback`, with
the same app APK hash. The two runs cover all 66 runnable methods. Four
fixtures require API 33 stream methods and one selected cast-budget method is
absent from this source revision. Every launch check passed and both runs
verified screen timeout and stay-awake restoration. Live provider playback and
long-running heap behavior remain unmeasured.
