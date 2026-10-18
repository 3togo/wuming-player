# Persistence and desktop casting Kotlin follow-up

Date: 2026-09-16. Follows the [38-file targeted delivery](targeted-conversions-20260916.md).

## Implemented

Converted four production Java files to Kotlin:

- `app/src/main/java/com/fongmi/android/tv/bean/Drive.kt`
- `app/src/main/java/com/fongmi/android/tv/bean/Track.kt`
- `app/src/main/java/com/fongmi/android/tv/bean/ViewingRecord.kt`
- `desktop/src/main/java/com/fongmi/tv/desktop/cast/CastFileServer.kt`

Updated `TrackUtil` and the mobile `CastController` to use Kotlin property syntax. No UI or playback policy changes are intended.

`Drive` retains its public fields, Java getters/setters, static methods, cache routing, and asynchronous database operations. `Track` retains nullable metadata, fluent methods, its Java boolean accessors, and the `selected` database/JSON name.

`ViewingRecord` retains nullable persisted metadata and empty-string normalization on reads. Fields implementing the non-null core recommendation interface use separate nullable storage, with explicit Room column and Gson names. Room storage accessors delegate to the existing accessors to retain Java override behavior. Snapshot copying preserves raw nulls; watch-time updates still use the shared `ViewingProgressPolicy`.

`CastFileServer` retains route-selected LAN binding, a random capability URL, GET/HEAD handling, bounded byte-range streaming, daemon workers, and shutdown behavior. Its Java constructor still declares `IOException`. The package-visible Java range helper becomes a public static Kotlin companion bridge for existing callers and tests.

## Validation

- All six Android verification variants compiled: mobile, leanback, and leanback-lite, each for ARM64 and ARM32.
- Mobile JVM suite: **824 passed, 1 skipped**.
- Leanback JVM suite: **831 passed, 1 skipped**.
- Desktop JVM suite: **178 passed, 5 skipped**.
- Total: **1,833 passed, 7 skipped**, no failures.
- Four new Java regression tests cover public-field/accessor compatibility, nullable metadata, legacy JSON names, and viewing-history snapshot/completion behavior. They run in both Android JVM suites.
- Added desktop byte-range boundary coverage; existing HTTP range, HEAD, route protection, and revocation tests also passed.
- Compiled original Android Java types and compared public/protected JVM signatures, generic declarations, and overridability against the Kotlin output in both main flavors: no missing or incompatible existing members among the three converted types. Desktop constructor/method descriptors and checked constructor exception also match. This is a focused API comparison, not a proof of all reflective behavior.
- All **38 generated table/index SQL statements** and the Room identity hash match version 44 in every compiled Android variant. All ten tracked schema exports remain byte-for-byte unchanged; no database migration is required.
- Architecture/lifecycle boundary tasks, Kotlin formatting, and `git diff --check` passed.

No device tests were run for this follow-up. The JVM and compile results do not establish device-level playback or persistence behavior.

Machine-readable counts, schema hashes, variants, and API comparison results are in [verification.json](../../PUBLIC_PREPARATION.md).

## Remaining scope

The Android inventory now contains **268 production Java files / 64,757 lines**, including seven legacy Room entities plus `AppDatabase`. Six desktop casting Java files remain. These are follow-on candidates, not unfinished work in this four-file batch.
