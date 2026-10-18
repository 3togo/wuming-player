# QR and HLS helper Kotlin conversion

Date: 2026-09-16. Implements the first batch from the [opportunity review](kotlin-opportunity-review-20260916.md).

## Changes

- Converted `bean/QrChallenge.java` completely to Kotlin. Retained Java static `from`, getters, identity equality, null input handling, regex priority, base64 length limit and URL normalization. Java `trim()` semantics are explicit because Kotlin's default trimming recognizes more Unicode whitespace.
- Moved `media/hls/HlsPipeline` orchestration into `HlsPipelineEngine.kt`. Retained the 22-line Java facade: its `of(HlsTransform...)` API accepts a null array, whereas an ordinary Kotlin vararg parameter rejects it. The original constructor, static varargs method and transform signatures remain. The Kotlin engine keeps `List.copyOf` behavior, transform order, confidence reduction and last-valid-playlist fallback.
- Added eight Java regression tests covering null inputs/arrays/context, defensive copying, null elements, invalid/null outputs, fatal errors, QR input limits, parsing priority and whitespace. Existing Java tests continue to exercise the Kotlin-facing JVM API.
- Updated the current audit and inventory: **267 production app Java files / 64,668 lines**. The historical opportunity review remains a pre-conversion snapshot.

These changes do not alter provider policy, TV focus behavior, database schemas, shared-core ownership or dependencies. The pre-existing working-tree conversions remain intact. Coverage-output wiring and the History/Keep filename guards identified in the review remain separate follow-up work; this batch does not touch those classes or claim to repair those tools.

## Verification

| Check | Result |
| --- | --- |
| Focused QrChallenge/HlsPipeline suite | 18 passed |
| Same 18 tests against separately compiled original Java implementations | 18 passed |
| Full mobile ARM64 verification JVM suite | 832 passed, 1 skipped |
| Full leanback ARM64 verification JVM suite | 839 passed, 1 skipped |
| All six Android production verification variants | Compiled: mobile/leanback/leanback-lite, ARM64/ARM32 |
| Architecture and lifecycle boundaries | All three passed |
| Original vs converted public/protected JVM API comparison | No missing or changed existing members for QrChallenge and HlsPipeline |
| Formatting and whitespace | Kotlin formatted with ktlint 1.8.0; git diff --check passed |

API comparison uses `javap -protected -s` against separately compiled original Java and current mobile output, comparing existing declarations/descriptors while permitting final methods on the already-final classes. The Java regression suite also verifies that the HlsPipeline factory retains its JVM varargs flag. This is a focused API comparison, not a proof of every reflective contract.

The broader JVM total is **1,671 passed and 2 skipped**, with no failures or errors. The focused and original-Java runs are additional checks, not included in that total. No device tests were run. No coverage percentage or performance improvement is claimed.

The main validation command was:

```sh
./gradlew-safe :app:testKeyLogic \
  :app:compileMobileArmeabi_v7aVerificationJavaWithJavac \
  :app:compileLeanbackArmeabi_v7aVerificationJavaWithJavac \
  :app:compileLeanback_liteArm64_v8aVerificationJavaWithJavac \
  :app:compileLeanback_liteArmeabi_v7aVerificationJavaWithJavac \
  verifyGenericLibraryBoundaries verifyDesktopBoundaries verifyCodeLifecycleIsolation
```

`testKeyLogic` also compiles mobile and leanback ARM64 production code. A compact result summary is saved in [verification.json](../../PUBLIC_PREPARATION.md).
