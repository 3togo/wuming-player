# Linux desktop review — 2026-09-19

## Conclusion

The desktop client has substantial shared coverage, but **does not have complete Android feature parity**. A shared Gradle dependency is not proof that a feature has a working desktop UI or host adapter. This review checked the desktop entry points and their implementations against Android's catalogue, player, live, library, cloud and settings code. It did not run Android instrumentation or verify arbitrary remote providers.

The architecture remains Kotlin/JVM core policy with Swing, filesystem, Preferences and mpv/VLC ports. Android-specific runtime APIs must not be copied into desktop. `desktop:check` enforces the three architecture boundaries described in [desktop-sync-architecture.md](desktop-sync-architecture.md).

## Coverage and remaining work

Paths below are relative to the repository root. “Available” means an implemented desktop path, not a guarantee that every third-party provider works.

| Android capability | Shared implementation and desktop entry point | Assessment |
| --- | --- | --- |
| Configured sources, categories, filters, details, episodes, pagination | `libraries/catalog-core`, `source-core`, `maccms-core`; `desktop/.../ui/CatalogPanel.kt`, `ConfigurationPanel.kt` | Available. Provider page aggregation supports the user's thumbnail count; folder/action/index cards have distinct behavior. |
| Search across providers, keyword history, recommendations | `search-core`, `user-library-core/recommend`; `DesktopSearchSupport.kt`, `CatalogPanel.kt` | Available. Search UI and preferences are desktop ports. Watch-time collection still needs refinement for pauses and players without state feedback. |
| History, VOD favorites, episode selection, automatic next episode | `user-library-core` (`EpisodeSelectionPolicy`, `EpisodeAdvancePolicy`, `HistoryPolicy`); `DesktopUserLibrary.kt` | Available. This review fixes resume leaking between episodes and losing the saved position on relaunch. History deletion/incognito are still missing from the desktop UI. |
| Playback choices | `DesktopPlaybackSettings.kt`, `PlaybackPreferencesPanel.kt`, `ExternalPlayerAdapter.kt` | Previously stored settings lacked a UI. Settings → Playback now exposes first/next autoplay, resume, speed and mpv fullscreen. Speed/fullscreen are applied to launch and reused mpv sessions. |
| Playback transport and subtitles | `playback-core`, `media-core`; `ExternalPlayerAdapter.kt`, `CatalogPanel.kt` | mpv supports pause, seek, speed, local subtitles and session transport. VLC has a narrower control contract. This review fixes incremental IPC events erasing other state, and late IPC connections missing subscriptions. Resume seeks after playback starts rather than after an arbitrary delay. |
| Live playlists, groups, routes, fallback, programme guide | `live-core`, `epg-core`; `LiveTvPanel.kt`, `DesktopLivePlaylistLoader.kt`, `DesktopProgrammeGuide.kt` | Available. Media preparation now runs off the Swing thread and is cancelled if selection/configuration changes or the panel closes. Live favorites, last-channel restoration and catch-up remain useful missing features. |
| Cloud login and share playback | `cloud-core`, `runtime-core`; `CloudAccountsPanel.kt`, `DesktopCloudPlaybackResolver.kt` | Login/account management, share resolution and managed-copy operations are implemented. The full Android cloud file browser is not ported. No provider quota, token or cleanup contract was changed by this review. |
| Casting and open URL/file | `media-core` and desktop UPnP/HTTP/player ports; `CastingPanel.kt`, `cast/` | DLNA sending/receiving and opening media are implemented. Screen mirroring/Chromecast are not interchangeable with DLNA. No device interoperability claim is made by offline tests. |
| Images, configuration/catalogue/resource caches | `cache-core`; `CatalogThumbnailCache.kt`, `desktop/.../cache/` | Available, including bounded thumbnail loading and retry. Cache clearing preserves accounts, favorites and trust. Desktop card image dimensions now match the more compact grid. |
| JS and JVM providers | `runtime-core`, `runtime-core`, `runtime-core`; desktop runtime adapters and trust UI | Available for supported runtimes and verified artifacts. Changed artifacts require review; Android-only WebView/GMS/DEX/Python behavior is not automatically portable. |
| Android player-specific controls | Android `bean/History.java`, player/controller and video activity ports | Per-title opening/ending skip, aspect-ratio UI, embedded danmaku/track controls and Android decoder/DRM choices are not equivalent desktop features. Some mpv controls exist in mpv itself, but that is not an app-level port. |
| Application lifecycle and updates | Android update/install, permissions, wake locks, services | Android APIs are platform-specific. Linux needs its own packaging/update and lifecycle behavior; adding Android dependencies is not an abstraction. |

## Changes made in this review

- Flat dark controls, readable typography, visible keyboard focus, horizontal navigation labels, a shorter header, compact poster cards and a wider source selector.
- Browse, detail and running-player controls now follow the current context. Source diagnostics are behind “Source tools”; empty/irrelevant page controls no longer dominate the screen. Detail pages hide browse/search controls.
- Settings separates Sources from Playback; source URL and diagnostic actions use separate rows.
- Shared `PlaybackState` is a complete snapshot. The mpv port merges individual property changes, subscribes when IPC connects and retains callbacks registered before connection.
- Shared episode resume policy requires matching line and episode identifiers. Relaunching the same episode preserves its saved position. Position persistence uses the existing shared synchronization interval and flushes on session finish.
- Live TV media preparation no longer runs on the UI thread. Stale preparation cannot launch a player after cancellation.
- The existing F11 maximize/restore behavior is retained in the redesigned window; its help text describes maximization accurately.

## Validation

Regression coverage includes persisted playback preferences, the real catalogue layout at 960×620 and 1280×800, browse/detail action visibility, navigation routing, episode/line resume isolation, accumulated mpv state, invalid IPC values, launch preferences, Live TV UI responsiveness and cancellation. The existing catalogue paging, image/cache, trust, cloud and cast tests remain part of the full desktop suite.

Validation result: 445 tests passed (249 desktop, 73 catalogue-core, 98 playback-core,
25 user-library-core); five explicitly opt-in desktop hardware tests were skipped.
The architecture checks, distribution build, desktop smoke check and Leanback static
validation passed. Browse layouts at both sizes, the detail layout and Playback
Settings were rendered and visually inspected.

Run the offline validation with:

```sh
TVBOX_DESKTOP_RENDER_DIR=/tmp/tv-desktop-review ./gradlew :desktop:check :desktop:installDist :libraries:catalog-core:test :libraries:playback-core:test :libraries:user-library-core:test
desktop/build/install/tvbox-desktop/bin/tvbox-desktop --smoke
python3 scripts/check-leanback-remote.py
```

The layout tests can emit PNGs to the directory above. They render actual Swing panels with a deterministic offline catalogue, not a browser mockup. Hardware tests require explicit opt-in; no device serial was supplied during this review.

## Follow-up order

1. Live favorites and last-channel persistence; history deletion and incognito. These are portable user-library features and should use shared policy plus platform persistence ports.
2. Per-title opening/ending skip and playback presentation choices, with tests for episode changes, resume and natural completion.
3. Accurate recommendation watch-time accounting during pause/buffering and for players without feedback; cancellable live preparation is now in place, but live startup has less detailed feedback than VOD.
4. Catch-up playback and a complete cloud file browser, respecting the existing provider-specific playback/cleanup lessons.
5. Runtime-specific features only after defining a supported Linux host contract. Do not enable speculative episode prewarming: resolution can have cloud-save side effects.

These gaps are intentional findings, not claims that the corresponding Android features are useless or already abstracted.
