# Shared Android startup and resources

Mobile, Leanback (TV), and Leanback Lite use the same code in `src/main`.
Leanback Lite also inherits the Leanback activities and layouts.

## Startup and configuration resources

Both home activities call `ConfigurationStartup.load`. It preserves the existing VOD,
Live and wallpaper startup order and the saved/default selection in `Config`; the
built-in first-run VOD source remains 饭太硬. Provider loading remains Android-native.

`Decoder` now has one direct/relay fetch pipeline. Direct and relay HTTP errors are
rejected before parsing; relay requests share the caller's cancellation tag. An
interrupted or explicitly canceled direct request cannot initiate a relay request.
JSON-object validation remains optional because Live also accepts plain playlists.

## Cache ownership

`ExpiringLruCache` owns synchronized lookup, bounded LRU eviction and expiry for both
the UI search cache and media-browser search-result cache. The existing five-minute
TTL and entry limits are preserved. Expiry uses elapsed time rather than the wall
clock. Expired entries are removed before evicting live entries; reads update LRU
recency without extending the TTL. UI search results retain list-copy semantics.

The class now lives in `:libraries:cache-core` (`com.fongmi.tv.core.cache`) next to
`MemoryBudget` and `Weights`, so Android and desktop share one cache primitive and one
heap-tier ladder. `MemoryBudget` replaced the private `maxMemory()` tier copies in
`SiteApi`, `Task`, `VodBrowse` and `SmoothnessLoadControl`; `PlaybackBufferBudget` values
and every existing entry limit and TTL stay the same. The cache gained optional weight
budgets: an entry whose own weight exceeds the budget is not retained, and eviction stays
oldest-first.

Byte budgets now cover the three caches which previously bounded only entry counts:

- `SiteApi` detail snapshots: tiered entry count, 10 minutes TTL, total 4% of the maximum
  heap and a 1% per-snapshot ceiling, so one huge detail cannot evict the working set. The
  cache still stores only a `Parcel` byte snapshot and returns an independent graph per hit.
- `M3u8Proxy` session playlists: the playback-owned playlist is stored apart and never
  expires, session keys are dropped oldest-first by 20-entry and 1% byte budgets with a
  six-hour TTL.
- Deferred OpenDrive episodes: 2h TTL, tiered entry count and a 2% byte budget measured on
  the retained payload text; the expired-handle message is unchanged.

`RegexUtils` moved to `:libraries:text-core` and its pattern cache is now a real bounded
LRU: past 64 patterns it evicts the least recently used entry instead of stopping inserts.

This refactor adds no disk cache and does not persist playback URLs or credentials.
Configuration and library persistence keep their existing owners. Poster, provider
artifact and playback caches are unchanged.

Android navigation retains the existing system/remote Back behavior.

## Validation

Focused `ExpiringLruCacheTest`, `MemoryBudgetTest`, `WeightsTest`, `RegexUtilsTest`,
`SitePerformancePolicyTest`, `RangeProbeOptimizerTest`, `AdaptiveBufferControllerTest`,
`DetailCacheBudgetTest`, `M3u8ProxySessionTest`, `OpenDriveExternalEpisodeCacheTest`,
`BaseConfigTest` and `RawThreadGovernanceTest` regressions run in the mobile and TV
verification variants.
`verifyGenericLibraryBoundaries` keeps the new libraries inside their registered packages.
Device playback, long-session cloud-detail expiry and the `SiteApi.detailContent` path were
not re-measured on hardware in this round. The offline unit and build checks above cover their
policy and lifecycle seams; hardware playback remains a separate release-readiness check.
