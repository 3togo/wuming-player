# Remaining Java-to-Kotlin opportunities

> Historical snapshot. Current decisions and release criteria live in
> [language boundaries](../language-boundaries.md) and the enforced
> [Java retention register](../java-retention.json). Do not use this candidate list
> or its counts as the current migration plan.

Snapshot: 2026-09-16, current working tree including existing uncommitted conversions.

Implementation: [recommended conversions and tooling](recommended-conversions-20260916.md). The inventory and ranking below preserve the pre-implementation snapshot; see the delivery report and current CSV for updated counts.

## Conclusion

Continue incremental migration. Kotlin is already established in the build, desktop, shared libraries and Android UI. The next useful work is **migration tooling, followed by small tested helpers**. A zero-Java target would encourage unnecessary rewrites of compatibility adapters and runtime-sensitive code.

This review supersedes the candidate ranking in `kotlin-opportunity-review-20260916.md`; that report's QrChallenge, Danmaku and desktop receiving-adapter recommendations have already been implemented in the working tree. HlsPipeline now delegates to a Kotlin engine and deliberately retains a Java API wrapper. Existing delivery reports are historical evidence, not tests rerun by this investigation.

## Inventory

Existing production files under `src/main`, `src/mobile`, `src/leanback`, and `src/leanback_lite`; excludes build/bin outputs, tests, verification sources, pending deletion and isolated snapshots. Lines include comments and blanks. Shared leanback sources are counted once.

| Area | Java files | Java lines | Kotlin files | Kotlin lines |
| --- | ---: | ---: | ---: | ---: |
| Android shared | 215 | 49,267 | 312 | 23,744 |
| Android mobile | 23 | 6,791 | 32 | 3,333 |
| Android leanback | 28 | 8,516 | 56 | 4,761 |
| **Android total** | **266** | **64,574** | **400** | **31,838** |
| Desktop | 4 | 412 | 67 | 11,078 |
| Shared libraries | 2 | 467 | 144 | 11,955 |
| QuickJS | 12 | 1,217 | 0 | 0 |
| CatVod | 25 | 3,037 | 0 | 0 |
| Chaquo | 3 | 336 | 0 | 0 |
| Guarded CatVod host | 103 | 3,524 | 0 | 0 |
| Guarded WebView helper | 3 | 455 | 0 | 0 |
| Guarded spider helper | 2 | 496 | 0 | 0 |

Android is 60.1% Kotlin by files and 33.0% by lines. These are language-mix measurements, not migration completion or complexity estimates. Large remaining files include ParallelRangeDataSource (1,681 lines), UcOpenDriveProvider (1,472), QuarkOpenDriveProvider (1,445), and BaseVideoActivity (1,391).

## Build readiness and tooling priorities

The local catalog specifies AGP 9.3.1, Kotlin 2.4.10, Room 2.8.4 and KSP 2.3.12. Android uses built-in Kotlin; desktop and core modules apply Kotlin/JVM. Room and Glide already use KSP. No plugin/dependency upgrade is required merely to begin another source conversion. Preserve Java 17 targets and the explicit leanback-lite Kotlin source directory. Android documents custom source configuration in its [built-in Kotlin guide](https://developer.android.com/build/migrate-to-built-in-kotlin).

1. **Repair coverage inputs first.** `app/build.gradle:268` defines `testKeyLogicCoverage` using only the variant's javac class directory. Its includes name already converted Kotlin classes such as Formatters and SiteViewModel. Resolve Java and Kotlin class artifacts for the same variant and verify their presence in report XML. This is a configuration finding; actual report omissions have not been measured here.
2. **Make source guards language-aware.** Root `build.gradle:120` reads History.java and Keep.java directly. Update these lookups alongside either conversion, requiring exactly one Java/Kotlin source and preserving the prohibited-dependency assertions. The prior audit also identifies Java-path/syntax assumptions in governance tests and profiling scripts; audit those for each chosen class.
3. **Keep EventBus validation separate from KSP readiness.** EventBus remains on `annotationProcessor`; Startup installs the default bus without an explicit generated index. Kotlin subscribers require preserved public annotations, reflection discovery and registration lifetimes.

## Ranked next candidates

Android paths are relative to `app/src/main/java/com/fongmi/android/tv/`.

| Order | Candidate | Opportunity | Required contract checks |
| --- | --- | --- | --- |
| 1 | `player/exo/AdaptiveWindowController.java` — 136 lines | Bounded state machine; existing Java tests in ParallelRangeDataSourceTest cover gain/loss thresholds, probe rollback, cooldown, reset and restoration. Good first behavioral migration. | Preserve integer arithmetic, overloads, enum, static helpers and call order. It is package-private: Kotlin `internal` is not an exact visibility replacement. Choose that API tradeoff explicitly. |
| 2 | `media/hls/TsVideoProbe.java` — 78 lines | Small parsing adapter with existing packet fixtures and malformed/null/size tests in TsVideoProbeTest. | Preserve unsigned-byte masks, widening before shifts, packet bounds, timestamp arithmetic and null-on-failure behavior. Kotlin bit operations may be more verbose; readability benefit is modest. Keep its Media3 dependency in the Android adapter. |
| 3 | `server/process/Cache.java`, `Parse.java`, `Media.java` — 134 lines combined | Short handlers where explicit nullable values can clarify request and service handling. | Add focused tests first: missing parameters, exact response/status fields, error mapping, and service/player teardown. Preserve prefix routing and legacy null/string behavior. |
| 4 | `bean/Filter.java` and `Value.java` — 248 lines combined | Related mutable models used across UI code; migration can make their state contracts clearer. | Preserve Gson fields, raw null state, deep copy, activation toggling, Value's value-only equality/hash, static factories and Parcelable ordering/CREATOR. Filter.check removes null list entries. Do not introduce data-class equality or switch to automatic parcel generation in the same batch. No dedicated contract suite was identified in the inspected bean tests. |
| 5 | `dlna/OkHttpStreamClient.java` — 102 lines | Desktop now has a Kotlin implementation that is useful as an interoperability reference. | Preserve request bodies, repeated/null headers, timeout arithmetic, response closure, cancellation, overridability and executor shutdown. Add transport tests before conversion. Desktop explicitly disables proxies and caps responses at 1 MiB; Android currently does neither. Copying desktop code would change behavior. |
| Later | `media/catalog/LazyRoomMediaCatalog.java` — 43 lines | Compact implementation of an existing shared-core port. | Preserve initialization on first operation, synchronized single publication, retry after initialization failure and database threading constraints. A small line count does not remove concurrency risk. |

The first two candidates total 214 Java lines and already have targeted tests. Treat them as separate reviewable changes. If the adaptive policy is extracted for desktop reuse, place it in playback-core in a separately scoped architecture change, following [desktop sync architecture](../desktop-sync-architecture.md).

## Java worth retaining or deferring

- **HlsPipeline:** its 22-line Java wrapper accepts a null varargs array and delegates to HlsPipelineEngine.kt. Ordinary Kotlin non-null varargs would change entry behavior. This is a deliberate compatibility boundary, not unfinished logic conversion.
- **Startup:** `Initializer<Void>.create()` is annotated non-null but returns null. Retain Java until deliberately changing the initializer contract.
- **Room entities:** Keep's non-null primary-key field starts uninitialized. Neither a Kotlin empty-string default nor `lateinit` reproduces that observable state. Review entity construction, Gson, annotation targets, generated SQL and exported schema before conversion. KSP availability alone does not settle those issues.
- **Normalized records:** preserve compact-constructor normalization, defensive copies, Java accessors, equality and any record reflection. Kotlin documents [JVM record support](https://kotlinlang.org/docs/jvm-records.html), but the annotation alone does not translate constructor normalization.
- **Desktop remainder:** CastPlayer includes a nested record; DesktopUpnpConfiguration owns an HTTP listener; MpvCastPlayer and DesktopCastService own process/service lifecycles. None is urgent merely to finish desktop's four remaining Java files.
- **Activities, playback and providers:** migrate coherent inheritance/ownership groups after focused baselines. Preserve callbacks, synchronization, cancellation and resources. Read Ali/UC/Quark lessons before provider work. TV navigation needs its own checks.
- **Compatibility hosts, QuickJS, CatVod and Chaquo:** prioritize measured maintenance benefit and runtime coverage over language uniformity. Keep plugin-facing signatures, reflection, native/JS ownership and protocol behavior. Frozen vendor snapshots remain excluded.
- **Java tests:** retain useful ones to exercise Kotlin's Java-facing API.

Use explicit static bridges, fields, overloads and checked-exception declarations where existing callers require them. Kotlin's [Java interoperability reference](https://kotlinlang.org/docs/java-to-kotlin-interop.html) documents these controls and the visibility differences; automatic conversion is only a starting point.

## Suggested delivery sequence

1. Capture the existing working-tree baseline; isolate new work from ongoing conversions.
2. Fix coverage inputs and establish language-aware guard handling where relevant.
3. Convert AdaptiveWindowController, then TsVideoProbe, preserving behavior and comparing JVM signatures. Add edge cases only where coverage is missing.
4. Run affected suites and `:app:testKeyLogic`; compile the six mobile/leanback/leanback-lite ARM64/ARM32 verification variants. Run `verifyGenericLibraryBoundaries`, `verifyDesktopBoundaries`, and `verifyCodeLifecycleIsolation`.
5. For later batches, add persistence schema checks, desktop/transport tests, or `python3 scripts/check-leanback-remote.py` as appropriate. Runtime/device validation needs task-specific authorization.

Keep coroutine adoption, Compose, dependency upgrades, architecture extraction and Gradle DSL conversion separate from language-only batches. The expected benefit is easier maintenance and clearer contracts; no performance, APK-size or build-time improvement was measured.

## Investigation performed

Recounted production sources; inspected architecture, build configuration, prior reports, selected Java implementations, Kotlin counterparts and test references; consulted official Kotlin/Android documentation. Only this report was added. No production source changes, builds, test execution, benchmarks or device access were performed in this investigation.
