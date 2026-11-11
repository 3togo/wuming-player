# Keeping the Linux desktop client in sync with mobile

The desktop client (`desktop/`, a Kotlin/JVM application) and the Android app
(`app/`) share behaviour through a set of platform-neutral JVM libraries under
`libraries/*-core`. This document describes the contract that keeps them in sync
so a desirable mobile feature is automatically available to the desktop build.

## The rule

The user clarified on 2026-10-12 that this applies to **all reusable non-GUI
logic in the app**, including existing code. Lack of a desktop screen or feature
is not an exemption: portable parsing, policy, workflows and caches belong in
shared core modules. Framework services remain ports, and features still require
platform wiring. Moving logic into a library alone does not establish UI or
feature parity. See [the extraction register](shared-non-gui-refactor.md).

A feature is shareable between mobile and desktop **only** when its
platform-neutral logic lives in a `libraries/*-core` module. The Android app and
the desktop app each provide *ports* (persistence, threading, UI) but never copy
the decision logic.

```
            +---------------------------+
            |  libraries/*-core        |   com.wuming.tv.core.*
            |  contracts + policy       |   no android / androidx imports
            +------------+--------------+
                         |
            -------------+-------------
            |                           |
 +----------v-----------+    +----------v-----------+
 |  app/ (Android)     |    |  desktop/ (JVM)      |
 |  Room DAOs, App.post|    |  JSON files, Swing   |
 |  Task.executor      |    |  java.util.concurrent|
 +---------------------+    +---------------------+
```

Adding a feature that both platforms should share means:

1. Put the pure policy/logic and the storage/behaviour **ports** (interfaces)
   into a `libraries/*-core` module. The package must be the one registered for
   that module in `build.gradle` (`com.wuming.tv.core.<domain>`), optionally with
   sub-packages.
2. Implement the Android side of each port in `app/` (e.g. a Room-backed
   repository) and wire it into the core policy. Behaviour must be unchanged.
3. Implement the desktop side of each port in `desktop/` (e.g. a JSON-file backed
   repository). The desktop feature now tracks mobile automatically.
4. Add unit tests in the core module and in `desktop/src/test`.

## Enforcement

Three Gradle tasks (rooted in `build.gradle`) guard this boundary:

- `verifyGenericLibraryBoundaries` — every `libraries/*` source owns only its
  registered `com.wuming.tv.core.*` package and never imports `android`,
  `androidx`, or `com.wuming.android`. Android sources may not own a `core`
  package.
- `verifyDesktopBoundaries` — desktop sources never import `android`, `androidx`,
  `com.wuming.android`, or `com.github.catvod`.
- `verifyCodeLifecycleIsolation` — incubating/archived/frozen vendor sources never
  enter a production build.

`desktop`'s `check` task depends on all three, so a regression in the boundary
fails CI before it can ship.

## Checklist before copying logic into desktop

- Is the logic Android-specific (Room, `Context`, `View`, lifecycle)? Keep the
  *port* in `app`, move the *policy* to core.
- Does the desktop already reimplement something the core owns? Replace the
  desktop copy with the core type.
- Can the Android class implement a core interface without changing behaviour?
  Prefer that over forking.

This discipline is what makes "desktop keeps updating with mobile" true by
construction rather than by manual porting.

## Worked examples of where the line falls

**GitHub mirrors -- a rule that had to be pulled out of both ports.** The request
interceptor in `catvod`, the desktop resource loader, the desktop mirror scanner and
the app's speed test each kept their own copy of "which hosts are mirrors, what does
a mirror prefix look like, which URL is hiding inside one". The copies drifted: the
Settings screen labelled one index with one host while the interceptor requested
another. `com.wuming.tv.core.source.GitHubMirror` now owns the list, the grammar and
the media extensions that must never sit behind a mirror, and every port calls it.
The choice a device made is remembered as a **host** (`github_proxy_host`), never as
a position in a list, because the list a user picks from can be refreshed from
outside the app.

**Live playlists -- a rule that stays split.** `com.wuming.tv.core.live` owns the
playlist grammar both platforms need (`LivePlaylistParser`) and the shared channel
numbering (`LiveChannelNumbering`), while `app/.../api/parser/LiveParser.java` stays
a port. What it does on top of the grammar is Android playback semantics: it mutates
the Room-backed `Live`/`Group`/`Channel` beans, fetches through spiders and `OkHttp`,
and interprets `#KODIPROP:` DRM, `#EXTHTTP:`, catchup, `parse=` and `click=` for the
ExoPlayer path. None of that has a desktop counterpart, and none of it appears in any
live source in the curated corpus, so moving it into core would put Android's player
model inside a platform-neutral module. When a source in the wild does need one of
those directives on desktop, add that directive to the core model with a test -- not
the player.


## Shared playlist, parser-response and search contracts

`live-core/M3uDirectives` supplies the lexical M3U rules to both Android's
player-aware `LiveParser` and the shared desktop playlist parser. Desktop loaders
pass the playlist location for relative guide resolution. Android retains DRM,
catchup and framework model mutation; shared grammar changes require adapter
regressions as well as core tests.

`playback-core/JsonPlaybackResponse` owns URL/header decoding for Android's
`ParseJob` and the desktop runtime's `CatVodCatalogCodec`. Decoder admission does
not imply that a particular player supports the transport or browser resolution.

`search-core/SearchLifecycle` owns planning and provider request epochs,
cancellation, bounded dispatch and terminal outcomes. Android's `SiteViewModel`
and desktop's `CatalogSourceCoordinator` consume it using host-owned executors.
Hosts serialize UI publication with teardown and recheck the shared epoch where
needed. Android supplies the main executor to this lifecycle and sets LiveData
inside guarded delivery, including cache hits; checking before a separate
LiveData.postValue would allow stale queued publication. Desktop retains its
overall wait budget and progressive home behavior;
Android retains alias expansion and cached-result handling.


Single-source Android search pages also use SearchLifecycle, with their own
instance and the existing 30-second VOD budget. They preserve the exact provider
query, quick flag and page, publish on the result channel, and still supersede
home/category/detail requests on that channel. They do not cancel a still-running
all-source batch. Stop and teardown invalidate both search lifecycles.

Android MediaBrowser search uses the shared `MediaBrowserSearch` workflow for
canonical/strict cache identity, alias iteration, partial successes, collection
limits and result ownership. Its lifecycle's `collectOrdered` mode preserves eager
provider submission, source-order collection and a fresh five-second wait at each
source's turn; it does not substitute the UI batch's dispatch-time deadline. It
cancels unconsumed work on early exit and invalidates queued service work before
it can start. Android supplies configuration/provider access and MediaItem mapping,
while the shared workflow owns cache publication and the 50-result cap. The
service's immediate acceptance response and existing full-result retrieval API
are unchanged. Each source checks its owning future before another alias call,
even if a provider consumes interruption. An alias cancellation exception retains
earlier successes while its owner remains active. Explicit browser-task cancellation
shares the cache-publication lock and preserves the caller's interruption flag choice.


## Subtitle, nearby-episode and recommendation policies

`media-core/subtitle/SubtitleSelectionPolicy` owns locale/script preference and
external subtitle default inference. Android maps raw provider flags to Media3;
desktop appends the same ordered language tags to mpv without replacing existing
user preferences. Each mpv list append contains one tag. Manual selection stays
with the player.

`user-library-core/EpisodeWindow` computes the current-centered slice in displayed
order. Leanback's nearby tab and desktop's nearby picker retain the full-series
browser. Android moves focus only after layout and validates the pending page;
desktop validates the detail and line before accepting a modal selection.

`user-library-core/recommend/ProviderRecommendationSession` owns deduplication,
bounds, stable paging and private/deleted-history behavior for the reviewed
`guess_you_like` bridge. Platform ports supply visible history names. Catalogue
interfaces carry an optional seed list separately from provider filter maps;
wrappers forward it without caching an old recommendation page. The shared
`ProviderRecommendationPager` also invalidates desktop paging when effective seeds
or privacy change. Recommendation navigation snapshots revalidate that context;
late category success/failure/loading callbacks check their request at delivery.
Android and the
guarded desktop host scope `historyvodname` around the provider call and restore
its previous value. Portable JVM providers may explicitly implement the optional
`setRecommendationSeeds(List)` hook, which is cleared after each scoped call;
it is not a CatVod ABI guarantee.

`catalog-core/CatalogHostContract` describes the host's exposed methods separately
from observed provider success in `CatalogVerification`. Node.js source admission
is classified in `source-core`; unavailable runtimes produce an explicit reason.

`playback-core/MpvConfigurationPolicy` provides diagnostic option scanning. The
desktop configuration store, editor and process launch remain ports; mpv interprets
the full grammar. See [the adaptation report](reference-reviews/2026-11-11-tvs-implementation.md)
for validation and coverage limits.
