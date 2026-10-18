# Offline cloud-drive streaming tests

The JVM provider tests protect request construction, response parsing, route selection, playback headers, and bounded fallbacks without contacting a cloud service or device.

## Route contracts

| Provider | Original route | Smart route |
| --- | --- | --- |
| Quark | Saved-file download and normalized original proxy | Saved-file transcode at or below 1080p; accepted low fallback; API 21005-only normalized-original fallback |
| UC / 优汐 | Signed share download without saving to personal cloud | Save and prefer the best returned transcode; quota-only signed share-download fallback |
| Aliyun Drive | Personal/shared `get_download_url`; a shared-original HTTP 403 saves the exact original to tracked cache and downloads that copy | Personal `get_video_preview_play_info` / shared `get_share_link_video_preview_play_info`; finished `url` or `preview_url` at or below 1080p |
| Baidu Netdisk | Original signed download with range hints | Signed preview playlist; observed errno 71-only original fallback |

Do not merge original and smart labels in test setup. A smart response without a playable rendition must not silently become original unless the provider has a documented, regression-tested narrow fallback.

## Test locations

- `QuarkOpenDriveProviderTest`: original download, smart rendition selection, low-quality behavior, API 21005 fallback, media classification, ranges, and headers.
- `UcOpenDriveProviderTest`: original/smart label routing, smart candidate selection, quota and cache boundaries, signed download headers, and legacy handle migration.
- `AliPanOpenDriveProviderTest`: injected resolver/credential transport, distinct download/preview endpoints, bounded token refresh, saved-original dispatch, smart selection and HLS hints.
- `AliSharedOriginalCacheTest`: exact source copies, account ownership, reuse/stale rows, protected leases, moved files, and failed-copy handling.
- `OpenDriveHttpTest`: bounded JSON bodies and explicitly allowed 204 trash responses.
- `BaiduOpenDriveProviderTest`: original/preview distinction, signed preview URL, VIP quality, errno 71 fallback boundary, transfer/cache limits, and range hints.

Run the focused matrix with:

```bash
./gradlew -PuseChinaMirrors=true \
  :app:testMobileArm64_v8aVerificationUnitTest \
  --tests com.wuming.android.tv.cloud.opendrive.UcOpenDriveProviderTest \
  --tests com.wuming.android.tv.cloud.opendrive.QuarkOpenDriveProviderTest \
  --tests com.wuming.android.tv.cloud.opendrive.AliPanOpenDriveProviderTest \
  --tests com.wuming.android.tv.cloud.opendrive.AliSharedOriginalCacheTest \
  --tests com.wuming.android.tv.cloud.opendrive.OpenDriveHttpTest \
  --tests com.wuming.android.tv.cloud.opendrive.BaiduOpenDriveProviderTest \
  --offline
```

These tests cannot prove that live credentials remain valid, a provider has kept its API contract, a signed CDN URL currently serves bytes, a particular file can be transcoded, or a device decoder can render it. Keep live contract and device playback verification separate from this deterministic suite.

## Public live fixtures

The four public share URLs are versioned in
`app/src/androidTest/assets/opendrive-public-shares.json`. They now use the verified
external candidates (UC extraction tutorial, Quark government training clip, Ali FFmpeg
sample, and Baidu health-education clip) from the [34-link catalog](../test-fixtures/cloud-drive/public-share-candidates.json).
All four original user-provided links remain in that catalog. The test selects exact
video filenames and bundles only links/metadata, not videos or credentials. The asset
is packaged in the separate test runner, not the normal application.

`OpenDriveLiveProviderInstrumentedTest#bundledPublicSharesCanStreamOriginalAndSmartRoutes`
requires all four provider accounts to be authenticated on the device. For each provider,
it lists the exact fixture video, resolves and reads bytes from the original route, creates
a distinct smart-route handle, resolves and reads that route, and removes only disposable
UC/Quark/Ali copies created by that test run. Use `-e fixture_provider uc` to run UC alone.

Prefer third-party shares for testing transfer routes: owner-account restrictions can
prevent saving one's own share. Do not substitute a personal-file handle for a rejected
share, because that would stop testing the shared-file contract. However, HTTP 403 alone
does not establish an ownership restriction. The original UC failure was also reproduced
on an unrelated share and traced to the obsolete query and mismatched token pair; see
`docs/uc-playback-cache-lessons.md`.

### Measured live results (2026-09-10)

- UC external fixture: original and smart both returned bytes; instrumentation passed
  (`OK (1 test)`) on `AUTHORIZED_PHONE_SERIAL`. This is not a full decoder playback test.
- Quark user-owned share: save returned API 41017. A personal-file diagnostic could read
  original bytes, but its two-second video returned API 21002 for smart. That diagnostic
  does not count as a passed shared-file fixture. An external university share probe timed out.
- Ali user-owned share: listing returned HTTP 401. An external FFmpeg sample
  (`https://example.com/public-share`, from
  [Alibaba's benchmark article](https://developer.aliyun.com/article/1289341)) listed,
  but resolution returned HTTP 410. The cause remains unverified.
- Baidu user-owned share: transfer returned errno 2. An external university-share probe
  timed out. Neither outcome proves an original/smart streaming pass.

The full four-provider live matrix is therefore not yet passing. Keep unsuccessful
external candidates out of the bundled asset until their exact video and both routes
are validated. None of these failures authorizes broadening production fallbacks.

### Later external-fixture audit

The [public candidate inventory and final audit](cloud-drive-public-sample-candidates.md#final-media-checks-2026-09-10)
supersede the earlier external-fixture timeout results above. All 34 public links, including
the four original user shares, are versioned in `test-fixtures/cloud-drive/public-share-candidates.json`.
Run them with `python3 scripts/test-cloud-public-shares.py --serial <authorized-serial>`.
The final checks obtained media bytes for both UC, Quark, and Baidu routes and an HLS
segment for Ali smart after refreshing its account; Ali original still returns HTTP 410.
Quark smart required a separate retry after HTTP 404/API 21001. This is not an eight-route
clean-run pass. The deterministic suite passed 108 tests.

The continuation fixed shared Ali account refresh and original-copy playback, handles
204 trash responses, and verifies a tracked copy still exists before reuse. The paired
harness retains copies until both routes are checked, reports per-route results, and
fails on cleanup errors. See [Ali regression lessons](ali-playback-cache-lessons.md) and
[Quark cleanup/task-replay diagnosis](quark-smart-quality-lessons.md) before changing it.
The Baidu errno-4 recovery requires a cached file matching the source MD5 and size.

The final paired device run passed all eight routes and UC/Quark/Ali harness cleanup;
125 offline tests passed. Earlier failures above remain historical evidence. See the
[final audit](../PUBLIC_PREPARATION.md).
