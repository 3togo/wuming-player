# Application test suites

## Category filter and poster regression

The JVM filter/cache/ViewModel tests run in both flavors through `:app:testKeyLogic`.
Android behavior is additionally covered by `MobileCategoryFilterInstrumentedTest`,
`LeanbackCategoryFilterInstrumentedTest`, and the filter Parcel methods in
`StateRestorationInstrumentedTest`. The category fixture clicks production category
adapter views, executes actual folder request builders, and checks visible titles
and decoded local poster pixels through real content fragments. It uses the isolated
verification host and synthetic providers; live-host availability is outside its scope.

Build the verification runners with `:app:assembleCriticalTests` and matching apps with
`:app:assembleMobileArm64_v8aVerification` / `:app:assembleLeanbackArm64_v8aVerification`, then run
the matching flavor's category class and the two filter Parcel methods using explicit
ADB `-s` device selection. Follow `docs/android-instrumentation-lessons.md` for device
authorization, wake/foreground handling, installation, and cleanup. Compilation alone
does not establish a device pass. See
`docs/migration/category-filter-test-audit-20261002.md` for measured results and limits.

## Fast JVM tests

Run the fast shared-logic suite with:

```shell
./gradlew :app:testKeyLogic
```

The task runs the same deterministic checks against both the mobile and leanback flavors without requiring an Android device. It currently executes 190 tests per flavor and covers:

- responsive grid calculations and invalid-size handling;
- search, fuzzy matching, ranking, filtering, and highlighting;
- regular-expression validation and media URL extraction;
- pagination, partitioning, grouping, and duplicate handling;
- string, file, byte, and stream hashing;
- EPG channel-name normalization and matching;
- provider date/time formats.
- site reliability, speed sampling, and performance scoring;
- ViewModel result reset and search-cancellation epochs.
- concurrent search completion, duplicate callbacks, and progress reset.
- complete collection factory, conversion, predicate, ordering, and set-operation contracts;
- regex extraction/replacement/validation helpers and flagged pattern compilation;
- fuzzy-channel matching strategies, variations, confidence ordering, and algorithm boundaries.
- SRT, VTT, and ASS/SSA subtitle parsing with time precision, HTML/style tag stripping, and edge cases;
- CastVideo record constructor delegation, compact-constructor clamping, and URL normalization;
- LayoutConfig aspect-ratio constants, column counts, and ratio consistency.
- charset conversion, BOM detection (UTF-8/16/32), content-type parsing, and round-trip validation;
- M3U8 ad-tag detection (DISCONTINUITY, EXTINF, ENDLIST, KEY, numeric) and false-positive rejection;
- HTTP header case-insensitive normalization (User-Agent, Referer, Cookie) and passthrough.
- CatVod episode-metadata payload parsing (base64/JSON limits, provider route labeling, control-character and length guards, delimiter splitting);
- catch-up bean defaults, the PLTV provider contract, `decide` precedence, and template-slot wiring (the URL token and query rules themselves stay covered by `CatchupUrlPolicyTest` in `epg-core`);
- offline download header registry snapshotting and length-prefixed header (de)serialization;
- cast call scope cancellation, close idempotence, and interrupted-thread rejection;
- OpenDrive provider locator validation and playback-resolution failure wrapping;
- cloud-copy reclaim eligibility (state, provider, folder, session protection, staged leases, oldest-first order).

Generate an HTML and XML coverage report for the deterministic mobile JVM suite with:

```shell
./gradlew :app:testKeyLogicCoverage
```

The HTML report is written to `app/build/reports/jacoco/testKeyLogicCoverage/html/index.html`.

## Compile device tests

Compile both instrumentation APKs without a device:

```shell
./gradlew :app:assembleCriticalTests
```

## Run device tests

Start an ARM64 emulator or connect an Android/Google TV device with USB debugging enabled, verify it appears in `adb devices`, and allow **Install via USB** plus any installation confirmation shown on the device. Then run:

```shell
./gradlew :app:connectedCriticalTests
```

Run a single flavor when the connected device profile is phone/tablet or TV-specific:

```shell
./gradlew :app:connectedMobileArm64_v8aDebugAndroidTest
./gradlew :app:connectedLeanbackArm64_v8aDebugAndroidTest
```

The instrumentation suites cover:

- isolated Room CRUD for favorites, history, search history, and site performance;
- Room CRUD/conflict handling for live sources, drives, tracks, and sites;
- backup JSON round trips, database restoration, and corrupt-backup handling;
- schema migration 38 to 39 with data preservation;
- validation of every exported migration path from versions 35, 36, 37, and 38 to 39;
- launcher and critical-activity manifest contracts;
- mobile front-page VOD navigation and activity recreation;
- mobile portrait/landscape redraw of the history grid;
- mobile portrait/landscape redraw of Home and search grids, including recreation;
- mobile collection-grid redraw, proportional site-column sizing, and restoration;
- mobile player-settings layout measurement across 1.0x/1.8x font scales and 480-1440px widths;
- player-setting persistence, selection restoration, touch-target sizing, and visible labels;
- player-setting persistence across a complete activity close and cold relaunch;
- playback detail intent extras;
- leanback VOD category focus navigation;
- leanback search-result and filtered-result grids remaining at five columns after recreation.
- JSON/XML provider responses, malformed payloads, live entries, and subtitle fixtures;
- HTTP media, magnet, shared-text, and search intent routing;
- leanback D-pad focus boundaries before and after activity recreation.
- nested leanback folders forcibly resolving list-style provider results to a multi-column grid;
- nested leanback provider results rendering as focusable multi-item rows across recreation;
- player source/episode switching, controls, subtitles, tracks, decode recovery, and fatal errors;
- real Media3 engine creation/release and idempotent player teardown;
- audio-focus loss/duck/gain transitions, background stop/restart, and repeated decoder failure;
- local HTTP configuration resolution, redirects, request headers, timeouts, and M3U8 session isolation.
- request-generation cancellation, stale callback suppression, and concurrent response isolation;
- framework parcel restoration of VOD screen state;
- preference backup across primitive types, partial backups, and thousand-entry payloads;
- transactional backup rollback, including suppression of preference writes after database failure;
- data preservation and index/table usability checks for every exported migration step.
- provider orchestration success/error channels, stale-request suppression, cached search, and partial multi-site failure;
- process-style Bundle marshalling for nested-folder arguments and styles.

The tests use synthetic or empty inputs and do not require live VOD providers.
There are currently 61 mobile and 58 leanback instrumentation tests (52 shared tests plus
flavor-specific UI tests), plus 66 JVM tests per flavor, for 251 total JVM/device test executions.

For a physical-device/API matrix, connect the required phones and TV devices, then run:

```bash
ANDROID_SERIALS="phone_api_26 phone_api_35 tv_api_34" \
REQUIRE_API_LEVELS="26,34,35" REQUIRE_TV=true \
./scripts/run-device-matrix.sh
```

The matrix runner records API level and device characteristics, runs both flavor suites on every
device, force-stops the app, and verifies a real cold start after process death. Run it locally
with explicitly authorized devices attached. Set `ANDROID_TEST_API_LEVELS` to the required
comma-separated API levels.

The selected deterministic logic classes currently report approximately 76% line coverage and
58% branch coverage. This is a focused regression metric rather than whole-application coverage.

GitHub Actions workflows have been removed. Run JVM acceptance, instrumentation compilation,
and coverage locally using `./gradlew-safe unitAcceptanceBaseline :app:assembleCriticalTests
:app:testKeyLogicCoverage`; reports remain in the local build directories.

## Lessons learned: provider QR login and delayed autoplay

- Preserve the provider's authentication protocol. A QR code issued by a provider can be tied to
  the provider's current spider session, polling token, and expected UC/Quark client. Replacing it
  with a generic browser QR code, or intercepting the request before the provider initializes that
  state, produces a QR code that looks valid but cannot complete authentication.
- Pass provider challenge messages through the ViewModel unchanged and display the original QR
  image. Retrying must use the same active provider/session so its token can be polled successfully.
- Treat every delayed UI callback as potentially stale. Before an autoplay callback mutates
  selection, verify that its episode still belongs to the adapter's current data set.
- Never use a selection sentinel as a list index. `getPosition()` legitimately returns `-1` when no
  episode is active, even when the list is non-empty; callers and adapter accessors must handle that
  state without indexing the list or starting playback with an empty selection.
- Apply defense in depth: reject stale callbacks at their source, stop refresh when selection is
  invalid, and keep the adapter accessor safe. Regression tests must include a populated list with
  no activated item, not only empty and normally selected lists.

## Android memory ownership gate

Run `./gradlew :app:verifyAndroidMemory` to run all three Android JVM suites and
QuickJS resource regressions, compile mobile/TV/TV Lite for both ARM ABIs, and build
isolated mobile ARM64 and TV ARMv7 device fixtures. This task does not install apps.

The ownership plan, focused device classes, results and remaining runtime audit
limits are documented in [`docs/android-memory-plan.md`](../../../docs/android-memory-plan.md).
`DialogMemoryInstrumentedTest` adds local HTTP fixtures for QR refresh/cancellation,
late bitmap and HTTP-response disposal, history fragment-view teardown, and queued
platform dialog events after dismissal. Use the `.verification` application and
test packages for these regressions; they do not require production credentials.

`DetailCacheOwnershipInstrumentedTest` checks ten independent cached-detail
screen graphs. `PlaybackServiceBinderInstrumentedTest` holds a local binder across
actual service destruction and checks that its service reference is cleared.
These regressions were added from real-device production heap findings documented
in [`docs/production-heap-profile.md`](../../../docs/production-heap-profile.md). Use the
capture/analysis tools in `scripts/heap-profile/` for production-package heaps;
keep those local and separate from isolated instrumentation results.
