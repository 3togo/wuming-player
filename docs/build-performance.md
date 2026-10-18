# Android build performance

For everyday development, select one app variant:

```sh
./gradlew :app:assembleMobileArm64_v8aDebug
./gradlew :app:assembleLeanbackArm64_v8aDebug
./gradlew :app:assembleLeanback_liteArm64_v8aDebug
```

Choose one command. Replace `Arm64_v8a` with `Armeabi_v7a` for 32-bit ARM.
APKs are under `app/build/outputs/apk/`. The root `assembleDebug` task builds
only `mobile-arm64_v8a` and `leanback-armeabi_v7a` app variants, the Android helper applications, and
Android library assemblies. It also copies app APKs to `debug/`, excluding
stale APKs from other variants. `assembleRelease` uses the same selection for `release/`.
Explicit variant commands remain available without an extra flag.
To include all six variants in aggregate builds and APK copying, use:

```sh
./gradlew assembleDebug -PincludeAllVariants=true
./gradlew assembleRelease -PincludeAllVariants=true
```

This selection applies to `assembleDebug` and `assembleRelease`; verification
tasks and other aggregates retain their existing coverage. The signed APK
workflow explicitly opts into all modes. Do not run `clean` between edits.
The previous `-PincludeLeanbackLite=true` flag remains an alias for all variants;
an explicit `includeAllVariants` value takes precedence.

## Local measurement, 2026-09-28

Measured before excluding lite from the default aggregates, against the
existing working tree and warm build caches, without
cleaning, changing application sources, or accessing a device:

```sh
./gradlew-safe :app:assembleMobileArm64_v8aDebug --offline --profile --info --console=plain
# Repeat the identical command to measure an unchanged build.
```

The first invocation succeeded in 14 seconds (17 tasks executed, 102 up to date).
The unchanged repeat succeeded in 2 seconds (8 executed, 111 up to date), reusing
the configuration cache. `--offline` was appropriate because this machine
already had the dependencies; omit it when dependencies need downloading.

The first build consumed changed playback-core output. App KSP ran for 4.808s,
Java compilation took 1.380s overall, and the Java compiler reported only 17
classes compiled incrementally in 0.838s. App Kotlin compilation was up to date.
In the repeat, app KSP, Kotlin, and Java compilation were all up to date.
The remaining executed tasks were verification/signing checks, not a complete
app recompilation. These measurements do not establish the cost of an arbitrary
source edit or a cold build.

A subsequent root `assembleDebug` with the same profiling flags succeeded in
21 seconds: 425 actionable tasks, 42 executed, 5 from cache, 378 up to date.
This was already warmed by the single-variant builds, so it is not a controlled
speedup comparison.

The root task's dry run confirms separate KSP, Kotlin, and Java compile tasks
for all six app variants. An executed compile task alone does not mean that
every source in that module was recompiled; inspect its `--info` output.
Gradle writes local timing reports under `build/reports/profile/`.

## Module boundaries still worth improving

Production Java/Kotlin source inventory (excluding test source sets) found
671 files / 96,834 lines in `app`, compared with 38 files / 3,291 lines in
`playback-core`, 23 / 1,077 in `cloud-core`, and 15 / 698 in `user-library-core`.
These are source-size counts, not compile-time attribution. The main Android
packages include roughly 15,368 lines of UI, 12,117 of cloud integration, 9,731
of utilities, and 8,289 of player code, before flavor-specific code.

The existing core modules therefore do not yet isolate most Android feature
implementation. Candidate extractions, subject to dependency review:

- Android persistence: isolate Room entities, DAOs, database implementation,
  and processing behind repository contracts. This could reduce the input
  scope of the app's Room processing, but entity dependencies must be untangled
  first.
- Cloud adapters: separate provider implementations from application UI and
  global state; keep portable policies in the existing cloud core modules.
- Player and cast adapters: isolate Android implementations behind playback
  contracts, retaining shared policy in playback-core.

Measure each extraction with the same private implementation edit, public API
edit, and unchanged rebuild before claiming an improvement. Avoid simply moving
the entire app into another large module or exporting all dependencies with
`api`. ABI flavors also duplicate compilation work; replacing them with APK
splits would require a separate compatibility review of artifact names,
publishing, signing, native packaging, and verification task consumers.

Follow `desktop-sync-architecture.md` for shared behavior and the Java retention
register before any language migration. Module extraction does not require
converting retained Java facades.
