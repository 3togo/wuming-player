# SMOKE-002 — Real provider and Leanback remote-input smoke

Date: 2026-09-25 (Asia/Shanghai). Authorized device: `7e34269d`, model
`22021211RC`, Android API 34, ARM64. This is a phone running the Leanback
verification flavor, not physical TV hardware. Normal app data was not cleared.

Latest extended run: **16/16 instrumentation methods and 4/4 launch checks passed**.
Pet Club TV rendered at **848×480**, advanced **15,059 ms**, and rendered **376**
additional frames during the observation window. The preceding connected run
stalled and remains a failure in the ledger; its cause is not established by the
later successful run.

## SMOKE-002.1 — Reproducible selection

```sh
python3 scripts/run-smoke-tests.py --tier production --mode leanback \
  --abi arm64_v8a --serial 7e34269d --offline \
  --tv-navigation --live-channel 'Pet Club TV'
```

The final selection contains 16 methods: 11 standard production-smoke methods,
four remote-navigation/startup methods, and one public-provider playback method.
The test uses the bundled Pet Club TV URL through the real VideoActivity direct-URL
route, with the production player/service and an actual display surface. It requires
rendered decoder frames, nonzero video dimensions, 15 seconds of observation,
10 seconds of advancing playback, at least 60 additional rendered frames, no
player/UI error, and no eight-second stall. There is no local-media substitution.

Remote coverage includes D-pad movement from Settings cache to route and back,
OK activation, stateful focus decoration, leaving text fields, reaching dynamic
dialog buttons, OK action execution and Back dismissal. The pre-existing form
helper test uses dialog key dispatch; the dynamic dialog and new Settings test
inject keys through Android instrumentation/ViewRootImpl.

## SMOKE-002.2 — Wallpaper startup crash

The first hardware attempt reproduced a null dereference in `WallConfig.getDesc`
when Settings opened before wallpaper initialization. Kotlin property access read
the inherited nullable field directly rather than calling BaseConfig's lazy getter.
The fix calls `getConfig()` explicitly and converts nullable Java URL/description
values to empty strings. The focused instrumentation regression clears the
in-memory field, reads both properties on the main thread and restores it afterward.

## SMOKE-002.3 — Direct playback lost its URL

The third attempt passed all 15 non-provider methods, but playback displayed
“播放地址加载失败” and rendered no video within 60 seconds. Source inspection showed
the unconfigured `push_agent` branch constructing a Result without setting the
supplied media URL before passing it to the extractor. The fix initializes the
Result URL first. The live test now also asserts a nonempty resolved URL before
launch and fails immediately on visible playback errors.

## SMOKE-002.4 — Attempt ledger

| Attempt | Evidence | Result |
| --- | --- | --- |
| 1 | [Summary](../../PUBLIC_PREPARATION.md) | 10 methods passed; Settings crash; later groups not run |
| 2 | [Summary](../../PUBLIC_PREPARATION.md) | 13 methods passed; wallpaper null URL and incorrect new test assumption that focus used a foreground drawable; provider not run |
| 3 | [Summary](../../PUBLIC_PREPARATION.md) | 15 methods passed; direct playback URL defect prevented decoding |
| 4 | [Summary](../../PUBLIC_PREPARATION.md) | 15 methods passed; direct URL preserved; no network available for provider playback |
| 5 | [Summary](../../PUBLIC_PREPARATION.md) | 15 methods passed; live video reached the initial decoded-frame condition, then failed the eight-second stall bound |
| 6 | [Summary](../../PUBLIC_PREPARATION.md) / [decoded samples](../../PUBLIC_PREPARATION.md) | **16/16 passed**, plus initial/cold-start/background-return/restart checks **4/4 passed** |

Attempt 2's focus assertion was corrected to inspect the actual stateful background
selector, which provides the focus outline. No production focus behavior was
weakened or bypassed. All six attempts restored the saved power value `15` and
reported no cleanup errors. The initial test build also failed because the new
class referenced a package-private launch helper; it was corrected before attempt 1.

## SMOKE-002.5 — Device connectivity prerequisite

Attempt 4 passed the 15 non-provider methods and the nonempty direct-URL assertion,
but the decoder returned `UnknownHostException (no network)`. Inspection confirmed
`wifi_on=0`, `airplane_mode_on=1`, and no active default network. Wi-Fi was temporarily
enabled for the next explicit attempt, which acquired a default network; airplane
mode remained enabled. [Network restoration record](../../PUBLIC_PREPARATION.md).
The playback test now rejects a missing active network as an explicit failure.
Attempt 5 with connectivity reached the decoded-frame condition but stalled during
the observation window. It remains a failed result, not a playback pass. A host-side
endpoint probe returned HTTP 200; that does not establish device segment throughput.
For attempt 6, the test additionally records dimensions, frame counts, positions,
player state and buffered duration throughout observation, without weakening the
pass thresholds. [Attempt 6 network restoration](../../PUBLIC_PREPARATION.md).

## SMOKE-002.6 — Evidence boundaries

A phone's injected D-pad events are not OEM TV remote certification. This public
stream test does not cover cloud accounts, saved-copy cleanup, VOD provider JARs,
DRM, geographic availability of every channel, audio output quality, or a signed
release build. No account login or cloud-save experiment is included. The channel
may create ordinary playback history in the isolated verification application.

## SMOKE-002.7 — Local validation

- Full offline `unitAcceptanceBaseline`, mobile verification app/runner assembly,
  and Leanback verification lint passed together in 2m 28s (477 tasks). This includes
  **98 Python tests** and all architecture boundaries.
- The final instrumentation diagnostic logging compiled in the Leanback device run
  and in a subsequent mobile app/runner build (4 seconds).
- Leanback static check passed: 125 layouts, 291 explicit focus targets, 26 action
  contracts, 156 listener checks and three app-label locales.
- Build/runtime results apply to the uncommitted working tree; APK SHA-256 values
  are retained in each device summary, not inferred from the baseline commit.

## SMOKE-002.8 — Mobile restoration and final device state

Restored the updated mobile verification app and matching runner using `install -r`.
The [mobile restoration smoke](../../PUBLIC_PREPARATION.md)
passed **11/11 methods** and **4/4 launch checks**. Final read-back confirms Wi-Fi off
(`0`), airplane mode on (`1`), and original keep-awake value `15`; see
[settings evidence](../../PUBLIC_PREPARATION.md).
The verification process was stopped and the device returned Home. No uninstall,
app-data clear, provider-account login, cloud-save operation or release publication
was performed.
