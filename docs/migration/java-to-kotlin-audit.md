# Remaining Java-to-Kotlin opportunities

> Historical snapshot, not the current retention policy. Paths, counts and the
> Startup/Room conclusions below have since changed. Read
> [language boundaries](../language-boundaries.md) and the enforced
> [Java retention register](../java-retention.json) before deciding what to migrate.

Latest follow-up: [model and desktop adapters](model-desktop-adapters-20260916.md) converts Danmaku, DesktopRenderingControl, and DesktopAvTransport.

Latest follow-up: [helper conversion](helper-conversions-20260916.md) converts QrChallenge and moves HlsPipeline behavior to Kotlin behind its nullable-varargs Java facade.

Follow-up: [targeted implementation](targeted-conversions-20260916.md) converts 38 production files across all five suggested areas and enables Room/Glide KSP2. The [four-file follow-up](persistence-casting-followup-20260916.md) converts Drive, Track, ViewingRecord, and CastFileServer. The inventory below reflects both deliveries.

Follow-up: [TV stabilization](tv-stabilization-20260916.md) passed 26 selected runtime tests for the earlier delivery.

Audit date: 2026-09-16. Updated after the targeted follow-up; earlier delivery details below are historical.

Follow-up: [stabilization results and fixes](stabilization-20260916.md). The earlier corrected build passed 17 selected mobile device tests. The subsequent TV run passed 26 selected tests.

Follow-up: [recommended conversions and tooling](recommended-conversions-20260916.md) converts eight more app classes and fixes mixed-language coverage and source guards. The current inventory includes this delivery.

## Conclusion

The migration is not finished. The current app has **258 production Java files / 63,876 lines**, across main (207), mobile (23), and leanback (28). Leanback-lite reuses leanback sources; do not count them twice.

There are **257 remaining files outside Startup**, including 8 persistence models/database entry points. KSP2 removes the annotation-processing prerequisite; this does not make every persistence model a mechanical conversion. Some legacy non-null Java fields are initially null, and larger beans mix serialization, persistence, and runtime behavior. Preserve those contracts deliberately in future maintenance. The earlier 28-file UI batch, 38-file targeted delivery, and four-file follow-up are complete; this is not a mandate to eliminate all Java.

Do not use zero remaining Java files as the completion criterion. Compatibility facades, processor entry points, and frozen code can legitimately remain Java.

## Complete app inventory

[java-to-kotlin-inventory.csv](java-to-kotlin-inventory.csv) assigns every remaining production app Java file to exactly one group. It includes line counts, structural review flags, matching test references, and explicit Java-filename references in tests/scripts.

Flags and test mentions are lexical discovery aids, not proof of coverage or a complete semantic dependency graph. For example, a test mentioning a class may only use it as a fixture; a file without a direct test mention can still have integration coverage. Snapshot counts exclude generated output and change as conversions land.

| Group | Files | Lines | Treatment |
| --- | ---: | ---: | --- |
| B2 QR and casting dialogs | 0 | 0 | Converted in targeted follow-up |
| B3 Activities and UI foundations | 53 | 18,513 | Split ordinary screens from base/live/video activity hierarchies |
| B4 Non-Room models | 21 | 4,079 | Preserve Gson fields, mutable state, null handling and record contracts |
| B5 Media and diagnostic contracts | 20 | 1,246 | Small files, but normalized records require deliberate constructor design |
| B6 Cloud runtime | 50 | 10,881 | Support types first; provider families separately with policy tests |
| B7 Playback and extractors | 25 | 6,743 | Engine helpers first; parallel range and buffering code last |
| B8 Server, casting and services | 35 | 7,326 | Separate HTTP handlers, UPnP adapters and long-lived services |
| B9 Application, configuration and utilities | 45 | 12,354 | Leaf utilities before shared configuration/executor/application roots |
| H1 Startup contract | 1 | 45 | Retain Java under the present contract |
| H2 Remaining persistence models | 8 | 2,689 | Processor prerequisite cleared; legacy model contracts still need individual review |
| **Total remaining** | **258** | **63,876** | |

B numbers identify work groups, not a requirement to finish all 53 activity files before smaller model or media work. B3/B6/B7/B8/B9 are portfolios, not single mechanical conversion batches.

## Completed delivery: 28 UI files

All paths below are under `app/src/<flavor>/java/com/fongmi/android/tv/`. The listed original `.java` files have been replaced by `.kt` files.

### Shared: 9 files

- `ui/custom/CustomMovement.java`
- `ui/custom/CustomSeekView.java`
- `ui/custom/CustomWallView.java`
- `ui/custom/CustomWebView.java`
- `ui/dialog/DanmakuSearchDialog.java`
- `ui/dialog/DanmakuSettingPanel.java`
- `ui/dialog/DepotDialog.java`
- `ui/dialog/SiteRankingDialog.java`
- `ui/dialog/VideoRankDialog.java`

### Mobile: 7 files

- `ui/activity/CastController.java`
- `ui/activity/DanmakuController.java`
- `ui/activity/SpeedScaleController.java`
- `ui/custom/CustomKeyDown.java`
- `ui/dialog/ControlDialog.java`
- `ui/dialog/MobileSiteDialog.java`
- `ui/dialog/SyncDialog.java`

### Leanback: 12 files

- `bean/FilterCache.java`
- `bean/Func.java`
- `ui/activity/CastController.java`
- `ui/activity/SpeedScaleController.java`
- `ui/activity/VideoFocusHelper.java`
- `ui/custom/CustomHorizontalGridView.java`
- `ui/custom/CustomVerticalGridView.java`
- `ui/custom/CustomKeyDownLive.java`
- `ui/custom/CustomKeyDownVod.java`
- `ui/custom/CustomMic.java`
- `ui/custom/RemoteWebView.java`
- `ui/dialog/LeanbackConfigDialog.java`

The delivery was converted and compiled in dependency order: controllers/cache helpers, custom views, then dialogs. Preserve these specifically:

- Grid views reflect into AndroidX layout-manager fields. Preserve fallback behavior and all XML-inflation constructor overloads.
- `CustomWebView` has a volatile stop flag, synchronized URL bookkeeping, queued callbacks, child views and teardown. Do not replace explicit ownership with a capturing inner class or change synchronization.
- `CustomSeekView` registers player listeners only while attached and removes periodic callbacks during detachment.
- `DanmakuSearchDialog.SearchRequest` keeps a weak dialog reference and closes HTTP responses; cancellation must close queued deliveries too.
- `CustomWallView` and `LeanbackConfigDialog` have EventBus subscriptions. Preserve public subscriber methods and registration/unregistration.
- `FilterCache` exposes static factories and a public constructor; changing it to a Kotlin `object` would change its Java API.

Validation completed: all six mobile/leanback/leanback-lite ARM64/ARM32 verification variants compiled; mobile and leanback ARM64 instrumentation sources compiled; 1,625 JVM tests passed and two were skipped. Three new Func regressions cover stable item identity, changed site-name content, and unnamed-site behavior. An existing instrumentation test's three stale significantGain calls were updated to AdaptiveWindowController, matching its JVM counterpart. Architecture, TV navigation source checks, formatting, and diff whitespace checks passed. No device tests were run.

These are moderate-risk conversions; compilation and source checks do not prove interactive behavior on a device.

## Completed targeted QR/casting delivery: 9 files

Shared dialogs: `BaseQrLoginDialog`, `BilibiliLoginDialog`, `OpenDriveLoginDialog`, `CastDialogV2`.

Shared utilities: `OpenDriveQrHandoff`, `ProviderDialog`, `ProviderQrLauncher`, `QrSizing`, `TvDialogFocus`.

`BaseQrLoginDialog` documents measured QR fitting and bounded relayout passes because fixed sizing previously clipped the TV QR code. Its protected fields are subclass contracts: retain field visibility with suitable JVM fields or update every subclass in the same delivery. Preserve request IDs, idempotent cancellation, weak Activity ownership, bitmap ownership, and one-shot completion callbacks. Include the provider handoff helpers when reviewing login flow parity. Casting dialog changes also need subscription/discovery cleanup review.

## Holds and migration traps confirmed in source

### 1. Startup is a real contract mismatch

[`Startup.java`](../../app/src/main/java/com/fongmi/android/tv/Startup.java) implements `Initializer<Void>`. Its `@NonNull create()` returns null. Kotlin cannot express that implementation as an ordinary non-null `Void` result. Keep this tiny adapter in Java. Changing the initializer type/result is a separate behavior/API decision, not a mechanical conversion.

### 2. Room and Glide now use KSP2

[`app/build.gradle`](../../app/build.gradle) configures Room and Glide through KSP2. The targeted follow-up converted all 15 DAO files, five entities (SearchHistory, CloudAuditEvent, CloudCacheRecord, MediaResourceEntity, MediaSourceEntity), and OkGlideModule. The remaining seven entities and AppDatabase retain Java pending their own contract review. Database version 44 and the exported schema are unchanged. Generated database/DAO and Glide registration code must continue to be validated when these inputs change.

EventBus is a different case: Startup currently calls `EventBus.builder().installDefaultEventBus()` without `addIndex`. The configured generated index is not explicitly installed there. Subscriber conversion is not automatically blocked by the Room/Glide hold, but public method annotations, reflection discovery, shrinker rules and lifecycle registration still require verification.

### 3. Short Java records are not automatically easy targets

Examples: `SpiderInvocation`, `ProbePlan`, `SubtitleQuery`, `SubtitleCandidate`, `HlsTransformContext`, `ProgramGuideLayout`, and `CastVideo` rewrite inputs in compact constructors. They copy collections, normalize nulls/text, clamp numeric values, or transform URLs.

A direct `data class` translation changes record accessors and can change invariants. `@JvmRecord` alone does not reproduce compact-constructor reassignment. Choose an explicit representation that retains public constructor behavior and Java accessors; check whether record identity/reflection must also remain. Cover null inputs, normalization, defensive copying, exception types, equality and overloads. Kotlin read-only `List` is not a substitute for Java `List.copyOf` immutability/null-element behavior. Do not silently weaken those contracts to increase the conversion count.

### 4. Tests and profiling tools depend on Java spelling

Update these alongside the classes they govern, preserving the original assertion strength:

- `RawThreadGovernanceTest` scans both languages but still registers individual `.java` paths for Task, ProviderClient, Nano, cloud providers, cast workers, and offline storage.
- `CloudProviderSharedPolicyTest` and `StreamCacheShapeTest` resolve provider names with a `.java` suffix. Their source-shape checks must understand Kotlin syntax, not simply have their filename changed or checks removed.
- `SitePerformancePersistenceGuardTest` reads `SitePerformanceTracker.java` and checks Java-style accessor text.
- `scripts/epg-profile/run.py`, `scripts/range-profile/run.py`, and `scripts/cast-profile/run.py` identify live Java sources and compile Java baselines. Use separate current-source and historical-Java paths, as the migrated danmaku harness already does. The EPG tracked-file list also contains previously converted Java paths (`Formatters`, `XmlTvStreamReader`); refresh those when exercising that harness.
- `RangeProgressTest`, provider tests, `DownloadTest`, `ScanTaskTest`, and several model tests use reflection. Moving a method into a companion or renaming a field can break that contract even if ordinary callers compile.

### 5. Larger runtime groups need focused migration order

- B4: ordinary Gson models before normalized records; do not turn mutable beans into data classes without preserving equality and serialized fields.
- B5: HLS/subtitle adapters before normalized record constructors. Existing platform-neutral logic under app is a separate architecture concern: if behavior is extracted or extended for desktop, put it in the appropriate core library, following [desktop sync rules](../desktop-sync-architecture.md).
- B6: credential/cache/support types first, then one provider family at a time. Read the applicable Ali/UC/Quark lessons before modifying provider code. Retain account scoping, quota-only fallback, cleanup rules and source-policy tests.
- B7: engine/metadata/extractor helpers before PlayerManager, ParseJob and parallel range sources. Preserve Java checked-exception declarations where callers need them, buffer ownership, cancellation and profiling results.
- B8: small HTTP handlers before CastMediaRelay, projection management and foreground services. UPnP adapters require inherited overload/annotation and event delivery checks.
- B9: leaf utilities before Task, App, Setting, BaseConfig and its subclasses. Preserve JVM static methods, lazy initialization, locking, executor ownership and nullable callback contracts.
- B3: ordinary screens/fragments first; migrate BaseActivityCore, flavor BaseActivity classes, PlaybackActivity/BaseVideoActivity and their video/live subclasses as coordinated families. Existing static inheritance can require explicit base-class calls from Kotlin.

## Opportunities outside app

Source counts below exclude build/bin outputs and test files.

| Module | Production Java files | Decision |
| --- | ---: | --- |
| desktop | 4 | Receiving adapters converted; CastPlayer, MpvCastPlayer, DesktopCastService, and DesktopUpnpConfiguration remain |
| libraries | 2 | GuardedNativeCryptoRuntime and GuardedSpiderNativeHostMain remain; technically convertible, but native emulation/worker protocol coverage comes first |
| quickjs | 12 | Convertible adapter layer; preserve JS handle ownership, varargs and runtime callbacks; use existing ownership tests |
| catvod | 25 | Host utility opportunities, but crawler/provider-facing ABI requires compatibility checks against loaded spiders |
| chaquo | 3 | Convertible Python bridge; needs an enabled-Chaquopy build and runtime validation, not just disabled-Chaquopy app builds |
| guarded-webview-helper | 3 | Separate WebView service/protocol lifecycle batch |
| guarded-spider-helper | 2 | Separate provider-process service lifecycle batch |
| guarded-catvod-host | 103 | Compatibility host; inventory individual contracts before bulk conversion; do not classify it as frozen merely because it contains Java |

There are also **246 app test Java files plus one verification-only activity**. Keeping Java tests exercises Kotlin's Java-facing API, so converting tests merely to lower the Java count is low priority. Profiling Java harnesses can remain Java. Frozen `isolated/vendor-snapshots` sources are explicitly outside routine migration under [code-lifecycle.md](../code-lifecycle.md). Pending-deletion directories are not conversion targets.

## Completion gates for future deliveries

1. Verify every planned Java removal has exactly one Kotlin replacement; inspect public JVM signatures, inheritance, nullability and callback ownership.
2. Run `:app:testKeyLogic`, compile mobile/leanback/leanback-lite verification Java+Kotlin for ARM64 and ARM32, and run the three architecture/lifecycle boundary tasks.
3. Run `python3 scripts/check-leanback-remote.py` for UI changes; preserve the explicit leanback-lite Kotlin source directory in app/build.gradle.
4. Run focused affected tests and any source-governance/profiling checks listed above. Compile applicable instrumented tests when their API use changes. Do not describe compilation as successful device testing.
5. For processor work, verify generated outputs and schema parity. For desktop/QuickJS/native/Python work, run their own build and test gates; app JVM tests alone do not validate those runtimes.
6. Format changed Kotlin and run `git diff --check`. Keep unrelated existing work intact.

A final migration milestone should mean that selected application-owned code is converted, each remaining Java exception has a reason and validation path, and all affected gates pass. It should not require rewriting compatibility or frozen code without a benefit.
