# Provider protocol evidence and lessons

> Package rename note (2026-09-25): historical `com.fongmi` commands and results
> below retain their original identity. Current app/test packages use `com.wuming`;
> use the current smoke runner and explicit device authorization for new tests.

This file records verified provider protocol evidence and implementation lessons for
cloud-drive playback. It is a companion to the standing rule in
[lesson-learned.md](lesson-learned.md): provider protocols are not logical specifications.

## Pinned evidence used for this UC implementation

- `wgx0307/netdisk@eb4cd97607558d1c54ed317fdf9aa1b364ea3535`:
  `share/sharepage/save` body/query and `task` polling through
  `save_as.save_as_top_fids`.
- `AlistGo/alist@843d9dc8149126976b2625911e45a4d3ffd6f2f5` and
  `kong-hen/cloud_driver_sdk@d72a8f7658d5840c5594f273030d1800a67c9cb8`:
  personal `file/download?pr=UCBrowser&fr=pc`.
- `hmjz100/LinkSwift@417ea5e28a3e1a90339710e17356e9fd22b8a34b`:
  Windows UC Electron UA and full browser cookie for the download URL.
- `YYDS678/uzVideo-extensions@f399ceb74ba5ceb0010688d7a72e16f1cc4ddd9d`,
  `panTools/js/panTools2_sub/quark_uc.js`: dynamic `ut`, optional raw
  `file/download`, personal `file/v2/play`, exact transcode body, filtered
  `playHeaders`, and direct handoff of the transcode URL to the player.
- Device `7e34269d`: the configured public-share route passed only after the
  test asserted a non-null saved fid and personal transcode. The observed URL
  was an HTTPS `*.drive.uc.cn/.../media.m3u8`; exact headers returned HTTP 200,
  while the shared player CookieJar replaced the cookie and produced HTTP 412.

## UC raw playback evidence added 2026-08-06

- `lushunming/AndroidCatVodSpider@ec6e36c76d2cc99274589ce4bb2aa887f91020de`,
  `UCApi.playerContent()` and `getDownload()`: the `uc原画` branch stops after
  `file/download` and returns its `download_url` directly. It does not call
  `file/v2/play`, add player headers, or place the raw file behind a local
  proxy. Its API call and player share the same persistent CookieJar, so the
  `__puus` rotated by `file/download` is also the cookie used by the player.
- Device `7e34269d` reproduced that CatVod path with `OkHttp.player()` and a
  direct `dl-pc-*.drive.uc.cn` URL. A signed URL without the current cookie
  returned HTTP 412. Therefore "direct" does not mean "unauthenticated": the
  exact response-time cookie must first be synchronized to the player jar.
- OpenDrive previously forced `application/x-mpegURL` for every `*.uc.cn`
  URL. The device then showed `Input does not start with the #EXTM3U header`
  because a progressive `file/download` MP4 was parsed as HLS. Only an
  observed `.m3u8` transcode URL may receive the HLS format hint.
- The corrected raw order is: save shared file, wait for the saved fid, call
  `file/download`, apply its returned `__puus`, synchronize the playback
  cookies, and hand the signed URL directly to the normal player. A transcode
  request is fallback-only when `file/download` has no valid HTTPS URL.

## CatVod-direct failure boundary verified 2026-08-06

- Device `7e34269d`, CatVod-direct mode, source `玩偶 / 优汐无限1`: the
  downloaded CatVod `Cloud_uc.playerContent()` selected its logged-in OAuth
  `/file` branch and threw `JSONException: No value for data`; CatVod then
  returned its terminal `获取到无效链接` result. Both the persisted CatVod UC
  token and UC login cookies were present, so this is not evidence of logout.
- CatVod must remain the first resolver in CatVod-direct mode. OpenDrive may
  run only after CatVod returns a message or an empty playback URL, and only
  when `routeExternalEpisode()` proves that an authenticated built-in provider
  understands the exact original payload. A valid CatVod URL, an unsupported
  payload, an OpenDrive ID, or OpenDrive-builtin mode must never enter this
  fallback.
- The same device replayed the same 388-byte episode payload after the fix:
  log stage `catvod-fallback`, UC save, `file/download`, direct normal-player
  handoff, then real 3840x2160 H265 video output. No 412, HLS parser failure,
  or playback exception occurred.

## UC OpenDrive cache reclamation verified 2026-08-06

- `lushunming/AndroidCatVodSpider@ec6e36c76d2cc99274589ce4bb2aa887f91020de`,
  `UCApi.clearSaveDir()`: before each share save, list the dedicated save folder
  through `file/sort`, collect every returned `fid`, and submit that exact list
  to `file/delete` with `action_type=2`. Cleanup based only on a local tracking
  table is not equivalent because a restart, interrupted transfer, or older
  build can leave an untracked provider-side copy consuming the account quota.
- Device `7e34269d`, OpenDrive-builtin mode: before the fix the same UC episode
  failed with `UC cloud space is insufficient; no disposable OpenDrive cache
  remains`. The fixed provider found and deleted one untracked entry inside its
  dedicated `TVBoxCache` folder, then completed save and `file/download` and
  rendered 3840x2160 HEVC. Switching to a second episode reclaimed the first
  tracked entry and again rendered 3840x2160 HEVC, with no 404 or quota error.

## UC PDS throughput boundary verified 2026-08-06

- `lushunming/AndroidCatVodSpider@ec6e36c76d2cc99274589ce4bb2aa887f91020de`,
  `DownloadMT.kt`: for a file below 10 GiB, generate a 16 MiB range window,
  divide it across `availableProcessors * 2` concurrent requests, await the
  response streams, and expose them in byte order through `SequenceInputStream`.
  This is the source for OpenDrive's parallel transport; the concurrency and
  range size must not be inferred from apparent UC behavior.
- On device `7e34269d`, the same 2.52 GiB UC file and network measured about
  0.51 MB/s through the old single-range OpenDrive source. The proven parallel
  transport measured 5.48-5.64 MB/s while Media3 was actively reading and
  built about 51 seconds of buffer ahead. The complete 35-second window was
  3.49 MB/s only because Media3 applied backpressure after reaching its buffer
  target.
- CatVod's 9.79 MB/s UID figure is not directly comparable to OpenDrive's
  direct socket. CatVod handed the player a `127.0.0.1` relay: during one
  37.9-second transfer the localhost data source delivered 156,766,639 bytes,
  while UID RX increased by 384,114,957 bytes because that accounting also
  included the relay's upstream activity. Use the data-source aggregate and
  buffer growth to compare playback, not raw UID RX across different routes.
- Both routes reached the same UC PDS host with HTTP 206 and
  `x-oss-traffic-limit=503316480`. Adding a bounded range or changing headers
  did not remove the old single-stream limit. Do not add unbounded background
  prefetch merely to make the CatVod relay and direct OpenDrive counters look
  alike; require a proven client implementation and an end-to-end device test.

## UC OpenDrive — follow the proven protocol, do not invent one (2026-08-05)

### Problem
When UC's upstream OSS storage rejected a signed download URL with HTTP 403
and error code `RequestDeniedByCallback`, playback failed with "UC rejected
this download authorization. Re-login to UC or use another source."

### Root Cause (real, per proven reference implementation)
The actual bug was in `postDownload()`: the download was authorized with the
cookie *sent* to `file/download`, while UC returns the correct, freshly-rotated
`__puus` cookie as a `Set-Cookie` on the `file/download` HTTP response itself.
The signed `download_url` is bound to that *returned* cookie. Our code threw
the returned cookie away, so the stream request used a stale `__puus` and was
rejected by upstream OSS.

### Proven reference (do NOT invent UC protocol from logic)
- `kong-hen/cloud_driver_sdk` → `Uc/` (UcAPI, UcSession, UcFile, UcDownload):
  the signed URL is obtained via
  `POST https://pc-api.uc.cn/1/clouddrive/file/download?pr=UCBrowser&fr=pc`
  with body `{"fids":[...]}`. The storage cookie is read from the
  `file/download` response's `Set-Cookie` (`__puus`) and that exact cookie is
  what authorizes the actual download. There is **no separate `config`
  refresh endpoint** for `__puus`.
- `qaiu/netdisk-fast-download` independently confirms the same `file/download`
  flow and cookie-from-response handling.

### Fix (aligned with the proven protocol)
1. **`postDownload()`**: use the cookie from the `file/download` response
   (`refreshCredential` + the returned `cookies.header()`), not the request
   cookie, as the cookie bound to the signed `download_url`.
2. **Removed the invented `refreshDownloadCookie` / `config?pr=UCBrowser&fr=pc`
   GET** — no basis in the real protocol.
3. **`proxy()` retry**: on `RequestDeniedByCallback`, re-resolve the signed URL
   and cookie by re-calling `file/download` (`reResolve`), which is exactly how
   a fresh cookie + signed URL are produced. `StreamRef` now also stores the
   `file/download` request body for this purpose.

### Key Insight
Never invent a cloud-provider protocol from intuition. Read a proven, working
open-source client and copy its request sequence, domains, headers, and cookie
handling verbatim. The `__puus` is issued by the `file/download` response, not
a separate config call.

## UC "use UC browser / open membership" prompt: evidence record (2026-08-05)

### Symptom (from screenshot)
UC's player overlay: "请使用UC浏览器播放 或 开通UC网盘会员" + a QR code, even
though the credential is present. Top status bar showed a live 815 KB/s stream
briefly, then UC blocked it. This is NOT a "not logged in" error — it is UC's
anti-third-party-player / membership gate.

### Rejected inference
The screenshot alone does not prove that extra cookies, a particular desktop
UA, or the absence of a transcoding call caused the prompt. Those changes were
not accepted from inference. They became admissible only after the exact
uzVideo UC implementation was inspected and the same request sequence was
validated on the target device.

### Recorded implementation evidence
- LinkSwift uses the Windows `uc-cloud-drive/2.5.20` Electron UA, the complete
  browser cookie, and `file/download`.
- Current AList and `kong-hen/cloud_driver_sdk` use
  `file/download?pr=UCBrowser&fr=pc` for a personal-drive fid.
- The exact uzVideo UC code treats raw `file/download` as optional, then calls
  `file/v2/play` with the saved personal fid and hands the chosen transcode URL
  plus filtered `playHeaders` directly to the player.
- The target device proved that raw download can return HTTP 404 while the
  personal transcode succeeds. It also proved that hiding the HLS URL behind a
  single-resource localhost proxy breaks relative playlist/segment URLs.
- The player must use `OkHttp.explicitCookiePlayer()` for the observed UC HLS
  host; the normal CookieJar replaces the protocol-required Cookie header and
  UC returns HTTP 412.
- Final screenshot `uc_drive_fixed_2026-08-05_1936.png` shows the actual drama
  playing at 00:05/46:05, not UC's gate clip.

### Files Modified
- `app/src/main/java/com/fongmi/android/tv/cloud/opendrive/UcOpenDriveProvider.java`
- `app/src/main/java/com/fongmi/android/tv/player/exo/MediaSourceFactory.java`
- `app/src/test/java/com/fongmi/android/tv/cloud/opendrive/UcOpenDriveProviderTest.java`
- `app/src/test/java/com/fongmi/android/tv/player/exo/MediaSourceFactoryTest.java`
- `app/src/androidTest/java/com/fongmi/android/tv/cloud/opendrive/OpenDriveLiveProviderInstrumentedTest.java`

## Quark 夸父原 playback — three layered bugs in stream handoff (2026-08-23)

### Problem Summary
Selecting the `夸父原` (Quark cloud drive original quality) flag on device `7e34269d`
caused ExoPlayer to fail with `UnrecognizedInputFormatException: None of the
available extractors could read the stream. sniff failures: [NoDeclaredBrand,
NoDeclaredBrand]`. The first attempt also surfaced `ProtocolException: Unexpected
status line: isom****isomiso2mp41` and a follow-up `Manifest Malformed` after
`retryFormat` set the MIME to `application/x-mpegURL`.

### Root Cause (three independent bugs, each sufficient to break playback)

#### Bug 1 — `StreamingResponse.send()` bypassed NanoHTTPD header writing
`Proxy.java` had a custom `StreamingResponse` extending `NanoHTTPD.Response` and
overriding `send()`. The override wrote the body bytes directly to the
`OutputStream` without first writing the HTTP status line and headers. OkHttp on
the player side then received raw MP4 bytes (`isom****isomiso2mp41`) where it
expected `HTTP/1.1 200 OK\r\n`, producing
`ProtocolException: Unexpected status line`.

A secondary issue in the same override: `PrintWriter` wrapping
`BufferedWriter`/`OutputStreamWriter` could flush header bytes into the body
stream, corrupting the first bytes ExoPlayer sniffs.

#### Bug 2 — `translatedRange()` always returned `null`
`QuarkOpenDriveProvider.translatedRange()` returned `null` for every input, so
the proxy opened the Quark CDN URL without a `Range` header. The Quark download
host `dl-pc-zb.drive.quark.cn` can close an unbounded HTTP 200 stream after
roughly one minute and may return an HTML error page or redirect instead of the
file when no `Range` is present. Without `Content-Range` Media3 also lacks the
file length on its first request.

#### Bug 3 — `isPngResponse()` consumed 8 bytes from the response stream (the fatal one)
`QuarkOpenDriveProvider.isPngResponse()` called
`readPrefix(body.byteStream(), 8)` to check for the PNG signature. When the
response was NOT PNG (the normal case), the method returned `false` and
`openPlayback` returned the same `Response` — but its body stream was now at
offset 8. ExoPlayer received the stream starting at byte 8, missing the MP4
`ftyp` box, and the MP4 extractor reported `NoDeclaredBrand`.

### Fix (each bug fixed at its source)

1. **`Proxy.java`** — removed the entire custom `StreamingResponse` class.
   Replaced with NanoHTTPD's standard factories:
   ```java
   if (contentLength >= 0) {
       response = Nano.newFixedLengthResponse(Status.lookup(responseStatus), dlnaMime, bufferedStream, contentLength);
   } else {
       response = Nano.newChunkedResponse(Status.lookup(responseStatus), dlnaMime, bufferedStream);
   }
   ```
   `Response.send()` in NanoHTTPD 2.3.1 writes the status line, headers, then
   body via `sendBodyWithCorrectTransferAndEncoding()` using a 16 KB buffer.

2. **`QuarkOpenDriveProvider.translatedRange()`** — return `bytes=0-` when no
   range is specified, so the CDN responds with HTTP 206 and `Content-Range`
   and Media3 gets the file length on the first request:
   ```java
   if (range == null || range.isEmpty()) return "bytes=0-";
   return range;
   ```

3. **`QuarkOpenDriveProvider.isPngResponse()`** — use `response.peekBody(8L)`
   which reads bytes without consuming them, instead of `body.byteStream()`
   which advances the stream position:
   ```java
   ResponseBody peeked = response.peekBody(8L);
   byte[] prefix = readPrefix(peeked.byteStream(), 8);
   ```
   `peekBody` is a method on `okhttp3.Response`, not on `ResponseBody`.

### Verification
Device `7e34269d`, source `盘她┃夸父`, video `花开锦绣`, flag `夸父原`:
- `PlaybackTransport: event=progress` with `contentLength=884301439`,
  `contentRange=bytes 12328210-896629648/896629649`, `contentType=video/mp4`,
  steady `avgBps=51105`.
- On-screen overlay: `3840 x 1606 | H265 | 硬解 | 114 KB/s` then
  `480 x 202 | H264 | 硬解 | 105 KB/s` after switching episodes.
- No `UnrecognizedInputFormatException`, no `Manifest Malformed`, no
  `ProtocolException` in the logcat after the fix.

### Key Insights

1. **`Response.peekBody(n)` vs `body.byteStream()`** — Any method that inspects
   a response body must use `peekBody` if the same `Response` will be consumed
   downstream. `byteStream()` (and `string()`, `bytes()`) consume the body and
   leave the stream at a non-zero offset.

2. **Do not override `NanoHTTPD.Response.send()`** — `Response.send()` is
   responsible for the full HTTP wire format: status line, headers, transfer
   encoding, and body. Overriding it to write only the body produces an invalid
   HTTP response.

3. **Send an explicit open-ended Range for CDNs that dislike unbounded 200** —
   Some CDNs behave better with an explicit `Range: bytes=0-` than with no
   `Range` header at all: they return 206 with `Content-Range` instead of an
   unbounded 200 that may be cut off or substituted with an error page.

4. **ExoPlayer `NoDeclaredBrand` means the first bytes are not a valid MP4
   box** — Before chasing CDN auth or format issues, confirm the bytes ExoPlayer
   actually receives — a stream that has been partially consumed by an
   inspection helper produces exactly this error.

5. **Three bugs can stack; fix them one at a time and re-test** — Each of the
   three bugs was independently sufficient to break playback. Fixing only one
   moved the error to the next layer.

### Files Modified
- `app/src/main/java/com/fongmi/android/tv/server/process/Proxy.java`
- `app/src/main/java/com/fongmi/android/tv/cloud/opendrive/QuarkOpenDriveProvider.java`
- `app/src/main/java/com/fongmi/android/tv/player/engine/ExoPlayerEngine.java`
- `app/src/main/java/com/fongmi/android/tv/player/exo/MediaSourceFactory.java`

## AList / OpenList REST bridge for OpenDrive (2026-09-20)

### Trigger and source of evidence
The catvod-plugin-lab corpus (`tools/catvod-plugin-lab`) showed five "coverage gap"
families whose plugins all speak the **AList generic REST API**:
`/api/public/settings`, `/api/fs/list`, `/api/fs/get`, `/api/public/path`,
`/api/auth/login`. 115 / PikPak / Thunder(迅雷) / Tianyi(189) are mounted *behind*
an AList/OpenList instance, so a single native AList bridge covers all four.

Evidence is **not** taken from CatVod plugin inference. It uses two admissible sources:

- **Readable source already in the repo**: `app/src/main/java/com/fongmi/android/tv/
  viewmodel/drive/AlistDriveViewModel.kt` already implements and validates the exact
  AList protocol the bridge reuses — `detectAlistVersion()` (v2 vs v3 from
  `/api/public/settings` data shape), `listVersion2()` (`/api/public/path`, v2), and
  `listVersion3()` (`/api/fs/list`, v3). That file is the authoritative request/response
  contract.
- **Version-pinned public AList API**: `AlistGo/alist@843d9dc8149126976b2625911e45a4d3ffd6f2f5`
  (already cited at the top of this file for UC), whose `/api/fs/get` returns
  `data.raw_url` / `data.url` for the direct download URL, and `/api/auth/login`
  returns `data.token`.

### What changed
`WebDavOpenDriveProvider` (the "OpenList / WebDAV" connector) now feature-detects
whether a connector points at an AList/OpenList instance:

1. `GET {base}/api/public/settings` — `code == 200` with `data` object ⇒ v3,
   `data` array ⇒ v2; a 401 with stored credentials triggers `/api/auth/login`
   to obtain a session `token` (cached per connector id).
2. When detected, listing uses `/api/fs/list` (v3, body `path/password/page/
   per_page/refresh`) or `/api/public/path` (v2, `path/password/page_num/page_size`),
   and playback resolution uses `/api/fs/get` (`raw_url`/`url`). The `token` is
   attached as `Authorization` on listing/get/resolve when the instance enforces auth.
3. When the probe fails, the connector keeps using the existing Sardine WebDAV path
   (no behaviour change for plain WebDAV servers).

Items carry an `alist.<sha>` handle; folders recurse through the AList path, files
resolve to a direct playback URL with an `Authorization` header when authed.

### Scope limits (deliberate)
- **115 standalone is NOT implemented.** `docs/opendrive-115-review.md` requires a
  115-issued Open Platform application ID (device-code PKCE); that credential was not
  available, so only the AList-mount route is added. A user who mounts 115 inside their
  own OpenList instance reaches it through this bridge.
- AList instances must be reachable over HTTPS for the OpenDrive HTTP client (same
  trust posture as the other built-in providers); self-hosted LAN HTTP instances fall
  back to the WebDAV path. v3 is the primary path; v2 listing is supported, v2
  playback (signed `/d` path) is not yet wired.
- No proxy/range layer was added for AList; playback returns the direct URL like the
  WebDAV path, so server-side range support depends on the AList backend.

### Files Modified
- `app/src/main/java/com/fongmi/android/tv/cloud/opendrive/WebDavOpenDriveProvider.java`
- `app/src/test/java/com/fongmi/android/tv/cloud/opendrive/WebDavOpenDriveProviderTest.java`

### Verification
- `:app:testLeanbackArm64_v8aVerificationUnitTest --tests
  "com.fongmi.android.tv.cloud.opendrive.WebDavOpenDriveProviderTest"` — green,
  including `listsAndResolvesAnAListV3Instance` and
  `authenticatesAnAListInstanceWhenSettingsAreBlocked`.
- Full `com.fongmi.android.tv.cloud.opendrive.*` unit suite — green, no regression.
- Not device-verified against a live AList/OpenList instance.
