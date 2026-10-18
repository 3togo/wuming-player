# Standing rule: provider protocols are not logical specifications

Cloud-drive protocols, especially UC, must never be completed or changed by
"educated guess", symmetry with another provider, or what appears logically
necessary. A protocol change is allowed only when its exact endpoint, query,
body, headers/cookies, response fields, and request order are supported by:

1. a cited working implementation whose relevant code has been inspected; or
2. an official specification; or
3. a captured request/response from the target client and a matching device test.

HTTP 200/206 and non-empty bytes prove only transport. They do not prove that
the required provider workflow ran. For a UC public share, acceptance must
independently prove `sharepage/save`, completed task polling,
`save_as_top_fids`, and then a personal-drive media request using that saved
fid. Final acceptance requires actual playback UI; a successful range request
is not enough.

Do not label an established protocol requirement as a new finding. Record the
source before editing code; if sources disagree, preserve the disagreement and
test it instead of choosing the version that seems more reasonable.

Provider-specific protocol evidence and implementation lessons are recorded in
[provider-protocol-evidence.md](provider-protocol-evidence.md).

## Professional Release Refactoring (2026-07-29)

### Scope
Comprehensive refactoring to bring the codebase to professional release quality.

### Changes Applied

1. Created `AppLog` utility (`app/.../utils/AppLog.java`) — drop-in replacement
   for `e.printStackTrace()` with proper Android `Log` output. Null-safe: falls
   back to class name when exception message is null. Replaced 68 occurrences
   across 27 files.
2. Fixed VerticalGridView touch scroll reset bug — root cause:
   `GridLayoutManager.onLayoutChildren()` re-aligns to `mFocusPosition=0`
   during touch. Fix: use reflection to update `mFocusPosition` after touch
   ends. Applied to both `CustomVerticalGridView` and `CustomHorizontalGridView`.
3. Fixed `ProviderDialog` concurrency — replaced `static int attempt` with
   `AtomicInteger` to prevent race conditions.
4. Cleaned up `CustomWebView` — removed unused `selfRef` WeakReference,
   deduplicated `stop()`/`destroy()`.
5. Refactored `App.java` — extracted hardcoded DNS domain to constant, replaced
   `android.util.Log` with `AppLog`.
6. Completed translations — added missing string-arrays to zh-CN and zh-TW.
7. Fixed `build.gradle` JaCoCo path — replaced AGP-specific hardcoded path with
   `layout.buildDirectory` API for AGP 9.x compatibility.
8. Verified DLNA architecture — DLNACastManager in leanback (stub) and mobile
   (full) are intentionally different, not duplicated.

### Key Rules Reinforced
1. Always use `AppLog.e(TAG, e)` instead of `e.printStackTrace()`
2. Use `AtomicInteger` for mutable static state
3. Remove dead code
4. Use `layout.buildDirectory` instead of hardcoded AGP paths
5. Keep all string-arrays present in all locale files
6. Deduplicate `destroy()`/`stop()` patterns

## Screen-width-relative sizing via AutoSize (2026-07-28)

### Problem
Leanback TV version used static `values-w1080dp/dimens.xml` (fixed 1.5x scaling)
on top of AutoSize's global density adjustment (960dp design width). This
created double-scaling.

### Fix
Deleted `app/src/leanback/res/values-w1080dp/dimens.xml`. The leanback flavor
already has correct screen-width-relative sizing via AutoSize in BaseActivity.

### How AutoSize works
- Leanback: `AutoSizeCompat.autoConvertDensityOfGlobal(resources)` — sets global
  density so the screen always behaves as 960dp wide. A `@dimen/vs_24` = 24dp →
  always 24/960 = 2.5% of screen width.
- Mobile: `AutoSizeCompat.autoConvertDensityBaseOnWidth(resources, designWidth)` —
  per-activity density adjustment, orientation-aware.

### Key Rule
Never reintroduce a large-width dimension overlay without first proving that
AutoSize is disabled for the affected screen.

## DO NOT copy mobile layouts to leanback — RESTORED (2026-07-28)

### Problem
Previous session incorrectly copied all mobile layouts, Java sources, and
resources to the leanback source set, destroying the original TV-optimized
layouts. This session fully restored leanback to its original git HEAD state.

### Why Copying Mobile to Leanback Was Wrong
1. Leanback has TV-specific views (`CustomVerticalGridView`,
   `CustomHorizontalGridView`, `CustomLiveListView`, `CustomTitleView`) that
   mobile doesn't have.
2. Leanback has D-pad focus navigation with explicit focus chains. Mobile
   layouts have no D-pad navigation — unusable on TV with remote control.
3. Leanback layouts are designed for TV aspect ratio. Mobile landscapes use
   `NavigationRailView` — works on phones, not optimized for TV.
4. Dimension scaling system differs: leanback uses AutoSize with 960dp design
   width; mobile uses per-activity orientation-aware adjustment.

### Anti-Patterns
1. Copying entire source sets between flavors without understanding differences
2. Manually writing layout files from memory (produces incorrect resource
   references)
3. Deleting TV-specific views (CustomVerticalGridView, D-pad focus chains)
4. Replacing TV layouts with phone landscape layouts that lack remote control
   support

### Correct Approach
- Study the AutoSize path and all runtime setters before changing XML dimensions
- Make targeted fixes to individual layouts, not wholesale replacement
- Inspect ownership and reverse only confirmed bad hunks; never broadly clean a
  shared dirty worktree

## TV QR Dialog and Runtime Layout Overrides (2026-07-28)

### Symptoms
- QR dialog expanded wider than requested
- Instruction text, URL, and site name overlapped
- Red focus underline appeared together with requested white divider
- XML-only size changes did not reliably affect rendered TV UI

### Root Causes
1. Material dialog width assigned before `dialog.show()` — Material reapplied
   its own window sizing during `show()`, overriding requested width.
2. Right-hand fields independently bottom-anchored in `RelativeLayout` —
   locale-dependent height caused overlap.
3. `EditText` supplied its own stateful underline — adding a separate divider
   without removing that background produced two lines.
4. QR view scaled at runtime, but generated bitmap remained fixed at 200px.
5. Fast-search chip dimensions overwritten in Java by dialog-scale helpers.

### Durable Rules
- Call `dialog.show()` first, then set `WindowManager.LayoutParams`.
- Use a horizontal container with one vertical content stack for QR dialogs.
- When using an explicit field divider, set the editable field background to
  `@null`.
- Generate raster content such as QR codes at the final rendered pixel size.
- Before changing XML size, search for `setTextSize`, `setLayoutParams`,
  width/height assignments, adapter sizing, and AutoSize hooks.
- Static instructions are not focus targets. D-pad order begins at the URL field.
- A successful compile validates resources and types, not visual geometry.

## Spider NPE Crash Fix (2026-07-28)

### Problem
App crashes with `NullPointerException` from obfuscated `Gc` class in a
pre-compiled AAR (catvod spider merge library) calling `exception.getMessage()`
on a null Throwable reference. The spider creates its own
`Handler(Looper.getMainLooper())`, bypassing `App.post()`.

### Fixes Applied
1. **App.java** — Extended `App.handler` with `dispatchMessage()` override
   wrapping `super.dispatchMessage()` in try-catch. Installed
   `Thread.setDefaultUncaughtExceptionHandler()` for spider's own Handler.
2. **SiteApi.java** — Extended crash flag recovery from just `homeContent()` to
   ALL spider method entry points.
3. **Nano.java** — Made `error(String)` null-safe.
4. **Download.java** — Null-safe `e.getMessage()` with class name fallback.
5. **ViewModels** — Null-safe callback error messages.

### Key Technical Insight
A pre-compiled AAR library can crash the app on the main thread via its own
Handler. The only way to catch this is with
`Thread.setDefaultUncaughtExceptionHandler()`. Defense-in-depth: global UEH +
crash flag + Handler wrapper.

## Debugging "Config #0 (Type:0)" Display Issue

### Problem
Config displayed as "config #0 (type:0)" instead of actual name, making it
impossible for users to identify the correct configuration.

### Root Causes
1. **UI Layer** — `ConfigAdapter` intentionally removed first config item from
   display list, hiding current config from users.
2. **Data Layer** — Empty configs created without URL or name as placeholders.
3. **Legacy Data** — Database contained configs with empty URL/name fields from
   previous versions.
4. **Thread Safety** — Initial fix attempted to save fixed configs synchronously
   on the UI thread.

### Key Insights
1. **Fallback Values Pattern** — Always define hardcoded fallback values for
   critical configuration.
2. **Multi-Layer Validation** — Validate at creation time, load time, display
   time, and use time.
3. **Thread-Safe Data Access** — Fix data in memory immediately for display,
   then persist asynchronously.
4. **User Visibility Principle** — Users should always be able to see what
   configuration is currently active and all available options.

### Anti-Patterns
- Creating objects with empty required fields
- Hiding data from users without clear UI indication
- Doing I/O on UI thread

## Leanback VerticalGridView Touch Scroll Reset Bug (UNRESOLVED)

### Problem
When using touch (not DPAD) to scroll down the video thumbnail grid in the
leanback version, the scroll position resets back to the top. This does NOT
occur with DPAD/keyboard navigation.

### Root Cause
`GridLayoutManager` (from `leanback-grid` v1.0.0) maintains a `mFocusPosition`
field that tracks which item should be "focused" (aligned to the keyline).
During touch scrolling, `mFocusPosition` is NOT updated. On every layout pass
(`onLayoutChildren()`), when `mFocusScrollStrategy == FOCUS_SCROLL_ALIGNED`,
the layout manager re-aligns to `mFocusPosition=0`, moving all child views back
to top.

### Why It Cannot Be Fixed at the View Level
The re-alignment happens inside `GridLayoutManager` during the layout pass,
bypassing all overridable `View` methods (`scrollBy`, `scrollTo`,
`smoothScrollTo`, `fling`, `onScrollChanged`). `computeVerticalScrollOffset()`
returns 0 in `VerticalGridView`, making position estimation unreliable.
Reflection on `mFocusPosition` is also unreliable because the layout pass
recalculates alignment based on actual child positions.

### What Would Actually Fix This
- **Option A**: Replace `VerticalGridView` with standard `RecyclerView` +
  `GridLayoutManager` (from androidx.recyclerview). Large refactor.
- **Option B**: Fork `leanback-grid` and add a `SCROLL_STATE_DRAGGING` check in
  `GridLayoutManager.onLayoutChildren()`. ~3 lines, but maintains a fork.
- **Option C**: Use `setFocusScrollStrategy(FOCUS_SCROLL_ITEM)` permanently.
  Reduces severity but does not eliminate the re-alignment.

### Key Insight
`VerticalGridView` is NOT designed for touch scrolling. The
`mFocusPosition`-driven re-alignment on every layout pass cannot be prevented
from the `View` level. The fix must be applied inside
`GridLayoutManager.onLayoutChildren()`.

## Pad search site rail — first rows keep a stale size (UNRESOLVED, 2026-10-04)

### Symptoms
On the search results page (`MobileFastSearchActivity` + `MobileCollectFragment`)
on a tablet in landscape (measured on 3200x2136), the first two rows of the left
site column render larger than every row below them. `uiautomator dump` bounds:

```
全部            x=34  y=458 w=380 h=109
糯米┃秒播       x=34  y=589 w=380 h=109
茉莉┃多線       x=28  y=714 w=392 h=79
… remaining 14 rows all w=392 h=79
```

Both the pill width and the row height differ, so this is not the label-fitting
logic — the whole cell was measured at another density. Text size is also off:
the glyph-height cap in `LayoutConfig.getMobileSearchSiteTextSizePixels()` uses
`vs_*` insets through `ResUtil.getDimensionPixelSize()`, which is density-derived.

### Root cause
`adapter_collect.xml` expresses margins (`vs_2`), padding (`vs_4`, `vs_6`) and
`textSize` as dp. AutoSize rewrites the activity density per orientation, and it
settles that density *after* the rail has already inflated its first holders.
Holders created in the first pass keep the pre-AutoSize px geometry for the rest
of their life; holders recycled later pick up the settled density, so the column
is internally inconsistent. The difference is visible as 380/109 versus 392/79.

### Attempts that did not fix it
- `notifyDataSetChanged()` from an `addOnLayoutChangeListener` on `llCollect`:
  that view's width is `screenWidth x 0.14` (`LayoutConfig.getSiteColumnWidthPixels`),
  which is density-independent, so the listener never fires. Watching the rail
  `mBinding.collect` (380 -> 392) would fire, but a rebind still cannot help,
  because the stale values were baked in at inflate time, not at bind time.
- Deferring the rebind by one frame: same reason, no change on device.
- `setHasFixedSize(true)` on the rail hides the inconsistency rather than causing
  it; removing it re-measures the stale holders but they still resolve their XML
  padding from the density captured at inflation.

### What would actually fix it
- **Option A**: attach the rail adapter only after the density has settled (post a
  layout pass before `setSites()`/first bind), so every holder inflates once, at
  the final density.
- **Option B**: drive the pad cell geometry from `LayoutConfig` pixels at bind time
  (margins, padding, text size set programmatically) and stop relying on dp for
  this rail, so a recycled holder is corrected when it is rebound.
- **Option C**: opt `MobileFastSearchActivity` out of AutoSize density rewriting.
  This contradicts the standing rule in "Screen-width-relative sizing via AutoSize"
  above — only take it with a measured comparison of the whole screen, not for the
  rail alone.

### Constraint
The pad sizing is gated by `LayoutConfig.isPadViewport()`. Phone and TV layouts
must stay byte-identical while this is investigated; do not "fix" it by editing
shared `vs_*` dimens or `adapter_collect.xml` defaults.
