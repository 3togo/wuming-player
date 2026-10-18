# Public source preview

This snapshot is intended for source review and separately installed verification
builds. It is not a stable APK release. The normal app is `無名`; verification
builds use `無名(testing)` and application ID `com.wuming.android.tv.publicedition.verification`.
The public package is `com.wuming.android.tv.publicedition`, separate from existing
private installations. An independent production certificate is pinned in updates.json. Its private key and
configuration remain outside source control. The public repository and update channel are `3togo/wuming-player`.
The first prerelease is `v6.4.5-preview.1`; TV/device checks remain pending.
No support group has been configured.
Existing private signing keys must not be copied into this repository. The guarded
Spider/WebView helper packages also add `.publicedition`; desktop bridge components
point to those separate packages while preserving the existing class namespaces.

## Current evidence

Prerelease `v6.4.5-preview.1` is built from public commit `5cfd751` with version
code 657 and the public update repository compiled in. It passed the unmodified
full manual release gate from the fresh public-history checkout: 212 Python tests,
8,139 JUnit executions (14 skipped; zero failures/errors), three release lint
checks with zero errors, and three optimized production-signed APK builds.
Signatures, package/version, compiled public repository, packaged notices,
removed-runtime absence and private-marker checks passed for every APK.
Existing lint warnings remain (501 mobile; 499 per TV variant).
The release provides mobile ARM64 and TV ARM64/ARM32 APKs, source and checksums.
See [the prerelease validation record](public-prerelease-657-validation.json).
**TV discovery, DLNA picture/sound and callbacks, D-pad navigation, and device
checks of these exact APKs remain pending.** The release feed is intended for
this public preview; the GitHub prerelease flag does not disable app update checks.

The following provenance-candidate results predate the public channel setup.

Final provenance candidate `d9b2ac1` passed the required manual gate task matrix:
211 Python tests, 8,139 JUnit executions (14 skipped; no failures/errors), three
release lint checks with zero errors, and three optimized production-signed APKs.
Compilation outputs were reused by omitting only the preparatory clean; preceding
candidate `572d91c` passed the unmodified full gate. APK certificates match the
independent pinned key. Packaged notices, Cling presence and removed-runtime
absence passed. Both DEX fixtures reproduced twice byte for byte. The source scan
found no known private markers or credential patterns in 4,070 files, including
nested archives. The final changes after APK validation are documentation, a neutral maintenance
example, a stricter export gate with a regression test, and Git tracking of an
existing ignored Bouncy Castle notice. That notice matches all three validated
APKs; application/package bytes are unchanged. Eight maintenance tests, five
release-matrix tests and the source provenance/privacy gates passed afterward.
All 88 bundled binary/media artifacts have recorded provenance. Existing lint
warnings remain (501 mobile; 499 per TV variant).
See [the final validation record](public-provenance-validation.json). This supports
source publication; it does not replace deferred TV/device checks or configure a
public release/update channel. Older results below apply to their own revisions.


The candidate at `dbaa8b4` passed offline acceptance and three release lint checks
on Ubuntu 26.10 (Stonking Stingray, development branch), x86_64, using OpenJDK
21.0.12.1. Mobile ARM64 and TV ARM64/ARM32 verification APKs assembled successfully.
A limited Android 15 ARM64 phone check confirmed synthetic H.264/AAC video and audio,
seeking, and returning from the background. TV casting and D-pad checks remain untested
because the TV was unavailable. Production signing is configured. The four SDKs with unresolved redistribution
permissions have been removed; TV/runtime readiness and the remaining provenance
limits still apply. This is not a stable release. See [the exact results and hashes](public-validation.json).

Production candidate `758a607` subsequently passed the full manual release gate:
209 Python tests, 8,237 JUnit executions (16 skipped, no failures/errors), three
release lint checks with no errors, and three optimized signed APK builds. Each APK
signature matches the independent production certificate. Existing lint warnings
remain. The actual production-key encrypted recovery was tested successfully.
See [production validation and APK hashes](public-release-validation.json). These
are offline checks; the earlier phone evidence does not validate these optimized APKs.

## Build a verification APK

Use JDK 21 to run Gradle/lint, keep JDK 17 available for the declared JVM toolchains,
and provide the Android SDK/platform/build tools declared by Gradle plus Python 3.10
for Chaquopy. Install prerequisites separately; the repository does not contain them.
The current lint parser crashes on JDK 17 with `List.removeLast()` even though the
application/JVM source target is 17.
Keep signing configuration outside the checkout and use an isolated location:

```sh
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/path/to/Android/Sdk
export WUMING_CONFIG_FILE="$HOME/.config/wuming-public-preview/config.json"
python3 scripts/setup-signing.py --ephemeral
python3 scripts/user_config.py set buildPython /path/to/python3.10
./gradlew-safe -PwumingEphemeralSigning=true :app:assembleMobileArm64_v8aVerification
```

For TV, replace the task with `:app:assembleLeanbackArm64_v8aVerification` or
`:app:assembleLeanbackArmeabi_v7aVerification`. Ephemeral keys are for verification
only. They cannot build release artifacts. Keep the generated key if you need to
update your own verification installation without uninstalling it.

No device is needed for offline unit acceptance:

```sh
./gradlew-safe -PwumingEphemeralSigning=true unitAcceptanceBaseline
```

`scripts/verify-release.sh` remains the full stable-release gate. It additionally
requires the configured maintainer signing identity, release lint and three signed
release APKs. Preview evidence is not a substitute for passing that gate.

## Native build policy

No project-native prebuilt is shipped. Calls use their existing Kotlin fallbacks.
Rust sources remain available for future JNI/API compatibility work; compiling
candidates does not adopt them into the public application.

## Startup after catalog screening

The former preferred default was not retained by screening. Fresh installations wait
for an explicit source selection from Settings/catalogs; they do not contact a removed
hard-coded fallback or automatically approve a different provider. Saved explicit
selections still load normally. Shared `source-core` policy governs preferred bundled
selection for Android and desktop.

## Catalogs and account data

The earlier anonymous-access catalog check did not establish content licensing.
Those source/live catalogs have been removed. The bundled list now links only to
Big Buck Bunny, with Blender Foundation attribution and CC BY 3.0 license links.
No movie is bundled. Availability and playback remain runtime checks.

## Before a stable release

- Complete the deferred device/TV checks before promoting a stable release.
- Preserve the completed provenance inventory, full notices and matching source snapshot.
- Pass full offline acceptance, release lint and signed artifact assembly on the
  exact release commit; retain checksums and toolchain provenance.
- Exercise startup, catalogs, playback, casting, and TV D-pad navigation using the
  actual candidate APKs on explicitly authorized representative devices.
- Recheck dependency/content changes against [the third-party inventory](public-third-party-inventory.json).

See [preparation details](../PUBLIC_PREPARATION.md), [validation evidence](public-validation.json),
and [native artifact hashes](public-native-verification.json). Known gaps remain
visible even when individual tests pass.

## Production key and recovery

The production certificate SHA-256 is recorded in `updates.json`. The key was
generated independently of the private application and preview keys. New developers
must restore this identity rather than generating another one. Keep private config,
keystores and encrypted recovery exports outside the checkout, with owner-only
permissions. `scripts/export-signing.py` renders a recovery installer pinned to this
project certificate; restore with an explicit absolute `WUMING_CONFIG_FILE` to an
isolated location. Never publish the archive or its separate password file.

For a new unpublished fork only, `scripts/setup-signing.py --initialize-project`
creates a production identity and pins its public certificate. It refuses a populated
project certificate/repository, existing signing configuration, ephemeral mode, and
configuration inside the checkout. Ordinary setup validates an existing identity.

The earlier `758a607` artifacts still contained the four removed engines. Use only
the rebuilt candidate recorded in the SDK-removal validation report for this change.

## Current SDK-free candidate

Candidate `1be12f7` passed the full signed release gate after removal of ForceTech,
JianPian, Thunder and TVBus. All three final APKs passed removed-SDK package/native
and manifest checks. Use [the SDK-free candidate results and hashes](public-sdk-removal-validation.json)
in preference to earlier APKs, which still contained those engines. TV/runtime checks
remain deferred; no new runtime evidence is claimed.

## Final provenance cleanup

The known provenance gaps were resolved on 2026-10-18 by replacement, removal and
retained notices. See [the final review](vendor-provenance-review.md). DLNA is
preserved through source-built LGPL Cling/Seamless with local callback restrictions;
Google Cast, obsolete Hook, project-native prebuilts and native desktop emulation
are removed. This source-publication review does not waive the device checks above.
