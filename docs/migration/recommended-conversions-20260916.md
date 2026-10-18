# Recommended Kotlin conversions and tooling

Date: 2026-09-16. Implements the immediate tooling work and candidates 1–5 from the [remaining opportunities review](remaining-kotlin-opportunities-20260916.md).

Subsequent authorized device run: [smoke-test results](device-smoke-20260916.md). TV runtime checks passed, but a provider native startup crash interrupted UI testing; the phone remained blocked by its secure lock screen. The offline validation below records the original implementation delivery.

## Changes

Converted eight production classes (698 original Java lines):

- `player/exo/AdaptiveWindowController`
- `media/hls/TsVideoProbe`
- `server/process/Cache`, `Parse`, and `Media`
- `bean/Filter` and `Value`
- `dlna/OkHttpStreamClient`

Paths are under `app/src/main/java/com/fongmi/android/tv/`; each Java file has one Kotlin replacement. Updated `Url` and the Gson `FilterAdapter` for explicit Kotlin accessors/nullability. Existing uncommitted migration work was preserved.

The Android app now has **258 production Java files / 63,876 lines**, down from 266 files / 64,574 lines. The current [inventory](java-to-kotlin-inventory.csv) and [audit](java-to-kotlin-audit.md) reflect this delivery; historical reports retain their original counts.

### Coverage and source guards

`testKeyLogicCoverage` now collects the selected variant's app-owned Java and Kotlin classes through AGP's scoped class-artifact API. It includes nested classes, fails if an intended class is absent, and supports configuration-cache reuse. The obsolete app `RegexUtils` entry was removed because that class is now owned and tested by `text-core`.

The generated XML contains all six intended app classes, each with executed instructions: GridSpecCalculator, SearchHelper, Formatters, SitePerformance, SearchProgress and SiteViewModel. There are 14 reported classes including nested implementations. This remains a selected app-logic report, not whole-project coverage.

The root generic-library guard now resolves exactly one `.java` or `.kt` file for History, Keep and ViewingHistoryTracker. Missing and duplicate files fail explicitly; prohibited dependencies are still checked in either language. Four temporary source fixtures verified missing-file rejection, duplicate rejection, Kotlin acceptance and forbidden-import rejection. The original files were restored afterward.

### Preserved behavior and explicit JVM differences

- Adaptive probing keeps arithmetic, thresholds, cooldown, rollback, constructor overloads and static entry points. Kotlin has no package-private class visibility: the controller is Kotlin `internal`, with public JVM visibility and unmangled callable members. This widening is deliberate and documented in source.
- TS probing retains unsigned-byte handling, all 33 timestamp bits, packet boundaries, runtime-exception fallback and nullable results.
- Request handlers retain prefix routing, absent parameter behavior, status/error mapping and separate nullable service/player reads. Their entry points implement the existing non-null Kotlin `Process` interface contract.
- Filter/Value retain Gson field names, raw null state, activation, deep copies, value-only equality/hash, static factories, overridability and manual parcel order. They remain ordinary mutable classes. The existing exception from `Filter.check()` on the default immutable empty list is preserved.
- Android's HTTP adapter retains its timeout arithmetic, system proxy selection, request bodies/headers, response-body closure, cancellation and executor ownership. It does not acquire desktop's explicit proxy bypass or 1 MiB response cap.
- JVM comparison found no missing existing callable signatures or unexpected declaration changes across the eight classes and two named nested types. Three compiler-generated generic bridge methods in OkHttpStreamClient change from protected to public; their `ACC_BRIDGE`/`ACC_SYNTHETIC` flags were verified. Their typed overrides remain protected. Kotlin companion/helper additions and the documented visibility widenings mean this is not byte-for-byte or reflection-shape identity.

## Validation

Added 13 JVM regression tests: adaptive bounds/reset/missing samples; TS timestamp/marker/malformed data cases; model serialization/copy/activation/equality contracts; handler parameter/error cases; and transport wire shape, ownership and response closure. The HTTP tests use controlled calls and response bodies without network/device access.

| Check | Result |
| --- | --- |
| Mobile ARM64 verification JVM suite | 850 passed, 1 skipped |
| Leanback ARM64 verification JVM suite | 857 passed, 1 skipped |
| **Combined JVM result** | **1,707 passed, 2 skipped; no failures/errors** |
| Java-original baseline | Targeted suite successful against separately compiled saved originals |
| Android production compilation | All six mobile/leanback/leanback-lite ARM64/ARM32 verification variants passed |
| Instrumentation source compilation | Mobile and leanback ARM64 passed |
| Architecture/lifecycle | All three boundary tasks passed |
| Coverage | XML class presence/execution verified; subsequent invocation reused configuration cache |
| TV source checks | Passed |
| Formatting and whitespace | ktlint 1.8.0 and `git diff --check` passed |

The two suite skips are the optional local HLS investigation fixture, once per flavor. Java-original baseline runs are additional compatibility checks and are not added to the full-suite totals.

Two new instrumentation cases verify Value's parcel field/activation-byte order and Filter's nullable fields/list independence. **These tests were compiled, not run on a device.** Physical DLNA interoperability, Android asset-backed parsing, active service/player responses and framework parcel execution remain runtime validation limits. No app installation or device access occurred.

Machine-readable results: [verification.json](../../PUBLIC_PREPARATION.md).

## Retained and deferred

HlsPipeline's Java nullable-varargs wrapper and Startup's current initializer contract remain intact. LazyRoomMediaCatalog remains a later initialization/concurrency batch. Large activities, provider engines, playback ownership, remaining persistence models and compatibility runtimes remain deferred as recommended. No dependency upgrades, coroutine/Compose adoption, architecture extraction, UI navigation changes or performance improvement are claimed.
