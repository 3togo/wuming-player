# Kotlin migration opportunity review

Date: 2026-09-16. Scope: investigation of the current working tree; no production conversions in this review.

## Recommendation

Continue with small, contract-preserving conversions. Start with **QrChallenge and HlsPipeline (111 Java lines combined)**, then consider Danmaku and the desktop receiving adapters. Prioritize reducing Java/Kotlin boundary friction in maintained features rather than reaching zero Java files. No performance or build-time improvement has been measured by this review.

Substantial conversions are already present as uncommitted changes. This review includes their Kotlin replacements in its baseline and leaves those changes and the previous audit intact. Prior delivery reports describe their own validation; those results were not rerun here.

## Fresh source inventory

Counted existing `.java` and `.kt` files under production `src/main`, `src/mobile`, `src/leanback`, and `src/leanback_lite` directories. Excludes generated/build/bin output, tests, verification-only sources, scripts, pending deletion, and isolated vendor snapshots. Counts are physical files, so leanback sources reused by leanback-lite are counted once. Lines include comments and blank lines.

| Area | Java files | Java lines | Kotlin files | Kotlin lines |
| --- | ---: | ---: | ---: | ---: |
| Android shared | 217 | 49,450 | 309 | 23,580 |
| Android mobile | 23 | 6,791 | 32 | 3,333 |
| Android leanback | 28 | 8,516 | 56 | 4,761 |
| **Android total** | **268** | **64,757** | **397** | **31,674** |
| Desktop | 6 | 568 | 65 | 10,717 |
| Shared core libraries | 2 | 467 | 144 | 11,955 |
| QuickJS | 12 | 1,217 | 0 | 0 |
| CatVod | 25 | 3,037 | 0 | 0 |
| Chaquo (currently disabled) | 3 | 336 | 0 | 0 |
| Guarded WebView helper | 3 | 455 | 0 | 0 |
| Guarded spider helper | 2 | 496 | 0 | 0 |
| Guarded CatVod host | 103 | 3,524 | 0 | 0 |

Android is approximately 60% Kotlin by file count but 33% by source lines: large Java classes still dominate the remaining work. These percentages measure language mix, not complexity or migration completion. The previous audit's outside-app table still says seven desktop Java files; the current count is six following the CastFileServer conversion. Its processor paragraph also retains an older count of ten remaining entities: currently seven entities plus AppDatabase remain, consistent with its newer conclusion.

## Build readiness

- `app/build.gradle` applies the Android application plugin and KSP. The version catalog specifies AGP 9.3.1, Kotlin 2.4.10, and Room 2.8.4. Room and Glide already use KSP; enabling them is not a new migration prerequisite.
- Desktop and all core modules already apply Kotlin/JVM. Java/Kotlin targets use Java 17. Preserve the explicit `leanback_lite.kotlin.srcDirs` entry when changing source layout.
- EventBus still uses `annotationProcessor`. `Startup` installs the default bus without installing the generated index. Converted subscribers need public annotated methods and registration/unregistration checks; the existing Java processor is not proof that Kotlin subscribers are indexed.
- Do not combine source conversion with coroutine adoption, Compose, a Gradle Kotlin DSL rewrite, or dependency upgrades. Each changes a different contract and complicates diagnosis.

The official [built-in Kotlin migration guide](https://developer.android.com/build/migrate-to-built-in-kotlin) confirms that custom Kotlin source directories belong in Kotlin source sets. The [Kotlin Java interoperability guide](https://kotlinlang.org/docs/java-to-kotlin-interop.html) documents the field, static-method, overload, and checked-exception bridges relevant here.

## Ranked candidates inspected in source

All Android paths below are relative to `app/src/main/java/com/fongmi/android/tv/`; desktop paths are under `desktop/src/main/java/com/fongmi/tv/desktop/cast/`.

| Priority | Candidate | Benefit and constraints | Validation needed |
| --- | --- | --- | --- |
| 1 | `bean/QrChallenge.java` — 60 lines | Immutable parser used by login flows, without Activity ownership. Preserve nullable factory input, private constructor, static `from`, regex matching order, URL normalization and 8 MiB base64-length cap. A regular class preserves existing identity equality; a data class changes it. | Existing `QrChallengeTest` covers seven parsing scenarios. Add null input, CR/LF stripping, and size-boundary cases. Retain Java callers and `SiteViewModelTest` challenge propagation. |
| 1 | `media/hls/HlsPipeline.java` — 51 lines | Completes orchestration around recently converted transforms. Preserve transform order, last-valid-content fallback, confidence reduction, and immutable defensive copies. Java permits a null list and null varargs array; ordinary non-null Kotlin varargs would reject the latter. | Existing `HlsPipelineTest` covers composition, thrown transforms and invalid input. Add null constructor/array/content, null transform result, invalid transform output, collection mutation and null-element behavior. Decide how to preserve the nullable-array ABI before conversion. |
| 2 | `bean/Danmaku.java` — 94 lines | Removes a mutable Java bean boundary used by Kotlin PlaySpec and UI. Preserve Gson field names, null backing state, fallback display name, URL-only equality/hashCode, `isSelected`, and factories. Avoid automatic data-class equality. | `PlaySpecTest` exercises selection/copy behavior, not the full bean contract. Add JSON round-trip, null/empty name and URL, equality and local-file URL cases. |
| 2 | Desktop `DesktopRenderingControl.java` — 42 lines; then `DesktopAvTransport.java` — 114 lines | Small remaining Java adapters in an otherwise Kotlin desktop client. Keep inherited UPnP action signatures, exception declarations, event delivery, state ordering and synchronization. Convert separately from process ownership code. | `DesktopCastingTest` checks service binding and transport behavior. Extend rendering-control checks for mute, volume bounds, invalid instance/channel and duplicate event suppression. |
| 3 | `player/exo/AdaptiveWindowController.java` — 136 lines | A bounded state machine with tests in `ParallelRangeDataSourceTest`. Preserve exact integer arithmetic, thresholds, rollback, cooldown, constructor overloads, nested enum and Java-accessible methods. Kotlin `internal` is not JVM package-private and may mangle members. | Existing state-transition tests plus Java API comparison. If shared with desktop, extract policy to playback-core in a separately scoped architecture change. |
| 3 | `media/hls/TsVideoProbe.java` — 78 lines | Isolated parsing helper with `TsVideoProbeTest`. Kotlin is possible, but the dense bit manipulation offers less readability payoff than its file size suggests. Preserve unsigned-byte masks, widening before shifts, bounds and nullable fallback. | Existing SPS/PTS, malformed input and length tests; compare packet fixtures. Keep the Media3-dependent adapter on Android. |
| Later | `server/process/Cache.java`, `Parse.java`, `Media.java` — 134 lines combined | Small HTTP handlers, but request maps contain absent values and Media observes service teardown. Preserve missing-key behavior, response fields, error mapping and nullable service/player reads. | Add focused request/response and teardown cases before treating these as easy mechanical conversions. |

The first pair has a small review surface and useful existing tests. Neither requires altering provider cleanup, playback concurrency, database schema, or TV focus behavior.

## Tooling work to schedule with migration

1. **Coverage must include Kotlin outputs.** `app/build.gradle`'s `testKeyLogicCoverage` takes class files only from `intermediates/javac/.../classes`, while its include list names already converted classes such as GridSpecCalculator, Formatters, SearchProgress and SiteViewModel. Wire the selected variant's Java and Kotlin class artifacts into the report and verify those classes actually appear in XML. A passing report task alone does not establish complete coverage. This is a source-configuration finding; no coverage report was regenerated here.
2. **Boundary checks hard-code Java filenames.** Root `build.gradle` directly reads `History.java` and `Keep.java`. Convert those path lookups with either entity, requiring exactly one supported source file and retaining the forbidden-dependency assertions. Simply skipping missing files would weaken the guard.
3. **Source-governance and profiling checks need language-aware handling.** Existing audit findings remain applicable: RawThreadGovernanceTest and provider source-shape checks use Java paths/syntax; EPG/range/cast profiling scripts retain Java source references. Preserve historical Java baselines while resolving live Kotlin sources correctly.
4. **Refresh documentation counts after each batch.** Existing audit prose contains historical counts mixed with current inventory. Keep delivery reports historical, and label the active inventory snapshot clearly.

## Defer or retain deliberately

- **Startup:** its annotated non-null `Initializer<Void>.create()` returns null. A normal Kotlin non-null implementation cannot preserve that return. Keep Java until an explicit initializer contract change is justified.
- **Room entities and AppDatabase:** KSP is ready, but `Keep.key` demonstrates an uninitialized Java field annotated non-null. Neither `lateinit` nor an empty-string default exactly preserves the old state. Review construction/deserialization, field/accessor annotations, nullability, generated SQL and schema hashes individually.
- **Normalized records:** HlsTransformationResult and other records normalize nulls and use `List.copyOf`. Do not replace them mechanically with data classes or Kotlin read-only lists. Preserve constructor semantics, Java component accessors, equality, and record reflection where required. See [Kotlin JVM records](https://kotlinlang.org/docs/jvm-records.html).
- **MpvCastPlayer and DesktopCastService:** only 142 and 190 lines respectively, but they own processes, sockets, threads or services. MpvCastPlayer has volatile state, synchronized commands, pending futures and cleanup. Its integration test explicitly skips when `/usr/bin/mpv` is absent; a green desktop suite can therefore leave this runtime untested.
- **Provider engines and parallel playback:** later, isolated batches. Read the Ali/UC/Quark lessons before changing those areas and preserve account scope, quota-only fallback, cleanup and buffer/cancellation ownership.
- **Activity hierarchies:** migrate related base/subclass families deliberately. Kotlin finality, static inheritance and Android lifecycle callbacks make bulk conversion risky. TV changes require D-pad/focus checks without changing phone behavior.
- **QuickJS, CatVod, native/Python and guarded hosts:** preserve plugin-facing JVM signatures, JS handle ownership, reflection and runtime protocols. Tiny compatibility stubs provide little Kotlin benefit. Frozen vendor snapshots and pending-deletion sources are excluded.
- **Java tests:** retain useful Java tests to verify Kotlin interoperability.

## Delivery gates for a future conversion

1. Establish the current baseline and separate each batch from the existing uncommitted work.
2. Compare old/new public and protected JVM APIs, including static bridges, constructor overloads, checked exceptions, generic signatures, visibility and overridability. Inspect reflective fields and annotations separately.
3. Run affected behavioral tests and the app key-logic suite. Compile all six mobile/leanback/leanback-lite ARM64/ARM32 verification variants for Android changes; run desktop tests for desktop changes and report skips explicitly.
4. Run `verifyGenericLibraryBoundaries`, `verifyDesktopBoundaries`, and `verifyCodeLifecycleIsolation`. Shared policy belongs in `libraries/*-core` under `com.fongmi.tv.core.*`, following [desktop sync architecture](../desktop-sync-architecture.md).
5. For persistence changes, inspect generated Room implementations and compare exported schemas. For TV UI/focus changes, run `python3 scripts/check-leanback-remote.py`. Apply runtime validation appropriate to the changed feature only with device authorization.
6. Format converted Kotlin, check whitespace, and record tests actually run. Source review and successful compilation must not be reported as device tests.

## Review validation

Recounted source files and lines from the current filesystem; inspected build configuration, architectural rules, selected implementations and their tests; checked official Kotlin/Android documentation. No production code or existing migration work was changed. No builds, JVM suites, benchmarks or device tests were run for this documentation-only investigation.
