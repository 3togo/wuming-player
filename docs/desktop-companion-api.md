# Desktop companion API v1

The Android TV application remains the authoritative source/spider runtime. The physically separate
desktop client uses a small paired HTTP API rather than loading Android JARs, AAR protocols,
Chaquopy or Android native libraries.

## Pairing

1. Open the remote/push pairing screen on the Android TV. This opens a five-minute pairing window.
2. Enter the TV server address, such as `http://192.0.2.10:9978`, in the desktop **Catalogue** tab.
3. Select **Pair**. The client claims `/pair` and stores the 256-bit token in an owner-only desktop
   configuration file.
4. Subsequent `/desktop/v1/*` requests send `Authorization: Bearer <token>`.

Rotating the TV remote-access token immediately invalidates the saved desktop pairing. Loopback
access follows the existing server policy; LAN access always requires pairing.

## Endpoints

All v1 operations use `GET` and JSON, except `/pair`, which returns the token as plain text.

| Endpoint | Parameters | Result |
| --- | --- | --- |
| `/desktop/v1/sources` | none | Searchable source summaries |
| `/desktop/v1/search` | `source`, `query` | Catalogue result summaries |
| `/desktop/v1/detail` | `source`, `id` | Metadata, playback lines and episodes |
| `/desktop/v1/play` | `source`, `flag`, `id` | Resolved playback capability and URL |

Search, detail and player calls execute through the existing Android `SiteApi`, so supported CatVod
providers retain their current implementation. The API validates source keys and input lengths.
Search result and episode identifiers are random, process-local handles with a 30-minute lifetime;
raw provider identifiers never cross the companion boundary because some providers embed share or
session tokens inside those values.

## Playback security and limitations

For a supported direct HTTP(S) result, Android creates an unguessable six-hour relay URL. The relay
applies provider headers upstream and rewrites child HLS requests. Provider cookies, authorization
headers and account tokens are never serialized to desktop.

The first version explicitly refuses DRM results, structured cloud episode payloads, episodes that
still require an Android parser, and resolved values that are not valid HTTP(S) URLs. Those cases
remain Android-only until a reviewed non-interactive server-side resolution path exists. A refusal
is a normal `CatalogPlayback` response, not a fallback that leaks provider data or opens Android UI
from a background server request.
