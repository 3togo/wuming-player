# 夸克智／夸父智：畫質調查與教訓

Recorded 2026-09-06. This is a resumable investigation record, not a claim that middle-quality playback is fixed.

## September 10 external-fixture 404 diagnosis

The external tutorial's original test passed, deleted its new disposable copy, then the
separate smart test failed with HTTP 404/API 21001. Save tracing showed the same task
identity hash `8b612617` and destination hash `40ff6ef7` in all three saves: the original
save, the smart save, and the immediate replacement save. Later tasks reported status 2
on their very first poll. The server replayed the completed task for the deleted copy.
A bounded same-destination wait did not repair this and was removed, not shipped as a fix.

The paired harness now records original and smart independently while retaining the
same disposable copy until both finish. An original failure still allows smart to run.
Both Quark routes then passed in one invocation, including an actual smart HLS segment.
Do not restore per-route immediate deletion in the default matrix or alter quality ranks
to hide this cleanup-induced 404. Separate invocations immediately after deleting a copy
can still encounter the provider's recent-task replay; keep that diagnostic limit visible.

## September 10 smart playback resolution

The user's later instruction to fix smart playback supersedes the earlier rule that an
API failure must never use original playback. Preserve this narrow fallback:

- Normal smart responses still select the best accessible rendition at or below 1080p,
  including low quality when it is the only playable rendition.
- Only HTTP 400 / API 21005 (`not video`) falls back to the normalized original. That
  combination was measured for the PNG-wrapped `E01.mkv`; Quark cannot transcode it,
  while the normalized original produced real 3840×2160 hardware-decoded frames.
- Entitlement failures, missing renditions, `plf_invalid`, and generic API failures must
  not be broadened into original fallback without new evidence.
- The UI displays that it is playing the normalized original. The internal notice header
  is consumed by `PlaybackTransportPlan` and must never reach Quark or the media host.

Device replay on `AUTHORIZED_PHONE_SERIAL` confirmed that `夸父智1` enters the original proxy
instead of showing the fatal `not video` error, and displays the localized fallback notice.
The connection was fluctuating between 0 and roughly 453 KB/s and remained buffering in
that replay. Earlier direct-original verification established first-frame decoding; this
smart replay verifies routing, not smooth playback under insufficient bandwidth.

## September 10 final device verification

- On authorized phone `AUTHORIZED_PHONE_SERIAL`, all seven `VideoCapabilitiesInstrumentedTest`
  tests passed. The actual `hvc1.2.4.L153.90` profile is supported at 4K/30 but reported
  unsupported at 4K/60. Hardware size/profile support exists; the rate exceeds the
  advertised performance envelope.
- Allowing Media3's standard `FORMAT_EXCEEDS_CAPABILITIES` attempt produced real
  hardware-decoded 3840×2160 H.265 playback of `早春晴朗 臻彩`, E01, `夸父原1`.
  Logcat initialized `c2.qti.hevc.decoder`; the persistent trace recorded video size,
  first frame, then READY. The image progressed from the permit card to the animated
  opening before the route was changed. The trace also recorded rebuffering after roughly seven seconds in READY.
  This verifies decoding/playback, not smooth 60 fps or full-episode stability. Evidence is in `screenshots/quark-playback-20260910/`.
- The fallback is now enabled for normal builds as well. The selector continues to
  reject unsupported type/subtype/DRM and respects strict policy when explicitly set.
  It prefers supported tracks and only attempts an exceeding track if necessary.
- Smart still cannot transcode this source: the provider lists `E01.mkv` as `image/png`
  and `/file/v2/play` returns HTTP 400 / 21005 / `not video`. The app now distinguishes
  that cause in its localized error message. It neither silently substitutes original
  4K for smart nor rejects a valid low-resolution transcode.
- Earlier paragraphs describing the fallback as uninstalled/debug-only or the runner
  installation as pending are chronological records superseded by this verification.

## September 10 follow-up (save lifecycle fixed; title playback still unavailable)

ADB logcat from phone `AUTHORIZED_PHONE_SERIAL` on September 10 showed:

- Original: `CapabilityTrackSelector` rejected HEVC Main10, 3840×2160 at approximately 60 fps with renderer support `3` (exceeds capabilities), before decoding.
- Smart: HTTP 400 / API 21005 returned no stream. Other attempts on both routes returned HTTP 404 / API 21001, followed by failed deletion requests.
- Source review found `awaitSavedFid` accepted a destination ID before checking task completion. It now waits for status 2 and honors failed/quota statuses even when an ID is present. The logs do not prove this explains every 404; safe task-status tracing was added for replay verification.
- A smart-unavailable failure now retains the shared saved copy for original-route reuse. Normal cache/quota reclamation still applies. This supersedes the unconditional smart-failure cleanup behavior described below; other resolution failures can still remove temporary copies.
- Smart quality bounds and the accepted low-resolution fallback are unchanged. No claim of middle-quality availability or successful original decoding follows from these changes.
- Validation: all 32 `QuarkOpenDriveProviderTest` unit tests passed; mobile debug APK built and installed over the existing app with its data preserved. Android rejected the separate instrumentation-runner install, so no runner tests succeeded in this follow-up.
- Replay fixture: `早春晴朗 臻彩`, episode E01, `夸父原1` / `夸父智1`. The updated app recorded save status 0 with no destination, then status 2 with one destination before requesting smart playback. Smart still displayed “no available 1080p or lower transcode”; switching routes reused the saved copy without a new save task. Original buffered without verified decoded playback. These lifecycle repairs do not establish that this title now plays.


## September 10 continuation: exact root cause and pending decoder experiment

A read-only request using the app's existing Quark session found the surviving fixture
`E01.mkv`, size 5,555,276,689 bytes, classified by Quark as `image/png`. The play API
returned HTTP 400, code 21005, message `not video`. This distinguishes this fixture's
failure from entitlement or absent middle renditions: the cloud does not recognize
this disguised file as video. No credentials or signed URLs were printed or saved.
The authenticated official web page supplied `cloud-drive-web/4.6.5/index.js`; that
client still calls `/file/v2/play` (supports `fmp4,m3u8`).

The phone's vendor HEVC decoder declaration advertises a 3840×2160 performance point
of 30 fps. A **debug-only, unverified** experiment now permits Media3's standard
`FORMAT_EXCEEDS_CAPABILITIES` fallback; unsupported types/subtypes/DRM remain rejected,
and release builds retain the strict policy. Persistent first-frame/size/error diagnostics
and exact-profile instrumentation checks were added. The phone was locked and Android
rejected the update, so the experiment was not installed or tested. Do not adopt it for
release without actual decoded playback. The separate runner installation also remains
pending user response. Original source playback and smart playback remain unresolved.

## Current state and user decision

The user explicitly chose **low resolution over no playback** after the middle-quality investigation failed. Preserve that working fallback when resuming this challenge.

- Smart flags (`夸父智`, `夸克智`, `quark智`) map to `auto`.
- Auto prefers the largest accessible, valid transcode at or below 1920×1080, using actual dimensions when provided. Without dimensions, eligible labels rank `super > high > normal > low`.
- A playable low stream is accepted when middle tiers are unavailable. Do not silently replace it with the original 4K file.
- An API failure that supplies no stream is different from a response containing only a low stream. The fallback cannot manufacture a missing URL. Auto still reports unavailable on the handled transcode API failures instead of forcing original playback.
- The temporary experiment that rejected low quality and displayed an error was **not a fix** and has been superseded.

The restored-fallback build passed all 29 `QuarkOpenDriveProviderTest` tests and was installed successfully on phone `AUTHORIZED_PHONE_SERIAL` on September 6, preserving app data. Low 480×270 playback had been observed before the rejection experiment; **post-install playback of the final fallback build was not separately verified**. No middle-quality success was demonstrated.

## Evidence: what we actually know

| Observation / experiment | Result | Limit of the conclusion |
| --- | --- | --- |
| Current PC playback API on a demonstrated video | `4k`, `super`, `high`: `accessable=false`, no playable URL; `low`: accessible, 480×270 | Changing selection order cannot select URLs absent from the response. |
| Earlier actual playback | Smart: 480×270 H.264; original: 3840×2160 H.265 | Original download and cloud transcoding are different capabilities. |
| App-session membership probe | `__pus` and `__puus` stored and sent; `member_type=NORMAL` | Does not prove the user's account lacks membership. User confirmed higher quality works in the official app. Session/client entitlement mismatch remains unresolved. |
| Request `super` alone / `high` alone | Inaccessible, no URL | Requesting these individually did not unlock them on that fixture. |
| Request `normal` alone | HTTP 400, API 21111 | Not evidence that every video lacks intermediate quality. |
| Combined request with `supports` omitted | Only low accessible | Removing that field did not fix the tested case. |
| Later same-file comparison after caching an original-route file | Current endpoint: HTTP 400 / 21005; OpenList project endpoint: HTTP 400 / 14018 | Neither returned a stream for this file. Do not assume what 14018 means without evidence. |
| Later original-route playback of 爱情有烟火, ending on 原2 episode 01 | Decoder reported 3840×2160 HEVC, 60 fps, exceeding capability | Retrieving an original URL does not prove this device can decode every original variant. |

Fixtures changed during the session. Do not merge results from different titles, episodes, or route variants into a single controlled experiment. In the final comparison both requests did use the **same cached fid**, but it was not the earlier low-only fixture.

## Mistakes to avoid next time

1. **Measure before changing ranking.** Separate API entitlement/availability, selection policy, media retrieval, and decoder capability. Log safe response metadata first; a selector change only helps when the desired stream exists.
2. **Do not swing between extremes.** “Highest available” can choose unplayable UHD; forcing low sacrifices quality; refusing low leaves no playback. Keep the user's accepted low fallback while investigating a better stream separately.
3. **Do not blame membership or request relogin from one enum.** Compare the official app and 無名 on the same account and file. Credentials were not cleared in this investigation; preserve them.
4. **Account for cleanup before designing a probe.** `QuarkOpenDriveProvider.resolve()` calls `cleanup(savedFid)` when smart resolution throws. Selecting 智 and then looking for its cached file repeatedly produced an empty `TVBoxCache`. This was a diagnostic setup error, not an API finding. Resolve an original route first, or design a bounded probe that compares both requests before cleanup; confirm the fixture exists before proceeding.
5. **A passing diagnostic is not successful playback.** The comparison test can finish successfully after recording HTTP errors. Require an accessible URL, successful media retrieval, and actual decoded middle-resolution playback before saying “fixed.”
6. **Do not overinterpret logging absence.** Logcat omitted some completed-run messages. The probe now returns metadata through instrumentation `sendStatus`, as well as `Log.i`.
7. **Avoid speculative endpoint permutations.** Browse implementation sources, reproduce a documented request faithfully, and test one meaningful difference. A published implementation can itself be broken.

## Source-backed alternatives already examined

The current production request is `POST https://drive-pc.quark.cn/1/clouddrive/file/v2/play?pr=ucpro&fr=pc`, with `resolutions=normal,low,high,super,2k,4k` and `supports=fmp4`.

[OpenList's Quark implementation](https://github.com/OpenListTeam/OpenList/blob/main/drivers/quark_uc/util.go), together with its [client configuration](https://github.com/OpenListTeam/OpenList/blob/main/drivers/quark_uc/meta.go), uses `drive.quark.cn`, `/file/v2/play/project`, `resolutions=low,normal,high,super,2k,4k`, and `supports=fmp4_av,m3u8,dolby_vision`. The diagnostic reproduced its UA and Referer. **Do not reuse production `NetworkTransport.postTranscode` unchanged for a different host:** it hardcodes `Host: drive-pc.quark.cn`.

The project request failed in our same-file test. There are also public [OpenList reports of `plf_invalid`](https://github.com/orgs/OpenListTeam/discussions/2111); those reports do not establish the meaning of our different numeric error code.

[OpenList's Quark TV driver](https://github.com/OpenListTeam/OpenList/blob/main/drivers/quark_uc_tv/util.go) has a distinct token-authenticated `/file?method=streaming` path requesting several resolutions. It requires a separate OAuth/token flow; existing web cookies are not a drop-in credential. **Not implemented or tested here.** Investigating a protocol called “TV” does not authorize connecting to the user's physical TV.

[Quark Open's Link implementation](https://github.com/OpenListTeam/OpenList/blob/main/drivers/quark_open/driver.go) uses `get_download_url`; this alone is not evidence of an intermediate-transcode solution. These are source snapshots reviewed during the investigation; recheck upstream changes before using them.

An authenticated GET of `pan.quark.cn/list` returned HTTP 200, but the diagnostic's narrow absolute `g.alicdn.com/...js` matcher found zero assets. This does **not** mean there are no scripts or that official playback behavior was inspected. Actual official-app/web-player request comparison remains unfinished.

## Efficient restart procedure

1. Read current `AGENTS.md` for device restrictions. Only phone serial `AUTHORIZED_PHONE_SERIAL` was authorized in this session; physical TV access remained prohibited. Always specify the serial. Check availability and unlock state before scheduling an install.
2. Preserve the accepted low fallback. Pick one title, episode, route and account; record them with an ADB screenshot. Verify the official app's selected quality and actual decoded dimensions for that same file if possible.
3. Establish a surviving cached fixture. A failed smart attempt deletes its temporary copy. Confirm a file exists before comparing requests; handle matching cache folders rather than arbitrarily selecting the last one.
4. Run the existing opt-in diagnostic below. Keep credentials, signed media URLs, share tokens and full account responses out of logs. Record HTTP/API codes, accessible flags, dimensions and URL presence. Do not reset credentials or bulk-delete cloud files to make tests convenient.
5. If middle URLs remain absent, investigate the official client's actual request/session differences or the separately authenticated streaming protocol. Do not spend another round adjusting ranks or assuming “no alternatives exist.”
6. Only adopt a new request after it produces a real middle stream that the device plays. Then add regression coverage for both the improved path and the accepted low fallback.

### Code and commands

- Production: `app/src/main/java/com/wuming/android/tv/cloud/opendrive/QuarkOpenDriveProvider.java` (`requestPlaybackUrl`, `selectTranscode`, `resolve`, `cleanup`).
- Failure handling: `QuarkSmartQualityException.java`, `ui/base/BaseVideoActivity.java`, localized `error_quark_smart_quality_unavailable` strings. The exception's internal message still says “middle-quality”; its matching is an internal sentinel, not proof of what was returned.
- Tests: `app/src/test/java/com/wuming/android/tv/cloud/opendrive/QuarkOpenDriveProviderTest.java`.
- Diagnostics: `app/src/androidTest/java/com/wuming/android/tv/cloud/opendrive/QuarkSessionInstrumentedTest.java`.

```bash
./gradlew :app:testMobileArm64_v8aVerificationUnitTest --tests com.wuming.android.tv.cloud.opendrive.QuarkOpenDriveProviderTest :app:assembleMobileArm64_v8aDebug --offline
./gradlew -PstatefulDeviceTests :app:assembleMobileArm64_v8aDebugAndroidTest --offline
```

The normal APK is under `app/build/outputs/apk/mobileArm64_v8a/debug/`; obtain its current name from `output-metadata.json`, not a historical commit suffix. Install with `adb -s AUTHORIZED_PHONE_SERIAL install -r <apk>` to preserve data. The runner APK is `app/build/outputs/apk/androidTest/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug-androidTest.apk`. Explain that it is the separate **無名(test runner)** package before installation. A verification application, if used, must instead have the distinct launcher label **無名(testing)**.

```bash
adb -s AUTHORIZED_PHONE_SERIAL shell am instrument -w -r -e class com.wuming.android.tv.cloud.opendrive.QuarkSessionInstrumentedTest#probePublishedProjectRequest -e run_quark_project_probe true com.wuming.android.tv.test/androidx.test.runner.AndroidJUnitRunner
```

Other opt-in methods: `reportPlaybackSessionMembership` with `run_quark_session_probe=true`; `probeMiddleQualityRequests` with `run_quark_quality_probe=true`. Avoid a Gradle connected-test workflow that reinstalls/resets the account-bearing app; use the manual stateful runner. Remove only `com.wuming.android.tv.test` when finished, never the normal package to clean up a test.

Historical evidence is in `screenshots/quark-resolution-20260905/`, including `smart-error-episode10.png` and `findings.txt`. The findings file is chronological and contains superseded intermediate states; **this document's current-state section records the final accepted decision**. Do not depend on `/tmp` artifacts surviving.
