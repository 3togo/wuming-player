# TVBoxOS behavior integration

Comparison source: `q215613905/TVBoxOS`, revision
`ab11d289e09963a9daf65ca7f6b7a9a8cbe184e1` (local checkout
`~/git/TVBoxOSC/TVBoxOS`). The tracked Java/Kotlin/Python inventory contains
337 files: app 268, player 57, pyramid 7, quickjs 5. The comparison covered
the module and feature inventory, recent changes, and targeted implementations;
it is not a line-by-line audit of every upstream file.

The user chose integration into Wuming's shared public code, with new
implementations under Wuming's existing GPLv3 license. TVBoxOS is AGPLv3.
No donor source files, binaries, assets, configuration feeds, or dependencies
were imported. The additions below implement general behavior in Wuming's
own architecture; this is not a claim of a clean-room process.

## Added behavior

| Capability | Shared implementation | Consumers |
| --- | --- | --- |
| Offline title suggestions, Unicode word splitting, base-title and next-season suggestions for explicit Chinese/English season suffixes | `search-core/SearchSuggestions` | Mobile and leanback `SearchWordClient`; desktop `DesktopSearchWordClient`; existing `SearchHelper.splitWords` entry point |
| Tolerant remote suggestion decoding and bounded, ordered merging with local suggestions | `search-core/SearchSuggestions` | Both Android search screens and desktop suggestions |
| JSON live playlists accepting `group`/`name`, `channels`/`channel`, `url`/`urls`, and common TVG/header aliases | `live-core/LivePlaylistJsonCodec`, `LivePlaylistParser` | Android `LiveParser` and desktop playlist loader |
| Audio/audiobook resource recognition, including M4A/M4B, FLAC, OGG/OGA, Opus and WAV; common video/manifest recognition | `playback-core/MediaUrlClassifier` | Android sniffer, shared CatVod playback admission, desktop progressive HTTP preparation |

Suggestions remain choices the user clicks: they do not change automatic query
expansion, ranking, or source selection. Bare numbers and years are not treated
as season suffixes. Local suggestions remain usable when the remote service fails;
request cancellation still prevents stale responses from replacing a newer query.

JSON normalization retains existing canonical fields and unknown platform options,
including Android DRM, catch-up, parsing, group passwords and click directives.
The desktop projection carries the existing shared channel metadata, stream URLs
and request headers; this does **not** add desktop DRM/catch-up/password UI.
M3U/TXT parsing and Android-specific directive handling remain unchanged.

Media recognition checks URL paths, avoiding matches inside web-page query strings.
Configured Android sniffing rules still run first. CatVod playback admission uses
the same Kotlin policy even if a native runtime is available; the old native codec
must not bypass the shared format list. The exposed legacy `Sniffer.SNIFFER` field
is retained for source/binary callers, while normal recognition uses the new policy.

## Existing coverage and decisions

| TVBoxOS area | Wuming coverage / decision |
| --- | --- |
| Source selection, keyword history, ranked/grouped search, folder results | Existing `search-core` policies/coordinator and selection store, Android search history, desktop history, and `catalog-core` folder model. Added the missing suggestion behavior rather than replacing these. |
| Detail parsing, source and episode selection, HTML title cleanup | Existing catalog codecs and Android `Vod`, mobile/leanback detail screens and desktop catalog UI. Retained. |
| Live grouping, alternate URLs, EPG names, catch-up | Existing `live-core`, `epg-core/ChannelNameMatcher`, `CatchupUrlPolicy` and platform live adapters. Added JSON compatibility; retained the richer EPG matcher and existing catch-up routes. |
| Track memory, speed, seeking, episode navigation | Existing Room `Track`, `TrackUtil`, player manager and platform controls. Donor's per-player numeric track index store is not a replacement for Wuming's track identities. |
| Music/background playback and media information endpoint | Existing Media3 `PlaybackService` and server `process/Media`. Added missing direct audio recognition without replacing the service. |
| Danmaku search, configuration, loading and cancellation | Existing `DanmakuApi`, search/settings dialogs, `DanPlayer` load jobs and bounded cancellable `Loader`. Retained the current lifecycle protections. No claim that Android danmaku UI now exists on desktop. |
| Subtitle lookup, downloads and rendering | Existing `media-core/subtitle` and Media3 rendering. Do not restore the retired custom subtitle parser stack. |
| HLS parsing, filtering, relays and parallel playback resolution | Existing `playback-core` HLS pipeline, platform relays and parse jobs. Retain their contracts and tests. |
| DNS, proxy routing, request transport, cryptographic helpers | Existing source/runtime modules and platform transport configuration. Donor's broader proxy rules would change Wuming's intentional GitHub-only Android routing; no transport-policy change is included. |
| JAR/JavaScript/Python providers | Existing dedicated JVM, guarded, JS and Python runtime modules. Do not import the older QuickJS/Chaquopy host and its environment-specific shims. |
| DLNA, remote input/control, LAN discovery | Existing `upnp-core`, renderer/server adapters and desktop discovery. Do not import another Cling stack or remote shell utility. |
| History, collections, backup, local files, settings | Existing `user-library-core`, Room and desktop stores and platform UI. Retained. |
| IJK/DKPlayer/old ExoPlayer, Crosswalk, player render views | Superseded by Wuming's current player/browser architecture; no parallel legacy engine added. |
| JianPian, Thunder and other proprietary protocol adapters | Existing private-extension ownership remains in place. No SDK or provider-specific binary enters the public repository. |
| Android widgets, layouts, navigation and visual tweaks | Platform UI remains Wuming's own. These are not portable non-GUI behavior; donor layouts are not copied. |

This integration is a bounded set of verified gaps, not wholesale TVBoxOS feature
parity. In particular it does not change cloud provider routing, introduce new
network services, or complete the separate extraction queue documented in
`shared-non-gui-refactor.md`.

## Validation

Behavior tests cover Unicode/season suggestions, bounded malformed-response handling,
offline and stale-request behavior, JSON aliases/metadata/order/alternate URLs,
preservation of Android options, audio URL admission and a local HTTP audiobook
probe/relay using dummy bytes. No player is launched by these tests.

Public validation (2026-10-26):

- `unitAcceptanceBaseline`: 8,202 JUnit cases, zero failures/errors, 14 skips;
  212 Python tests passed. Includes runtime-license and shared-library boundary checks.
- Mobile arm64 and leanback arm64 verification APKs built successfully with
  `-PwumingEphemeralSigning=true`.
- Leanback navigation/label check passed (124 effective layouts, 300 focus targets,
  26 action contracts, 159 listener checks, three label locales).
- Java retention inventory and Gradle check passed with all 94 protected files.
- Public provenance passed: 88 bundled artifacts, 567 pinned vendor files.
  The only reviewed build-input hash change is the internal `catalog-core` →
  `playback-core` dependency; no third-party dependency or artifact changed.
- No device installation, audible playback, production signing change or release
  publication was performed. These checks do not establish live provider/device parity.

The private edition consumes this implementation through its public revision lock;
its separate validation record belongs in the private repository.
