# Casting implementation summary

The current implementation provides mobile-to-TV DLNA casting.

Core components:

- `CastBackend` is the protocol boundary for discovery and remote transport.
- `DlnaCastBackend` adapts that boundary to the existing Cling implementation.
- `ProjectionManager` owns connection state, the episode queue, automatic
  advancement, media URL/relay preparation, and observable status. It does not
  depend directly on Cling transport classes.
- `DLNACastManager` owns Cling service binding, SSDP discovery, and renderer
  lookup.
- `DLNACast` owns AVTransport commands and completion monitoring.
- `CastDialogV2` selects a renderer and optional audio-track metadata.
- `CastSessionService` keeps an active mobile session visible and stoppable
  after the video activity leaves the foreground.

Automatic next-episode playback resolves provider episode identifiers only
when needed and rejects callbacks from old cast generations. Explicit
disconnect stops the renderer and clears the queue.

Miracast and Google Cast are not implemented. Earlier design notes that called
those protocols complete were inaccurate and have been removed.

Google Cast adoption is intentionally deferred; it must not be added to the
current universal build. The compatibility findings and reconsideration
criteria are recorded in
[docs/google-cast-decision.md](docs/google-cast-decision.md).

Additional URL-based protocols such as Google Cast should implement
`CastBackend`; provider resolution and `CastMediaRelay` remain shared above the
backend boundary. Miracast is screen mirroring and does not fit this contract.

Detailed lifecycle rules and the verified Skyworth behavior are documented in
[docs/cast-auto-next.md](docs/cast-auto-next.md).
