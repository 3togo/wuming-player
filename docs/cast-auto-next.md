# Cast auto-next design

This feature is initiated by the mobile video UI. The Leanback build is not a
casting controller and must not create or hand off an episode queue.

Local playback and DLNA casting have different lifecycles. Local playback can
ask the visible episode adapter for the next item, but a cast can outlive its
activity. Consequently, cast auto-next must be owned by `ProjectionManager`,
not by `VideoActivity` or an individual `DLNACast`.

## Regression guardrails

- Pass an immutable snapshot of the complete current episode list and its
  current index to `ProjectionManager`.
- Keep provider episode IDs in the queue. Resolve the next media URL just
  before casting it because URLs, cookies, and headers may expire.
- Never store only one `nextUrl` in the active media object. Doing that limits
  auto-next to a single transition.
- `DLNACast` owns transport commands and completion monitoring only. It reports
  completion to `ProjectionManager`; it must not resolve or select episodes.
- Only accept repeated `STOPPED`/`NO_MEDIA_PRESENT` as completion after
  `PLAYING` has been observed for that media.
- Transport query failures are not playback completion. A receiver going
  offline must never cause the next episode to start.
- Use Cling's typed `GetTransportInfo` callback. Do not manually construct this
  action with a Java integer: Skyworth receivers require the UPnP `ui4`
  `InstanceID=0` value and otherwise return HTTP 500.
- Guard every asynchronous transition with a cast generation. Results from an
  old or disconnected cast session must be discarded.
- An explicit disconnect must send AVTransport `Stop` before releasing the
  phone-side `DLNACast`. Releasing only stops local polling and leaves the TV
  playing, which can also make a queued auto-next appear impossible to cancel.
- Use the newly resolved episode's headers. Do not copy headers or duration
  from the previous episode.
- Keep remote playback state separate from the local episode adapter. The
  mobile video screen observes `ProjectionManager.CastSessionState` and shows a
  cast status chip; it must not activate an episode as though the phone were
  playing it.
- Publish `PLAYING` only from the DLNA `Play success` callback. Resolution and
  transport setup are shown as pending, and failed setup is shown as an error,
  so the UI never claims that an unconfirmed episode is playing on the TV.
- The mobile cast-status chip is also the persistent stop control. Its label
  must include `Disconnect`; tapping it invalidates the cast generation, sends
  AVTransport `Stop`, clears the queue, and hides the chip.
- An active mobile cast also owns a foreground notification showing the
  receiver and episode. Its Disconnect action must call the same
  `ProjectionManager.disconnect()` path as the status chip. Leanback must not
  declare or start this service.
- Repeated transport-query failures mark the visible session as an error but
  never advance the queue. The notification remains available so the user can
  explicitly stop a receiver whose status can no longer be queried.

DLNA behavior varies by receiver, so polling remains a compatibility fallback.
An AVTransport event subscription can be added later without moving episode
selection back into the transport class.

## Verified Skyworth transition

On a Skyworth P3D renderer, the fix was verified with the following log
sequence:

```text
TransportState: PLAYING
TransportState: STOPPED
TransportState: STOPPED
TransportState: STOPPED
Playback ended after 3 consecutive STOPPED
SetAVTransportURI success for 02
Play success
TransportState: PLAYING
```

If auto-next stops working, first confirm that typed `GetTransportInfo` calls
still return `PLAYING`. Repeated HTTP 500 responses mentioning `InstanceID` or
message-payload transformation indicate that the action was manually
constructed or supplied with the wrong UPnP argument type.
