# Remote access security

TVBox's private HTTP control API is separate from standard DLNA. UPnP
discovery, AVTransport, and renderer media fetching must remain interoperable
with ordinary televisions and must never require a TVBox credential.

## Flavor policy

- Leanback exposes the remote web interface. Displaying its QR screen opens a
  five-minute pairing window. A browser or mobile scanner can claim the TV's
  persistent 256-bit credential only while that screen is present.
- Mobile stores a TV credential after scanning the TV's QR and claiming it
  during the pairing window. TVBox
  cast and synchronization requests then send the credential in the
  `Authorization` header.
- Long-pressing the pairing QR on the TV rotates its credential and immediately
  revokes every previously paired controller. Controllers must scan the new QR
  before their next protected request.
- Mobile rejects remote administration. Localhost callers remain available for
  parser and playback internals.
- Media served by mobile for a local DLNA cast uses an independent 192-bit,
  six-hour URL capability scoped to exactly one active resource. Disconnecting
  invalidates it immediately.

## Protected routes

The authorization boundary covers `/action`, `/cache`, `/media`, `/file`,
`/upload`, `/newFolder`, `/delFolder`, and `/delFile`.

`/device` remains public for LAN discovery. Playback delivery endpoints such as
`/proxy` and `/m3u8` remain compatible because a renderer may not be
able to attach custom HTTP headers.

## Regression guardrails

- Keep packaged web-asset streams open until NanoHTTPD sends the response, and
  provide their actual length. Closing an asset in `getAssets` or passing `-1`
  produces a truncated chunked response: the QR URL answers HTTP 200 but the
  browser receives no usable page.
- Never put the persistent TV credential in a URL. It is claimed from `/pair`
  only during a user-visible pairing window and must be sent as an
  `Authorization: Bearer` header afterward.
- Never include server or paired-device credentials in application backups.
- Do not authorize a request by source IP or device name.
- A `/cast/{token}/...` request must resolve only when the complete path matches
  the currently authorized resource; it is not a prefix granting directory
  access.
- App-to-app cast and sync clients must handle HTTP 401 as "pairing required,"
  not as success or a generic playback failure.

The former `/tvbus` authentication-response endpoint was removed with its SDK.
