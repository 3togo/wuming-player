# Keep supported cloud playback on its selected route

Supported cloud episodes could fall back to a CatVod provider when the built-in
account needed login. The resulting provider QR prompt made an account failure
look like a different playback workflow. Wrapped cloud failures could also select
another manifest source, retry saved raw provider data, or trigger player/parser
fallback.

## Changes and affected contracts

Routing now recognizes available native providers without consulting account
authentication. Authentication remains required when resolving playback. A deferred
episode keeps its provider identity while login is needed, and can resolve using
the same handle after login.

Typed OpenDrive failures survive resolver and player exception wrappers. They
disarm saved-history fallback and stop automatic parser/source switching. Ordinary
stale catalogue fallback and unrelated broken manifest-source recovery remain
available. One selected-route value is used throughout each SiteApi request.

Coverage includes the six existing native cloud adapters, saved history, the
Android resolver, URL-refresh errors, and player error reporting. Recognition
does not claim unrelated numeric source episode IDs as cloud links. No provider
name-specific AI剧 rule was introduced, and Quark smart-quality behavior was
preserved.

## AI剧 and the TVBoxOSC reference

The observed AI剧 QR prompt belongs to the CatVod provider's playback gate.
Successful playback returns the provider's local proxy URL rather than a cloud
share locator. Its provider proxy delegates to `HgCencProxy`. This is distinct
from a supported cloud episode falling through to CatVod.

Reviewed TVBoxOSC source at revision
`793e60e6b4f1fb607f728909e8c410eccfdeb736`:

- `engine/core/src/main/java/com/kukuqi/tvbox/engine/api/loader/JarLoader.java`
- `engine/core/src/main/java/com/kukuqi/tvbox/engine/server/process/Proxy.java`
- `engine/core/src/main/java/com/kukuqi/tvbox/engine/player/media/MediaItemFactory.java`
- `engine/core/src/main/java/com/kukuqi/tvbox/engine/bean/Drm.java`
- `engine/core/src/main/java/com/kukuqi/tvbox/engine/bean/ClearKey.java`

The host invokes the provider proxy and returns its response stream and headers.
The player separately accepts standard DRM configuration. Wuming already has
these two host paths. The reviewed reference does not provide an AI剧-specific
CENC integration. No reference implementation or provider code was copied.

The captured provider JAR contains a Java CENC proxy and metadata-unpacking
helper. That revised the initial assumption that a separate license service was
required. The built-in resolver now fetches video metadata independently and
prepares a process-local playback capability. Android consumes the shared,
range-aware MP4 transformation through its DataSource port; desktop mpv consumes
the same resolved metadata using its CENC support. Ordinary provider catalogues
remain unchanged. Only the recognized AiDj episode protocol selects this new
resolver; arbitrary numeric IDs from unrelated sources are not claimed.

The shared transformer handles the tested unfragmented AES-CTR layout, including
whole samples, subsamples and arbitrary range boundaries. It preserves file
length and sample offsets for seeking, bounds metadata/sample/session storage,
and rejects unsupported layouts. Keys and signed media locators remain in memory.
The implementation does not copy provider classes or add the captured JAR to the
repository. Native resolution errors do not call the vendor login gate.

The original media URL scheme must be preserved. Forcing HTTPS worked in the
remote probe but timed out on the TV. Returning the service-provided HTTP media
URL made the same native TV decoder test pass. Metadata requests remain HTTPS.
Android's existing mobile/roaming video restrictions apply to the new source.

## Validation and limits

Source base: public `d7f59f2f8a20fd1b89735d642e431e4e056f7ea8`.
The feature commit containing this report identifies the candidate source; private
consumption and its exact pins are recorded in the private validation report.

On Ubuntu 26.10 (Stonking Stingray), x86-64, OpenJDK 25.0.5.1:

- Focused cloud routing, history, wrapped-error, resolver and player tests passed.
- A removed-fix control restored the old OpenDriveSpider implementation: the
  unauthenticated-route regression executed and failed as expected. The final
  implementation was restored before acceptance.
- `unitAcceptanceBaseline` and ARM32 leanback verification APK assembly passed
  with ephemeral test signing. JUnit reports contain 8,788 cases, 18 skipped,
  zero failures/errors; 227 Python tests and the 11- and 4-case artifact checks
  passed.
- Skips cover optional real-provider/device, display/mpv, network integration,
  and downloaded-media fixtures. They do not establish those live behaviors.

A separate public testing app decoded the live native episode, advanced more
than three seconds, then resumed beyond 16 seconds after seeking to 15 seconds.
That opt-in test ran without loading the provider JAR or using cloud credentials.
An independent transformed-range probe also decoded two seconds with FFmpeg,
with no decoder errors. Subsequent source hardening and production-route smoke
coverage are part of private-edition validation, not implied by the first test.

This establishes one live episode and the shared offline contracts. It does not
establish every third-party source, every rendition, casting, offline downloads,
or native danmaku support. Desktop launch wiring is covered offline; a live
window/codec check is distinct. These are bounded first-party playback changes,
not a claim that every provider-owned QR gate has been removed.

## Lessons

Separate route recognition from account readiness; report the account failure on
the selected route. Follow an error through history recovery, manifest selection,
refresh and UI handling rather than checking only its producer. Distinguish a
provider's own proxy and QR gate from cloud account routing. CENC support in a
host player alone does not establish that a source supplies a compatible license
or playback integration.
