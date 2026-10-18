> The public distribution removed the JianPian/Thunder engines and rejects their protocols consistently on Android and desktop. Earlier native integration proposals below are historical.

# Linux Desktop Client

Activation date: 2026-08-10

## Status

For the current implementation audit and remaining parity gaps, see
[the 2026-09-19 desktop review](desktop-review-2026-09-19.md). The milestones below
record the original foundation plan, not the current feature-completeness status.

Active foundation milestone. An explicit support decision authorized the physically separate
Kotlin/JVM `desktop` subproject. The feasibility findings below remain useful constraints, but the
earlier prohibition on implementation is superseded.

## Decision Summary

A native desktop client is feasible, but it is not an Android flavor. The first supported target is
Linux on x86-64 and arm64. The initial module uses Kotlin/JVM 17, JDK Swing and an external mpv/VLC
adapter while consuming only the generic core libraries.

Do not add a `desktop` product flavor to the Android app. The standalone `desktop` Gradle project
owns its UI and host adapters and is prevented by build checks from depending on Android code,
CatVod or Android AARs.

## FreeBox Findings

FreeBox is a dedicated desktop application rather than an Android build adapted
for PCs. It uses:

- Java 17 with JavaFX/FXML/CSS for its desktop interface;
- VLCJ for embedded VLC playback and mpv as an external player option;
- GraalJS for JavaScript spiders;
- `jlink` and `jpackage` for native application images and installers; and
- separate CI packaging for Windows, macOS, and Linux, including both amd64 and
  arm64 where supported.

Relevant upstream references:

- [FreeBox overview and compatibility notes](https://github.com/kknifer7/FreeBox/blob/main/README.md)
- [Gradle dependencies and desktop packaging](https://github.com/kknifer7/FreeBox/blob/main/build.gradle)
- [Release packaging workflow](https://github.com/kknifer7/FreeBox/blob/main/.github/workflows/build.yml)
- [VLC desktop player](https://github.com/kknifer7/FreeBox/blob/main/src/main/java/io/knifer/freebox/component/node/player/VLCPlayer.java)
- [Desktop JAR spider loader](https://github.com/kknifer7/FreeBox/blob/main/src/main/java/io/knifer/freebox/spider/SpiderJarLoader.java)
- [GraalJS spider implementation](https://github.com/kknifer7/FreeBox/blob/main/src/main/java/io/knifer/freebox/spider/js/JSSpider.java)

FreeBox documents an important compatibility boundary: Android JAR spiders do not
generally run on desktop because they depend on the Android runtime. Its desktop
loader rejects DEX-based JARs. FreeBox therefore supports desktop-compatible
Java/JavaScript spiders directly and uses an Android companion for Android-only
sources.

The architectural pattern we adopt is its interchangeable spider execution boundary: desktop UI
and source-audit workflows target one catalogue contract while local JVM/JS execution and an
Android companion are separate adapters. In this repository that contract is the independently
written `catalog-core` `CatalogGateway`; `DesktopCompanionClient` is its first implementation.
Future local runtimes must implement the same contract instead of adding a second catalogue UI.
FreeBox code, FXML, CSS, assets and protocol messages are not copied: upstream is GPL-3.0 with an
additional application agreement, so any direct reuse requires an explicit repository licensing
decision and attribution review.

## Current TV Architecture Constraints

The production feature-complete client remains Android, while the pure JVM `catalog-core`,
`cloud-core`, `epg-core`, `live-core`, `media-core`, `playback-core`, `search-core`, `source-core`,
and `user-library-core` libraries own the
first portable cloud, programme-guide, identity, media-pipeline, playback-policy, search,
source-catalogue, and user-library slices. Result models and script conversion are supplied through
platform adapters. Desktop code therefore does not need Android's `Vod`/`Site` beans or the CatVod
module for these policies. Most existing
behavior is still implemented by the Android application and Android library modules. At the
original feasibility review:

- 284 of 461 files in `app/src/main/java` directly imported Android or AndroidX;
- the mobile and Leanback source sets contained another 124 Java files;
- the resource trees contained approximately 226 Android layout files; and
- only 177 main Java files had no direct Android import, with many of those still
  transitively coupled to Android application globals, Room entities, callbacks,
  or loaders.

The principal porting boundaries are:

| Layer | Current implementation | Desktop requirement |
| --- | --- | --- |
| UI | Android Views, resources, activities, and fragments | Complete JavaFX, Compose Desktop, or web UI |
| Playback | AndroidX Media3/ExoPlayer | VLC, mpv, or another desktop media engine |
| Persistence | Room, Android `Context`, app-private paths | Desktop SQLite or file-backed repositories |
| JavaScript | Android QuickJS wrapper | GraalJS or a desktop QuickJS binding |
| Python | Chaquopy Android runtime | Desktop CPython integration or a child process |
| Java spiders | Android/Dalvik-compatible loaders | JVM-compatible spiders or remote execution |
| Native providers | ARM Android `.so` libraries | Per-OS and per-architecture replacements |
| Packaging | APK flavors and Android ABI filters | MSI/DMG/DEB/RPM/AppImage or archives |

Examples of otherwise central types that expose Android APIs include the Media3
types in `PlayerEngine`, Room and `Context` in `AppDatabase`, and `Parcelable`,
Android HTML parsing, and application globals in `Vod`. Consequently, files cannot
be classified as portable solely because they contain ordinary Java business
logic.

The current native JianPian and Thunder integrations only ship Android ARM libraries. Their source
trees are isolated from production builds and their AARs remain Android adapters. They would be
unavailable in a standalone PC client unless their vendors provide supported desktop binaries or
the features are replaced.

## Preferred Feature Integration Architecture

The preferred first implementation is a companion model:

```text
Standalone Kotlin/JVM desktop UI and VLC/mpv player
                    |
          authenticated HTTP/WebSocket API
                    |
             Android TV application
          +---------+----------+
          |                    |
    source/spider runtime   protected media relay
```

The Android app would remain responsible for configuration parsing, Android JAR,
QuickJS and Python execution, provider authentication, and final playback URL
resolution. The desktop client would provide desktop navigation and playback and
would consume platform-neutral JSON responses.

This direction can extend existing infrastructure instead of introducing a
second spider implementation immediately. The Android app already contains:

- an embedded NanoHTTPD server;
- pairing tokens and remote-access authorization;
- remote playback actions and player-state responses;
- spider proxy and M3U8 proxy routes; and
- a short-lived media relay that preserves private upstream headers.

A future protocol should be explicitly versioned and could expose operations such
as:

```text
/desktop/v1/config
/desktop/v1/sites
/desktop/v1/home
/desktop/v1/category
/desktop/v1/search
/desktop/v1/detail
/desktop/v1/play
/desktop/v1/history
/desktop/v1/favorites
```

These names are illustrative, not a frozen API. Before implementation, define
request and response DTOs, authentication, pairing UX, token revocation, relay URL
lifetimes, error codes, pagination, cancellation, and backward compatibility.
Never expose provider cookies, account tokens, raw authorization headers, or
unbounded proxy access to the desktop client.

## Delivery Phases

The foundation module is now active. Later phases remain gated product work rather than implied
commitments.

### Phase 0 — Desktop foundation (active)

Maintain the physically separate `desktop` Gradle project, generic-only dependency boundary,
headless smoke check, and safe external-player adapter. This phase deliberately contains no Android
runtime or source-engine dependency.

### Phase 1 — Companion contract (active)

The versioned `/desktop/v1` contract now implements pairing, source listing, search, detail, direct
play URL resolution, playback through VLC/mpv, and a transport-neutral staged source audit inspired
by FreeBox's audit workflow. Provider secrets stay behind an expiring Android media relay. Audit
reports contain summaries rather than provider identifiers and distinguish Android-only playback
from source failure. Runtime validation still needs to cover latency, seeking, cancellation, Android
background-process behavior, and a representative provider matrix before installers are published.

### Phase 2 — Companion desktop MVP

Keep the desktop application as a separate Gradle subproject with its own distribution. Evaluate
desktop windowing, player abstractions, and packaging independently, while keeping the Android
source engine authoritative. Add history and favorites only after the catalog and playback protocol
is stable.

### Phase 3 — Optional direct desktop sources

The first local slice now imports plain or image-plus-Base64 TVBox configurations without Android,
maps non-sensitive source metadata, classifies direct JavaScript candidates versus sources needing
a spider runtime, and exposes a headless inspection command. `CatalogSourceExecutor` and
`ExecutorCatalogGateway` form the runtime-neutral execution boundary abstracted from FreeBox's
useful `SpiderTemplate` separation without copying its implementation.

Next, allow desktop-compatible Java or JavaScript sources to implement that executor contract,
potentially using a reviewed GraalJS adapter and a JVM loader that rejects DEX payloads. Keep
Android/Dalvik, Android framework APIs, Chaquopy, and Android-only native libraries explicitly
unsupported by the standalone desktop runtime rather than silently delegating them.

### Phase 4 — Optional standalone product

Only pursue a fully standalone desktop product if the companion MVP demonstrates
sustained demand. First extract platform-neutral DTOs and use-case interfaces into
a pure JVM module, then implement separate Android and desktop adapters for
storage, networking, source execution, playback, threading, and filesystem access.

## Alternatives Considered

| Option | Change level | Compatibility | Assessment |
| --- | ---: | ---: | --- |
| Run the APK in an Android environment | Very low | Nearly complete | Useful for internal testing, not a native PC product |
| Desktop client with Android backend | Low to moderate | Highest | Preferred future MVP |
| Desktop client with compatible JS/JVM sources | Moderate | Partial | Suitable after the companion protocol |
| Fully standalone port | High | Depends on replacements | Long-term possibility only |
| Android Gradle `desktop` flavor | Impractical | Poor | Rejected |

## Indicative Effort

The following ranges are planning estimates, not commitments:

- paired search/detail/play prototype: approximately 2–4 engineer-weeks;
- distributable companion-based MVP: approximately 6–10 engineer-weeks;
- standalone desktop client for compatible JavaScript sources: approximately
  3–6 engineer-months; and
- broad feature parity, including cloud storage, downloads, provider edge cases,
  Python sources, and native extractors: potentially 6–12 months or more.

## Gates for Expanding Beyond the Foundation

Do not expand the foundation into a published, feature-complete desktop product until the relevant
items below are decided:

1. There is a confirmed target audience and supported OS/architecture matrix.
2. A maintainer is available for a separate desktop release and update pipeline.
3. The companion requirement versus standalone-source requirement is explicitly
   decided.
4. The desktop threat model and pairing protocol have been reviewed.
5. VLC/mpv installation and redistribution expectations are settled per platform.
6. GPLv3 obligations and all reused FreeBox assets and third-party dependencies
   have been reviewed individually.
7. The Android release and regression workload will not be reduced to fund the
   desktop experiment.

Until those decisions are made, the checked-in Linux desktop foundation remains intentionally
limited to generic-core validation, Live TV playlists, paired companion catalogue/search/direct
playback, basic navigation, and distributable JVM application scaffolding.
