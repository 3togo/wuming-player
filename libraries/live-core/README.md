# Live core

`live-core` owns platform-neutral live-playlist models and parsing for standard M3U and TVBox TXT
playlists. It preserves channel groups, alternate stream URLs, EPG locations, logos, stable TVG
identifiers and HTTP playback headers.

`LivePlaylistReference` represents the named playlist choices advertised by a configuration so
platform clients can share source identity while retaining their own network and UI adapters.

Network loading, persistence, credentials, EPG downloads, UI and player integration remain platform
adapters. Android-specific DRM directives and spider-produced JSON live catalogues are not part of
the first portable contract.
