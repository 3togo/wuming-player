# Ali download references and adaptive playback — 2026-09-11

User requested production implementation of the measured connection ramp, then
supplied two repositories and Ali's playback API documentation. This is a bounded
review of download, client, and file-read behavior, not an exhaustive review.
No managed references were updated or added to the manifest. Read-only snapshots
were downloaded to `/tmp/ali-reference-review/` for the explicitly supplied repos.

## Sources and reviewed scope

- [messense/aliyundrive-webdav](https://github.com/messense/aliyundrive-webdav/tree/00caff62b55ae53327f1c8824b19b1c9532cc174),
  commit `00caff62b55ae53327f1c8824b19b1c9532cc174`:
  `src/drive/mod.rs` client configuration/download/get_download_url;
  `src/vfs.rs` read_bytes/redirect_url; README token-contract note.
- [messense/aliyundrive-fuse](https://github.com/messense/aliyundrive-fuse/tree/0ecdf20eb1d067fa2d3028a45046fa9c9c4da1d2),
  commit `0ecdf20eb1d067fa2d3028a45046fa9c9c4da1d2`:
  `src/drive/mod.rs` download/get_download_url; `src/file_cache.rs` read_chunk/read;
  `src/vfs.rs` read delegation.
- [Ali Open Platform video playback](https://www.yuque.com/aliyundrive/zpfszx/xegh9q),
  read through its public Markdown export. Describes HLS transcoding, explicit
  authorization, quality entitlement, URL expiration, metadata and progress APIs.
- The PHP article supplied earlier remained unreadable (403/browser challenge),
  so no technical claim or implementation is attributed to it.

## Findings and protocol coverage

| Contract / behavior | Android mobile and leanback | Desktop | Decision |
| --- | --- | --- | --- |
| Personal `v2/file/get_download_url` used by FUSE | Covered in Ali provider | External via Android/provider adapters | Same contract; no faster original URL found |
| WebDAV v2 Open Platform `getDownloadUrl` | Not covered by current consumer-token provider | Not built-in | Different authorization contract; do not transplant endpoint |
| Exact HTTP byte ranges | Covered; strict 206/Content-Range validation in new Ali adapter | Platform adapter required | Preserve offsets, final short range and original bytes |
| Direct download / relay bypass | Covered for Ali original | Adapter dependent | Earlier no-proxy measurements already ruled out app relay cause |
| WebDAV server / FUSE mount | Not applicable to playback product scope | Not implemented by this change | Do not import mount/server architecture |
| Open Platform transcoded HLS playback | Partial: consumer smart counterpart, different API/token contract | Adapter dependent | Preserve original/smart distinction; no original fallback to HLS |
| URL renewal during long reads | Partial: refreshable transport exists; Ali original does not yet attach it | Adapter dependent | Separate remaining work; short smoke does not validate expiry renewal |
| Remote playback progress and recent-list APIs | Not covered | Not covered | Outside connection-ramp task; no unsolicited account writes |

WebDAV uses bounded retry middleware and caches/renews expiring download URLs.
FUSE fetches a download URL on cache misses and reads exact buffered ranges.
Neither reviewed read path provides evidence for a throughput-driven concurrent
ramp. Their code is useful for route and lifecycle comparison, not proof that
consumer and Open Platform credentials can be exchanged. No upstream source was
copied and no upstream dependency added; the new algorithm follows the local
device measurements recorded in `docs/ali-playback-cache-lessons.md`.

## Implementation

- Portable `AdaptiveRangeConcurrency` policy in playback-core: starts at one,
  measures two complete batches per level, increases 1 → 2 → 4 → 6 … up to a
  hard bound of 16, and compares aggregate bytes divided by wall time. Less than
  10% improvement returns to the last proven level; a later >10% slowdown lowers
  the level. A rejected level is not repeatedly retried within the same session.
- Android `AliAdaptiveRangeDataSource`: 128 KiB ranges read concurrently, returned
  in byte order, with no overlapping speculative batches. Response bodies close
  before the next batch; cancelled workers retire before reopening or retrying.
  HTTP 429/503 reduces concurrency, waits at least five seconds and respects
  Retry-After (seconds or HTTP date), with at most two local recovery attempts.
- `MediaSourceFactory` selects this adapter only for Ali original's existing
  transport hint. Original-copy ownership, credentials, preview routing, and
  other providers' parallel transports retain their existing contracts.
- Network changes reset the measurements. Session state survives extractor seeks;
  new playback starts conservatively. Logs contain aggregate throughput, decisions,
  connection counts and status, never signed URLs or credentials.

## Validation checkpoint

Five core policy regressions pass. Three Android instrumentation tests pass:
exact bytes plus seek and header isolation, simulated 503 recovery with measured
cooldown/concurrency bounds, cancellation, malformed ranges, and Retry-After.
Mobile and leanback arm64 debug APKs and the test runner build offline; architecture
boundary checks run through preBuild. The normal app and runner were updated on
the explicitly authorized phone `7e34269d`, preserving application data.

Live smart playback passes (+4000 ms). Original's short startup check still fails
while warming up through 1 → 2 → 4; that evidence remains in
`ali-adaptive-live.txt`. An extended original observation is recorded separately
and must not erase that startup limitation. The 90,077 ms extended observation advanced the playhead to 22,786 ms,
with 28,821 ms buffered and player state READY. It correctly failed the requested
30-second-progress threshold. Real logs show 1 → 2 → 4 → 6 → 8 → 10 → 12,
HTTP 503, five-second backoff to 10, then continued 189,766–204,066 bytes/s
batch rates. Adaptation and recovery are verified; smooth original playback under
this provider throttle remains unresolved. See `ali-adaptive-long-live.txt` and
`ali-adaptive-decisions.txt`. The normal short startup failure is retained.

## Resume

Continue from this report and `status.json`; do not repeat the two repository
reviews without new evidence. An example request is: “Use tv-reference-abstraction
to review changes to Ali download and URL-renewal behavior since these revisions,
then apply a bounded improvement and verify original/smart routes separately.”
