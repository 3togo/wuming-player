# Test strategy: offline acceptance and two optional smoke levels

## What decides whether a routine change is acceptable?

The deterministic offline baseline is mandatory:

```bash
./gradlew-safe --offline unitAcceptanceBaseline
```

It runs JVM tests for production modules, Python tooling tests, and architecture
boundaries. Dependencies must already be cached for `--offline`. Release acceptance
also runs lint, clean-source/signing checks and release assembly for mobile ARM64 and TV ARM64/ARM32 through
`scripts/verify-release.sh`. Neither acceptance command requires a connected device.

Unit tests cannot prove native ABI compatibility, OEM focus/input behavior, actual
codecs, or the state of remote providers. Device tests provide additional evidence
for those risks; they are not a substitute for missing unit tests or a universal
requirement after every change.

## The two smoke levels

| Level | Purpose | Contents | When useful |
| --- | --- | --- | --- |
| Production smoke | Small production-readiness check | 11 selected tests: package/manifest, parcel restoration, playback routing/engine creation, critical phone or TV UI; launch, background return and restart checks | Candidate builds; manifest, startup, UI or platform-boundary changes |
| Deep smoke | Integration gaps the quick smoke cannot cover | Includes production smoke, adds 21 tests: loopback HTTP, in-memory Room, platform SAX, adaptive-range cancellation/recovery, QuickJS ownership, player lifecycle and extra UI lifecycle cases | Relevant dependency upgrades, native/resource changes, risky releases, investigation |

The selections are versioned in `tests/smoke/suites.json`. Each test is listed as
`class#method`; every selection is checked offline against the correct source set.
Deep smoke is an explicit superset, not a separate set whose quick checks can drift.

Both levels run **production code in the isolated verification build**. They do not
install the normal app and do not prove the exact signed/minified release APK.
Signing, release lint/assembly and artifact provenance remain separate release
checks. The short launch checks establish launch/focus at their sample points;
they are not long stability or performance measurements.

### Review a selection without touching a device

```bash
python3 scripts/run-smoke-tests.py --tier production --mode leanback --dry-run
python3 scripts/run-smoke-tests.py --tier deep --mode mobile --dry-run
```

### Run on an explicitly authorized, unlocked device

```bash
python3 scripts/run-smoke-tests.py \
  --tier production --mode leanback --abi armeabi_v7a --serial AUTHORIZED_SERIAL --offline

python3 scripts/run-smoke-tests.py \
  --tier deep --mode mobile --abi arm64_v8a --serial AUTHORIZED_SERIAL --offline
```

Omit `--offline` if Gradle needs to resolve dependencies. `--no-build` installs
existing APK outputs; it does not silently test a previously installed version.
The runner verifies metadata and the actual APK package via SDK `aapt`, checks the
device ABI, and uses `adb -s` for every operation. It never auto-selects a device.

The two installed packages are `無名(testing)` and `無名(test runner)`. Installation
uses `-r` to preserve data; no package uninstall, application-data clear, or signing
substitution is attempted. Read `docs/android-instrumentation-lessons.md` before
running. The runner restores the original power setting, stops the verification
process, and returns Home, including failure paths. Secure keyguards require the
user's on-device unlock.

Non-UI groups keep the verification app foregrounded to avoid OEM background
freezing. UI groups only receive wake events. Starting Home may load the user's
configured provider; the suite's HTTP fixtures themselves use loopback, but a
device run is not promised to make zero external requests.

### Results must be exact

A successful group requires the expected start and finish method sets exactly once,
all finish statuses zero, a successful shell exit and final runner code `-1`.
Skipped/assumption-failed tests, crashes, missing/duplicate tests and host timeouts
are failures or incomplete evidence. There are no automatic retries to turn a
failed run green. Each invocation gets a new report directory with its exact
selection, APK hashes, per-group output, remaining groups and cleanup results under
`app/build/reports/smoke/`. Report failures honestly even if a later attempt passes.

## What neither smoke level proves

| Risk | Appropriate additional evidence |
| --- | --- |
| Provider JAR/config drift, missing delegate, stale native extraction | Explicit staged-provider diagnostic through loader **and** init/home, using `NativeProviderLoaderInstrumentedTest`; pin JAR hash and record config API |
| Real account login, cloud-save/cleanup contracts | Authorized provider-specific stateful tests with exact-file cleanup and account boundaries |
| Codec compatibility or sustained playback | Local media/hardware playback tests, then authorized real-provider decoded-progress tests |
| DLNA discovery and renderer behavior | Authorized LAN renderer tests; loopback HTTP is not LAN interoperability |
| OEM background suspension, long-session leaks, slow networks | Bounded device soak/performance comparisons with matching device/build baselines |

These tests are explicit opt-ins because they require fixtures, accounts, receivers,
or hardware conditions. They are not included as skipped tests to inflate coverage.
An unavailable prerequisite means **not tested**, not pass. Keep real-provider
failures distinct from app-policy regressions and from test-environment interference.

## Explicit remote-input and public-provider smoke

For additional Leanback key injection and real decoded playback of a bundled public
channel, opt in explicitly:

```sh
python3 scripts/run-smoke-tests.py --tier production --mode leanback \
  --abi arm64_v8a --serial AUTHORIZED_SERIAL --offline \
  --tv-navigation --live-channel 'Big Buck Bunny - Blender Foundation (CC BY 3.0)'
```

`--tv-navigation` adds the editor/dialog D-pad checks, OK activation and Back dismissal,
a Settings route check driven by injected keys, and the wallpaper startup regression.
It requires Leanback. `--live-channel` is independently usable with either flavor;
its name must exactly match `app/src/main/assets/live/public_samples.m3u8`.
It launches the production VideoActivity direct-URL route, requires video dimensions
and at least 30 rendered decoder buffers within 60 seconds, then observes 15 seconds
with at least 10 seconds of observed progress (excluding seeks/live-window shifts), 60 further rendered frames, no player
error, no eight-second stall, and active playback. The default host deadline is 115 seconds.
It never substitutes a local clip or silently selects a different provider on failure.
Provider metadata/redirect inspection alone cannot pass this check. Add
`--live-seconds 120` for a two-minute observation (allowed range 15–300; default 15).
The host allows the observation duration plus 100 seconds for startup/cleanup.
JSON diagnostic samples are saved during startup/observation and collected after
success, failure or timeout when available. They include frame/buffer/state and
sanitized load-error/discontinuity categories, never signed URLs or credentials.
A missing artifact fails an otherwise passing diagnostic group.

The production tier plus both options selects 16 methods. The same options with the
deep tier deduplicate the existing dialog methods. Evidence includes exact test
results, channel name, decoded dimensions/progress/frame count and APK fingerprints.
The channel can create normal playback history in the isolated testing app. Neither
option logs into an account or exercises cloud-save routes. A pass on a phone running
the Leanback flavor proves that app's injected-key behavior on that phone; it is not
physical-TV/OEM remote certification. Public direct-stream decoding does not prove
VOD catalog/JAR resolution, paid-account playback, or every provider.

Mobile and Leanback verification flavors share a package ID. Installing one replaces
the other with data retained; restore the previous flavor after cross-flavor testing.

## Disposable persistence smoke (APP-015)

Add `--persistence` to either flavor's explicit smoke command. Its nine additional methods
are enumerated in `tests/smoke/persistence.json`; production plus this option selects
20 methods. It uses in-memory or uniquely named test databases and private recovery
fixtures; it does not restore or clear the user's library. Recovery UI tests stub
the system picker result but exercise the actual export and unsupported retry code,
then reopen the exported SQLite/WAL to verify its rows. Artwork and a controlled
loopback playback-starvation/reset diagnostic are included.

See [APP-012–APP-015 implementation/evidence](reviews/followups-2026-09-25.md) for
regressions, exact runs and limits. Normal smoke remains account-free and does not
silently enable these longer checks or real-provider observations.

## Growing the unit suite

For every defect that exposes deterministic behavior, add a regression at the
smallest production boundary, not a test that copies the implementation:

- Test observable results and rejected states, including failure/cancellation paths.
- Exercise the complete contract being claimed. A provider wrapper constructor is
  insufficient: init may fail because its delegate is null. `JarLoaderTest` now
  covers that case, missing/wrong implementations, constructor failure, and a
  successfully initialized provider serving with its site identity and options.
- Use synthetic providers, clocks, transports and temporary files. Keep real
  credentials, downloaded binaries and provider availability out of unit acceptance.
- Put shared product rules in `libraries/*-core`; keep Android ports and native
  interop tests in the Android modules. Check both happy and adversarial behavior.
- Keep one focused runtime check where JVM stubs cannot establish correctness.
  Do not claim unit mocks validate JNI, real Android Parcelable/Room, or codecs.

The smoke-runner unit tests deliberately feed it incomplete runs, skips, duplicates,
setup failures, crashes and failed cleanup. Those tests protect the meaning of a
reported pass without needing a device.

## CI and scope of validation

The existing required CI baseline includes the Python manifest/runner tests and the
expanded JVM contracts. `assembleCriticalTests` compiles mobile ARM64 and leanback
ARM64/ARM32 fixtures; compilation is not reported as a successful device test.

The optional, non-blocking device job uses this runner when `RUN_DEVICE_MATRIX=true`.
Configure authorized `ANDROID_PHONE_SERIAL` and `ANDROID_TV_SERIAL`, plus optional
`ANDROID_PHONE_ABI` and `ANDROID_TV_ABI`. It never infers authorization from `adb devices`.
`ANDROID_SMOKE_TIER` defaults to `production`; manual workflow dispatch can select
`deep`. Self-hosted runners need the existing signing identity to update installed
verification packages. They also need the relevant SDK/NDK components and caches.

Legacy exploratory and stateful scripts remain specialized tools. The versioned
runner above is the entry point for the two standard smoke levels. Adding it does
not mean the new combined suites have already passed on hardware: record their
actual run evidence separately from unit tests and APK compilation.

See [the platform capability matrix](platform-capabilities.md) for release-candidate runtime checks
and current desktop cloud support.

## Candidate evidence ledger (APP-008)

A successful `scripts/verify-release.sh` now creates
`app/build/reports/acceptance-baseline/runtime-evidence.md`, linking a candidate
ledger under `evidence/`. Each candidate is identified by commit, variant, APK
SHA-256 and signing-certificate SHA-256. All runtime checks initially say
**not-tested**. Repeating initialization preserves observations for the same
candidate; changed APKs create a different ledger.

After a separately authorized runtime check, record its result locally:

```sh
python3 scripts/release-evidence.py record PATH_TO_CANDIDATE_JSON \
  --variant leanbackArm64_v8a --level exact-release --check first-frame \
  --status passed --device AUTHORIZED_SERIAL \
  --apk PATH_TO_EXACT_RELEASE_APK --evidence PATH_TO_SANITIZED_OBSERVATION
```

Levels are `verification-build`, `exact-release`, and `live-provider`. Status is
`passed`, `failed`, or `not-tested`. Verification-build observations additionally
require `--tested-apk PATH_TO_VERIFICATION_APK`; they never establish exact-release
playback. Evidence files are referenced and hashed, not copied into the ledger.
Keep those files free of credentials and signed media URLs. The tool verifies
artifact identity but does not independently attest the observer's claim or grant
device/account authorization. A changed candidate cannot inherit previous results.

CI also uploads every production module's JUnit XML/HTML reports, app lint/coverage,
and `build/reports/test-summary/` for 14 days, even after a failed invocation. The
summary records the baseline step outcome and available report counts; missing or
cached reports are not fresh passes.

APP-016 adds a changing-manifest/cache regression to `--persistence` (20 methods on mobile).
See [adaptive cache validation](reviews/adaptive-cache-2026-09-25.md).
