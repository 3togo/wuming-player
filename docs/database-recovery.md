# Backup, restore and database recovery

## Portable restore policy (APP-001)

A configuration backup replaces only Site, Live, Keep, Config, History,
ViewingRecord, MediaResource and MediaSource in one transaction. Missing portable
tables in an older backup become empty, as before. MediaSource is cleared before
MediaResource to honor its foreign key.

CloudCacheRecord and CloudAuditEvent are local ownership/audit evidence and survive
restore. Device, Drive, Track, SearchHistory, SitePerformance and EpisodeNameLog
also remain local. A restored file cannot grant authority to delete a remote file
on a different account. Server/pairing secrets remain excluded from preferences.
This is a portable configuration/library backup, not a clone of the entire installation.

## Archive publication (APP-002)

Backup writes a unique temporary gzip, verifies decompression against the payload,
forces its bytes to storage, then atomically replaces the dated archive. A failed
write/compression/verification/replacement preserves the last good archive and
reports failure. Retention runs only after publication. Filesystems without atomic
replacement report failure instead of attempting a destructive overwrite. Restore
limits decompressed input to 64 MiB and reports malformed/oversized archives.

## Upgrade recovery (APP-003 / APP-004)

Production migration paths cover versions 30 through 46. Historical exported test
schemas begin at 35; copies under the current Room database class name preserve
those JSON fixtures byte-for-byte. Production and migration tests use `Migrations.all()`.
The offline guard checks registry continuity and fixture availability.

The app no longer enables destructive migration fallback. A read-only SQLite
header preflight rejects versions outside the supported range before Room opens
the database. Unreadable headers/storage failures are presented separately. The
recovery screen runs in the lightweight error process and can export the original
`tv`, `tv-wal`, `tv-shm` and `tv-journal` files through the system document picker.
It offers retry after repair or installing a compatible version, without resetting
history, favorites or cloud ownership records. No automatic remote cleanup occurs.

Recovery exports contain private data. They are for deliberate recovery by the
owner; never attach them to public diagnostic reports. Export is not an automatic
repair, and the header preflight does not prove that every SQLite page is intact.
Room migration transactions retain their normal rollback behavior. Authorized
Android runtime validation remains necessary for system document-picker and
D-pad interoperability.
