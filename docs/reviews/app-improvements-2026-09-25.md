# App improvement review — 2026-09-25

Source baseline: `cab5b85b03ac27f155a2b416997f4e1dfcc32418`.
Scope: Android mobile/TV and Linux desktop source, persistence, playback accounting,
existing architecture/lessons, tests and CI. This is a source-led investigation, not
a visual usability study, security audit, live-provider test or device certification.
The initial review changed only documentation. The follow-up implementation covers
all 11 findings; see the [implementation record](#implementation-record). The original
finding descriptions below describe the recorded baseline, not the updated code.

## Implementation record

Implemented on 2026-09-25 in the working tree (not committed). All 11 IDs have code,
test or workflow changes. **Offline implementation is complete.** Subsequent
SMOKE-001/SMOKE-002 runs establish selected phone/Leanback and public-stream runtime
coverage. APP-015 subsequently validated disposable backup/migration/recovery tests
on Android; exact-release acceptance remains unverified. See the dated evidence below.

| ID | Delivered change | Regression evidence |
| --- | --- | --- |
| APP-001 | Restore replaces only eight portable tables; cloud ownership/audit and other local-only tables survive | `BackupInstrumentedTest.portableRestorePreservesLocalCloudOwnershipAndAudit`; existing transaction rollback tests; compiled, not device-run |
| APP-002 | Shared [BackupArchive](../../libraries/user-library-core/src/main/kotlin/com/wuming/tv/core/library/BackupArchive.kt): unique temporary gzip, bounded verification, force-to-storage and atomic publication; explicit failure callback; retention after success | `BackupArchiveTest`: failed compression, corrupt output and failed replacement retain the previous archive; round-trip and same-day replacement |
| APP-003 | [Migrations.all()](../../app/src/main/java/com/wuming/android/tv/db/Migrations.kt) shared by Room and tests; byte-identical 35–45 fixtures under the renamed class; every exported start version tested to current | `ReviewContractsTest`; compiled `MigrationInstrumentedTest` including cloud-row preservation and new indexes |
| APP-004 | Removed destructive fallback; read-only version/header preflight; independent localized [recovery screen](../../app/src/main/java/com/wuming/android/tv/ui/activity/DatabaseRecoveryActivity.java) with export/retry | `DatabaseUpgradePolicyTest` preserves unsupported files and WAL bytes; compiled Android recovery UI; runtime export/D-pad untested |
| APP-005 | [Persistent private-viewing switch](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/CatalogPanel.kt); history delete/clear also removes recommendation signals and favorite progress, retaining favorite membership; active sessions cannot recreate deleted records | `DesktopUserLibraryTest` covers private close, stale callbacks, persistence and favorites; `DesktopLayoutTest` covers reachable controls after Search/Details |
| APP-006 | Shared [observed-watch-time accumulator](../../libraries/playback-core/src/main/kotlin/com/wuming/tv/core/playback/ObservedWatchTime.kt) counts bounded progressing observations, excluding pause, buffering, seek jumps and disconnected gaps | `ObservedWatchTimeTest`; desktop session completion records only observed watch time |
| APP-007 | Shared [live-library identity/selection policy](../../libraries/live-core/src/main/kotlin/com/wuming/tv/core/live/LiveLibraryPolicy.kt) and desktop Preferences port; favorite controls and no-autoplay selection restoration | `LiveLibraryPolicyTest`, `DesktopLiveLibraryStoreTest`, `LiveTvPanelTest` cover source isolation, restart, reorder and removed channels |
| APP-008 | Release gate generates [immutable-candidate evidence ledger](../../scripts/release-evidence.py) and Markdown index; hashes/signatures/ABI, separate evidence levels and explicit not-tested checks | `ReleaseEvidenceTest`: signature-tool fixture, candidate mismatch rejection, required evidence and separate verification APK identity |
| APP-009 | Active entry-point/package/path corrections; historical-command rename notes; optimization report explicitly labeled unverified historical estimates | `ReviewContractsTest` checks local links and key identities; historical schema files are unchanged |
| APP-010 | Always-upload module JUnit XML/HTML, acceptance/summary artifacts with 14-day retention; invocation outcome and report counts | Workflow contract guard and local [test-report-summary.py](../../scripts/test-report-summary.py); hosted CI upload not executed here |
| APP-011 | Current package/component/runner/method identities in smoke tooling; build-identity guard | Python regression suite and four dry-run combinations; full offline baseline |

Implementation details: [backup and recovery](../database-recovery.md),
[desktop behavior](../desktop-build-test.md#library-privacy-and-live-tv-continuity-app-005app-007),
[release evidence](../test-strategy.md#candidate-evidence-ledger-app-008).

The desktop unit renderer now explicitly runs headless so its 960×620 and 1280×800
layout assertions do not depend on the host monitor's DPI. Rendered layouts were
inspected locally. This does not establish interactive high-DPI window or Android
OEM behavior. Production DPI policy was not changed.

Follow-up validation commands/results are recorded in the implementation validation
section below. No commit identifier is invented for uncommitted changes.

## How to reference and maintain this review

Use `APP-001` through `APP-021` in tasks, PRs and future discussions.
APP-012–APP-015 are implemented in the working tree; see the
[follow-up implementation and validation record](followups-2026-09-25.md). IDs and anchors
are permanent; do not renumber them when priorities change. Evidence paths are
relative to this document; method names remain useful when line numbers move.
When resolving an item, append the implementation commit, validation result/date
and remaining limits. Do not mark a source finding as runtime-reproduced without
runtime evidence. Later reviews should link these IDs instead of duplicating them.

Priority: **P1** = data preservation or misleading success to address first;
**P2** = user experience, correctness or validation improvement;
**P3** = supporting maintenance. These are proposed implementation priorities,
not measured incident frequency. Effort S/M/L is relative and excludes device setup.

## Findings index

| ID | Priority | Platform / area | Finding | Evidence class | Effort |
| --- | --- | --- | --- | --- | --- |
| [APP-001](#app-001) | P1 | Android / restore | Preserve local cloud-copy ownership records during restore | Confirmed source behavior; remote impact inferred | M |
| [APP-002](#app-002) | P1 | Android / backup | Report actual backup write/compression outcomes | Confirmed failure-path defect | S–M |
| [APP-003](#app-003) | P1 | Android / upgrades | Repair migration test chains and renamed schema fixtures | Confirmed test/source mismatch | M |
| [APP-004](#app-004) | P2 | Android / upgrades | Replace unrestricted destructive migration fallback with recovery | Confirmed configuration; affected population unknown | M |
| [APP-005](#app-005) | P2 | Desktop / privacy | Add history deletion and private viewing | Confirmed current UI/API gap | M |
| [APP-006](#app-006) | P2 | Desktop / recommendations | Count observed playback time, excluding pauses/startup | Confirmed accounting behavior | M |
| [APP-007](#app-007) | P2 | Desktop / live TV | Persist favorites and last channel | Product proposal grounded in current gap | M |
| [APP-008](#app-008) | P2 | Release / evidence | Make exact-release runtime evidence easy to inspect | Validation workflow proposal | M |
| [APP-009](#app-009) | P3 | Documentation | Reconcile package names and dated recommendations | Confirmed documentation drift | S–M |
| [APP-010](#app-010) | P2 | CI / diagnostics | Retain reports from desktop and every shared module | Confirmed artifact-collection gap | S |
| [APP-011](#app-011) | P1 | Acceptance / smoke tooling | Repair stale package constants that break acceptance | Reproduced offline failure | S |

## Follow-up findings index — after SMOKE-002

These findings review the current working tree. APP-001–APP-011 retain their
original meanings. The evidence and proposals below describe the original findings;
[implementation and subsequent device results](followups-2026-09-25.md) are recorded separately.

| ID | Priority | Improvement | Evidence class | Effort |
| --- | --- | --- | --- | --- |
| [APP-012](#app-012) | P2 | Make performance-stat cache misses safe on the UI thread | Confirmed source defect; corresponding Room exception observed during SMOKE-002 investigation | S–M |
| [APP-013](#app-013) | P2 | Keep local artwork out of the HTTP image loader | Confirmed source mismatch; corresponding Glide exception observed during SMOKE-002 investigation | S–M |
| [APP-014](#app-014) | P2 | Diagnose intermittent playback stalls with longer, structured observations | Recorded failed and successful provider runs; cause unresolved | M |
| [APP-015](#app-015) | P1 | Run the persistence and recovery acceptance tests on Android | Explicit coverage gap for APP-001/003/004, not a new proven product defect | M |

Original recommended order was APP-015, APP-012/013, then APP-014.
Original evidence for APP-012/013 includes source inspection
below; the incidental logcat observations were not archived as complete test runs
and must not be treated as dedicated regression-test results.

<a id="app-012"></a>
### APP-012 — Ready caches can still trigger UI-thread Room reads

**Evidence.** [SitePerformanceRepository](../../app/src/main/java/com/wuming/android/tv/db/SitePerformanceRepository.kt),
`findBySiteKey`: the ready-cache branch queries the DAO on a cache miss before
checking the current thread. `findAll` also allows a DAO read on the main thread
when the cache is ready. Both catch the resulting exception and return an empty
result. [SitePerformanceTracker](../../app/src/main/java/com/wuming/android/tv/utils/SitePerformanceTracker.java)
then creates a fresh record when a lookup returns null. The playback-error path
observed a main-thread Room exception while recording a dead link during SMOKE-002.

**Impact.** Lost reads and misleading fallback statistics are possible; persisted
statistics being overwritten is a risk to verify, not an established incident.

**Proposal.** Serve UI reads exclusively from memory; move cache-miss hydration to
a worker and merge pending observations without losing previously stored counters.
Do not merely suppress the exception or turn on Room main-thread queries.

**Acceptance.** Exercise cache-not-ready, ready-hit and ready-miss states on the UI
thread with zero DAO calls, then verify background hydration preserves old counters
and events recorded while hydration was pending.

<a id="app-013"></a>
### APP-013 — Local artwork is wrapped as an HTTP request

**Evidence.** [SiteApi.detailContent](../../app/src/main/java/com/wuming/android/tv/api/SiteApi.java)
uses an `android.resource://` URI for the pushed-video image.
[ImgUtil.getUrl](../../app/src/main/java/com/wuming/android/tv/utils/ImgUtil.java)
only exempts `data:` before wrapping nonempty models in `GlideUrl`.
[OkGlideModule](../../app/src/main/java/com/wuming/android/tv/utils/OkGlideModule.kt)
routes GlideUrl through OkHttp. Logcat during SMOKE-002 reported that OkHttp expected
HTTP/HTTPS but received `android.resource`.

**Impact.** Local placeholder artwork can fail to load and produces noisy errors.
This was separate from the missing playback-URL defect already fixed.

**Proposal.** Route resource/content/file models to Glide's local loaders; restrict
HTTP header parsing and GlideUrl wrapping to HTTP/HTTPS models. Preserve existing
provider image-header behavior.

**Acceptance.** Load the real pushed-video resource on Android; cover resource,
content, file, data and HTTP(S) models, including provider header suffixes and
fallback behavior for unavailable artwork.

<a id="app-014"></a>
### APP-014 — Successful short playback does not explain the earlier stall

**Evidence.** [SMOKE-002 attempt ledger](extended-smoke-2026-09-25.md#smoke-0024--attempt-ledger):
the connected fifth attempt reached decoded video and then stalled beyond eight
seconds; the sixth passed with 15.059 seconds of progress. Its samples record player
state and buffer depth, but the failed fifth run lacks equivalent detailed samples.
No root cause was established.

**Proposal.** Add bounded, opt-in multi-minute observations with startup timing,
rebuffer count/duration, decoder progress, load-error categories and discontinuity
reasons. Persist these on failure as well as success, redact signed URLs/credentials,
and label each run separately. Diagnose transport starvation versus decoder or
activity resets before changing buffer/retry defaults.

**Acceptance.** Deterministically trigger a stalled transport and a playback reset
with controlled fixtures and verify distinct diagnostics. Repeat the authorized
real-provider experiment without replacing failures with a retry-only success.
Keep live-window position discontinuities separate from actual watched progress.

<a id="app-015"></a>
### APP-015 — Complete device evidence for persistence and recovery

**Evidence.** APP-001/003/004 compiled the relevant instrumentation and passed offline
checks, but the standard and extended smoke selections do not execute the portable
restore regression, full migration matrix or recovery export UI.

**Proposal.** Add an explicit persistence smoke selection using disposable databases:
portable restore with retained cloud ownership/audit rows, every available historical
schema migration, unsupported-version preservation, and database/WAL/SHM recovery
export. Use only an explicitly authorized device and avoid the user's actual library
or cloud accounts. Existing `7e34269d` authorization applies to this task; no other
device is implicitly authorized.

**Acceptance.** Exact passing method selection and database-content assertions;
exported fixtures can be read back, unsupported originals stay unchanged, failed
restore rolls back, and cleanup removes only test-owned temporary fixtures.
Physical TV recovery-screen focus still requires an authorized TV run.

## Further improvements — APP-016–APP-017

<a id="app-016"></a>
### APP-016 — Keep changing adaptive manifests out of the runtime disk cache

**Confirmed source defect.** Public HLS, DASH and Smooth Streaming links qualify
for write-through caching. `MediaSourceFactory` previously supplied that same
`CacheDataSource` to manifest and segment loaders. A cached live playlist could
therefore prevent later sequence updates from reaching the player. APP-014's
playlist-stuck result motivated this review; it was not proof of an upstream fault.

**Implementation.** Media3 protocol factories now receive separate manifest and
segment sources. Manifests bypass existing/runtime LRU entries while segments keep
caching. Explicit offline downloads retain their separate read-only cache path.
Adaptive items with sidecar subtitles or clipping use the default factory without
runtime caching, preserving its wrappers. No global cache deletion or retry changes.

**Acceptance.** A changing extensionless loopback playlist demonstrates the old
stale-cache behavior, then proves successive fresh manifest reads; segment reuse,
explicit offline manifest reads and subtitle merging are checked on Android.
See [implementation and run evidence](adaptive-cache-2026-09-25.md).

<a id="app-017"></a>
### APP-017 — Capture private-safe nested playback failures in the smoke artifact

**Confirmed diagnostic gap.** APP-014 reported `ERROR_CODE_IO_UNSPECIFIED` with an
empty load-error map; finding `PlaylistStuckException` required a separate logcat
read. The main media-source debug statement also printed the complete URL and
headers, potentially including signed query parameters or authentication values.

**Implementation.** The player error callback records bounded exception class names
through shared `FailureDiagnostics`, with cycle protection and no messages. Error
causes survive player release in the saved report. Removed the raw URL/header log.
Startup/pre-roll buffering is excluded from interruption counters until playback begins.
This is a targeted logging fix, not a claim that every application log is redacted.

**Acceptance.** Core tests cover nested, cyclic and deep exceptions. Instrumentation
verifies the encoded report retains cause types without synthetic secret messages.
See [implementation and run evidence](adaptive-cache-2026-09-25.md).

## Deeper review findings — APP-018–APP-021

These four items are confirmed follow-up findings, **not implemented fixes**.
Their [detailed evidence and acceptance criteria](deep-review-2026-09-25.md) distinguish
actual core/JVM behavior from schema-minimal SQLite and typed-preference fixtures.

| ID | Priority | Improvement |
| --- | --- | --- |
| <a id="app-018"></a>[APP-018](deep-review-2026-09-25.md#app-018) | P1 | Make recovery preflight use the committed WAL-aware database version |
| <a id="app-019"></a>[APP-019](deep-review-2026-09-25.md#app-019) | P1 | Capture backup tables in one consistent transaction |
| <a id="app-020"></a>[APP-020](deep-review-2026-09-25.md#app-020) | P2 | Preserve Long/Int/Float preference types across backup and restore |
| <a id="app-021"></a>[APP-021](deep-review-2026-09-25.md#app-021) | P2 | Unify IPv6 and alternate loopback classification across playback paths |

## Suggested delivery order

1. **Restore the acceptance gate:** APP-011 is reproduced and should be fixed first.
2. **Protect existing users:** APP-001 and APP-002; repair APP-003 before changing
   migration behavior in APP-004. Backup integrity and upgrade evidence reinforce
   each other, but a backup must never become permission to erase unknown data.
3. **Improve everyday desktop use:** APP-005 and APP-006, then APP-007. These are
   smaller, observable gains than adding more provider runtimes or a full cloud browser.
4. **Make future changes verifiable:** APP-010 can ship independently; APP-009 should
   precede further architecture work. Add APP-008 evidence tracking before the next
   release candidate that receives authorized hardware validation.

## Detailed findings

<a id="app-001"></a>
### APP-001 — Restore clears records it cannot restore

**Evidence.** [Backup.kt](../../app/src/main/java/com/wuming/android/tv/bean/Backup.kt),
`restore(database)`, calls `clearAllTables()` and then reinserts only the serialized
site/live/keep/config/history/viewing/media tables. The backup model has no
`CloudCacheRecord` or `CloudAuditEvent` fields. Both are registered entities in
[AppDatabase.java](../../app/src/main/java/com/wuming/android/tv/db/AppDatabase.java).
[CloudCacheDao.kt](../../app/src/main/java/com/wuming/android/tv/db/dao/CloudCacheDao.kt)
uses provider/account/folder identities to select tracked copies.

**Impact.** A successful restore erases local tracking without removing the remote
copies. In particular, Ali's conservative cleanup relies on tracked ownership;
losing that evidence can strand copies and prevent safe reuse/cleanup. This is a
source-supported risk, not a reproduced remote quota incident. Other omitted
entities, such as tracks, drives and search history, also need an explicit restore
policy rather than an accidental consequence of clearing every table.

**Recommendation.** Define a table-by-table restore policy. Replace only the
portable library tables covered by the selected backup format; retain local
account-bound operational records. Do not import cleanup authority from another
installation or turn lost tracking into permission for a folder/recycle-bin sweep.
Keep the existing transaction and secret-preference filtering.

**Acceptance.** Seed library data, cloud tracking and audit rows, then restore an
older backup: selected library data changes and local ownership rows survive.
Inject an insert failure and prove rollback. Test account changes and moved remote
files through fake ports without contacting a provider. Follow
[Ali lessons](../ali-playback-cache-lessons.md) and [UC lessons](../uc-playback-cache-lessons.md).

<a id="app-002"></a>
### APP-002 — Backup success is independent of compression success

**Evidence.** [AppDatabase.java](../../app/src/main/java/com/wuming/android/tv/db/AppDatabase.java),
`backup(callback)`, writes the file, calls `gzipCompress`, then posts success and
rotates backups. [FileUtil.kt](../../app/src/main/java/com/wuming/android/tv/utils/FileUtil.kt),
`gzipCompress`, catches `IOException`, deletes the gzip output and returns `Unit`.
The caller cannot distinguish this failure from success. The date-only filename
also means repeated backups on one day target the same output.

**Impact.** Disk-full or I/O failures can produce a success message without a usable
compressed backup. A failed overwrite can remove that day's earlier gzip backup.
No disk-failure injection was performed in this review.

**Recommendation.** Return a typed result or propagate errors; write/compress into
a unique temporary file and publish by atomic replacement where supported. Check
the completed archive before announcing success or applying retention. Preserve
the last good archive when any stage fails. Give restore the same explicit error
contract and bounded input handling.

**Acceptance.** Inject write, compression and final-replacement failures. Each
produces one failure callback, retains the old usable backup and performs no
success-path rotation. Verify a successful backup can round-trip and repeated
same-day backups are safe.

<a id="app-003"></a>
### APP-003 — Migration tests no longer reach the current schema

**Evidence.** [MigrationInstrumentedTest.java](../../app/src/androidTest/java/com/wuming/android/tv/db/MigrationInstrumentedTest.java),
`everyExportedSchemaMigratesToCurrentVersion`, supplies migrations ending at 44
while `assertMigratesToCurrent` requests `AppDatabase.VERSION`, now 46.
[Migrations.kt](../../app/src/main/java/com/wuming/android/tv/db/Migrations.kt)
does implement 44→45 and 45→46, and the production builder registers them: this
finding is about test coverage, not proof those production migrations fail.
The helper selects `AppDatabase.class.getCanonicalName()` under `com.wuming`, but
[the schema tree](../../app/schemas/) has only 46 under that name; 35–45 remain
under `com.fongmi`. Earlier fixture creation therefore also needs repair.

**Recommendation.** Preserve historical schema identities deliberately, make
fixtures accessible to the renamed helper, and test each supported start version
through 46. Centralize the migration chain so the test cannot silently lag the
builder. Add an offline consistency guard for schema versions, paths and chain
coverage; compilation alone cannot detect missing migration runtime paths.

**Acceptance.** All exported supported versions have reachable fixtures and a
continuous chain to the current version. Validate representative history,
favorites, cloud ownership rows and newly added indexes after upgrade. Run Room
instrumentation only on an explicitly authorized device; this review did not run it.

<a id="app-004"></a>
### APP-004 — Missing migration paths can trigger destructive recovery

**Evidence.** [AppDatabase.java](../../app/src/main/java/com/wuming/android/tv/db/AppDatabase.java),
`create`, registers 30→46 and calls `fallbackToDestructiveMigration(false)`.
The boolean controls whether all tables are dropped; it does not disable fallback
for Room-managed tables. The repository's exact historical upgrade population was
not established, and no data-loss incident is claimed.

**Recommendation.** Define the supported upgrade floor and an explicit recovery
flow for unsupported schemas. Preserve the original database and sidecar files
before recovery; offer export/recovery or a deliberate reset with a clear effect
on local history and tracking. Avoid replacing a recoverable database merely to
make startup succeed. Separate schema errors from storage/corruption errors.

**Acceptance.** Test supported upgrades plus a deliberately missing path. The
missing-path case preserves the original data and presents a recoverable state.
Complete APP-003 first so the normal upgrade path is trustworthy.

<a id="app-005"></a>
### APP-005 — Desktop users cannot manage viewing privacy in the app

**Evidence.** [CatalogPanel.kt](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/CatalogPanel.kt)
exposes History and Keep; `loadLibrary` displays saved entries.
[DesktopUserLibrary.kt](../../desktop/src/main/kotlin/com/wuming/tv/desktop/library/DesktopUserLibrary.kt)
records playback and recommendation history but exposes no matching public
history deletion/private-session policy. This agrees with the still-relevant
[September 19 review](../desktop-review-2026-09-19.md).

**Recommendation.** Add delete-one, clear-history and private-viewing controls.
Define whether deleting history also removes recommendation signals; make the
choice understandable. A private session must suppress both progress/history and
recommendation writes, while leaving explicit favorites available.

**Acceptance.** Restart preserves deletions. Private playback creates no history
or recommendation records, including on close/auto-next. Keyboard users can reach
every action. Put reusable privacy policy in `user-library-core`, with platform
persistence/UI ports per [desktop sync architecture](../desktop-sync-architecture.md).

<a id="app-006"></a>
### APP-006 — Desktop watch time includes non-viewing time

**Evidence.** [CatalogPanel.kt](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/CatalogPanel.kt)
creates `ActiveViewing` before player preparation/launch. `finishViewing` records
`System.nanoTime() - startedAtNanos` as watched duration. Playback-state callbacks
exist, but this duration calculation does not subtract pause, buffering or startup.

**Impact.** Leaving a paused player open can exaggerate engagement and affect
recommendations. A player without reliable feedback is treated as though its
entire session was watched.

**Recommendation.** Accumulate bounded observed playing intervals using a
monotonic clock and player state. Keep elapsed session time separate from watched
time; explicitly represent unknown feedback. Do not count seek jumps as viewing.
Use shared policy with mpv/VLC capability-aware ports.

**Acceptance.** Fake-clock tests cover long pause, startup failure, buffering,
seek, speed changes, state disconnection and duplicate close/end callbacks. Ten
minutes paused must not become ten minutes watched; unknown feedback must not
silently generate full-confidence viewing duration.

<a id="app-007"></a>
### APP-007 — Make desktop live TV convenient across restarts

**Evidence.** [LiveTvPanel.kt](../../desktop/src/main/kotlin/com/wuming/tv/desktop/ui/LiveTvPanel.kt)
offers playlists/groups/channels/routes/EPG and keeps current selection in panel
state. It has no live-favorite or persisted last-channel interface. The prior
[desktop review](../desktop-review-2026-09-19.md) identifies the same gap.

**Recommendation.** Add live favorites and restore the last selected channel and
route without forcing autoplay. Key persistence by source and stable channel
identity, not list position. Handle removed channels and changed playlists.
Defer catch-up until these simpler daily-use flows work reliably.

**Acceptance.** Restart, playlist reorder, source switch and removed-channel tests
preserve the intended selection or choose an explained safe fallback. Maintain
existing background preparation/cancellation; never launch stale selections.

<a id="app-008"></a>
### APP-008 — Expose the boundary between offline and release runtime evidence

**Evidence.** [Test strategy](../test-strategy.md) correctly states that production
smoke runs the isolated verification build, not the exact signed/minified release.
[Platform capabilities](../platform-capabilities.md) defines exact-candidate runtime
checks. The historical `tests.yml` workflow (removed on 2026-10-18) kept device
readiness optional/non-blocking. These are intentional policies, not defects.

**Recommendation.** Add a compact per-candidate evidence ledger linked from the
release record: commit, APK hash/signature, flavor/ABI, authorized device, cold
start, restoration, first frame/progress/seek and TV navigation. Use separate
passed/failed/not-tested fields. Keep deterministic acceptance independent of
device availability and preserve existing explicit authorization requirements.

**Acceptance.** A reader can distinguish offline pass, verification-build smoke,
exact-release playback and live-provider evidence without interpreting log files.
Missing hardware/provider checks remain visibly not tested. No account or device
access is implied by this proposal.

<a id="app-009"></a>
### APP-009 — Documentation still describes pre-rename code

**Evidence.** [README.md](../../README.md) lists `com.fongmi.android.tv` as the
application ID, while [app/build.gradle](../../app/build.gradle) declares
`com.wuming.android.tv`. [Desktop sync architecture](../desktop-sync-architecture.md)
describes `com.fongmi.tv.core.*`; [build.gradle](../../build.gradle) enforces
`com.wuming.tv.core.*`. Multiple lessons link old source paths. The
[optimization report](../optimization-report.md) presents historical estimates and
old `.java` paths without a clear current measurement boundary.

**Recommendation.** Reconcile active documentation with verified identifiers and
source paths, including AGENTS references. Preserve historical records as dated
evidence; do not globally rewrite historical schema names or external protocol
identities. Add local-link checks and a small check for key documented identifiers.
Mark performance claims with measurement date, fixture, command and observed vs
estimated status before using them to prioritize optimization.

**Acceptance.** Active entry points link to existing files and state current
application/core identifiers. Historical exceptions are explicit. Future package
changes fail a small documentation consistency check instead of leaving silent drift.

<a id="app-010"></a>
### APP-010 — CI discards useful non-app test reports

**Evidence.** Root [build.gradle](../../build.gradle), `unitAcceptanceBaseline`,
includes desktop, helper/runtime and shared-core tests. The `test-reports` artifact
in the historical `tests.yml` workflow (removed on 2026-10-18) collected only
`app/build/reports/tests`, app coverage and app lint reports.

**Recommendation.** Collect test XML and HTML reports from all production modules,
plus the baseline summary. Keep paths structured by module, use bounded retention,
and continue uploading on failure. Do not include user configuration, credentials
or raw provider diagnostics in artifacts.

**Acceptance.** A deliberately failed shared-core/desktop test is identifiable in
the uploaded artifact without rerunning locally. Successful and failed workflows
retain the same report structure.

<a id="app-011"></a>
### APP-011 — The offline acceptance gate fails after the package rename

**Evidence.** Running `./gradlew-safe --offline unitAcceptanceBaseline` at the
recorded baseline failed in `:pythonUnitTests`. Python ran 88 tests with five
errors, all in `SuiteSelectionTest`. [run-smoke-tests.py](../../scripts/run-smoke-tests.py)
lines 17–20 still hardcode `com.fongmi` package/component/runner/method identities,
while [suites.json](../../tests/smoke/suites.json) contains `com.wuming` classes.
`load_suite` rejects the first real selection with:

```text
ValueError: Invalid or duplicate method: com.wuming.android.tv.VerificationIsolationInstrumentedTest#instrumentationTargetsTheIsolatedApplication
```

The failed tests cover deep-suite inclusion, serial enforcement, dry-run safety,
method existence and exclusion of live/opt-in fixtures. Old fixture identifiers
also remain in [test_smoke_suites.py](../../tests/test_smoke_suites.py).

**Recommendation.** Reconcile runner, tests and built APK metadata with the current
application identity. Preserve explicit serial requirements and rejection of the
normal application package. Add a consistency check tied to the build identity;
do not simply loosen the method regex or remove isolation validation.

**Acceptance.** Both tiers in both modes load successfully in dry-run without ADB
or builds. Package validation accepts only the intended verification app/runner.
The Python suite and full offline baseline pass. Then reassess any downstream
failures previously hidden by this early failure; this review cannot certify the
remaining suite as passing.

## Areas intentionally not recommended for broad rewrites

- Shared JVM cores, boundary checks, account-scoped cleanup, default validated TLS
  and separated verification app identities already provide useful foundations.
- Do not merge original/smart cloud contracts or add generic fallbacks. Existing
  Ali/UC/Quark lessons document why these distinctions matter.
- Do not pre-resolve episodes indiscriminately: cloud resolution can create saved
  copies. No new cloud route or account experiment was performed here.
- Do not prioritize blanket language conversion, new provider integrations or
  speculative cache/bundle tuning ahead of the concrete persistence defects.
  Performance work should start with a reproducible bottleneck and before/after data.

## Implementation validation

Offline working-tree validation on 2026-09-25, before the device smoke run below:

- `./gradlew-safe --offline unitAcceptanceBaseline`: passed, including all production
  modules and the three architecture/lifecycle boundary tasks. Cached/up-to-date
  tasks remain part of Gradle's normal incremental acceptance, not fresh hardware evidence.
  The final rerun after history-navigation and test-fixture isolation changes passed in 24 seconds.
- `python3 -m unittest discover -s tests`: **95 tests passed**. These include mocked
  publishing/signing fixtures; no artifacts were published by the tests.
- `:app:assembleCriticalTests`: passed for the mobile ARM64 and Leanback ARM64/ARM32
  verification/test-runner APKs. No APK was installed.
- Desktop reports: **270 tests, zero failures/errors, five opt-in tests skipped**.
  Shared user-library/playback/live reports: **27 / 125 / 24 tests**, zero failures.
- Four `run-smoke-tests.py --dry-run` combinations (production/deep × mobile/leanback): passed.
- Leanback static check: **125 layouts, 291 explicit focus targets, 26 action
  contracts, 156 listener checks, three label locales**; UI-theme contract passed.
- Desktop browse/detail layouts rendered and inspected at the tested sizes;
  review file links/anchors and `git diff --check` passed.
- Verification-flavor lint passed for mobile ARM64, Leanback ARM64 and Leanback
  ARM32 (`lintMobileArm64_v8aVerification`, `lintLeanbackArm64_v8aVerification`,
  `lintLeanbackArmeabi_v7aVerification`). The combined acceptance/assembly/lint run
  completed successfully in 3m 53s.

Lint also exposed existing blockers outside the original list: contradictory
Java nullability annotations on Kotlin functions in BrowseTree/LiveBrowse/Task,
`activity!!` in BaseSideSheetDialog, and the missing Chinese `detail_title_total`
translation. The annotations were removed in favor of Kotlin types, the activity
is read safely once, and both Chinese translations were added. No lint suppression
or test assertion was removed to make validation pass.

At the end of offline validation, no device/provider/decoded-playback tests, hosted CI
upload, release publication or clean-source signed-release gate had been run.
The subsequent authorized device smoke evidence is recorded below. A verification APK build/lint result is
not an exact signed-release runtime result. Runtime recovery export, real Room
migration and OEM D-pad checks remain explicitly untested.

## SMOKE-001 — Authorized mobile production smoke

On **2026-09-25 (Asia/Shanghai)**, the user authorized device **`7e34269d`**.
The production smoke tier passed on model `22021211RC`, Android API 34, using
`mobileArm64_v8a` verification APKs built from the implementation working tree.
“Production” names the test tier; the installed app is `無名(testing)` and the
separate instrumentation package is `無名(test runner)`.

Command:

```sh
python3 scripts/run-smoke-tests.py --tier production --mode mobile --abi arm64_v8a --serial 7e34269d --offline
```

| Evidence ID | Coverage | Result |
| --- | --- | --- |
| SMOKE-001.1 | Isolated package identity and critical activity declarations | 3/3 passed |
| SMOKE-001.2 | Parcel and nested navigation state restoration | 4/4 passed |
| SMOKE-001.3 | Flavor playback launch routing; Media3 engine creation/release | 2/2 passed |
| SMOKE-001.4 | Mobile navigation recreation; player settings labels/touch targets | 2/2 passed |
| SMOKE-001.5 | Initial launch, cold start, background return, restart | 4/4 passed |
| SMOKE-001.6 | Cleanup and temporary power-setting restoration | Passed; saved/restored value `15`, no cleanup errors |

Every selected instrumentation method started and completed exactly once with
status `0`; all four groups returned final instrumentation code `-1` and ADB exit
code `0`. No groups remain incomplete. Both APKs installed with `adb install -r`;
no uninstall or data clear was performed. Cleanup stopped the verification app
and returned the device to Home.

Preserved [machine-readable summary and APK fingerprints](../../PUBLIC_PREPARATION.md)
and [instrumentation UI output](../../PUBLIC_PREPARATION.md)
allow future reference independent of generated build-directory retention.
This directly verifies the repaired APP-011 smoke runner on a phone. It does not
exercise Room migration/backup regressions, recovery export, real provider decoding,
TV D-pad navigation, desktop behavior, or an exact signed release candidate.

## SMOKE-002 — Extended provider and remote-input follow-up

The follow-up adds opt-in decoded public-channel playback and injected Leanback
D-pad/OK/Back coverage. It exposed and fixed wallpaper initialization/null URL
crashes and a direct-URL playback branch that discarded the supplied URL.
The final extended run passed 16/16 tests and 4/4 launch checks, including decoded
Pet Club TV playback at 848×480 with 15.059 seconds of progress. Earlier failed
attempts, including a connected playback stall, are preserved.
See the [indexed extended smoke report](extended-smoke-2026-09-25.md) for exact
attempts, runtime results, connectivity restoration and hardware scope.

## Initial investigation validation record

| Check | Result on 2026-09-25 | Limits |
| --- | --- | --- |
| `./gradlew-safe --offline unitAcceptanceBaseline` | **FAILED**, 34 seconds; `:pythonUnitTests`, 88 Python tests, five errors | APP-011; full JVM/boundary acceptance not established; some tasks were cached/up-to-date |
| `python3 scripts/check-leanback-remote.py` | **PASS**: 124 effective layouts, 289 explicit focus targets, 26 action contracts, 156 listener checks, three label locales | Static contracts only; no device navigation |
| New review's local file links and APP anchors | **PASS** | Existence/uniqueness only, not runtime correctness |
| `git diff --check` | **PASS** | Documentation whitespace check |

During the initial investigation, no APK installation, ADB access, provider login,
cloud mutation, release signing or publication was performed; only documentation changed. Existing
runtime results mentioned in linked historical reports were not rerun here.
The failed baseline is an investigation result, not a claim of successful acceptance.

## Topic index

- Follow-up correctness and validation: APP-012–APP-015

- Authorized phone smoke results: SMOKE-001.1–SMOKE-001.6
- Real provider / Leanback remote follow-up: [SMOKE-002](extended-smoke-2026-09-25.md)

- Backup / restore / cloud ownership: APP-001, APP-002
- Database upgrades / fixture continuity: APP-003, APP-004
- Privacy / recommendations: APP-005, APP-006
- Desktop live TV: APP-007
- Release confidence / CI evidence: APP-003, APP-008, APP-010, APP-011
- Package rename fallout: APP-003, APP-009, APP-011
- Documentation and future investigations: APP-009

## Review log

| Date | Change | Evidence boundary |
| --- | --- | --- |
| 2026-09-25 | Deeper APP-018–APP-021 review | Offline disposable probes; four pending fixes; no runtime/device changes |
| 2026-09-25 | APP-016/017: adaptive cache routing and private-safe failure causes | [Implementation and validation](adaptive-cache-2026-09-25.md) |
| 2026-09-25 | Implemented APP-012–APP-015 follow-ups | [Code changes and validation ledger](followups-2026-09-25.md); earlier stall cause remains unresolved |
| 2026-09-25 | Added proposed APP-012–APP-015 follow-ups | Source review and existing SMOKE-002 evidence; no new device run or code implementation |
| 2026-09-25 | SMOKE-002: public-provider decoding and Leanback key injection; fixed wallpaper startup and missing direct URL | Final extended run 16/16 plus 4/4 launch checks; mobile restored 11/11 plus 4/4; all earlier failures preserved |
| 2026-09-25 | SMOKE-001: authorized mobile production smoke on `7e34269d` | 11/11 instrumentation tests and 4/4 launch checks passed; power setting restored; verification APK only |
| 2026-09-25 | Implemented APP-001–APP-011 in the working tree | Offline validation and compiled instrumentation; no device/account access |
| 2026-09-25 | Initial APP-001–APP-011 investigation and prioritization | Local source review; reproduced offline acceptance failure; static Leanback pass; no hardware/account tests |
