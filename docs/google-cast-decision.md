# Google Cast adoption decision

Status: **Deferred**
Decision date: 2026-08-31

## Decision

Do not implement Google Cast for the time being. Continue to use DLNA as the
shipping mobile-to-TV casting protocol and keep Google Cast as a possible future
`CastBackend` implementation.

This is a product and compatibility decision, not an architectural limitation.
The casting code has a protocol-neutral `CastBackend` boundary, so Google Cast
can be added later without duplicating provider resolution, media relay, queue,
or application session logic.

## Reasons

- The existing DLNA path works without Google Play Services and supports
  non-GMS Android devices.
- The Google Cast Android Sender SDK is distributed through Google Play
  Services. Adding it to the universal build would introduce a runtime
  dependency unavailable or unreliable on some target phones, particularly in
  mainland China.
- Google Cast only benefits genuine Cast-enabled receivers. A device exposing
  DIAL, an `Eureka` identity, or Chromecast-like branding is not necessarily a
  certified or compatible Google Cast receiver.
- The tested HP HD03 dongle has a limited DLNA renderer and DIAL endpoint, but
  there is no evidence that it implements a usable Google Cast receiver. Adding
  the sender SDK is therefore not a justified fix for its HLS limitation.
- Google Cast does not remove the need for provider URL resolution, cookies,
  headers, LAN relay URLs, HLS rewriting, or receiver-format compatibility.
- Maintaining one reliable shipping protocol is preferable to exposing a
  second, lightly tested path with different session and UX semantics.

## Current direction

- Retain Cling and `DlnaCastBackend` for discovery and AVTransport commands.
- Continue improving the shared resolver and `CastMediaRelay` only in response
  to observed source or receiver requirements.
- Keep protocol behavior behind `CastBackend`; do not add Google Cast concepts
  to `ProjectionManager` or the media-source pipeline.
- Do not require users or developers to install Google Play Services, Google
  Home, or an additional receiver application for the current DLNA feature.

## Reconsider when

Re-evaluate Google Cast only when at least one of these conditions is met:

- Users demonstrate meaningful demand from certified Chromecast, Google TV, or
  Cast-enabled TV hardware.
- Representative GMS phones and at least two certified receiver models are
  available for repeatable integration testing.
- A separate GMS-enabled product flavor can be shipped without imposing Google
  Play Services on non-GMS builds.
- Expected receiver coverage clearly outweighs the additional dependency,
  binary size, testing matrix, Cast UX requirements, and maintenance cost.

If reconsidered, prefer AndroidX Media3 `CastPlayer` and the official Google
Cast framework. Add it as a sibling of `DlnaCastBackend`, with runtime Google
Play Services checks and no regression to DLNA-only builds.
