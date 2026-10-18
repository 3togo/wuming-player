# Generic JVM libraries

This directory contains reusable, independently testable JVM libraries. They use neutral
`com.wuming.tv.core.*` packages and do not apply an Android Gradle plugin. Android (`app`) and the
Linux desktop client (`desktop`) are the only ports; platform-specific behaviour (persistence,
threading, UI) lives there, never in these modules.

## Module inventory

Each module owns exactly one registered `com.wuming.tv.core.<domain>` package prefix and never
imports `android`, `androidx`, or `com.wuming.android`. The package map is enforced by
`verifyGenericLibraryBoundaries` (root `build.gradle`) and must match
`settings.gradle`'s `include ':libraries:…'` entries.

> Consolidation note (2026-10-07): `native-core` was folded into `base-core`; `clock-core` and
> `update-core` were folded into `design-core`; `subtitle-core` was folded into `media-core` (module
> count 24 → 16, then 16 → 20). The five runtime families were first merged into `runtime-core` and
> later **split back** into `js-runtime` / `python-runtime` / `jvm-spider-runtime` / `guarded-runtime`
> / `cloud-login-runtime` so each pulls only its own heavy dependency (GraalJS/QuickJS, native Python,
> CatVod/UnidBG, WebView/ADB, cloud) while still implementing the shared `catalog-core` /
> `cloud-core` contracts. Shared contract + separate implementations is the preferred shape; shared
> behaviour lives in `catalog-core`/`cloud-core`, never duplicated across runtime modules.

| Library | Artifact | Package ownership | Responsibility |
| --- | --- | --- | --- |
| `base-core` | `tvbox-base-core` | `com.wuming.tv.core.base` | Dependency-free cross-cutting primitives with no domain bias: SHA-256 digests (`Digests`), atomic write-then-move file publishing (`AtomicFilePublisher`), HTTP byte-range wire forms (`ByteRanges`), RFC 7230 token shapes (`HttpTokens`), and the native (Rust/JNI) loader bridge (`NativeLoader`, formerly `native-core`). Every other core module may depend on this. |
| `cache-core` | `tvbox-cache-core` | `com.wuming.tv.core.cache` | Bounded cache policies, LRU/expiring maps, memory budgets. |
| `text-core` | `tvbox-text-core` | `com.wuming.tv.core.text` | Text/string helpers (regex utilities, etc.). |
| `design-core` | `tvbox-design-core` | `com.wuming.tv.core.design` | Design tokens, typography and theme policy (`design`), plus folded-in `design.clock` (testable time/ticker) and `design.update` (update manifest + version policy). |
| `catalog-core` | `tvbox-catalog-core` | `com.wuming.tv.core.catalog` | Catalogue, detail, episode and resolved-playback transport contracts, plus the shared `CatalogSourceExecutor`/`CatalogGateway` contract that every runtime family implements. |
| `cloud-core` | `tvbox-cloud-core` | `com.wuming.tv.core.cloud` | Cloud-provider ports and immutable item, playback, login and account contracts. |
| `epg-core` | `tvbox-epg-core` | `com.wuming.tv.core.epg` | Programme-guide contracts, time-window policy and conservative channel-name matching. |
| `live-core` | `tvbox-live-core` | `com.wuming.tv.core.live` | M3U/TVBox TXT live-playlist models and parsing. |
| `upnp-core` | `tvbox-upnp-core` | `com.wuming.tv.core.upnp` | jUPnP stream client and renderer identity ports (shared by `app` cast and `desktop` cast). |
| `media-core` | `tvbox-media-core` | `com.wuming.tv.core.media` | Media identity, file classification, domain contracts, ports and orchestration — including the folded-in `media.subtitle` parsing/search domain (formerly `subtitle-core`). |
| `playback-core` | `tvbox-playback-core` | `com.wuming.tv.core.playback` | Protocol inference, casting queue, HLS transformation, capabilities and cache policy. |
| `search-core` | `tvbox-search-core` | `com.wuming.tv.core.search` | Title/query planning, result aggregation, matching and relevance policy. |
| `source-core` | `tvbox-source-core` | `com.wuming.tv.core.source` | Source contracts, catalogue selection and performance ranking policy. |
| `user-library-core` | `tvbox-user-library-core` | `com.wuming.tv.core.library` | History, favorites and viewing-session policies plus persistence ports. |
| `js-runtime` | `tvbox-js-runtime` | `com.wuming.tv.core.runtime.js` | GraalJS module loading + catalogue-source execution. Implements `catalog-core`; depends only on GraalJS/QuickJS. |
| `python-runtime` | `tvbox-python-runtime` | `com.wuming.tv.core.runtime.python` | Native CPython (JNI) bridge. Implements `catalog-core`; ships `src/main/python` resources + the `prepareLinuxPython` native-runtime hook. |
| `jvm-spider-runtime` | `tvbox-jvm-spider-runtime` | `com.wuming.tv.core.runtime.jvmspider` | JVM Spider host + CatVod executor. Implements `catalog-core`; depends on `base-core`. |
| `guarded-runtime` | `tvbox-guarded-runtime` | `com.wuming.tv.core.runtime.guardspider` | Guarded Spider host/preparer/diagnostics. Implements `catalog-core`/`cloud-core`; depends on UnidBG + ASM. |
| `cloud-login-runtime` | `tvbox-cloud-login-runtime` | `com.wuming.tv.core.runtime.cloudlogin` | Cloud QR/login resolvers. Implements `cloud-core`. |
| `maccms-core` | `tvbox-maccms-core` | `com.wuming.tv.core.maccms` | MacCMS source parsing and normalization. |

## Dependency discipline

- A core module may depend only on other `:libraries:*` modules (and `:libraries:base-core` as the
  shared base). `verifyGenericLibraryBoundaries` forbids a core module depending on `:app`,
  `:desktop`, `:catvod`, `:quickjs` or any platform module.
- `:desktop` may depend only on `:libraries:*` (plus a `runtimeOnly` allowance for
  `:guarded-catvod-host`); `verifyDesktopBoundaries` forbids `android`/`androidx`/`com.wuming.android`/
  `com.github.catvod` imports in `desktop`.
- `:app` and `:desktop` are the only places that should own a `com.wuming.tv.core.*` *implementation*;
  `verifyGenericLibraryBoundaries` also rejects `com.wuming.tv.core.*` package ownership in `app/src`.

## Add / keep a module only for a cohesive domain

Add another library only for a cohesive domain with a stable public boundary — for example
`base-core` (low-level JVM primitives + native loader), `cache-core` (cache policy), `playback-core`
(platform protocol transforms). Do **not** create modules for individual utilities.

For families that share a contract but carry distinct heavy dependencies, prefer a **shared contract
module + separate implementation modules** over a single monolith. The runtime family is the model:
`catalog-core`/`cloud-core` hold the shared `CatalogSourceExecutor`/`CatalogGateway` (and cloud)
contracts + codec, while `js-runtime` / `python-runtime` / `jvm-spider-runtime` / `guarded-runtime`
/ `cloud-login-runtime` are separate implementations, each pulling only its own heavy dependency
(GraalJS/QuickJS, native Python, CatVod/UnidBG, WebView/ADB, cloud). Shared behaviour goes in the
contract module, **never duplicated** across implementations.

## Shared (extracted) cross-cutting utilities

The following were consolidated into `base-core` on 2026-10-07 so domain modules no longer pull an
unrelated dependency just to reuse a primitive:

- `Digests`, `HttpTokens` — moved out of `text-core`.
- `AtomicFilePublisher` — moved out of `cache-core`.
- `ByteRanges` — moved out of `playback-core` (its only in-module caller, `CastRelayTransfer`, now
  depends on `base-core`).
- `NativeLoader` — moved out of the former `native-core` when that module was folded into `base-core`.

Other shared policy objects extracted earlier (e.g. `GitHubProxyProbe` in `source-core`,
`LiveLineProbeMemory` in `live-core`, `OkHttpStreamClient`/`RemoteDevice.toRendererIdentity` in
`upnp-core`) keep their domain home because their surrounding policy is domain-specific; only their
duplicated *logic* was unified, while each port keeps its own HTTP stack.

## Enforcement tasks

Three root Gradle tasks guard the boundaries (`check` depends on all three, so a regression fails
before it ships):

- `verifyGenericLibraryBoundaries` — package ownership + no Android imports in any `libraries/*`
  source; also rejects `com.wuming.tv.core.*` ownership in `app`.
- `verifyDesktopBoundaries` — no `android`/`androidx`/`com.wuming.android`/`com.github.catvod`
  imports in `desktop`.
- `verifyCodeLifecycleIsolation` — incubating/archived/frozen vendor source never enters production.

Run `./gradlew unitAcceptanceBaseline` to exercise every production module's offline unit tests
plus the boundary and lifecycle gates.
