# HLS ad filtering contracts

## Existing behavior

Android applies the first matching nonempty host-rule group (VOD before live).
The automatic filename/domain minority heuristic precedes configured playlist
regex/duration rules and short-circuits them if it removes segments. Those
semantics remain unchanged. WebView host blocking is a separate mechanism.

Provider HLS at the local `/proxy?do=m3u8&url=...` endpoint is eligible for app
purification. The upstream URL selects host rules; the fetched playlist URL
resolves relative segment references. Other local proxy routes and the already
purified `/m3u8` endpoint retain their previous direct-playback behavior.

## Additional resolution/timestamp confirmation

The additional pass processes the existing purifier's output, or the fetched
playlist if existing rules produce no replacement. It does not reinterpret
`regex`, change host-rule precedence, or require a frame-rate difference.

- **Candidate feature:** a sudden resolution change in either direction lasting
  strictly less than ten minutes (600 seconds). A higher-resolution insertion is
  not required. Every sampled candidate must differ from the programme and share
  the same candidate dimensions. Resolution and duration alone do not confirm removal.
- **Confirmation evidence:** a short discontinuity-bounded format change,
  a backward PTS reset, and return to the preceding programme's resolution and
  original timestamp sequence after the block. Candidate segment timestamps
  must also form a continuous internal sequence.
- Resolution-change bounds: candidate duration strictly below 600 seconds;
  each neighbouring block at least 60 seconds; reset to PTS
  at most 10 seconds with a backward jump of at least 30 seconds; timeline
  comparisons allow 0.5 seconds of tolerance. Candidates with up to 16 segments
  probe every segment; larger candidates probe 16 evenly distributed segments,
  including the first and last. Each sample must match its expected cumulative
  timestamp. Unsampled segments are not claimed to have been inspected.
- Only complete, unencrypted MPEG-TS/AVC VOD is probed. Live, encryption,
  fragmented MP4, byte-range, partial, and unsupported playlist forms are kept.
  Unknown or failed probes retain the affected block.
- Android probes use at most 64 KiB per segment, 24 requests per preparation,
  three seconds per request, and a total 15-second probe deadline. Cancellation
  cancels the active request. No external FFmpeg process is required by the app.

The confirmation is deliberately conservative and is evidence of an insertion,
not a universal semantic guarantee that every such insertion is advertising.

## Proposed minimum resolution — not implemented

The user proposed an additional essential-feature gate: candidate video width
must be at least 800 pixels **and** height at least 600 pixels. A candidate below
either threshold would be excluded from ad removal. This is a per-dimension
minimum, not an equivalent pixel-area threshold.

Passing this proposed gate would not confirm an ad. The resolution-change,
timestamp-reset, and programme-return confirmation checks would still apply.
The former requirement to exceed programme resolution was superseded by the
user's 2026-10-12 instruction described above.

This is an unvalidated working assumption. Both confirmed fixture ads are
1920×1080, so they satisfy it, but this fixture does not establish that other ads
cannot be smaller. Per the user's instruction, this proposal is documented only;
no 800×600 check is implemented, and runtime behavior remains unchanged.

## Verified investigation fixture

The downloaded episode is 洗冤录1粤语 第06集 from FF有广. Its 682 original
segments consist of 673 programme-format segments, five confirmed 1080p/30fps
ad segments, and four confirmed 1080p/25fps ad segments. The provider removed
only the five 30fps segments. The added pass removes both confirmed blocks
from the original playlist, or only the remaining four from the provider
playlist; both outputs retain the identical 673 programme segments in order.

The missed block is at player 27:56.960–28:14.320, corresponding to original
28:16.593–28:33.953. The earlier 14:29 report was not reconciled with this
download and is not used as a removal target.

Core unit tests cover essential-feature exclusion, insufficient confirmation,
timeline discontinuities, unavailable probes, and unsupported playlists. Android
tests exercise actual codec parameter sets and split TS packets. An optional
offline replay test uses the downloaded fixture without committing video data:

```sh
TV_AD_FIXTURE_DIR="$PWD/artifacts/ad-filter-investigation/2026-09-11-36940" \
  ./gradlew :app:testLeanbackArm64_v8aVerificationUnitTest \
  --tests com.wuming.android.tv.media.hls.TsVideoProbeTest
```

Without `TV_AD_FIXTURE_DIR`, only that optional replay test is skipped; synthetic
probe and core decision tests still run. Passing these tests is not a device
playback verification or a claim of general accuracy on other providers.

## Device case: 2026-09-11, stream 36942, interruption around 28:00

### Source and reproduction identity

- Provider: 非凡 / FF有广 (`ffm3u8`).
- Stream ID: `36942_58afcefe` (distinct from downloaded episode-06 fixture
  `36940`). The captured log does not establish this stream's title or episode
  number; do not infer them from the numeric ID.
- Master URL: `https://vip.ffzy-online3.com/20230704/36942_58afcefe/index.m3u8`
- Media URL: `https://vip.ffzy-online3.com/20230704/36942_58afcefe/2000k/hls/mixed.m3u8`
- Player transport: `http://127.0.0.1:9978/proxy?do=m3u8&url=<encoded master URL>&headers=<encoded headers>`.
- Logged upstream User-Agent: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6299.95 Safari/537.36`.
- Logged player duration after provider processing: 2717.44 seconds (45:17.44).
- Device: `172.16.30.86:5555`; playback PID: `16237`.
- Active app: normal 無名, `com.wuming.android.tv`, version
  `6.4.1(bd0b79426)`, last updated `2026-09-05 17:09:56`.
- Separate 無名(testing), package
  `com.wuming.android.tv.verification.s8b30a61e7a82`, was installed at
  `2026-09-11 16:13:04`. It was not running when this case was inspected.

### Observed evidence

Times below are September 11 device log wall-clock times; player positions are
`mediaPos`, not original-source positions or raw transport-stream PTS.

| Wall clock | Player position | Evidence |
| --- | --- | --- |
| 16:02:19.631 | Preparation | Provider reports `Master FPS: 25`, standard size 89. |
| 16:02:19.805–.806 | Preparation | Provider reports `命中广告 Block: 1` and `检测到广告段: 5`. This is five segments, not proof of five separate ad breaks. |
| 16:02:20.382 | 00:00.00 | AVC `avc1.64001F`, 1280×720 programme format. |
| 16:30:18.731 | 27:58.04 | Input changes to AVC `avc1.640032`, 1920×1080. |
| 16:30:19.847 | 27:59.01 | Video decoder released; recreated at 16:30:20.133. Audio underruns follow. |
| 16:30:20.416 | 27:59.74 | Rendered video size becomes 1920×1080. |
| 16:30:36.212 | 28:15.33 | Input returns to AVC `avc1.64001F`, 1280×720. |
| 16:30:37.316 | 28:16.43 | Rendered video size returns to 1280×720. |

The approximately 17.29-second interval between input-format events matches
the user's reported ad interruption around 28:00. These callback positions
are approximate playback evidence, not exact segment boundaries.

The provider listed these five removed segment basenames:

```text
f22b22b1249b37cb75977eeaaf23f355.ts
12cc8bd0517e0cf14584528471e0c06d.ts
50c19dc0107b574d2aa75246b8241149.ts
616194b981b562a85923c500697960e4.ts
bc9ea08589f67613913b909628fdfe8e.ts
```

### Interpretation and limits

The active normal app predates the localhost bypass fix and the additional
resolution/PTS detector. Playback began before the testing package was
installed and continued in the normal app. Thus this occurrence does not test
the new detector: the provider processed the playlist, but the old app let the
local proxy playlist go directly to playback.

**Essential feature:** the observed 1920×1080 interval exceeds the surrounding
1280×720 programme in both dimensions. This satisfied the historical higher-resolution
gate (now superseded), but does not alone confirm removal or verify every
candidate segment.

**Confirmation evidence:** a short resolution change followed by programme
resolution return is visible. Raw PTS reset, discontinuity tags, exact candidate
segment boundaries, internal continuity, and return to the original timestamp
sequence have not been measured for this stream. Therefore full confirmation
under the new detector remains unverified. The provider log does not establish
this remaining block's frame rate or the precise reason its detector kept it.
Do not reuse stream 36940's original/player offset or PTS measurements here.
The proposed 800×600 minimum remains documentation only.

For follow-up, reload this exact source in 無名(testing), capture `HlsAdFilter`
output, and preserve the original and provider playlists plus TS prefixes
around the candidate and both neighbours. Measure raw PTS and SPS resolution
before deciding whether this is a new rule failure or unsupported/probe-failure
case. Do not add this case as a confirmed removal regression until those
measurements exist.

### Local evidence archive

The captured log was copied out of `/tmp` to the ignored local artifact path
`artifacts/ad-filter-investigation/2026-09-11-36942/logcat.txt`.
SHA-256: `d603642bdc9da62bedcd14dd93fbffb155947266d709d35419fcd5b8f12faebc`.
It contains unrelated device logs and is intentionally not committed. This
document preserves the relevant findings for other checkouts; the full log
requires the local archive. No MP4 or TS fixture was downloaded for stream
36942 during this capture. Source URLs may change or expire.

## Device case: episode 09, burned-in advertising text near 39:56

On 2026-09-11 the user reported a text popup around 40:15. An ADB screenshot
at wall clock 17:36:33 instead captured the visible occurrence paused at
**39:56 / 44:51**, with title **洗冤录粤语版：第09集**, resolution 1440×1080.
The active package was 無名(testing),
`com.wuming.android.tv.verification.s8b30a61e7a82`, PID 5644.
This is a different source from the earlier FF有广 cases.

### Source identity and evidence

- Host: `vd.wmvbo.com`; request User-Agent: `Lavf/57.83.100`.
- Stable identifying portion of the captured URL:
  `/decry/vd/20250603/Y2VmZTk1NzQ3Yj/032415/1440_1080/aac/h264/hls/decrypt/index.m3u8`.
  The complete URL includes transient access components and is preserved in
  the local artifact `source-url.txt`, not reproduced here.
- Fetched playlist duration: 2690.8 seconds; no `EXT-X-DISCONTINUITY` tags.
- Candidate segment: `DkxO-00239.ts`, playlist interval 2390–2400 seconds
  (39:50–40:00). Downloaded neighbouring segments 238 and 240–242 as well.
- Screenshot text: `本片由【ONE游戏 182.bet】赞助播出 注册就送188元`
  and `主营：体育 真人 棋牌 电子`.
- Direct FFmpeg decoding of the upstream TS, without Android, subtitle
  rendering, or player UI, reproduces the same advertising text over the
  programme. One-second samples of segment 239 show it approximately at
  39:52–39:56, absent in the earlier and later samples. Exact onset/end frames
  have not been measured.
- The earlier sample window 40:00–40:30 missed this occurrence. It must not
  be cited as evidence that the reported text was absent from the source.

| Segment | Playlist start (s) | Video start PTS (s), ffprobe | Format |
| --- | --- | --- | --- |
| DkxO-00238.ts | 2380 | 2381.560000 | 1440×1080, AVC, 25 fps |
| DkxO-00239.ts | 2390 | 2391.560000 | 1440×1080, AVC, 25 fps |
| DkxO-00240.ts | 2400 | 2401.560000 | 1440×1080, AVC, 25 fps |
| DkxO-00241.ts | 2410 | 2411.560000 | 1440×1080, AVC, 25 fps |

### Conclusion and filtering implications

**Confirmed:** the text is burned into the encoded source video pixels. It is
not an Android toast or independently rendered player text. The programme
continues behind the advertising banner. This establishes its form in the
delivered stream; it does not establish who added it or at what production stage.

There is no evidence of a separate HLS ad block: resolution stays unchanged,
the sampled segment-start timestamps advance normally by 10 seconds, and the
playlist has no discontinuity markers. Segment-start measurements alone do not
prove every frame's timestamp continuity, but direct frame decoding conclusively
identifies the burned-in banner.

The existing detector must retain this segment: it fails the **essential**
resolution-change gate and lacks the required **confirmation** evidence for
a discontinuity-bounded insertion with PTS reset. A nominal 800×600 minimum
would not help distinguish this banner from programme video and remains
unimplemented. Removing this segment would also remove programme dialogue.
Any future masking/OCR approach is a separate feature requiring explicit scope
and safeguards for normal subtitles; no filtering rule was changed for this case.

### Local artifacts and reproduction

All evidence is under the ignored directory
`artifacts/ad-filter-investigation/2026-09-11-popup-4015/`:
`logcat.txt`, `screenshot-now.png`, `source-url.txt`, `source.m3u8`,
`DkxO-00238.ts` through `DkxO-00242.ts`, `probe-238.json` through
`probe-241.json`, contact sheets `2380.jpg` through `2420.jpg` (10-second
increments), and `source-39m56.png`. Media and raw device logs are local only.

To reproduce the decisive source-only frame:

```sh
ffmpeg -i artifacts/ad-filter-investigation/2026-09-11-popup-4015/DkxO-00239.ts \
  -ss 6 -map 0:v:0 -frames:v 1 source-39m56.png
```

## Tablet case: 2026-10-12, advertisement at 24:51

The authorized tablet `AUTHORIZED_TABLET_SERIAL` was playing **爱情蛙跳跳2**,
line **中字**, in `com.wuming.android.tv.verification`. Logcat records a pause
at media position 1491.09 seconds (24:51.09). The exact master and media
playlists were recovered from the application's HTTP cache:

- Master: `https://v8.zuidazym3u8.com/yyv8/202311/02/96eiptSihd1/video/index.m3u8`.
- Media: the same directory plus `2000k_1080/hls/index.m3u8`.
- Segment host: `yyv2.qwe132456.cc`.

The reported insertion occupies 1470–1499.28 seconds (24:30–24:59.28),
15 segments totaling 29.28 seconds, ending with a 1.28-second truncated segment.
Both programme and insertion are AVC 1920×1080. Downloaded segments measured:

| Position in playlist | Segment | Raw video PTS |
| --- | --- | --- |
| 1468 | `kECNI0bH36.ts` (programme) | 1469.48 |
| 1470 | `abTCL64Ptn.ts` (insertion start) | 1.48 |
| 1498 | `g8BCyXqjCP.ts` (insertion end) | 29.48 |
| 1499.28 | `rSSdzA6APU.ts` (programme return) | 1471.48 |

Every insertion segment advances by its declared duration. The returning
programme starts exactly at the preceding programme PTS plus its two-second
segment duration. The measured original duration is 4232.20 seconds; removing
only this insertion produces 4202.92 seconds.

The original resolution detector correctly excludes this case under its recorded
contract: the insertion has the same resolution. Its neighboring blocks also
fail the 60-second bound because this source marks ordinary programme video with
many short discontinuity blocks. Random segment basenames on one host do not
provide a minority filename/domain signature.

A separate `HlsTimestampInsertionFilter` now handles this measured pattern;
At initial validation, `HlsResolutionAdFilter` still required higher resolution;
that gate was subsequently replaced by the resolution-change rule above.
The separate timestamp-insertion pass requires all of the following:

- Complete, unencrypted supported TS VOD, with an enclosed block of at most
  30 seconds and 15 segments.
- A truncated final candidate segment relative to `EXT-X-TARGETDURATION`.
  This is a candidate-selection gate, never sufficient evidence for removal.
- At least 60 seconds of playlist context before and after the candidate,
  even when ordinary discontinuity tags divide that context into short blocks.
- The same dimensions in every candidate segment and both programme neighbors;
  a reset to PTS at most 10 seconds and backward jump of at least 30 seconds;
  continuous candidate timestamps; exact programme resumption within 0.5 seconds.
- Unknown probes, timeline mismatches, and failures retain the whole candidate.

The Android request cap is now 24, since a 15-segment candidate plus its two
neighbors requires 17 requests. The 64 KiB prefix limit, three-second request
limit, and 15-second total deadline remain in force. This recognizes a separate
encoded insertion; it does not classify burned-in advertising or remove video
based only on equal dimensions, duration, or a scene change.

Local raw logs, the captured playlist, all measured TS prefixes, and ffprobe
results are preserved under ignored
`artifacts/ad-filter-investigation/2026-10-12-tablet-2451/`. Regression tests
verify the short-neighbor structure, all 15 candidate segments, exact retained
programme URI order, normal continuity, failed resumption, probe failures,
format changes, and unsupported playlists. The optional Android fixture test
requires `-e hlsAdFixture true` and `files/hls-ad-fixture` in the verification
app; media data is not committed.

Validation on this tablet: all 24 selected core HLS filter tests passed; the
Android TS probe unit suite passed five tests, with its older optional fixture
test skipped. The new captured-media Android instrumentation test passed in
2.22 seconds, including the real Android `M3u8.purify` adapter. The APK build
and generic-library, desktop, and lifecycle boundary checks passed.

The first playback reload did not publish a replacement playlist; the existing
code did not report per-probe failures, so that attempt does not establish the
specific network failure. Preparation now logs whether it replaced the playlist
and how many probes it attempted, and reports preparation exception types without
logging media URLs or headers. A subsequent ordinary playback reload logged
`timestamp-insertion: removedBlocks=1, removedSegments=15` and
`Playlist preparation: replaced=true, segmentProbes=17`. Fetching the live
`/m3u8` output confirmed 2117 original segments reduced to 2102, with exactly
the measured insertion removed and every programme URI retained in order.
The Media3 player reached READY/PLAYING and the media session reported no error.
This proves live preparation and playback startup, rather than a visual viewing
of the entire film or a general guarantee for other advertising patterns.

### Resolution policy correction: 2026-10-12

The user rejected the higher-resolution requirement and proposed a sudden
resolution change plus duration less than ten minutes. The resolution detector
now accepts changes in either direction and uses a strict 600-second cutoff.
The short enclosed block, timestamp reset, internally consistent sampled
sequence, and programme resolution/timeline return remain confirmation checks.
Large candidates use at most 16 evenly distributed TS probes plus the two
programme neighbors, preserving the existing Android network budget. This is
sampled evidence, not a claim of inspecting every segment in a ten-minute block.
The separate same-resolution timestamp detector retains its 30-second / 15-segment
limit and still inspects every candidate segment.

Regression coverage includes a downward resolution change, normal continuity
following a downward change, mixed candidate dimensions, changes in only one
dimension, a 598-second candidate, exclusion at exactly 600 seconds and above,
and failure of the sampled final timestamp. Historical confirmed higher-resolution
insertions and the tablet's same-resolution insertion remain covered.

After the resolution-policy correction, all 27 selected core filter tests passed,
including the 600-second boundary and distributed-probe checks. The Android TS
probe suite again passed five tests, with its older optional fixture skipped;
all three architecture boundary checks and the updated verification APK build
passed.
The revised APK was installed over the existing tablet verification app with
application data preserved. The captured same-resolution advertisement test
passed again on the tablet in 2.234 seconds, including the Android preparation
adapter, removing exactly 15 segments. The original power setting was restored.

### Shared segment probes: 2026-10-12

Android now creates one `HlsSegmentProbeCache` per `M3u8.purify` preparation and
passes it to both the resolution-change and timestamp-insertion detectors.
The platform-neutral cache stores successful dimensions/PTS, unavailable or
invalid results, and recoverable probe exceptions. A repeated read reuses the
same outcome without another underlying request or budget increment. It does
not turn failed probes into evidence for removal.

Keys include the entire resolved segment URL, including its query. Caches are
never shared across preparations, so refreshed playlists, account headers, or
new playback attempts cannot reuse another preparation's results. The original
probe port still owns the request deadline, cancellation, and 24-request limit.

An integration regression uses both actual detectors on a same-resolution
insertion: underlying probe calls fall from nine to six while the removed
segments and retained playlist remain identical. Tests also cover unavailable,
invalid, and failed reads, distinct URL queries, and fresh preparation lifetimes.
The Android adapter regression repeats the preparation and requires exactly six
unique reads in each run, rather than sharing a cache between playback attempts.

Validation: 30 selected core tests and all three architecture boundary checks
passed. The updated app and runner builds passed and were installed on the
same authorized tablet without clearing app data. Both Android instrumentation
methods passed in one run in 2.222 seconds: the adapter made six unique underlying
probe calls per preparation, and the captured real-media fixture still removed
exactly the original 15 ad segments. Temporary power settings were restored.

### Shared pipeline and desktop integration: 2026-10-12

`playback-core` now owns the entire portable preparation pipeline:
`HlsAdFilterEngine` selects the first matching rule group, runs the existing
purifier, then runs both automatic detectors with one preparation-scoped cache.
`HlsSegmentProbeSession` owns the 64 KiB / 24 requests / 3 seconds / 15 seconds
budget, cancellation checks, prefix validation and TS-wrapper stripping.
`HlsHttpProbeTransport` bounds actual response reads and cancels its active call.
`TsStreamProbe.create()` uses the portable `AvcSpsParser`, including AVC crop,
chroma and interlacing rules; Android no longer supplies a Media3 SPS parser.
`HlsReferenceRewriter` owns playlist URI/attribute traversal.

Mobile and both leanback modes use the same Android preparation adapter.
Desktop's `HlsCompatibilityRelay` invokes the same engine, parser, probe session
and transport; each platform supplies its HTTP client configuration, headers,
URL resolver, cancellation signal, and relay registration. Core logic contains
no Android, Media3 or desktop UI dependencies. Relay shutdown cancels active
probe requests too.

Desktop regression tests serve real generated TS prefixes over local HTTP and
exercise same-, lower- and higher-resolution insertions through the actual relay.
They assert one underlying read per URL, the Range header, exact programme URI
retention and matching removal across both detectors. Core tests exercise bounded
reads when Range is ignored and cancellation of an active HTTP request.
These local tests are independent of live source availability.

Final validation of the shared implementation: full mobile, leanback and lite
unit suites, the full desktop suite, all six verification APK builds, and all
four library/lifecycle/retention checks passed (optional unit fixtures retain
their recorded skips). The rebuilt mobile app and runner passed both HLS
instrumentation methods together on the authorized tablet in 2.240 seconds,
with no instrumentation skips. The real TS fixture removed exactly 15 segments
through the Android adapter using the portable parser. App data was preserved,
the fixture removed, and original power/lock settings restored.
