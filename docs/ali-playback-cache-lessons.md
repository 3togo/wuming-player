# Ali original, smart, and account-refresh regression lessons

Recorded 2026-09-10. Keep original and smart source labels and request contracts separate.

## Measured contracts

- The old `v2/file/get_share_link_download_url` returned empty HTTP 410 responses.
- [Alibaba's PDS download contract](https://help.aliyun.com/en/pds/drive-and-photo-service-dev/developer-reference/api-pds-2022-03-01-getdownloadurl)
  accepts `share_id` and `x-share-token`, but the consumer Ali endpoint returned HTTP 403
  `Forbidden` for the external sample with the phone's account. An anonymous call returned
  HTTP 401. Documentation alone was insufficient to call the replacement fixed.
- Saving the exact external `LG_X86.mp4` into the account using `adrive/v4/batch`
  (`/file/copy`), then calling `v2/file/get_download_url` with the **destination** file ID
  and account drive ID succeeded. Its media response was HTTP 206 with original bytes.
  The disposable diagnostic folder's trash request returned HTTP 202 afterward.
- The save body was verified against the official public web client bundle at
  `https://g.alicdn.com/aliyun-drive-fe/aliyun-drive/6.2.3-share/share/bundle.js`.
- Shared smart remains `get_share_link_video_preview_play_info`, accepting `preview_url`
  as well as `url` and ranking SD/HD/FHD below the 1080p ceiling. It does not save a copy
  or silently substitute original when a preview is unavailable.

## Original-copy boundary

`AliPanOpenDriveProvider.resolve()` first tries the documented original download API.
Only HTTP 403 on a **shared original** enters `AliSharedOriginalCache`. Personal failures,
smart failures, network errors, and other status codes do not enter this copy path.
The returned media is the copied original, not an HLS preview relabeled as original.

Only `/file/copy` receives the share token. Personal folder, download, and trash requests
must omit `x-share-token`. Carrying it into folder creation caused
`InvalidParameterNotMatch.ShareId`; do not repeat that diagnostic setup mistake.

Copies are auto-renamed without overwrite inside `TVBoxCache` and recorded in the host
cache database with a hash of the destination drive identity and source identity. Reuse
tracked copies on repeated resolution. Cleanup must:

1. Match the current account, provider, and the internal original-copy ownership marker.
2. Verify the exact tracked file is still inside its recorded parent before trashing it.
3. Leave unrelated files, moved copies, cache folders, and the global recycle bin alone.
4. Reclaim expired 15-minute leases when a new original is selected, without a timer
   deleting an ongoing stream. Limit tracked active copies to ten; refuse another save
   rather than delete protected copies when all leases remain active.
5. Preserve the local record if a remote deletion fails. A failed save must never be
   recorded as successful merely because an ID appears in the error body.

Ali file trash returns **HTTP 204 No Content**. The original JSON-only parser reported
invalid JSON after the file was already deleted, leaving its local row active. Ali returned HTTP 403 for the deleted destination on the next play, so reuse
now verifies the recorded parent listing before trusting the row. The Ali
trash transport now explicitly accepts 204; ordinary listing/playback still reject empty
200 or invalid JSON, and error statuses still fail. The live harness reports cleanup
separately and fails the invocation on cleanup errors instead of swallowing them.

Trashing is not permanent deletion or proof of reclaimed provider quota. No whole-bin
purge or smart substitution is added to conceal a full account. Manual app-cache cleanup
uses the same tracked-file boundary. The live harness removes only copies it created.

## Expired credentials

The share listing/resolver formerly used `loadToken()` and bypassed the personal path's
refresh. The device reported `AccessTokenInvalid`; listing the personal account refreshed
it and restored smart playback. Shared requests now refresh expired credentials and retry
an account-authenticated HTTP 401 once. HTTP 403/410 and a second 401 are not auth retries.

Persist an absolute `expire_time` after refreshing. Reconstructing `expires_in` relative
to every process start made an old credential appear fresh again. A stored relative-only
expiry is treated as unknown and refreshed. Do not log or version credentials to test this:
the offline tests inject synthetic credentials and an in-memory store.

## Regression locations

- `AliPanOpenDriveProviderTest`: separate routes, measured preview fields, shared refresh,
  bounded rejected-token retry, absolute expiry persistence, and original-copy dispatch.
- `AliSharedOriginalCacheTest`: exact copy/download bodies, reuse, lease limits, ownership,
  moved/untracked/different-account files, and failed-copy handling.
- `OpenDriveLiveProviderInstrumentedTest`: independent route results before cleanup;
  initial media bytes and HLS media segments, not decoded-frame or full-video verification.
- Public links and dated attempts remain under `test-fixtures/cloud-drive/`.

## Original playback stall — measured 2026-09-11 on 7e34269d

The original `LG_X86.mp4` is 17,206,710 bytes and 42,867 ms long: about
401,392 bytes/s (392 KiB/s) average media demand. Resolution, HTTP 206 bytes,
and a decoded first frame succeeded, but the player remained BUFFERING with
position 0 and buffered position 1,429 ms after a 15-second observation.
Smart independently decoded 1080p and advanced normally.

Bounded raw reads of the **same resolved original**, retaining its media headers:

| Probe | Bytes read | Total time | Provider QoS delay header |
| --- | ---: | ---: | ---: |
| Clean client, HTTP/1.1, 256 KiB range | 262,144 | 8,071 ms | 7,831 ms |
| App client, HTTP/1.1, 64 KiB range | 65,536 | 2,434 ms | 1,957 ms |
| HTTP/2 permitted, 256 KiB range | 262,144 | 8,070 ms | 7,831 ms |
| Open-ended range, bounded to first 256 KiB read | 262,144 | 15,940 ms | 514,890 ms |

The HTTP/2-permitted request negotiated HTTP/1.1, so it is **not** evidence about
actual HTTP/2 performance. Another 256 KiB request exceeded the diagnostic's
8-second read timeout. The clean client uses no app interceptors or cookie jar.
No signed traffic-limit query value was reported.

[Alibaba's OSS documentation](https://help.aliyun.com/en/oss/user-guide/limits)
defines `x-oss-qos-delay-time` as provider throttling duration in milliseconds
(estimated for downloads). This directly establishes throttling at the media
service, roughly 33 KB/s per observed single stream; it is not merely an inference
from a slow player. The open-ended response's estimate is about 8.6 minutes for
this 42.9-second clip. These measurements do **not** establish whether Ali applies
the restriction because of account entitlement, the selected download service,
or another provider policy. Do not claim a membership upgrade is the fix.

The app compounds this constraint: the Ali profile opens four approximately
1 MiB ranges concurrently but has `prefetchRanges=false`, reading their bodies
in order. Opening multiple connections alone is not concurrent body downloading.
Changing selection to the personal URL after the saved-original fallback did not
remove the observed throttling.

Controlled scheduling experiments used the identical original URL and headers:

- Four buffered 256 KiB ranges hit the 6-second first-byte timeout, fell back to
  sequential, and read only 785,690 bytes in 36,023 ms before the diagnostic failed.
- Sixteen buffered 128 KiB ranges encountered HTTP 503 during rolling prefetch,
  fell back to sequential, and took 22,835 ms to read 1 MiB.
- Eight buffered 64 KiB ranges read 1 MiB in 10,270 ms (about 100 KiB/s including
  startup), still below this clip's average demand.
- Sixteen buffered 64 KiB ranges again encountered HTTP 503 and failed after
  1,047,868 bytes in 33,817 ms. The response did not yield an XML error code;
  concurrency limiting is a hypothesis, not a proven meaning of that 503.

Do not ship an unverified high-concurrency change or weaken the decoded-progress
assertion to call this fixed. No production range policy changed during this
investigation. Smart remains separate; silently substituting it would conceal the
original failure. Remaining work is to establish an original-download contract
that supplies adequate throughput, then verify sustained decoded playback.

Reproducible opt-in instrumentation arguments on the existing bundled-share test:
`fixture_provider=alipan`, `fixture_route=original`, `fixture_transport_probe=true`,
`fixture_range_compare=true`. These probes report raw transfer results and **do not
count as decoded playback tests** (`fixture_decode=true` is a separate check).
Latest evidence is in `app/build/reports/live-cloud-smoke/7e34269d/`:
`ali-transport-probe.txt`, `ali-range-comparison.txt`, and
`ali-small-range-comparison.txt`. Diagnostics export timings/status only, not
account credentials or signed media URLs. Cleanup retains the existing exact-copy
ownership boundary.

## Explicit no-proxy comparison — 2026-09-11

At the user's request, compared the same signed original URL and media headers
with the app's normal client and with `Proxy.NO_PROXY` forced. Each used a fresh
connection pool; an OkHttp connection listener recorded the actual proxy type.
The media URL was remote (`localMediaRelay=false`), not the app's localhost relay.
Android global `http_proxy` and `global_http_proxy_host` settings were both unset.

| 256 KiB HTTP/1.1 range | Actual route | Total time | QoS delay |
| --- | --- | ---: | ---: |
| Normal app client | DIRECT | 8,411 ms | 7,831 ms |
| Forced `Proxy.NO_PROXY` | DIRECT | 8,505 ms | 7,831 ms |

Both returned HTTP 206 and all 262,144 requested bytes. A separate actual decoder
run forced `Proxy.NO_PROXY` for both provider API clients and PlayerManager's
media client. It recorded **19 direct connection acquisitions, zero proxied**.
The first frame rendered, but the playhead again stayed at 0 ms with only 1,429 ms
buffered after the 15-second observation. The test correctly failed.

Therefore bypassing app/HTTP proxy selection does not fix the reproduced stall.
This does not establish anything about transparent routing outside the phone
(such as a router-level VPN). Device or network proxy settings were not changed;
the test-only client replacements were restored on exit. The runner rebuilt
successfully offline. Evidence: `ali-proxy-ab.txt` and `ali-no-proxy-decode.txt`
in the existing device report directory.

For the raw A/B probe use `fixture_proxy_compare=true` on the bundled-share test.
For decoded verification use `aliOriginalWithoutProxyCanDecode` with
`fixture_provider=alipan`, `fixture_route=original`, `fixture_no_proxy=true`,
and `fixture_decode=true`. The scope is diagnostic-only and does not change the
production downloader or the original/smart routing contract.

## Gradual connection ramp — 2026-09-11

At the user's request, tested the **same resolved original URL** with 1, 2, 4, 6,
8, 10, then 12 direct HTTP/1.1 connections. Each level used two synchronized
rounds of disjoint 128 KiB ranges, unchanged media headers, a fresh connection
pool, no automatic connection retries, and no app parallel-range wrapper. Recorded
aggregate delivered bytes every two seconds, actual peak acquired connections,
HTTP responses, and mean QoS delay. Aggregate rates include setup and range waits.

| Concurrent connections | Aggregate KiB/s | Completed requests | Result |
| ---: | ---: | ---: | --- |
| 1 | 30.1 | 2/2 | HTTP 206 |
| 2 | 58.4 | 4/4 | HTTP 206 |
| 4 | 116.8 | 8/8 | HTTP 206 |
| 6 | 157.2 | 12/12 | HTTP 206 |
| 8 | 199.4 | 16/16 | HTTP 206 |
| 10 | 232.2 | 20/20 | HTTP 206 |
| 12 | No completed media | 0/24 planned | HTTP 503 in first round; stopped |
| 1, after five-second pause | 30.1 | 2/2 | HTTP 206 |

Peak acquired connections matched each requested level. All successful ramp
levels reported the same 3,915 ms mean `x-oss-qos-delay-time` for 128 KiB requests.
At the first rejection, remaining requests were cancelled and the second round
was not started; the failed-request count includes these cancellations. No
Retry-After header was reported. The planned 16-connection stage was not run.

Interpretation:

- Useful total throughput rises with concurrency through ten in this run; there
  is no measured additional per-request slowdown at those levels.
- Twelve triggered a rejection during this sequence. This supports backing off
  on overload, but does not prove a universal 10-connection limit: concurrency,
  recent request rate, and service load have not been isolated from each other.
- The original one-connection rate returned after five seconds on the same link.
  There is no evidence of a persistent link penalty in this short recheck; this
  does not establish long-term account/provider behavior.
- Best measured healthy throughput (232 KiB/s) remains below the clip's average
  demand (392 KiB/s). This is transfer evidence, not a successful playback test.
- Any production adaptation must measure **aggregate delivered bytes per wall
  time**, increase in bounded steps only while improving, back off on 429/503 or
  deterioration, respect Retry-After, and count all active media requests including
  prefetch and recovery. A nominal worker count is not sufficient if old response
  bodies remain open. Do not hard-code this experiment's threshold for all users.

The probe is opt-in with `fixture_connection_ramp=true` on the bundled-share test
and `fixture_provider=alipan`, `fixture_route=original`. Evidence is
`app/build/reports/live-cloud-smoke/7e34269d/ali-connection-ramp.txt` (including
2-second samples) and its extracted `.json` summary. Runner build and diff check
passed. No production connection policy was changed.


## Production adaptive downloader — implemented 2026-09-11

The app now uses `AliAdaptiveRangeDataSource` for Ali original playback, with
`AdaptiveRangeConcurrency` in playback-core. It starts at one actual range reader,
uses 128 KiB ranges, measures two full batches per level, and steps 1 → 2 → 4 →
6 … within a 16-connection ceiling. Measurements are aggregate bytes per wall
time, not the sum of nominal per-thread speeds. Plateau rolls back to the last
proven level; deterioration lowers concurrency. A rejected ceiling is retained
for the session, avoiding repeated probes into the same rejection.

No future batch or hedge overlaps the active batch. Every response body closes
before another batch starts. On 429/503, active workers are cancelled and drained,
concurrency drops, and retry waits at least five seconds or the server's larger
Retry-After. At most two local recovery attempts occur per batch. Cancellation
interrupts backoff and in-flight calls; exact Content-Range, ordering, tail reads,
and EOF seeks are validated. New playback starts conservatively; extractor seeks
retain session measurements; network changes reset them. Original/smart route
contracts and tracked-copy cleanup remain separate.

Five portable policy regressions and three device regressions pass. Mobile and
leanback arm64 builds pass offline. Real app playback demonstrated the full ramp
to 12, real HTTP 503, backoff to 10, and continued 190–204 KB/s batch transfers.
The first 15-second startup observation still failed at position 0. A separate
90-second observation reached 22.786 seconds, below the requested 30-second
progress threshold, and correctly failed. Smart playback advanced 4 seconds and
passed. **Implementation is verified; smooth original playback is not fixed.**
The source remains below the clip's approximately 401 KB/s average demand.

Evidence: `ali-adaptive-regressions.txt`, `ali-adaptive-live.txt`,
`ali-adaptive-long-live.txt`, `ali-adaptive-decisions.txt` under the device report
directory. Reference/API distinctions and reviewed revisions are in
[the bounded reference review](reference-reviews/2026-09-11-ali-download-ramp.md).
