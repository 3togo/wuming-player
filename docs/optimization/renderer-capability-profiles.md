> Correction from direct PC testing (2026-09-28): this Coocaa TV **does** expose
> ConnectionManager and GetProtocolInfo. Android's AVTransport-only service
> filter hid them. Earlier no-format-list findings below describe the filtered
> Android registry, not the actual TV. See `scripts/cast-profile/pc_dlna_probe.py`
> and `app/build/reports/pc-dlna/identify/report.json` for the direct evidence.

# Internal DLNA renderer capability profiles

The sender learns about the selected DLNA renderer, not the TVBox application.
It never launches another player, installs software, or starts test media on its
own. Use the TV's internal DLNA entry when testing that receiver.

## User controls

In the casting device list, **Saved TV profiles** opens previously identified
renderers even when they are offline or their DLNA service has crashed. Each live
DLNA entry also has a **Capabilities** button (available on long press too). Select a capability to set a manual Supported,
Unsupported, or Unknown override, or choose **Use learned result** to remove it.
The phone and TV dialog buttons support normal OK/Back navigation.

**Copy report** copies a JSON report for review. It excludes the device name,
unique device ID, session IDs, media URLs, tokens and credentials. It still
contains manufacturer/model, reported service types, timestamps, app versions,
observations and manual decisions; sharing is always user-initiated.
**Reset profile** removes observations and overrides across all stored revisions
for that device. Later playback may teach it again. Reset does not delete the
legacy projection preferences or repair a malformed configuration file.

## Config files

- Android: private app file `files/renderer-capabilities.json`, written on a
  bounded background worker using Android AtomicFile.
- Linux: `$XDG_CONFIG_HOME/tvbox-desktop/renderer-capabilities.json`, or
  `~/.config/tvbox-desktop/renderer-capabilities.json`, atomically replaced with
  owner-only permissions. `DesktopRendererProfiles.default` is the shared port;
  desktop discovery records identity and outgoing control failures record evidence.

Schema version 1 contains profiles with `identity`, `evidence`, `overrides` and
computed `assessments` (state, evidence confidence, independent session count and
last observation time). Assessments are recomputed from evidence when loaded;
edit overrides, not computed assessments. Writes commit memory only after storage
succeeds. Malformed and newer-schema files are preserved, not overwritten. On
Android a failed initial load disables recording until restart, and the UI reports
unavailable. Each file holds at most 128 renderer revisions, each with 128 recent
observations. Full capacity rejects new profiles; it never silently deletes a TV.

Identity uses a hash of the DLNA UDN, plus manufacturer, model, known firmware and
reported services. Friendly names and IP addresses are not keys. A rename keeps
knowledge; a different UDN/model/firmware/services revision starts separately.
DLNA model numbers are NOT treated as firmware versions. Firmware remains
`unknown` when not actually available, so an undetected firmware update cannot
be automatically distinguished. Reset after such an update or contradictory
results. No hardware capability claims are bundled for the currently investigated
Coocaa TV without controlled evidence.

## Evidence policy

- Supported / Unsupported / Unknown are distinct. A manual override takes priority.
- Two independent sessions with at least three advancing position samples and
  six seconds of progress can mark an identified format supported. This is lower
  confidence **observed playhead progress**, not proof of correct picture/sound.
- A controlled successful test can establish support; two independent controlled
  failures can establish lack of support. Android's `recordControlled` API accepts
  explicit test results with independent run IDs; normal failures never use it.
- Conflicting positive and negative evidence returns Unknown. Repeated callbacks
  from one session are deduplicated. A stalled playhead, Play acknowledgement,
  UPnP 501, HTTP failure or missing response does not prove format incompatibility.
- Seeking resets progress accumulation. Aggregated HLS output never counts as
  evidence of native HLS support; its actual output may be TS or MP4.
- Codecs, byte ranges, chunking, seek/stop/recast reliability remain Unknown until
  specifically tested or manually overridden. No codec is inferred from a URL.
- Observations carry timestamps and app versions; no automatic expiry is imposed.
  The bounded evidence history can eventually age out older evidence.

The former name-based HLS blacklist is intentionally ignored: its evidence was
only a Play acknowledgement after fallback. A profile's **Unsupported HLS** state
now selects the existing aggregated relay route; other states keep normal routing.
Other capability flags are recorded and displayed but do not yet select new
transcoding or DASH conversion paths. This module does not itself fix DASH playback.

## Incorporating learned experience

Policy, evidence accumulation, JSON validation and reviewed rules live in
`libraries/playback-core` under `com.wuming.tv.core.playback.renderer`.
Platform ports supply storage, device metadata and callbacks. The Linux adapter
currently has no playhead polling, so it does not automatically learn successful
format support from a Play acknowledgement.

Export reports, reproduce against known-good media, remove identifying information,
and review before adding an exact manufacturer/model/firmware rule to
`libraries/playback-core/src/main/resources/renderer-capability-rules.json`.
Include an `evidenceReference` to the reviewed test report. The catalog currently
contains no speculative rules. Known exact firmware is required for matching;
local evidence/overrides take precedence. There is no upload or automatic
crowdsourced rule publication.


## Read TV declarations when opening a profile

Opening a live receiver profile now asks that receiver's ConnectionManager for
`GetProtocolInfo` (`Sink`) and reads its AVTransport action descriptions. The
shared parser recognizes explicit HTTP MIME types and DLNA codec profile names.
The desktop sender queries the selected receiver through the same policy.
Discovery does not query every TV on the network or start media playback.

Declarations are saved atomically as `INCONCLUSIVE` evidence with reason
`dlna_advertised`; exported assessments include `advertised`. The UI displays
**Advertised by TV**, separate from Supported/Unsupported playback results.
Wildcards, absent MIME entries, and query failures never imply unsupported
formats. MP4 alone does not imply H.264/AAC; Seek/Stop declarations do not prove
successful operation. HTTP ranges, chunked transfer, and recasting remain
unknown until there is appropriate evidence. A successful refresh replaces old
declarations; a failed query retains saved evidence and displays the failure.
Offline saved profiles remain readable. Queries have a ten-second UI deadline.

Phone opt-in smoke test: `RendererCapabilityQueryInstrumentedTest`, with the
`rendererName` instrumentation argument matching the explicitly authorized TV's
friendly name. It performs discovery and a read-only protocol query, then
checks that advertised capabilities reached the saved profile. It never starts
TV playback. Devices without a format list cannot pass this positive smoke test.

### Controlled playback samples

The profile now includes **Test sample playback…**. Choose MP4/H.264, HLS,
DASH, or MP4/HEVC. The phone serves a bundled 15-second synthetic moving
pattern and AAC tone at a temporary random URL. Starting a test explicitly
replaces the selected TV's current playback. Dismissal or the 45-second deadline
stops the probe and its local server. No application is installed on the TV.

A user confirmation of both picture and sound records controlled success for
that sample's format and codecs. Confirmation is rejected if no media request
arrived. Failed/inconclusive results are saved but never automatically ban a
format. This tests the low-resolution sample's exact combination, not all
resolutions, codec profiles, DRM, or provider URLs. Seek, ranges, chunked transfer,
and recast need separate evidence; the sample does not claim to verify them.

The first phone query smoke test failed its expected-format-list assertion:
Android had filtered the registry to AVTransport only. Direct PC testing then
proved the TV exposes ConnectionManager and GetProtocolInfo. Android now loads
AVTransport, ConnectionManager, and RenderingControl; the device regression
expects a successful format query and preserved MP4/H.264/AAC confirmation. A media
fetch smoke test is distinct from the user's visual/audio confirmation.

### Android cross-process writes

The UI and `:provider` process share the configuration file. Atomic replacement
alone does not serialize read/modify/write: a process with an old in-memory
registry can overwrite a newer confirmed result. Every Android profile operation
now acquires `renderer-capabilities.lock`, reloads the latest JSON, and performs
its operation before releasing the lock. The lock uses a separate stable file
because AtomicFile replaces the JSON inode. Corrupt configurations still fail
closed. The storage regression test primes the app's cached registry, writes a
success through an independent repository, then checks that a subsequent app
update retains that success.


### Repair of filtered Android discovery

The mobile `DLNACastService` now includes ConnectionManager and RenderingControl
in `getExclusiveServiceTypes()`. This restores GetProtocolInfo discovery instead
of mistaking a client-side filter for a receiver limitation.

A targeted core migration repairs old AVTransport-only profiles when the same
UDN, manufacturer, model and firmware are rediscovered with exactly these three
standard version-1 services. Confirmed observations and manual overrides survive;
old declaration-only evidence is discarded and queried again. Existing newer
overrides win. Other identity/service changes remain isolated. Open dialogs resolve
to the current live identity so their callbacks cannot recreate the filtered profile.

### Receiver-denied queries (2026-09-28)

A second, explicitly selected Skyworth `7AA49_P3D` renderer exposed all three
standard services and `GetProtocolInfo`, but returned HTTP 403 with an empty body
to a direct read-only query. The phone reproduced the query failure. This is
separate from the earlier filtered-service issue and from the other renderer's
successful playback evidence; profiles must not be merged by manufacturer/name.

Android now reports HTTP/UPnP error codes, distinguishes timeout/storage failures,
and offers a read-only retry in the profile. A failed or missing format query
retains existing format declarations and measured evidence while refreshing only
the independently available Seek/Stop action declarations. Desktop uses the same
scoped update. An advertised action remains a declaration, not a verified result.
Unverified rows explain absent, insufficient, or conflicting evidence. Access must
be allowed by the receiver before its format list can be queried; HTTP 403 must
never be converted into either supported or unsupported codec claims.

### Identifying the app receiver for pairing guidance

The private-name receiver above is the leanback app's DLNA service. Its generated
UDN matches `UUID.nameUUIDFromBytes(manufacturer + model + "-MediaRenderer")`.
Its 403 is the app's `QR pairing required` response, not a format incompatibility.

New leanback builds advertise the stable model-description marker
`Wuming TV leanback renderer; qr-pairing-v1`. The shared `RendererPairingHint`
policy recognizes that marker together with HTTP 403. Older builds require all
three compatibility signals: the generated UDN matches the advertised
manufacturer/model, the description is exactly `DLNA Renderer`, and the 403
reason is exactly `QR pairing required`. Names and IP addresses are never used.
A generic 403 or a transport failure does not activate app-specific instructions.

Android displays the TV settings/QR/browser pairing steps in the capabilities
list, followed by the existing read-only retry action. English, Simplified Chinese,
and Traditional Chinese instructions use the corresponding TV menu labels.
Desktop diagnostics use the same recognition policy. The marker only changes the
model description; profile keys and saved evidence are retained. Discovery is
self-reported information: these hints do not authenticate the receiver and must
never grant pairing access or bypass the receiver's existing authorization gate.

### Empty format list after pairing

The leanback service previously constructed jUPnP's default ConnectionManager,
whose Source and Sink lists are both empty. A paired-phone read-only query on
2026-09-28 confirmed `empty_protocol_info`. Pairing worked; it could not create
format declarations or playback evidence.

The receiver now supplies explicit MP4/HLS/DASH sink protocols for its existing
MediaSourceFactory paths. Shared `RendererSinkProtocols` builds these declarations;
the desktop receiver uses it for its existing MP4/HLS declarations as well.
No codec profile, seeking reliability, HTTP transfer behavior, or successful
playback is inferred from container support. These formats display as
**Advertised by TV** until measured playback evidence exists. The receiver update
requires restarting the TV app, which intentionally ends the old pairing session.

Validation on 2026-09-28: 188 playback-core tests and 2 desktop profile tests
passed; mobile ARM64 and leanback ARMv7 APKs built and were installed in place
on the explicitly authorized phone and TV. A focused phone runner then passed
all 3 selected tests in one run: pairing instructions/Retry mapping, cross-writer
storage preservation, and the real paired receiver query with the new marker and
MP4/HLS/DASH declarations. The earlier run expecting an unpaired rejection found
the TV already accepting queries; that failed expectation is retained separately.
Reports are in `app/build/reports/renderer-pairing/`; `pairing-guidance.png` is the
UI test fixture, not a claim that the real receiver was unpaired. No sample
playback or codec-success evidence was manufactured by these tests.
