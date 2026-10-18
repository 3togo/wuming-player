# Maintainer update publishing

This public preview has an independent production certificate pinned in `updates.json`.
The update repository remains empty; update checks return without
contacting a feed, and publishing is rejected until a maintainer configures a channel.
The public app ID is `com.wuming.android.tv.publicedition`; verification adds
`.verification`. Neither replaces the private installation. See [the preview guide](public-preview.md).

## Signing

Stable public distribution requires an independent, retained signing key and its
SHA-256 certificate pin in `updates.json`. Do not import the private application's
identity or assume access to a private workflow. The setup tool's ordinary import
path validates the configured pin; generating a verification identity with
`--ephemeral` does not provision a production identity. The public identity has been established independently; verification builds may
still use a separate ephemeral identity in an isolated configuration.

No GitHub Actions workflows are supplied. Builds, acceptance and publishing are
manual. Never commit keystores, passwords, private configuration or Gradle caches.
Retain and verify encrypted production-key recovery backups outside source control before distribution.

The handoff and publishing mechanisms below describe maintainer tooling after
that configuration is complete; they do not indicate an active public service.

To prepare a private developer handoff, run
`python3 scripts/export-signing.py /absolute/private/output --generate-password`.
The output directory must be new and outside this repository. It contains an
AES-256 encrypted `wuming-signing.zip`, a standalone `install-signing.sh`, and
a separate `zip-password.txt`. Omit `--generate-password` to choose a password
interactively. Transfer the password separately from the ZIP.

The recipient runs `bash install-signing.sh wuming-signing.zip` without sudo.
Bash, Python 3, a JDK providing `keytool`, and 7-Zip (`7zz` or `7z`) are required.
The installer prompts for the ZIP password, verifies the pinned certificate and
private-key access, and installs into `~/.config/wuming-player/production` with private file
permissions. It preserves unrelated configuration and refuses to replace an
existing signing setup. Set an absolute `WUMING_CONFIG_FILE` to restore elsewhere;
use the same override for the build. The recovery installer's default is deliberately
isolated from the older application's `~/.config/wuming` configuration.

Isolated verification tests use `setup-signing.py --ephemeral` and
`-PwumingEphemeralSigning=true`. Such artifacts are not project distributions;
the publisher always rejects their certificates and release assembly rejects
the ephemeral option.

## Publishing

1. Authenticate with `gh auth login`.
2. Run `bash scripts/upload-apks.sh` (all variants), or select variants below.
   The command chooses `max(local versionCode, published testing versions) + 1`,
   including draft and partial releases. It builds only the selected variants
   using that version, validates the resulting APKs, saves `versionCode` to
   `updates.json`, and publishes them. A failed build does not change the local
   version. Release builds retain their clean-source and acceptance gates;
   debug builds allow local source changes. No automatic Git commit is created.
3. If upload fails after a successful build, repeat the same selection with
   `--resume` to reuse the existing version and APKs. A normal invocation starts
   a new version and build. `--dry-run` validates existing APKs without bumping,
   building, or contacting GitHub.

For example, `bash scripts/upload-apks.sh -ld` builds and uploads two leanback
debug APKs; retry an interrupted upload with
`bash scripts/upload-apks.sh -ld --resume`.
Use `--build-root /path/to/checkout` to build/upload from another checkout.
The underlying `scripts/publish-updates.py --publish` remains available for
publishing prebuilt artifacts without an automatic version increment.

Upload only a selected variant with `--debug --leanback`, or these shortcuts:
`-ld` (leanback debug), `-lld` (leanback lite debug), `-md` (mobile debug),
`-lr` (leanback release), `-llr` (leanback lite release), `-mr` (mobile release).
Each includes both ARM ABIs and validates only the selected APKs. `--debug` or
`--release` alone selects all three modes for that channel. With no selectors,
all twelve APKs are selected. `--dry-run` works with every selection.

A partial variant upload publishes the selected release assets but leaves the
feed unchanged: the current client manifest uses a single version for all
variants. Publish all three modes together for a channel to advance that feed;
already uploaded matching assets are reused. Missing assets may be appended
to the same testing release, but existing assets are never replaced.

The publisher uploads immutable versioned release assets, checks GitHub's
SHA-256 digests and sizes, then advances both JSON channels in one Git commit.
A failed upload leaves the previous channels intact. Re-running the same
publication resumes drafts; replacing a version's bytes or rolling a channel
back is rejected. A concurrent channel update fails instead of overwriting it.

The app selects only its build channel, mode, ABI and package ID. Before opening
Android's installer it verifies file SHA-256, the APK version, and the certificate
against both the manifest and installed application. Android still performs
its own installation verification and requests user installation approval.
The separate `.verification` application never updates into the normal package.

## Bootstrap limitation

Previously installed builds hard-code FongMi's update endpoint. They require
one installation of a build with this project's endpoint before they can use
the new automatic checks. The new server cannot redirect clients that never
contact it. Declining one update now dismisses that prompt without disabling
future update checks; Settings retains its explicit update preference.

## Vivo / Gitee update recovery (2026-09-20)

On the authorized Vivo phone, the installed app and cached `update.apk` both had
version code 650. The cached APK's SHA-256 matched the Gitee debug feed. Subsequent
checks failed at `UpdateManifest.select` URL validation; the installed updater
only accepted GitHub URLs, while the feed pointed to the Gitee mirror. The
captured log did not contain an Android `INSTALL_FAILED_VERSION_DOWNGRADE` result.
Do not confuse this metadata failure with a confirmed installer downgrade.

The shared selector now accepts only the configured GitHub repository and its
explicit `freetogo/TV-updates` Gitee mirror. A same-version or older feed returns
no update before inspecting its download entries, so a lagging mirror cannot
cause repeated failures after installation. Newer APKs still require matching
package, version, checksum and signing certificate before installer handoff.
Recovery build 651 advances beyond the device's 650; lowering `updates.json` to
649 does not make an APK installable over 650. Use the publisher's monotonic
version allocation for subsequent distributions.

Validation: six `UpdateManifestTest` cases and all three architecture boundary
checks passed; `assembleMobileArm64_v8aDebug` succeeded. The project-signed 651 APK
installed with `adb install -r` on `192.0.2.10:39809`, preserving data. Vivo
showed its external-source confirmation and installation returned `Success`.
The in-app Settings version check then displayed `已是最新版本` against the live
Gitee 650 feed, with no new `BaseUpdater` errors. This verifies installation and
the current/older-feed path on-device; newer-feed selection is covered by JVM
tests, not a newly published end-to-end update.

## Standalone Gitee debug publisher

`python3 scripts/publish-gitee-updates.py` builds and publishes all six debug
variants to `freetogo/TV-updates` on Gitee, updating `channels/debug.json` on the
`release` branch. It does not push source commits or publish to GitHub. The
`scripts/upload-apks.sh` GitHub publisher remains available separately. For the
normal dual-host workflow, use `scripts/upload-debug-apks.sh` as described below.

Provide `GITEE_TOKEN` or `GITEE_TOKEN_FILE`; the default token file is
`~/Desktop/gitee.txt`. Keep credentials outside the repository. Python `requests`
is required in addition to the existing build dependencies. Use `--version 652`
to require an exact new version, `--resume` to publish existing validated APKs,
or `--dry-run` to validate local artifacts without network access.

The next version exceeds the local code, live Gitee feed and every Gitee testing
release, including partial uploads. Only a completed build creates
`build/.gitee-debug-built.json`; rerunning after an upload failure automatically
resumes it. Every release asset is downloaded through its public app-facing URL
and checked against the local SHA-256 and size. Existing assets are never
replaced, and the feed advances only after all six verify. A changed feed SHA
stops publication instead of overwriting concurrent changes.

### Reproduced Vivo installer rejection through the app

Publishing 652 enabled the full Settings → Version → Update test. Vivo's
`PackageInterceptActivity` displayed `已安裝更新版本` even though the installed
code was 651 and the downloaded APK was 652 with the exact published SHA-256.
Opening those same bytes through a fresh filename reached the normal installer.
Thus a version-code bump and manifest fix alone did not resolve the original
in-app symptom. The shared updater now uses
`update-<versionCode>-<sha256>.apk`, avoiding reuse of the content URI for different
APKs and the OEM installer's stale metadata. Version names were left unchanged;
the earlier same-version-name suspicion was not established as the cause.

652 was retained as an immutable publication. The URI repair is published in
653; an existing broken updater needs a one-time data-preserving ADB repair to
use the new URI on its next download. Never uninstall the user's app to work
around this warning. During testing, a 651 repair attempt after the phone had
reached 652 correctly failed with `INSTALL_FAILED_VERSION_DOWNGRADE`; the repair
was rebuilt at 652 and `adb install -r` succeeded.

Full device validation completed on `192.0.2.10:39809`: after the one-time
652 updater repair, Settings → Version detected the public Gitee 653 feed;
Update downloaded `update-653-c5c34190ee74face3297ebe9b9c4731ba3554b4297a7d9a5b79780ea0841a2db.apk`.
Vivo showed the normal installer with source `無名`, and Continue installation
completed. Package Manager reported code 653 at 01:32:56 on 2026-09-20. The
version name remained `6.4.4-testing(20260920040e)`, supporting the reused-URI
fix rather than a version-name workaround. A second Settings → Version check
showed `已是最新版本`, with no new `BaseUpdater` errors. App data was preserved.
All six APKs for both 652 and 653 were verified by public-download SHA-256 and
size before each feed publication; all 18 publisher regression tests passed.

## Dual-host debug publication and automatic fallback

`bash scripts/upload-debug-apks.sh` now delegates to `scripts/publish-mirrored-updates.py`.
It allocates one version above local state and both hosts' testing releases,
builds the six debug APKs once, and uploads those identical signed files to both
GitHub and Gitee. GitHub uploads run with at most three concurrent transfers;
Gitee uploads remain sequential. Existing GitHub assets are checked before any
transfer starts, and matching assets are skipped on resume. All upload workers
finish before remote checksum/size verification; a worker failure aborts feed
publication and cancels uploads that have not started. It verifies both
distributions before advancing either feed.
The two feed commits are necessarily separate; `--resume` repairs a partial
commit without replacing APKs. Automatic resume uses
`build/.mirrored-debug-built.json`, written only after successful assembly and
local APK validation. `--version`, `--resume`, `--dry-run`, `GITEE_TOKEN` and
`GITEE_TOKEN_FILE` remain supported. GitHub uses the maintainer's `gh` login.

The additive `mirrors` array lists both immutable release URLs for each artifact.
The original `url` remains host-specific for old clients. New clients fetch both
feeds concurrently, with an eight-second HTTP call timeout and nine-second
aggregate deadline. Shared `UpdateManifest` policy chooses the highest valid
version, merges identical copies, rejects conflicting checksums/certificates
for the same highest version, and validates every mirror URL. One inaccessible
or malformed feed cannot mask a usable peer; failure of both is reported as an
error, not "up to date". Older single-URL feeds remain readable.

Downloads prefer the last successfully verified host when its feed is reachable,
otherwise the first responding feed's artifact. Connection timeout is eight
seconds, idle read timeout fifteen seconds, and total attempt limit five minutes.
An error, timeout or APK verification failure tries the next mirror from a new
staging file. Both attempts require the same SHA-256, package, version and
installed signing identity. Cancellation/dismissal invalidates all queued
callbacks and prevents another mirror attempt or installer launch. The final
version-and-checksum filename is unchanged, preserving the Vivo URI fix.
No geolocation service, device locale inference or location permission is used.

Validation on Xiaomi `7e34269d`: the normal app was upgraded from 650 to a 653
bootstrap containing the dual-host updater, preserving app data. A separately
labelled `無名(test runner)` APK ran seven failure-injection tests in one run
(13.144 s, all passed): either feed unavailable, both feeds unavailable,
Gitee-to-GitHub and GitHub-to-Gitee download fallback, corrupt-primary rejection,
cancellation without fallback/delivery, and the aggregate stalled-feed deadline.
These tests used injected HTTP responses and the real signed 654 APK fixture;
they do not claim both public hosts were reachable from the device.

The existing `AccountCredentialBackupInstrumentedTest` has unrelated Java calls
to non-static Kotlin methods and prevents a complete test-runner compile. This
run excluded that class from `*AndroidTestJavaWithJavac` via a temporary Gradle
init script; only `UpdateMirrorsInstrumentedTest` methods were executed. The
application build, ten shared update-policy tests, 24 Python publication tests,
and three architecture boundary checks passed.

The mirrored publisher records the build's full base commit and filename stamp
in its pending marker and retains `build/.mirrored-debug-identity.json` for later
`--resume`/`--dry-run` calls. These paths remain anchored to the validated APKs
when a developer commits during a slow upload. A source commit change during
the build itself aborts publication. This was exercised when HEAD advanced from
`2ed2aa2ae` to `ecaacb29a` during 654 uploads; the original 654 artifacts were
reused rather than rebuilt under the same version.

Gitee returned HTTP 400 after two 654 assets with the explicit reason that the
repository's 1 GB attachment quota was exceeded. Feed publication remained
blocked on the previous version. Error reporting now includes the scalar API
reason with credentials redacted. The initial quota recovery used an explicitly approved backup and deletion of
649. The subsequently authorized three-version retention policy below now
handles obsolete debug APK attachments automatically.

Two additional device tests used the actual public 654 APK URLs (not fixture
responses) while injecting failure only for the primary host. Both passed in
one run (33.917 s): GitHub failure → Gitee download and Gitee failure → GitHub
download. Android verified each downloaded APK's hash, package, version and
installed signing identity. These prove both public download routes on this
Xiaomi/network, not universal geographic availability. Logs are under
`build/validation/update-mirrors-654/` (local, ignored by Git).


### Completed mirrored 654 release and Settings installation

With the user's authorization to remove obsolete Gitee versions, the six 649
APK attachments were backed up locally and verified (size, SHA-256, version and
signing certificate), then deleted to free 247,638,452 bytes. The backup and
inventory remain in `build/backups/gitee-testing-649-debug-20260919d6b9/`.
Newer release attachments were retained. Resuming publication reused the original
654 APKs and verified all six on both hosts before updating both debug feeds.
The live feeds advertise code 654, matching hashes for all six variants, and two
immutable mirror URLs per artifact.

All ten `UpdateMirrorsInstrumentedTest` tests subsequently passed in one run on
`7e34269d` in 39.547 seconds, including the live published feeds and real downloads
with each primary host deliberately failed. Settings → Version then downloaded
654 from Gitee, passed APK verification, and opened Android's ordinary update
confirmation. Installation preserved app data; Package Manager reported code
654 and update time `2026-09-20 02:25:20`. The Settings screen showed
`已是最新版本` afterward. The version-and-hash installer filename remains in use.
Temporary test fixtures were removed and the original plugged-in awake setting
(`15`) restored. This validates this device/network, not worldwide availability.


### Three-version debug APK retention

`scripts/upload-debug-apks.sh` publishes to both GitHub and Gitee and then automatically
keeps the latest three APK-bearing debug version codes on each host. Cleanup
runs only after both feeds commit successfully; it refuses to run while their
version codes disagree and never removes a live feed's referenced APK. Older
APK attachments are deleted, while tags/release metadata remain for monotonic
version allocation. Release-channel APKs, drafts, future uploads and unrelated
attachments are outside this debug script's retention scope.

To retry cleanup without building or uploading a new version, use
`bash scripts/upload-debug-apks.sh --cleanup-only`. Cleanup is repeatable after a partial
failure. `--dry-run` remains local validation only and never deletes anything.
The user explicitly authorized keeping only the last three versions on both
hosts; recurring retention does not require another approval or local backups.

Initial retention removed 20 obsolete GitHub debug APKs and six Gitee 650 APKs.
GitHub retains 654/649/648; Gitee retains 654/653/652. A second cleanup removed
zero assets. All 30 publisher tests passed. Historical Gitee debug releases can
have `prerelease=false`, so retention identifies the channel by its exact
`testing-<code>-debug-<stamp>` tag rather than the prerelease presentation flag.


GitHub upload concurrency validation: all 33 publisher tests passed, including a
synchronized transfer test proving three overlapping uploads, resume skipping,
preflight conflict rejection, and upload failure preventing verification/feed
changes. These are local regression tests; actual network speedup has not been
measured and depends on available upload bandwidth.

Recovery exports embed the current updates.json public certificate pin in their installer.
Set absolute WUMING_CONFIG_FILE when restoring into a separate private location.
The unrendered installer template in scripts is not a standalone recovery package.
