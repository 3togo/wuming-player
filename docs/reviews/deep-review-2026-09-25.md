# Deeper review — APP-018–APP-021

Status: **confirmed findings; fixes not implemented in this review**. This reviews
the current working tree after APP-016/017, including our earlier changes. Permanent
IDs continue the [main findings index](app-improvements-2026-09-25.md).

| ID | Priority | Finding | Evidence strength |
| --- | --- | --- | --- |
| [APP-018](#app-018) | P1 | Recovery preflight ignores the committed WAL database version | Actual core policy called against disposable SQLite WAL databases |
| [APP-019](#app-019) | P1 | Backup can capture mutually inconsistent tables | Confirmed source ordering; deterministic minimal SQLite interleaving reproduces an unrestorable snapshot |
| [APP-020](#app-020) | P2 | Backup round trip loses long preference types | Actual Gson configuration and `Prefers` implementation, with a typed in-memory SharedPreferences double |
| [APP-021](#app-021) | P2 | IPv6/alternate loopback proxies receive the wrong playback and cache policy | Actual core `PlaybackTransportPlan.parse` results |

The [raw probe report](../../PUBLIC_PREPARATION.md) includes built
artifact hashes. These are offline JVM and host SQLite experiments, **not Android
device tests**. No provider/account access, real-library restore, cache clearing,
installation or runtime source changes occurred in this review. Temporary databases
and compiled probe classes are removed automatically.

<a id="app-018"></a>
## APP-018 — Main-file version is not the committed SQLite version

**Source:** [DatabaseUpgradePolicy.requireSupported](../../libraries/user-library-core/src/main/kotlin/com/wuming/tv/core/library/DatabaseUpgradePolicy.kt),
lines 11–20; [AppDatabase.create](../../app/src/main/java/com/wuming/android/tv/db/AppDatabase.java)
and the recovery activity both use this preflight.

The policy seeks directly to byte 60 of the main database file. It never consults
WAL transactions. A cleanly committed SQLite database can therefore have a different
effective `user_version` from the main header until checkpointing.

Measured results, with the connection held open and automatic WAL checkpoints disabled:

| Main-file header | SQLite committed version | Current preflight |
| --- | --- | --- |
| 0 | 46 | Rejects version 0 and requests recovery |
| 29 | 46 | Rejects version 29 and requests recovery |
| 46 | 47 | Accepts despite the unsupported effective version |

Every original database/sidecar byte remained unchanged by the probe. This state
can matter after an unclean process exit leaves committed WAL content. The probe
does not claim every normal app close leaves this state.

**Impact:** a valid library can be routed into recovery after restart. Conversely,
the guard can miss an unsupported effective version; Room's later open may reject
it outside the intended preflight recovery path. No destructive Room fallback was
reintroduced, and no data deletion was demonstrated.

**Why prior tests missed it:** APP-015 checked raw WAL export/readback and preservation;
the version-999 recovery fixture put that version into the main file before WAL
mode. The core policy test writes synthetic main headers. Neither exercised a
version change committed only to WAL. This is a gap in the earlier APP-004 change.

**Fix direction:** obtain SQLite's effective version with WAL-aware read-only
handling, or inspect a validated private snapshot of the database plus sidecars.
Keep supported-version policy in core and SQLite access in platform ports. Do not
checkpoint, truncate or discard originals merely to discover their version.

**Acceptance:** supported versions present only in WAL open normally; unsupported
WAL versions enter recovery; main/sidecars remain unchanged on rejection; interrupted
initial creation, invalid WAL and ordinary checkpointed databases are covered.

<a id="app-019"></a>
## APP-019 — Backup creation is not a consistent database snapshot

**Source:** [Backup.create](../../app/src/main/java/com/wuming/android/tv/bean/Backup.kt),
lines 108–120, reads eight tables separately without `runInTransaction`.
[RoomMediaCatalog.save](../../app/src/main/java/com/wuming/android/tv/media/catalog/RoomMediaCatalog.kt)
can concurrently insert a resource and its sources in one transaction.
[MediaSourceEntity](../../app/src/main/java/com/wuming/android/tv/media/catalog/MediaSourceEntity.kt)
requires every source's `resourceUri` to reference an existing resource.

A deterministic interleaving is:

1. Backup reads the resource list.
2. A writer commits a new resource and source together.
3. Backup reads the source list and includes the new source, but not its resource.

The schema-minimal SQLite fixture produced **0 resources, 1 source**, and restoring
that snapshot failed with `FOREIGN KEY constraint failed`. Wrapping both reads in
one read transaction produced **0 resources, 0 sources**, which restored correctly.
The fixture models the actual query order and foreign-key relationship; it does
not invoke Android Room or `Backup.create` itself.

**Impact:** backup can report success for valid JSON/gzip whose relational contents
cannot be restored. APP-002's compression verification cannot detect this. Restore
currently uses a transaction, so the failed insertion should roll back its database
changes; this finding is about backup usability, not proven partial database erasure.

**Fix direction:** capture all portable database tables within one Room transaction,
then serialize/compress outside it. Retain cloud-ownership exclusions. Treat the
preference snapshot as a separate storage boundary rather than claiming cross-store
atomicity.

**Acceptance:** deterministic writer/reader barriers around resource/source reads
must yield one consistent snapshot; restore it successfully into a disposable Room
database. Existing cloud-preservation and failed-restore rollback cases must still pass.

<a id="app-020"></a>
## APP-020 — JSON loses preference types that Android getters require

**Source:** [Backup's number decoder and preference restore](../../app/src/main/java/com/wuming/android/tv/bean/Backup.kt),
[Prefers.put/getLong](../../catvod/src/main/java/com/github/catvod/utils/Prefers.java),
and [DanmakuSetting duration/offset accessors](../../app/src/main/java/com/wuming/android/tv/setting/DanmakuSetting.kt).

JSON encodes both `Long(12000)` and `Integer(12000)` as `12000`. After the backup's
`LAZILY_PARSED_NUMBER` decoding, `Prefers.put` selects `putInt` for integral numbers
in the 32-bit range. `Prefers.getLong` then receives a type mismatch, catches it and
returns the default.

The probe used actual `Prefers` bytecode and the same Gson number strategy, with a
typed SharedPreferences double: **12,000 ms before backup → stored Integer after
restore → 8,000 ms returned**. Danmaku duration, fixed duration and time offset use
Long getters and small millisecond values, so they are concrete affected settings.
The entire Android backup UI was not executed in this experiment.

**Fix direction:** preserve preference types in a versioned backup representation.
Provide a documented conversion policy for legacy untyped archives, using known
setting types where necessary; do not rely solely on numeric magnitude to infer type.

**Acceptance:** small/large/negative Long values, Ints and Floats round-trip with their
original types; legacy backups recover the three known Long settings; remote pairing
secrets remain excluded. Use temporary preference keys for device regressions.

<a id="app-021"></a>
## APP-021 — Loopback detection disagrees between core and Android

**Source:** [PlaybackTransportPlan](../../libraries/playback-core/src/main/kotlin/com/wuming/tv/core/playback/PlaybackTransportPlan.kt),
`isLoopbackM3u8Proxy` and `isLoopback`, compared with Android's more complete
[MediaSourceFactory.isLoopbackHost](../../app/src/main/java/com/wuming/android/tv/player/exo/MediaSourceFactory.java).

The core checks only `localhost`, `127.0.0.1` and bare `::1`. Java URI hosts for IPv6
include brackets; the core never strips those brackets. Other 127/8 addresses and
`.localhost` names are also omitted, despite being recognized by the Android port.

For the same `/proxy?do=m3u8` path, actual core results are:

| Host | Media kind | Cache policy |
| --- | --- | --- |
| `127.0.0.1` | HLS | NONE |
| `[::1]` | PROGRESSIVE | WRITE_THROUGH |
| `[0:0:0:0:0:0:0:1]` | PROGRESSIVE | WRITE_THROUGH |
| `127.0.0.2` | PROGRESSIVE | WRITE_THROUGH |
| `player.localhost` | PROGRESSIVE | WRITE_THROUGH |

**Impact:** without an explicit HLS MIME/hint, the IPv6 proxy can be routed to a
progressive extractor and its mutable response made eligible for caching. Android's
parallel-range guard rejecting the host does not repair the core media kind or cache
policy. This is a reproduced policy error, not a claim that the current server binds
to IPv6 or that a device playback failure was observed.

**Fix direction:** centralize lexical loopback classification in core and use it for
proxy media inference, caching and Android parallel-range eligibility. Normalize
brackets/case and supported literal forms; do not add DNS lookups to these hot paths.

**Acceptance:** IPv4/IPv6 loopback and `.localhost` controls produce HLS/NONE for the
proxy path; ordinary public hosts and deceptive suffixes remain correctly classified.
Explicit MIME and provider hints retain their existing precedence.

## Reproduce and scope

Build the current verification app first if the local dependency jars are missing:

```sh
./gradlew-safe --offline :app:assembleMobileArm64_v8aVerification
python3 scripts/review-deep-probes.py
```

The runner uses the Java runtime beside the selected `javac` (the host's default
`java` and `javac` versions differ), installed Android SDK interfaces, local Gson/
Kotlin dependencies, and existing compiled app/core artifacts. Source:
[Python orchestration](../../scripts/review-deep-probes.py),
[Java application-code probes](probes/DeepReviewProbe.java).
The script reports observed behavior; exit zero means the experiment completed,
not that the application passed these proposed acceptance criteria.

Recommended fix order: **APP-018 → APP-019 → APP-020 → APP-021**. Runtime code is
unchanged in this review. Broad acceptance and device suites were not rerun for
these documentation/probe additions.

A discarded candidate: malformed JSON does become an empty `Backup`, but the actual
`AppDatabase.restore` entry point rejects an empty configuration before deletion.
That path was not promoted to an unsupported claim of corrupt-file data loss.
