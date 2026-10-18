# Site resources and caching

The desktop application now caches public resources on disk and successful catalogue
results in memory. Playback resolution and account secrets do not use these caches.
The defaults below are application ceilings: restrictive HTTP cache directives take precedence.

## Implemented policies

| Resource | Storage/budget | Freshness |
| --- | --- | --- |
| TVBox configuration | Validated original envelope; 32 entries / 32 MiB total / 16 MiB per entry | Up to 1 hour fresh, up to 7 days stale on network/5xx failure |
| M3U/TXT playlists | Validated bytes; 16 entries / 32 MiB total / 16 MiB per entry | Up to 15 minutes fresh, up to 24 hours stale |
| XMLTV/XMLTV.GZ | Validated feed; 2 entries / 128 MiB total / 64 MiB compressed per entry | Up to 6 hours fresh, up to 24 hours stale; decompression capped at 128 MiB |
| Home/category results | Memory LRU, 128 entries per executor | 5 minutes |
| Search results | Memory LRU, 100 queries per executor | 2 minutes |
| Details/episode lists | Memory LRU, 256 titles per executor | 10 minutes |
| Posters | Memory LRU, 256 decoded images; existing disk cache of 512 images / 64 MiB | Disk expiration remains 30 days since last access; failed downloads become retryable after 30 seconds |
| Remote JavaScript modules | 128 entries / 32 MiB total / 2 MiB per module | Always revalidated when constructing a new runtime bundle; no stale fallback |
| JavaScript provider `local` state | Owner-only files; 512 entries / 256 KiB per value / 1 MiB per provider identity | Persists across restarts; removed when the provider identity is disabled or removed |
| Guarded prepared providers | Existing SHA-256/ABI/tool-version cache | Existing validation and trust requirements remain in force |
| Playback URLs, media buffers, login responses, credentials | No new disk cache | Resolve playback when requested; credentials remain in Secret Service |

Catalogue budgets are per provider instance, so aggregate memory use depends on the
number of enabled providers. The UI retains the parsed EPG index and calculates now/next
against the current time rather than caching a time-sensitive now/next result.

## Ownership and identity

`DesktopResourceLoader` exposes response status, final URL and headers while retaining its
byte-loading API. `ResourceDiskCache` is opt-in for configuration, playlists, EPG and module
fetches; ordinary provider requests, media and account endpoints do not acquire disk caching.

`DesktopConfigurationPayloadResolver` owns envelope decoding and advertised fallback traversal.
`DesktopTvBoxConfigurationLoader` validates configurations before caching and maps site models.
The default path uses the HTTP-aware resource cache. Injected byte transports retain the
legacy decoded-cache contract, now with bounded reads, retention and a 7-day stale ceiling.
Existing legacy entries are not imported into the HTTP store because they have no cache headers.
Navigation HTML can still be followed by the resolver but is not stored as a configuration.

`CachingCatalogSourceExecutor` wraps each local provider instance. A new configuration or
replacement runtime gets a new cache, even when its site key matches a previous provider.
Home, category, page, filters, query and detail ID are distinct keys. Filter ordering does
not affect identity. Successful credential writes/removals invalidate the memory caches
through a generation counter, without retaining credentials in keys.

JavaScript module downloads retain mirror fallbacks and populate the existing private,
read-only runtime bundle. Every remote member is fetched or revalidated before that new
bundle is used; a failed graph build cleans up its temporary directory. Existing graph
size limits and GraalJS restrictions are unchanged. Persisted module bytes are checksummed
and checked for empty/HTML responses; JavaScript syntax and API validation still occur in
the runtime. A disk hit does not execute code or grant additional trust.

JavaScript provider state uses a hash of the stable site key and module URL as its file identity.
Provider namespaces remain distinct inside that file. State is quota-limited and atomically
replaced, is never stored in the checkout, and is not the account credential store. Provider proxy
URLs are random loopback capabilities with a six-hour ceiling and are revoked when their executor
is retired. Proxy bodies are bounded to 24 MiB; streamed/file-backed proxy modes are rejected.

## HTTP and disk behavior

- Full URL and all explicit request headers form a hashed identity. This deliberately
  partitions more conservatively than `Vary`; it never drops query strings from cache keys.
- ETag and Last-Modified drive conditional requests. A 304 uses a validated stored body;
  an unexpected 304 without a usable body triggers one unconditional retry.
  A bounded fallback-location index (up to 32 entries of 16 KiB) also preserves offline
  access when a previously working configuration navigation endpoint becomes unreachable.
- Cache-Control, Age, Date and Expires shorten freshness. `no-cache` requires validation;
  `must-revalidate` disallows stale fallback. `no-store`, `Vary: *`, authorization/cookie
  requests and Set-Cookie responses bypass storage. Authentication errors do not return stale data.
  The underlying semantics are documented in [RFC 9111](https://www.rfc-editor.org/rfc/rfc9111.html).
- This is a conservative application cache, not a general-purpose HTTP proxy. Stale
  fallback is the explicit application policy in the table; it applies to I/O and 5xx
  failures only, never after cancellation or beyond the policy ceiling.
- Entries have a versioned binary format, payload digest and size limits. Content is
  validated before replacement. Invalid or corrupt entries cause a miss. Local files
  and bundled assets are always read directly.
- Writes use private temporary files and atomic replacement where supported. Store
  directories use owner-only permissions on POSIX. Temporary writes are cleaned up,
  including abandoned temporary files older than one day during trimming.
- Validation timestamps are separate from access timestamps. Offline reads never renew
  validation age. Access order drives entry/byte-budget eviction.
- One shared store per resource family serializes reads/fetches/writes, so successful
  concurrent misses reuse the resulting entry. Different families can run concurrently.
  Catalogue caches share in-flight futures per key, do not retain errors, and fence
  completions from before invalidation so they cannot repopulate the cleared cache.
- Resource hit provenance and byte counts are logged under `tvbox.cache`, without logging
  request headers or resource URLs.

## User controls

- **Settings → Force reload** bypasses configuration freshness and revalidates resources.
  A permitted offline fallback is labeled as stale in the configuration summary.
- **Settings → Clear resource caches** clears configuration, playlist, EPG, remote-module
  and poster disk caches and invalidates catalogue/poster memory caches. It keeps saved
  subscriptions, favorites, account secrets and provider trust/preparation data. Existing
  rendered data can stay visible until the next load; in-use JS bundles remain valid.
- **Catalogue → Refresh** clears the selected site's memory results (or all sources for
  Show All) and reloads the current home/search view. Clicking **Details** explicitly
  clears local catalogue results before retrieving an updated episode list.
- **Live → Load** forces playlist revalidation. Stale playlists and EPGs show an offline
  indication. Other automatic loads benefit from the fresh cache.

## Deliberate limits

The existing prepared-provider cache has no new automatic eviction policy: safe eviction
requires tracking files used by active provider processes. It is excluded from general
cache clearing. Thumbnail disk freshness is still based on idle time, not download age.
There is no background polling or stale-while-revalidate worker; refreshes occur when
requested, on the UI's existing background workers.
