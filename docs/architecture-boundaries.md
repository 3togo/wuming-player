> Public-distribution update: the ForceTech, JianPian, Thunder and TVBus integrations and their dedicated core helpers were removed. SDK-specific rows below are historical; see [vendor-sdk-removal.md](vendor-sdk-removal.md).

# Platform architecture boundaries

TVBox now separates reusable product rules from Android implementation code at the Gradle module
and package levels.

```text
 catalog-core  cloud-core    epg/live-core   media/playback-core    search/source/library
 wire models   cloud ports   guide/live rules media/transport rules portable policies
      ^                    ^                    ^
      +--------------------+--------------------+
                    /             \
          app (Android)       desktop (Kotlin/JVM)
        /       |       \       Swing + host adapters
  leanback  leanback_lite  mobile
```

`cache-core` (shared cache primitives and heap tiers) and `text-core` (regex reuse) sit in the same
generic-library row as the modules above and are available to both platforms.

The dependency directions are `app -> generic libraries` and `desktop -> generic libraries`.
Generic libraries never import Android, AndroidX, Media3, Room, application globals, Android
resources, or the Android application namespace. Desktop provides its own UI, playback,
persistence and runtime adapters. It must not depend on `app`, Android runtime modules or AARs.

## Current ownership

| Area | Owner | Reason |
| --- | --- | --- |
| Linux desktop shell and external-player adapter | `desktop` | Desktop lifecycle, Swing and host process discovery are platform concerns |
| Bounded cache and heap-tier primitives | `cache-core` | `ExpiringLruCache` entry/byte budgets, `BoundedReferenceCache` reference eviction and the `MemoryBudget` heap tiers are platform-neutral and shared by both clients |
| OpenDrive playback handle maps | `app` | Deliberately not unified: Quark and Uc hold unbounded live-handle registries whose entries carry a lease or guard a provider-side copy, while only Tianyi's are idle-expiring bounded references. `StreamCacheShapeTest` pins the split; see *Deliberate divergences* |
| Catalogue/detail/episode wire contracts | `catalog-core` | Stable transport models contain no Android beans or JSON implementation |
| Catalogue execution contract | `catalog-core` | `CatalogGateway` lets UI/use cases select a local JVM/JS engine or Android companion without coupling to either transport |
| Per-source execution contract | `catalog-core` | `CatalogSourceExecutor` and `ExecutorCatalogGateway` adapt local JS/JVM runtimes without duplicating catalogue workflows |
| Catalogue source-audit workflow | `catalog-core` | Staged discovery/search/detail/playback checks, cancellation and platform-capability classification operate on `CatalogGateway` only |
| Companion catalogue API and provider execution | `app` | Pairing, spiders, private credentials and relay creation remain Android responsibilities |
| Companion endpoint paths and parameter rules | `source-core` | Android server and desktop client share one versioned protocol contract and cannot drift independently |
| Cloud provider ports and immutable cloud models | `cloud-core` | Android and desktop can supply independent transports and credential adapters |
| Credential shape and bare-https origin rules | `cloud-core` | `CredentialShapePolicy` and `FirstPartyOriginPolicy` validate token/passcode shapes and reject user-info, port and non-https look-alikes without a URL client |
| Cloud and playback media-name rules | `media-core` | `MediaFileClassifier` holds both the strict cloud listing allow-list and the broad playback set; providers keep no local extension copy |
| Provider HTTP, credentials, CatVod payloads and cloud UI | `app` | These depend on Android runtime or provider-specific integrations |
| Programme-guide contracts, XMLTV parsing and channel matching | `epg-core` | Streaming XML parsing, time-window and conservative identity rules are pure JVM policy |
| Catch-up URL expansion | `epg-core` | Template and time-window expansion is shared programme-guide policy |
| M3U/TVBox TXT playlist models and parsing | `live-core` | Playlist structure and metadata extraction are platform-neutral |
| Live playlist loading, browsing and player launch | Platform adapters | Networking, filesystem access, UI and process launch are host concerns |
| EPG download, cache and presentation | Platform adapters | Network, storage and UI remain host concerns |
| Stable OpenDrive media identity | `media-core` | URI parsing and identity are pure JVM rules |
| Media contracts and pipeline | `media-core` | Models, ports and orchestration are platform-neutral |
| Playback capability metadata | `playback-core` | Header filtering and capability validation are platform-neutral |
| Episodic cast queue | `playback-core` | Queue snapshots and advancement eligibility are platform-neutral |
| HLS playlist purification | `playback-core` | Playlist transformation accepts host-selected rules and URI resolution as inputs |
| HLS rule configuration | `app` | Merges Android VOD/live configuration and preserves CatVod URL behavior |
| Transport and cache decisions | `playback-core` | Protocol inference and cache policy do not require Media3 |
| Range probing and adaptive buffering | `playback-core` | `RangeProbeOptimizer`, `AdaptiveBufferController` and the `RangeTuningProfile` wire format are pure JVM playback rules |
| Search identity, planning and result policy | `search-core` | Query variants, matching, aggregation and relevance are pure JVM policy |
| Regular expression reuse | `text-core` | A bounded pattern cache is platform-neutral policy; sniffing and parsing callers remain platform code |
| Traditional/Simplified conversion adapter | `app` | CatVod remains an Android runtime dependency |
| `Vod` search-result adapter | `app` | Maps the Android bean and source provenance to generic search policy |
| Source catalogue and ranking policy | `source-core` | Selection, capabilities and metric ranking are pure JVM rules |
| JianPian and Thunder link spelling | `source-core` | Scheme recognition and native URL normalization are pure; the Android app owns both native engines |
| Provider cooldown policy | `source-core` | Failure thresholds and retry timing do not depend on Android IPC |
| Provider JAR recovery integrity | `source-core` | Digest matching and last-good eligibility are pure decisions; Android owns artifact storage and Dex loading |
| Site performance scoring, sampling and flush policy | `source-core` | `SourcePerformancePolicy`, `SiteSpeedSamplePolicy` and `SitePersistencePolicy` are measurement, cadence and merge rules without storage |
| User history, favorites and viewing policy | `user-library-core` | Identity, retention, visibility, session math and storage ports are platform-neutral |
| Episode navigation | `user-library-core` | Next/previous index and reverse-play direction use the displayed episode order on both clients |
| Recommendation scoring and penalties | `user-library-core` | Android and desktop rank the same catalogue metadata with one bounded, clock-safe viewing profile |
| Room/cache user-library persistence | Android `repository` adapters | Implements generic ports and contains platform scheduling/invalidation |
| Desktop user-library persistence | `desktop` | Atomic JSON adapters retain independent playback sessions and record monotonic elapsed viewing time |
| User-library workflows | Android `library/UserLibrary` service | Composes generic policies and repository ports; entities remain persistence-free data models |
| Site/Room/spider source adapters | `app` | Parsing, persistence and execution remain Android runtime concerns |
| Site performance persistence | `app` | Room/`DataCache` writes go through the write-only `SitePerformanceStore` port; `SitePerformanceTracker` keeps the RAM cache, flush cadence and retry budget, and `SitePerformanceStoreContractTest` plus `SitePerformancePersistenceGuardTest` hold the seam |
| Detail snapshot, playlist session and deferred episode budgets | `app` | `cache-core` primitives are wired to per-feature byte budgets; the retained graphs are Android beans and playlists |
| Media3 data sources and player engine | `app` | AndroidX and Android lifecycle integration |
| Room media catalog | `app` | Android persistence adapter |
| Activities, services, resources and UI flavors | `app` | Android presentation and lifecycle |
| Native ForceTech/JianPian/Thunder/TVBus adapters | `app` AAR boundary | Android ABI-specific integration |
| `catvod`, `chaquo`, `quickjs` | Android runtime modules | Not generic merely because some sources are Java |

## Enforcement

`verifyGenericLibraryBoundaries`, included in library `check` and Android `preBuild`, rejects:

- Android or AndroidX imports anywhere in generic library sources or tests;
- imports from `com.wuming.android.*` into generic libraries;
- project dependencies from generic libraries to platform modules;
- sources outside their registered library package ownership; and
- Android application sources claiming a core package.

The Kotlin/JVM and Java Library plugins provide an additional physical guarantee: generic libraries
have no Android SDK or Android resource source set. Each library builds a normal JAR and sources JAR.
The Android application declares one-way Gradle dependencies on the generic libraries.

## Deliberate divergences

Shared code has a cost when the copies are not actually the same rule. These were measured rather
than assumed, and are kept apart on purpose:

- **OpenDrive playback handle maps.** `files` is one bounded `cache-core` reference cache at a
  single named capacity across all providers. `streams` is not: Quark's map is a lease registry
  (`active` reader count, drops a handle only while idle, and dropping one releases provider-side
  quota), Uc's holds immutable records that it *reads* to build a protection set and never ages
  out, and only Tianyi's are idle-expiring bounded references over a value type with no lease.
  Applying a size limit to the first two could evict a handle mid-playback or strand a saved copy,
  and the bounded cache exposes no iteration view for either pass. `StreamCacheShapeTest` pins the
  split so a future "just unify it" edit fails.
- **Share-link canonicalization.** Each provider parses a different grammar — Quark/Uc `/s/<id>`,
  Baidu `/s/1<suffix>`, AliPan `/s/<id>[/<folder>]`, Pan123 `/s/<key>` with percent-encoded
  passcodes, Tianyi `/web/share?code=`. Only the passcode shape and the origin guard they genuinely
  agree on are shared; forcing one canonicalizer would silently accept or reject different links.
- **Cloud versus playback extension lists.** Drive listings use the strict container allow-list;
  WebDAV mirrors a directory with the broad playback set. These are two policies, not one that
  WebDAV forgot to deduplicate.
- **Site performance reads.** The port is write-only by decision, not by unfinished work. The
  tracker's live records are the objects ranking reads, and a flush lags the increments it stores,
  so a read-through port would hide everything recorded since the last write. The one persistence
  call still inside the tracker is the read-through load and its cache fill.

## Migration rule

Move coherent behavior, not individual convenience methods. First establish JVM regression tests,
then move the model or policy into core, update Android callers, and verify every Android flavor and
ABI. Kotlin conversion can follow without changing behavior. Classes coupled to Android-heavy bean
graphs remain Android code until their data contracts are separated deliberately.
