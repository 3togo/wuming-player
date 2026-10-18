# Private desktop LAN relay

The Linux desktop can fetch live HLS streams for paired Android clients on the
same LAN. This reuses the desktop's upstream connections and network route.
The service is off by default and does not publish or synchronize channel lists.
Clients supply the URL of the channel they select; playlists and pairing tokens
are not advertised during discovery.

## Use

1. Desktop: **Settings → Playback assistance → Enable playback assistance**.
2. Desktop: **Pair a device** displays a six-digit code, valid for two minutes
   and one successful pairing. Five incorrect attempts close that pairing window.
3. Android: **Settings → Desktop relay → Find desktop**, select the desktop,
   and enter the code. Pairing enables desktop assistance on that client.
4. Select an ordinary live HLS channel. Android starts directly. If playback remains
   buffering for eight continuous seconds while playback is requested, it discovers
   the paired desktop and asks it to prepare the same upstream URL and headers.
   The direct attempt continues while preparation runs in the background.
5. Desktop preparation has a seven-second HTTP deadline and reads at most 1 MiB
   of playlist data. The Android request has an eleven-second overall budget,
   including discovery and executor waiting. The freshly prepared playlist is reused
   once for up to two seconds, avoiding a second upstream fetch at handoff.
6. Android switches only if that same playback selection is still buffering.
   Recovery, pause, channel replacement, or teardown cancels preparation and discards
   any late result. Abandoned/completed sessions can be released with an authenticated
   `DELETE /relay/v1/session/{id}`; only the creating client and peer can release them.
7. If the desktop route buffers continuously for twelve seconds, fails terminally,
   or misses startup, Android restores the original URL, headers and format.
   Recoverable live-window seeks and format recovery keep the relay. There is at most
   one desktop trial per playback selection, and failed desktop requests/routes impose
   a cooldown before another channel can try. A brief rebuffer or user pause is not
   evidence of slow connectivity.

Pairing enables assistance; it does not send healthy channels through the desktop.
This detects playback starvation rather than measuring raw link bandwidth. It can
help when the desktop has a better route to the source; it cannot improve a source
that is equally slow from both devices or a LAN that cannot carry the selected stream.
VOD, parsed sources and DRM retain their existing routes. Purified live playlists
use the original remote channel URL when requesting assistance.

Both mobile and leanback settings expose pairing, enable/disable and forget.
Desktop **Forget paired devices** revokes all pairings and active stream sessions.
The desktop must stay awake. Its tray icon appears at startup and survives closing
the main window, whether assistance is enabled or disabled. Double-click the icon
or choose **Show 無名** to reopen the main window. If the desktop environment has
no supported tray, an enabled relay keeps the main window minimized; with the relay
disabled, window-close exits normally. Use **Exit 無名 and stop assistance** in
settings or the tray for a full exit, including the service and monitor windows.

Choose **Playback assistance monitor** from the tray or settings to open an
independent window. It shows pending preparations, paired-device/session counts,
each device's LAN address, transfer status, delivered bytes, delivery speed,
request/error counts and last activity. Counters cover this app run; ended sessions
leave the table. The monitor reports desktop transfers, rather than claiming to
measure Android decoder state or buffering. It never displays stream URLs,
upstream headers or pairing/session tokens. Closing this monitor stops its refresh
timer and leaves the tray and assistance service running.
Enabled state and pairing survive desktop restarts; discovery locates its current
IP address and HTTP port.

A headless desktop installation can also run `tvbox-desktop --lan-relay --pair`.
Omit `--pair` to serve already-paired devices without opening a pairing window.
Stopping that process stops its relay.

## Implementation

`libraries/playback-core` owns the protocol, pairing gate, discovery, header
scope policy and bounded fallback memory. Desktop and Android provide HTTP,
persistence and UI ports. Android currently uses the relay only for live HTTP(S)
HLS playback; DRM streams retain their existing playback path.

Discovery uses UDP broadcast port **39282** and replies by unicast. HTTP binds to
one private LAN IPv4 address on a dynamically chosen port. Paired discovery
responses carry a fresh nonce proof so clients verify the saved desktop identity
before using the advertised endpoint. Guest Wi-Fi isolation or blocked broadcast
can prevent discovery; direct playback remains available.

Pairing uses a private app preferences file on Android and an owner-readable
`~/.config/tvbox-desktop/lan-relay.properties` file on desktop. This LAN protocol
uses HTTP, intended for the user's trusted local network. Stream URLs contain
unguessable, peer-address-bound session identifiers and expire after ten minutes
of inactivity. The server accepts paired session requests and public HTTP(S)
upstreams, rejects private upstream targets and redirects, and does not forward
cookies or authorization headers to another upstream origin. Resource URLs,
sessions and request/playlist bodies have bounded sizes.
Upstream response cookies are retained in memory for one stream session and one
exact origin, including its scheme and port. They are discarded with the session.

Focused checks cover pairing expiry/attempt limits/revocation, discovery proofs,
credential scope, original URL restoration, chunked HLS rewriting (including
keys), binary streaming and byte ranges. The opt-in tablet smoke probe can use a
private pairing fixture to exercise actual discovery, pairing, routing, fallback
restoration and decoded playback without recording channel URLs in its output.

## Verified tablet result

On 2026-10-07, the authorized tablet discovered and paired with the integrated
desktop relay, restored the original URL through the fallback path, and decoded
a private 1080p H264 channel. Startup was 7.4 seconds; the following 30-second
observation recorded 28.2 seconds of progress, 786 frames, zero rebuffers and zero
load errors. HTTP/2 through the desktop proxy stalled in the comparison, so relay
upstreams use HTTP/1.1 with connection reuse. The normal app was also paired
through its settings UI and observed using the Linux GUI service after a service
restart changed its HTTP port. The resource-retention integration test rotates
more than 2,000 segment URLs and verifies that the entry playlist and active key
remain accessible.

A second tablet regression smoke deliberately injected a behind-live-window error
after video started. The player retained its relay URL, recovered, and rendered
763 frames with 28.2 seconds of progress in the observation period, no load errors
and no measured stalls. Its single rebuffer corresponds to the injected seek.

The later 60-second observation did not pass sustained-playback checks: the
upstream delivered too slowly and the player buffered. A comparison of the same
live playlist through both implementations also varied widely (integrated relay
41–522 KiB/s; temporary Python relay 63–93 KiB/s). That observation predates the
assistance-on-buffering revision. The desktop route is now requested after sustained
Android buffering; it cannot guarantee sufficient upstream bandwidth.
Cookie retention, origin isolation and explicit/default port equivalence are
covered by offline regression checks.

## Automatic-assistance smoke (2026-10-09)

The authorized ARM64 tablet `AUTHORIZED_TABLET_SERIAL` passed the new
`DesktopRelayAssistanceSmokeInstrumentedTest` against the Linux desktop distribution
on this PC. The test deliberately stalls only Android's direct fetch of a public
HLS sample and opts that fixture into the live-assistance path. It uses the real
PlayerManager, Media3 decoder, LAN discovery, pairing and desktop HTTP relay.
This is a controlled functional test, not a live-provider throughput benchmark.

Android remained direct during the first seven seconds, handed off at 10.907 seconds,
and decoded 848×480 video. The 30-second observation advanced 30.439 seconds and
rendered 1,829 additional frames. An injected terminal error restored the original
direct URL; the relay record was consumed and the desktop media URL subsequently
returned 401, confirming session release. The single smoke method passed in 45.655
seconds. Sustained relay-buffering fallback is covered by policy unit tests; this
device smoke exercises terminal-error fallback.

The first device attempt failed during pairing with connection refused. This PC has
two LAN interfaces: wildcard UDP discovery replies used a different source address
from the HTTP binding. Desktop discovery now sends replies from the HTTP-bound
address, with a regression test that verifies the advertised endpoint accepts HTTP.
The corrected device run passed; the initial failure remains part of the test record.

All 21 selected Android lifecycle/player instrumentation methods passed in one run,
including filter restoration and both control-dialog lifecycle regressions. Local
Android JVM, playback-core and full desktop unit suites passed, as did the Linux
`--smoke` check and library boundary checks. Temporary pairing fixtures were removed,
the isolated desktop relay stopped, and device power/lock settings restored.

The subsequent native Linux tray smoke on this PC verified registration at app
startup, main-window hide/reopen, independent monitor close/reopen, and an HTTP-
reachable relay while the main window was hidden. Explicit tray Exit removed the
icon, disposed both windows and stopped the relay. Unit regressions additionally
cover missing/failed tray registration, stale actions after exit, monitor timer
teardown, transfer accounting and closing while service startup is queued.
