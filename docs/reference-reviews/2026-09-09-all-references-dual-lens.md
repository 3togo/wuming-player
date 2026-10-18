# All-reference abstraction and standards-coverage review — 2026-09-09

This is a bounded, workspace-wide dual-lens review of every repository managed by
`workspace-projects.json`. It asks both which behavior or architecture is worth adapting and which
observable protocols or standards each reference exercises that TV can consume on Android and
desktop. References were inspected at their checked-out commits after a dry-run update; none was
pulled or modified. `CatVodProviders` and `j4Uq` were dirty and were read in place.

This is a contract-oriented review, not a claim that every implementation file or live provider was
tested. The scope was top-level documentation plus targeted configuration, provider ABI, server,
proxy, live, cloud, casting, player, and test surfaces. Live third-party endpoints were not invoked.

## Baselines and bounded evidence

| Reference | Commit | State | Principal evidence sampled |
| --- | --- | --- | --- |
| Box | `258a5fef61578869ae905ca230bdde9e99fc19a8` | clean | README, Android config/loaders, Java/JS/Python modules, live/server/player surfaces |
| CatVodProviders | `ca5f128a6f61cff6d721706af799441417f582a0` | dirty | architecture, provider contract, security model, registry, build/tests |
| CatVodSpider-PC | `4c235be304c9bc5a0cef8c8bbf621ca21d9aba7d` | clean | base `Spider` ABI, cloud/WebDAV helpers, proxy and result models |
| FreeBox | `86ca1aa7a6c40f8867a7170d15b6acd79ab230d4` | clean | README, source/runtime paths, WebSocket companion, audit/player behavior |
| Ghosten-Player | `1f8b8e241720f6ead8cdc7874f64b893353efc7d` | clean | README and driver, metadata, live, DLNA, subtitle, download surfaces |
| TV-Multiplatform | `46fe61a49ac06c0e0de7e69add8ccaba08b2f404` | clean | README, CatVod core, staged search, local `/postMsg` and `/proxy`, players |
| TVSpider | `d740cb717859dae415d2f3d757147e62dce6a0ca` | clean | README, build/config transforms, JavaScript provider corpus and branches |
| TV_FM | `4afc4473e22a7ed3d98ee12233e0c2a490061000` | clean | current Android sources, generated protocol-doc site, local API and media endpoint |
| TV_Spider | `8149b227586d2547817dceb647615a810d14bdc2` | clean | Flask T4 `/vod`, request parameters, aggregate search/cache, proxy extension |
| Tv | `48055dd5cac2e465c35bd8ba260d94d959931251` | clean | historical CatVod Java ABI, TVBox config, XPath rules, parse/proxy documentation |
| j4Uq | `b705cdcb7604594434b19c3e95f796a980af0445` | dirty | README and release/signing workflows; no runtime source at this revision |
| q215613905 | `ccc25f67ce35c6699529d493177c737be3ad4ba4` | clean | Android config/loaders, JS/Python, DLNA/live and new `/media` contract |
| tv-quark-app | `ac6b814258b194263651096f18aea759e9d5e003` | clean | FastAPI routes, cookie store, Quark client, static D-pad PWA/WebView wrapper |
| CatVodOpen | `417012839c5fb168826e959581240b9c42d4de79` | clean | reused completed bounded review and its cited JS/Node/sniffer/config evidence |
| winbox | `d090afc8bef2915f3b29cd92a7f1a8d0c9e06955` | clean | README, config/CMS clients, SpiderHost/native preparation, proxy relay, health tests |

## Classification vocabulary

- **Covered** — TV implements the observed contract on that platform.
- **Partial / compatibility defect** — TV presents the surrounding compatibility surface, but a
  concrete representation or operation is missing and can break a compatible source.
- **Externally supplied** — the host can load, call, or transport the capability, but provider code,
  credentials, an external player, or an external service supplies the behavior.
- **Intentionally unsupported product scope** — the reference solves a different product problem;
  absence is not a defect in TV's declared workflow.
- **Not applicable** — the contract is platform- or repository-specific and has no corresponding
  role on that TV platform.

## Lens 1 — worthwhile ideas and disposition

| Reference | Worthwhile abstraction | TV disposition |
| --- | --- | --- |
| Box | Legacy TVBox configurations and providers as regression fixtures | **Use as compatibility evidence, not code.** Crosswalk and old runtime duplication are liabilities; current TV already has richer track selection, players and isolation. |
| CatVodProviders | Reproducible single-DEX artifacts, checksums, capability/provenance registry, redacted secret and network policy | **Propose REF-012.** TV already fingerprints and isolates code; add a machine-readable compatibility profile rather than importing providers. Artifact production remains external. |
| CatVodSpider-PC | A small compile-only provider SDK and deterministic ABI tests | **Propose REF-013.** Extend TV's fixture matrix across Java method variants and proxy results; keep cloud implementations in external bundles. |
| FreeBox | Source audit, redacted diagnostics, hot-reload feedback | **Mostly covered.** TV already has staged audit and persistent redacted traces. Hot reload is developer tooling, not a runtime requirement. Reject the phone-to-PC WebSocket dependency as a default architecture. |
| Ghosten-Player | Personal-library metadata, multi-account file management and downloads | **Defer to product scope.** Useful for a library product, but not evidence of a TVBox compatibility defect. TV already has cloud playback and range acceleration. |
| TV-Multiplatform | Bounded, staged multi-source search with explicit progress | **Covered in principle.** TV already has bounded provider work, late-result merging, health policy and cancellation; use the reference as behavioral evidence only. |
| TVSpider | Dialect-tagged provider fixtures and declared host requirements | **Fold into REF-012/013.** This corpus demonstrates why “JavaScript supported” is too coarse: QuickJS, Node and host globals differ. |
| TV_FM | Searchable protocol documentation generated from one structured contract catalogue | **Propose REF-014, documentation-only.** Do not copy the website stack; generate platform coverage from TV-owned metadata if maintained documentation becomes a priority. |
| TV_Spider | Bounded concurrent T4 aggregation and optional caching | **No transplant.** TV's client-side search already owns concurrency and health; server aggregation is externally deployable capability. Never pass secrets in query strings. |
| Tv | Historical CatVod/TVBox protocol examples and XPath rule corpus | **Use as fixtures only.** Unattributed or bundled binaries must not become product dependencies. |
| j4Uq | Release provenance/version mapping | **Already covered by workspace manifest/status.** This revision has no independent implementation to abstract. |
| q215613905 | LAN playback-state `/media` endpoint | **Already covered on Android, more safely.** TV returns the same eight fields behind pairing/access control. The differing numeric state mapping is fork-specific and not a stable standard. |
| tv-quark-app | Explicit static-mode/backend-mode degradation and D-pad web focus | **No adaptation.** TV is native and already owns cloud login/playback. Its unauthenticated wildcard-CORS server and plaintext cookie file are the wrong trust boundary for TV. |
| CatVodOpen | Normalize object/string provider results; executable classic-video conformance corpus | **Implemented as REF-008/009.** Keep host capabilities mediated; Node sidecar, browser service and non-video registries remain outside scope. |
| winbox | Capability-classified source activation, precise failure reasons, stateful proxy lifetime and credential revocation | **Largely covered.** TV desktop has guarded runtime diagnostics, session cleanup and range-safe relays; retain winbox as a compatibility oracle for DEX/native edge cases. |

The highest-value next abstraction is not another runtime. It is a small, machine-readable provider
compatibility profile that says which ABI and host capabilities a source requires, backed by
offline conformance fixtures. That makes external capability, platform incompatibility and product
scope visible before execution.

## Lens 2 — reference-by-reference platform matrix

| Reference | Observable contract family | TV Android | TV desktop | Classification note |
| --- | --- | --- | --- | --- |
| Box | Flat TVBox JSON; MacCMS; CatVod Java/JS/Python; parse/proxy; M3U/XMLTV; WebDAV | **Covered** for the main contracts | **Partial**: MacCMS and Java/JS subsets work; Python and Android/WebView-dependent providers do not | Python/native/provider bodies are **externally supplied**; Crosswalk is **unsupported scope** |
| CatVodProviders | CatVod Java zero-arg `csp_` classes and JSON results in a single DEX/JAR | **Externally supplied / covered host ABI** | **Externally supplied / partial** through JVM/guarded DEX profiles | Provider sites, cloud APIs and credentials are not built-in TV standards |
| CatVodSpider-PC | Pure-Java CatVod lifecycle, `proxyLocal`, cloud/WebDAV helpers | **Externally supplied / covered classic ABI** | **Externally supplied / partial**; pure Java works when dependencies match, Android APIs do not | Its SDK and providers are bundle capabilities, not host guarantees |
| FreeBox | TVBox/CatVod JS and Java imports; LAN WebSocket companion; HLS/ASS; external players | **Covered** for source-side TVBox contracts; companion client not needed | **Partial** for JS/JVM and media; companion WebSocket is absent | Companion pairing and watch-room direction are **unsupported scope** |
| Ghosten-Player | WebDAV; proprietary Ali/Quark; M3U/IPTV; XMLTV; DLNA; subtitles; Emby/Jellyfin; NFO/TMDB | **Covered** for WebDAV, cloud, IPTV/XMLTV, DLNA and common subtitles | **Covered/partial** for WebDAV, IPTV/XMLTV, DLNA and subtitles | Emby/Jellyfin/NFO/TMDB and library downloads are **unsupported scope**; FTP/SMB are roadmap only |
| TV-Multiplatform | Flat config; CatVod Java; local HTTP `/postMsg` and `/proxy`; range fetch; VLC/mpv | **Covered** for equivalent core contracts | **Covered/partial**: stronger Java runtime/relay exists; endpoints are implementation-specific | Compose UI and exact local endpoint names are **not standards** |
| TVSpider | CatVod JavaScript dialects; QuickJS/Node globals; config transforms; proxy/sniff/cloud examples | **Covered/partial** for classic QuickJS video providers | **Partial / defects below** for classic JS; Node dialect absent | Node/db globals and provider bodies are **external or unsupported scope** |
| TV_FM | Canonical Android TVBox/CatVod, local control API, Media3/mpv, live/XMLTV, DLNA | **Covered**; TV is closely aligned and `/media` is access-gated | **Covered** for shared data/live/DLNA subsets; Android control/Auto surfaces not applicable | Platform UI/service differences are **not compatibility defects** |
| TV_Spider | Type-4 HTTP `/vod` with TVBox query/result schema; optional Redis and `/m3u8proxy` | **Covered** by `RemoteSpider` | **Not covered / compatibility defect**: type 4 is classified unsupported | Aggregator hosting/cache and credentials are **externally supplied** |
| Tv | Historical CatVod Java ABI; TVBox config; MacCMS/XPath; parse type 2; proxy/push | **Covered/strong** for classic contracts | **Partial**: MacCMS and compatible JVM spiders work; Android-only provider dependencies do not | Bundled rules/JARs are evidence, not product dependencies |
| j4Uq | Release aggregation only at this revision | **Not applicable** | **Not applicable** | No independent protocol implementation to credit |
| q215613905 | TVBox/CatVod, live/XMLTV, DLNA, local `/media` playback state | **Covered**; same fields, stronger access control | **Not applicable** for Android LAN-control endpoint; desktop has its own internal relays | q215 numeric media states are a fork dialect, not a stable standard |
| tv-quark-app | Proprietary Quark HTTP APIs behind FastAPI; PWA manifest; D-pad browser UI | **Unsupported product scope** as a server/PWA; Quark capability already native | **Unsupported product scope** as a server/PWA; Quark capability already native | Quark is proprietary and credential-dependent, not a portable standard |
| CatVodOpen | Bucketed config; classic video JS ABI; object/string async results; Node REST; sniff/proxy; non-video families | **Covered/partial** for classic video; extended file/stream globals are bundle-dependent | **Covered/partial** after REF-009; provider proxy and durable local storage remain gaps | Node REST/sniffer service and non-video families are **unsupported scope** |
| winbox | TVBox types 0/1/4; Java/DEX and selected ARM64 JNI; loopback proxy with HTTP range semantics | **Covered** for canonical Android paths | **Covered/partial** via JVM/guarded runtime; native support is profile-specific | Unidbg/native execution and provider code are **externally supplied** capabilities |

## Cross-reference protocol and standards matrix

| Protocol or standard surface | Exercised by references | TV Android | TV desktop | Result |
| --- | --- | --- | --- | --- |
| Flat TVBox JSON (`sites`, `spider`, `parses`, `lives`, `rules`, `doh`) | Box, FreeBox, TV-Multiplatform, TVSpider, TV_FM, Tv, q215, winbox | **Covered** | **Covered/partial**; consumes the shared core fields but not every Android-only directive | Platform-specific partial coverage |
| MacCMS XML/JSON (`type` 0/1) | Box, TV_FM, Tv, q215, winbox | **Covered** | **Covered** | No defect found |
| CatVod Java/JAR/DEX ABI (`type` 3, `csp_`) | Box, CatVodProviders, CatVodSpider-PC, FreeBox, TV-Multiplatform, TV_FM, Tv, q215, winbox | **Covered host ABI; bundles external** | **Partial; bundles external** through JVM/guarded profiles | Capability depends on provider and Android API/native requirements |
| CatVod JavaScript classic-video ABI | Box, FreeBox, TVSpider, TV_FM, q215, CatVodOpen | **Covered/partial** | **Covered/partial** | Desktop proxy callback and durable storage defects remain |
| CatVod Python ABI | Box, TV_FM, q215 | **Covered when the external Python bundle is configured** | **Intentionally unsupported product scope** | Not a desktop defect under current declared runtime set |
| Type-4 remote Spider HTTP `/vod` | TV_Spider, TV_FM, Tv, q215, winbox | **Covered** | **Compatibility defect: not classified/executed** | REF-015 proposed |
| CatVod result JSON and play headers/quality arrays | Nearly all TVBox/CatVod references | **Covered** | **Covered** for shared video result forms after REF-008/009 | Keep fixture coverage |
| Provider proxy response and HTTP `HEAD`/`Range`/206/conditional headers | CatVodSpider-PC, TV-Multiplatform, TVSpider, TV_FM, TV_Spider, Tv, winbox, CatVodOpen | **Covered/partial** | **Covered for product relays; partial for JS provider-owned proxy callback** | Host relay strong; JS bridge defect remains |
| HLS/progressive HTTP media | Box, FreeBox, Ghosten, TV-Multiplatform, TV_FM, q215, winbox | **Covered** | **Covered** with HLS and progressive compatibility relays | External player still supplies decoding on desktop |
| M3U/TXT/JSON live playlists and XMLTV | Box, Ghosten, TV_FM, q215 | **Covered** | **Covered** for M3U/TXT/JSON and XMLTV; catch-up workflow remains partial | REF-003 remains deferred |
| WebDAV/OpenList-style storage | Box, CatVodSpider-PC, Ghosten, TV_FM/q215 provider ecosystem | **Covered** | **Covered** | Credentials and remote servers are external |
| Proprietary Ali/Quark/UC/Baidu cloud APIs | CatVodProviders, CatVodSpider-PC, Ghosten, TVSpider, TV_FM, q215, tv-quark-app, winbox | **Covered first-party plus external-provider paths** | **Covered/partial first-party plus external-provider paths** | Not standards; availability and accounts are external |
| DLNA/UPnP AVTransport and SSDP | Ghosten, TV_FM, q215 | **Covered** sender/receiver | **Covered** sender/receiver | Google Cast, AirPlay and Miracast remain intentionally unsupported |
| Common subtitles/danmaku (SRT, VTT, ASS and related XML) | Box, FreeBox, Ghosten, TV_FM, q215 | **Covered** | **Covered/partial**, depending on external player | Decoder/rendering may be external on desktop |
| LAN local-control `/device`, `/action`, `/media` | TV_FM, q215 | **Covered** with pairing/access policy | **Not applicable/product-scope**; desktop companion API is separately scoped | Do not expose unauthenticated parity merely for endpoint matching |
| Browser sniff/WebView interception | Box, FreeBox companion, TVSpider, TV_FM, q215, CatVodOpen | **Covered/partial** through host-owned WebView capability | **Intentionally unsupported** as a general browser/sniffer service | REF-006 remains rejected |
| Emby/Jellyfin APIs, NFO/TMDB library metadata | Ghosten | **Intentionally unsupported product scope** | **Intentionally unsupported product scope** | Separate personal-library product direction |
| PWA manifest/browser-hosted TV shell | tv-quark-app | **Not applicable/product-scope** | **Not applicable/product-scope** | Native TV clients do not need a PWA compatibility layer |

## Compatibility defects to fix within existing TV claims

1. **REF-015 — desktop type-4 remote Spider support.** The shared desktop runtime policy accepts
   HTTP types 0/1 and local type 3, but classifies type 4 as unsupported. Android `RemoteSpider`,
   TV_Spider's `/vod` service and winbox establish the request/response family. Implementing a
   bounded HTTP executor would restore a canonical TVBox configuration path without importing any
   provider code.
2. **REF-016 — desktop JavaScript provider proxy callback.** TV desktop claims classic JavaScript
   providers, but the provider `proxy` lifecycle is outside its executor. Providers that return a
   `js2Proxy`-style URL can therefore catalogue successfully and fail at playback. Reuse TV's
   existing safe relay and URL policies; do not expose arbitrary filesystem or listener access.
3. **REF-017 — durable, scoped desktop JavaScript local storage.** The desktop CatVod `local`
   bridge is process-memory-only. Providers using local state for non-secret session continuity
   behave differently after restart. Persistence needs per-provider namespaces, quotas, secret
   separation and clear-on-disable behavior.

These are compatibility defects because they sit inside source types or runtimes desktop already
presents as TVBox/CatVod-compatible. They are distinct from unsupported media products and from
third-party code that TV merely hosts.

## Externally supplied capabilities — do not claim as built in

- Provider bodies and their target-site behavior in CatVodProviders, CatVodSpider-PC, TVSpider,
  Tv and arbitrary configuration JAR/DEX/JS/Python artifacts.
- Android API shims, JNI/native libraries, unidbg decryption profiles and other provider-specific
  runtime dependencies. TV desktop can support bounded profiles without promising universal DEX.
- Cloud accounts, cookies/tokens, proprietary Ali/Quark/UC/Baidu endpoints, WebDAV servers, remote
  T4 services, Redis caches and companion phone services.
- Desktop VLC/mpv codecs and rendering behavior even where TV owns URL resolution and relay safety.
- Availability, legality and current correctness of third-party catalogues and scraping targets.

## Intentionally unsupported product scope — not compatibility defects

- CatVodOpen books, comics, music, pan registry and Node REST host.
- General Electron/Puppeteer sniffing service, Crosswalk resurrection, and a default phone-to-PC
  WebSocket execution architecture.
- Ghosten's Emby/Jellyfin personal-library model, NFO/TMDB enrichment, library management/download
  product, and its unimplemented FTP/SMB roadmap.
- tv-quark-app's hosted FastAPI/PWA deployment model and scraping catalogue.
- Watch rooms, synchronized social playback, Google Cast, AirPlay, Miracast and Android Auto on
  desktop unless separately approved as product features.

## Proposed candidate ledger changes

| ID | Candidate | Status | Acceptance boundary |
| --- | --- | --- | --- |
| REF-012 | Machine-readable provider compatibility/capability profiles | proposed | Distinguish ABI, host globals, UI/native needs, proxy, storage and credential requirements before activation; no provider code bundled |
| REF-013 | Cross-runtime offline conformance fixture matrix | proposed | Java method variants, T4 query/result shapes, JS object/string/async forms, proxy response and range behavior; no live endpoints |
| REF-014 | Generated searchable protocol/coverage documentation | deferred | Generate from TV-owned metadata only when a maintained docs surface is desired; do not import TV_FM's web stack |
| REF-015 | Desktop type-4 remote Spider executor | proposed-compatibility-fix | Bounded HTTP only, redirects/timeouts/size limits, shared CatVod codec, redacted diagnostics and tests |
| REF-016 | Desktop JavaScript provider proxy lifecycle | proposed-compatibility-fix | Host-owned loopback relay, URL validation, cancellation and quotas; no arbitrary listeners/files |
| REF-017 | Durable scoped desktop JavaScript local storage | proposed-compatibility-fix | Per-provider namespace/quota, atomic writes, secret separation, lifecycle clearing and tests |

No implementation was performed in this review. The recommended order is REF-015, REF-016,
REF-017, then REF-012/013. That sequence fixes observable compatibility first and uses the profile
and fixtures to prevent regressions afterward.
