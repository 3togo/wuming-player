# Java, Kotlin and Rust boundaries

Current decision: keep a small, explicit Java surface where a contract requires
it; use Kotlin for JVM implementation and shared policy. A low Java file count
is not an acceptance criterion. This policy supersedes historical migration
rankings under `docs/migration/`.

## Review findings (2026-09-25)

1. **Documentation alone did not protect the contract.** The September 16 report
   retained a Java `HlsPipeline` facade for nullable varargs. The current tree
   had converted it to Kotlin. Its Java test still said it covered nullable
   arrays, but called `of()` twice instead of passing a null array. A new Java
   caller test reproduced `NullPointerException` for `of((HlsTransform[]) null)`.
   The facade is now Java again in `libraries/playback-core`; normalization and
   transformation stay in `HlsPipelineEngine.kt`. Current null-element filtering
   is preserved. Both the static factory and the previously generated companion
   factory remain callable. The Java test also checks the JVM varargs flag.
2. **Old retention reasons can expire.** `Startup` now implements
   `Initializer<Unit>` in Kotlin; the old `Initializer<Void>` argument no longer
   applies. This was a contract change, not proof that the former contract was
   mechanically convertible. `Keep` is also Kotlin now. Neither should be put
   on a Java-only list based on stale reports. This review does not establish
   runtime parity for those earlier conversions.
3. **Compatibility sensitivity is not a language impossibility.** CatVod's two
   `Spider` APIs and the desktop host's 90 `android.*` shim files expose names,
   fields, constructors and overridable methods to separately compiled jars.
   Retain these during routine conversion; release a scoped hold when ABI and
   linkage evidence supports it. Do not freeze all of CatVod, every provider,
   every native bridge, or entire host implementations as “must be Java.” The
   existing shim tests cover only part of that API.
4. **Rust has a linkage issue to resolve before expanding its use.** The public
   snapshot removed the mismatched prebuilts described below and uses the Kotlin
   fallbacks. The historical linkage evidence still governs any future adoption. Kotlin
   native callers use `com.wuming.tv.core.*`, while the shipped prebuilt
   `app/src/main/jniLibs/*/libtvbox_native.so` still exports
   `Java_com_fongmi_tv_core_*` symbols (verified on device: a search-result sort
   threw `UnsatisfiedLinkError` on `SearchTextMatcher.nativeFuzzyMatch`, because
   loading a library is not sufficient evidence that its methods link).
   `NativeLoader` now calls a `nativeProbe` canary after `System.loadLibrary`, so
   a library whose symbols do not link reports itself unavailable and every
   caller takes its Kotlin fallback instead of crashing. The canary is exported
   from `native/tvbox-native/src/lib.rs`, so the next rebuilt `.so` must be built
   from this crate. Rebuild and re-exercise the boundary before another Rust
   migration; native-source tests alone do not test JNI.

## The classification

The machine-readable authority is [java-retention.json](java-retention.json).
Each retained rule has exact paths, a stable ID, reason, evidence and explicit
release condition. Human-readable markers at the source or scoped directory
point back to the same rule.

| Classification | Current scope | Rule |
| --- | --- | --- |
| `language-required` | `HlsPipeline.java` | Retain Java while accepting a null array **and** exposing a JVM varargs factory. Delegate implementation to Kotlin. |
| `compatibility-hold` | Android and host `Spider.java`; 90 host `android.*` shims; `CastRemuxCache.java` record facade | Retain Java during routine migration. Kotlin is possible, but requires affected ABI and precompiled-consumer linkage evidence. |
| Review before migration | All other active Java, including UI, Room, playback, providers and worker/process orchestration | No Java-only constraint has been registered. Inspect behavior and ownership before converting; absence from the register is not approval for mechanical conversion. |
| Frozen vendor source | `isolated/vendor-snapshots/` | Follow [code lifecycle](code-lifecycle.md); do not migrate these sources or equate them with the shipped AARs. |
| Java caller tests/tools | Tests and profiling harnesses | Keep Java when it verifies the consumer API or supports the harness. They are not migration debt. |

Regenerate counts with `python3 scripts/check-java-retention.py --inventory`.
Discovery covers conventional Gradle module source sets (including verification
and flavor sets), excludes test sets/build output/isolated trees, and counts a
shared source only once. It does not interpret arbitrary Gradle source mappings.
The register protects 94 files: one language-required facade and 93 compatibility
holds. This is a reviewed boundary list, not a proof that every other source is
convertible or that every foreign API has been exhaustively audited.

## How to keep decisions from being lost

- Put language-required facades in **`<owning-module>/src/<source-set>/java-required/`**.
  The first is [HlsPipeline.java](../libraries/playback-core/src/main/java-required/com/wuming/tv/core/playback/hls/HlsPipeline.java).
  Its package is unchanged, so imports and JVM names stay stable. Per-module
  folders preserve dependency ownership and shared-core boundaries. Compatibility
  holds remain in their existing source roots.
- Register each dedicated directory in its module's Gradle Java source set;
  playback-core uses `sourceSets.main.java.srcDir('src/main/java-required')`.
  It is part of the existing `main` source set, not a separate module or artifact.
  The folder's `AGENTS.md` explains why it must stay Java. New entries still
  require exact paths and evidence in the register.
- Read this policy from root `AGENTS.md` before migration. The shim directory has
  its own `AGENTS.md` because its Java treatment differs from ordinary app code.
- `JAVA-RETENTION: <id>` comments identify the individual facades; the directory
  marker identifies the shim surface. Add exact paths, not broad new exemptions.
- `verifyJavaRetention` runs through root/module `check`, Android `preBuild`,
  and `unitAcceptanceBaseline` (used by CI and release acceptance). The Python
  checker is also runnable without Gradle or an Android SDK.
- The guard rejects missing/moved retained sources, missing markers/evidence,
  duplicate rules, unregistered additions to the shim scope, and a Kotlin type
  with the retained name/package, even in a different filename/source folder.
  Language-required entries must live in `java-required`; that folder rejects
  Kotlin, unregistered Java, and files classified only as compatibility holds.
  It is a lexical tripwire, not a Kotlin parser or ABI checker. It does not prove
  method behavior or prevent someone deliberately changing the register.
- Keep executable contract tests beside the boundary. The Java HLS test must
  actually pass a null array; a Kotlin-only test cannot substitute for that
  consumer call. Do not rewrite tests to accommodate an accidental conversion.
- To release a hold, update the implementation, rule, marker and contract tests
  together, recording how `release_when` was satisfied. A source rename or desire
  for language uniformity is not release evidence.

## Extraction and migration rules

**Kotlin is the default JVM implementation language.** Put shareable policy in
`libraries/*-core` under `com.wuming.tv.core.*`, following
[desktop sync architecture](desktop-sync-architecture.md). Keep Android and
desktop ports in their respective modules. Extract reusable behavior behind an
existing Java API only when it removes a real migration constraint; do not create
a new facade for every Java class. `HlsPipeline` is the concrete pattern: Java
constructor/factory/forwarder, Kotlin engine, Java consumer contract tests.

Review remaining code in coherent slices. Room/Gson beans need raw-null state,
field names, constructor/defaults, schema and serialization checks; KSP availability
does not settle them. Player/provider/service code needs lifecycle, cancellation,
threading and ownership checks. CatVod utilities, QuickJS and Chaquo need their
actual external-call contracts reviewed individually. A compatibility hold on
`Spider` does not establish that the rest of its package is safe to convert.

Kotlin can expose static methods, fields, overloads and checked exceptions using
its JVM interoperability controls. JNI itself also does not require Java: Kotlin
supports `external` methods. See the official [Java-facing API guide](https://kotlinlang.org/docs/java-to-kotlin-interop.html)
and [JNI interop guide](https://kotlinlang.org/docs/java-interop.html#using-jni-with-kotlin).
These facilities still require explicit treatment of null checks, wildcard
signatures, final/open behavior, reflection and default constructors.

**Rust requires its own justification.** Use it for a measured CPU/memory benefit
or a concrete native-library requirement. Do not translate UI, Room, lifecycle or
network orchestration just to remove Java. Before shipping a Rust change:

1. Define ownership, errors, encodings, numeric/Unicode semantics and cancellation
   at a narrow boundary; include JNI crossing/copy cost in any claimed benefit.
2. Compare Kotlin/JVM and Rust behavior on the same edge-case fixtures.
3. Check exported method names and descriptors against the actual compiled JVM
   callers; execute real JNI calls and test both native-present and fallback paths.
4. Build every supported target, including Android ARM32 and ARM64, and validate
   the desktop path. Device operations still require the task-specific authorization
   described in root `AGENTS.md`.

## Validation for this change

The new Java HLS contract suite first reproduced the null-array failure in the
Kotlin facade. After restoring the Java entry surface:

- `:libraries:playback-core:test`: 135 passed.
- `:guarded-catvod-host:test`: 14 passed, one hardware test skipped.
- Python tooling suite: 110 passed, including 10 retention/mutation checks.
- `:app:testMobileArm64_v8aVerificationUnitTest --tests
  com.wuming.android.tv.media.hls.HlsPipelineTest`: passed.
- `:desktop:compileKotlin`: passed.
- `verifyJavaRetention`, `verifyGenericLibraryBoundaries`,
  `verifyDesktopBoundaries`, `verifyCodeLifecycleIsolation`: passed.

Mutation tests exercise deletion/conversion, alternate Kotlin filenames, stale
markers/evidence, duplicate rules, unreviewed shim additions and scope exclusions.
`javap -protected -s` inspection confirmed the retained list constructor,
transform method, static factory, companion field and companion factory
descriptors. Kotlin metadata and its synthetic companion constructor are not
retained; this is not a claim of complete reflection compatibility.
No device testing, broad Java conversion, native migration, or performance claim
is part of this boundary review.

Folder-separation follow-up: all 13 retention tests and the four boundary tasks
passed. A clean playback-core build restored unchanged compilation/test inputs
from Gradle's cache and successfully produced the binary and sources JARs; the
sources JAR contains `com/wuming/tv/core/playback/hls/HlsPipeline.java` exactly once
from `src/main/java-required`. The package and source content are unchanged.

## Implementation migration follow-up

The [2026-09-25 implementation migration](migration/2026-09-25-kotlin-implementation-migration.md)
records completed batches, validation and the remaining queue. It does not release
any of the retention rules above.
