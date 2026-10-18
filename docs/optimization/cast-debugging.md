# Diagnosing slow casting

Build and install the updated debug APK before collecting these events. A version
suffix identifies the Git commit, not uncommitted source changes: check the build
and installation times as well. No new telemetry is sent off-device.

Capture from an explicitly selected device while reproducing the problem:

```sh
scripts/cast-profile/trace-cast.sh 7e34269d 60
```

The script prints a private temporary directory containing the app version,
installation time, device time, and filtered logcat. It does not install an APK,
clear logs, start/stop casting, or change device settings. It starts at the newest
log entry and captures subsequent events; start it before reproducing the issue.
Debug builds emit detailed `CastDebug` events. Action failures remain warnings in
release builds too.

## Reading the events

- `cast=… event=start relay=…`: links a DLNA session to its relay. The relay ID is
  a short hash of the authorization token, not the token itself. Request IDs are
  process-local counters. `non_relay` means the URL is outside the generic relay.
- `event=action_failure`: reports the actual action (`SetAVTransportURI`, `Play`,
  `Seek`, `GetTransportInfo`, or `GetPositionInfo`), HTTP status, UPnP error code,
  last observed state/position, and whether the session was released. `-1` means
  unavailable, not a confirmed timeout. A URI-setup failure is no longer mislabeled
  as a Play failure. `causes` lists up to eight exception types through the cause
  chain, without messages, stack traces, URLs or credentials. Desktop sender
  failures use the same formatter. Failure recovery and UI messages are unchanged.
- `event=position`: reports the renderer's playhead and `unchangedMs`. PLAYING with
  an unchanged playhead may indicate a stall or inaccurate renderer reporting;
  it does not alone prove a network problem. Position-query failures now log too.
- `event=request`: identifies root versus child, method, sanitized range, and
  upstream host. Group all root/child requests by `relay`, individual responses by
  `request`. Paths, query values and credentials are omitted.
- `event=dash_manifest`: shows the exact number of rewritten URL occurrences and
  whether the manifest changed. `untouchedHttpReferences` includes metadata URLs;
  it is not a count of direct media downloads. Zero rewrites plus no child requests
  is a routing clue, not proof of where the TV fetched its media.
- `event=upstream`: separates connection/header wait from body transfer.
  `event=transfer` reports sequential or selected parallel profile;
  `event=parallel_fallback` records setup failure class.
- `first_bytes`, `progress`, `complete`, `short_eof`, `read_error_*`, and `closed`
  report bytes consumed by the HTTP response reader, expected bytes where known,
  elapsed time, average bytes/sec, and maximum read duration. Progress emits at
  most every five seconds, plus first/final events. These measure consumption
  inside the phone, not delivery to the TV or decoder throughput. Early close can
  be a normal probe/seek/cancellation; it is not automatically an upstream fault.
- `aggregate_prepare` / `aggregate_ready` separate HLS probing/remux preparation
  from body reads. Aggregate expected length is currently reported as unknown.

Read delays are recorded when a read returns or throws; there is no background
watchdog and no claim that a permanently blocked read will emit periodic progress.
The new diagnostics do not change retries, ranges, concurrency or playback control.
Existing unrelated log tags are outside this filtered capture.

## DASH manifest probe regression

A local HTTP regression test reproduced a relay HTTP 500 when a renderer sent
`Range: bytes=0-7` for an MPD: the upstream returned partial XML and the relay
attempted to parse it as a complete document. Recognized MPDs (including the
Bilibili `type=mpd` proxy) now fetch a complete upstream GET for both GET and HEAD
probes. Provider-supplied Range/If-Range headers are removed too. The relay ignores
the manifest probe range and returns the complete rewritten representation with
200; HEAD advertises that representation's length and sends no body. Media child
requests retain their exact ranges and authentication-header policy.

`CastDashRelayTest` exercises the actual HTTP relay against a local upstream that
honors ranges. `DashRelayRequestTest` also covers provider headers and Bilibili
audio/video range requests. This establishes a reproducible defect and its fix;
it does not establish that the unavailable TV sent this probe during the reported
failure, or confirm picture/sound on that TV.

## Manifest-only failure on 2026-10-17

A subsequent user-triggered attempt fetched the rewritten MPD five times, made
no child-media requests, kept the playhead at zero and stopped. Phone-side probes
returned valid MPD GET/HEAD responses and exact HTTP 206 initialization/index
ranges. A captured video fragment identified H.264 High, level 5.1, 1920x1080;
the AAC initialization identified stereo 44100 Hz. These probes establish what
the relay serves, not the TV's decoder capabilities.

The captured MPD failed the [DASH-IF conformance schema](https://github.com/Dash-Industry-Forum/MPEG-Conformance-and-reference-source/blob/master/conformance/MPDValidator/schemas/DASH-MPD.xsd)
with seven errors: decimal `frameRate="29.992"` and three audio representations
each carrying nonstandard `numChannels` and `sampleRate` attributes. The latter
contained bitrate-like values rather than the measured audio sample rate.
The Bilibili cast compatibility path now expresses the frame rate exactly as
`3749/125`, maps mono/stereo channel counts to `AudioChannelConfiguration`, and
omits the untrustworthy `sampleRate` attribute. It never copies that value into
`audioSamplingRate`, invents 44100 for other media, or changes media byte offsets.
Standard audio descriptors and sampling rates remain intact. This fixes a measured
schema defect: the actual captured MPD passed the same full conformance schema
after normalization (seven errors before, zero after). Playback on the receiver
still requires a new APK and user retry.

The phone had global `log.tag=I`, which discarded the debug events before logcat
could collect them. A temporary `log.tag.CastDebug=D` override captured the attempt
and was restored afterward. When restoring an originally empty property, pass
quotes through to the device shell, e.g. `adb -s SERIAL shell "setprop log.tag.CastDebug ''"`.


## Direct PC reproduction on 2026-10-18

The authorized TV became directly reachable on the PC's current Wi-Fi network.
Identity was checked against the exact previously selected UDN before playback;
no inference was made from the misleading Windows Media Player model string.
A bundled H.264/AAC MP4 advanced from 0 to 9 seconds. A bundled DASH sample
reproduced the failure independently of Bilibili and Android: five manifest
requests, no initialization/media requests, then STOPPED at zero. Advertised
sink protocols included MP4 and HLS but omitted DASH. Successful URI/Play SOAP
responses therefore did not establish working DASH playback.

For the original Bilibili aid/cid, the progressive playurl API with `fnval=1`,
`platform=html5`, and the requested quality returned one real combined MP4 at
quality 64 (720p) when 80 (1080p) was requested. A PC relay forwarding exact byte
ranges and public Referer/User-Agent headers advanced the TV's playhead to 17
seconds. This is protocol/playhead evidence; visible picture and audible sound
were not independently observed from this computer.

`BilibiliCastPlayback` now owns source recognition, fixed API construction,
response validation and actual-quality labeling for both senders. Only a known
local Bilibili DASH proxy can trigger this route; Android also recognizes the
exact owning server address. The response must contain a single trusted
bilivideo-domain MP4, never multipart FLV or an invented quality upgrade.
Resolution has an eight-second call budget and a one-megabyte response bound.
Unsupported/API-failed results retain the existing route. Android publishes its
relay only after checking the session generation, including next-episode
resolution. CDN requests never receive the API account Cookie in DIDL headers.

The production `DesktopCastService` (discovery, API resolution, DIDL, HTTP relay
and Play) then advanced the same TV from zero to 23 seconds with matching
controller status queries. A repeat with the normal proxy environment also
advanced playback. This went beyond the standalone diagnostic relay;
Android still requires installation and a phone-to-TV retry.

The first Stop after an idle playback interval failed with a connection error
and no HTTP response. Shared UPnP control requests now use `Connection: close`
instead of reusing receiver sockets. The production sender's first Stop succeeded
on the next full playback test; cleanup confirmed STOPPED and a closed relay.
A loopback HTTP regression verifies separate connections for consecutive actions.

Desktop integration also uncovered a null receiver registration during sender
startup, now avoided. Its media relay preserves HEAD/range semantics, binds to
the route toward the selected receiver, restricts requests to that receiver and
an opaque capability, and cancels transfers on disconnect. Streaming calls no
longer inherit the shared client's 30-second API call timeout; a delayed-body
HTTP regression covers that distinction. The user's configured proxy policy is
preserved; a direct-network probe runs with proxy variables removed only from
that temporary probe process.

Local reports are ignored under `app/build/reports/pc-dlna/`. No current Android
ADB device is connected, so compilation and offline Android tests do not claim
installation or phone-to-TV picture/sound verification.


### Controller identity in direct status probes

This receiver returned STOPPED/empty track data when a Python status probe used
its default User-Agent after the JVM sender started playback. Using the sender's
exact User-Agent for the same status queries returned its active PLAYING session.
An apparent User-Agent acceptance problem was therefore a misleading comparison
between controller identities. The transport identity was left unchanged. Keep
control and observation identity consistent when diagnosing this receiver, and
wait for advancing positions rather than treating PLAYING alone as success.

## Restore temporary phone settings after reconnecting

If USB disconnects before cleanup, retain the original settings snapshot outside
the repository at `$XDG_STATE_HOME/wuming/cast-debug/SERIAL.json` (default
`~/.local/state/wuming/cast-debug/`). Keep the directory owner-only and the JSON
file mode 600. The snapshot records `serial`, `power` (the original
`stay_on_while_plugged_in` string), `lockDisabled` (`"true"`/`"false"`), and
`castDebug` (the original `log.tag.CastDebug`, possibly empty).

After reconnecting and authorizing USB debugging, run:

```sh
python3 scripts/device/restore-cast-debug-settings.py SERIAL
```

Use `--dry-run` to review commands or `--state-file PATH` for another original
snapshot. The helper checks the serial, restores and reads back all three values,
retains the snapshot on failure, and can be rerun. It preserves the working Wi-Fi
connection used for casting. Never create the original snapshot from values
already modified for a debugging session.
