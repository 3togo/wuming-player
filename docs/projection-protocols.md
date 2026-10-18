# Video casting support

TVBox currently supports media casting from the **mobile flavor** to
DLNA/UPnP MediaRenderer devices. The Leanback flavor is a playback receiver
and does not expose casting controls. The Linux desktop Push screen now supports a separate
JVM-only DLNA sender and opt-in mpv-backed receiver; see [desktop instructions](desktop/README.md#video-casting-with-android-phones).

## Implemented behavior

- SSDP discovery through Cling.
- `SetAVTransportURI`, seek-to-resume, play, and explicit stop.
- An immutable episodic queue owned by `ProjectionManager`.
- Just-in-time resolution of each next episode so provider URLs and headers do
  not expire while the previous episode is playing.
- Cast generation guards that reject stale asynchronous callbacks.
- Bilibili DLNA compatibility playback: request a real combined progressive MP4
  before sending a local DASH proxy. Android and desktop use the same shared
  source/response policy, show the API's actual quality, and retain the existing
  route when a supported single-file MP4 is unavailable. A selected 1080p stream
  can therefore cast at 720p; the compatibility notification states that quality.
  Account credentials stay on the API request; media relays use public browser
  headers and opaque session URLs.
- Completion monitoring through typed `GetTransportInfo` polling.
- A foreground mobile notification that identifies the active device and
  episode and always provides a Disconnect action.

## Backend boundary

`ProjectionManager` prepares playable media URLs, owns queues and application
session state, and delegates discovery/transport operations to `CastBackend`.
The current `DlnaCastBackend` adapts Cling discovery and `DLNACast` AVTransport
commands. A future Google Cast implementation should be a sibling backend,
without duplicating provider resolution or relay logic.

See [docs/cast-auto-next.md](docs/cast-auto-next.md) for the lifecycle and
receiver-compatibility rules.

## Not implemented

Miracast, Google Cast/Chromecast, AirPlay, Samsung Smart View, and Roku are not
implemented. Do not advertise or add UI entries for these protocols until a
real implementation, dependency set, device test, and lifecycle design exist.

Google Cast is intentionally deferred rather than merely unfinished. See
[docs/google-cast-decision.md](docs/google-cast-decision.md) for the dependency,
device-coverage, and testing rationale.

Miracast is screen mirroring rather than URL-based media casting and would need
a separate MediaProjection/Wi-Fi Direct design. Google Cast would require the
Google Cast SDK and its own session/queue adapter. Neither should be represented
as a thin wrapper around the existing DLNA transport.

## Regression rules

- Keep Android sending controls mobile-only; desktop casting remains physically separate from Android modules.
- Keep the cast session outside `VideoActivity`; casting can outlive the screen.
- Never resolve the entire episode queue in advance.
- Never treat transport-query failure as playback completion.
- Disconnect must send `Stop`, invalidate the generation, clear media URL
  capabilities, and remove the foreground notification.
- Verify transport changes on at least one strict DLNA receiver before release.
