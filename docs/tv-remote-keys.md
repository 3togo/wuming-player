# TV remote keys

The leanback app accepts standard Android key codes from HDMI-CEC, IR and Bluetooth remotes. A TCL TV must forward a button to the box before the app can handle it; TV-owned Home, volume and streaming-app keys are not intercepted.

## During playback

| Button | Action |
| --- | --- |
| 0–9 / numeric keypad | In live playback with panels closed, enter a channel number (up to four digits). Tune after two seconds without another digit. |
| OK after digits | Tune immediately. |
| Back after digits | Cancel the pending number without leaving playback. |
| Arrows / OK / Back | Existing navigation; live OK opens the channel list and video OK toggles playback. |
| Menu / red | Playback controls. |
| Captions / green | Subtitle settings. |
| Audio track / yellow | Audio track selection. |
| Info / blue | Playback information. |
| Play / Pause | Explicit play or pause, rather than toggling either command. |
| Play/Pause | Toggle playback. |
| Rewind / Fast forward | Seek backward / forward where the stream permits seeking. |
| Next / Previous | Next / previous episode during full-screen on-demand playback; existing channel navigation during live playback. |

Playback shortcuts apply in the live and cast players and in full-screen on-demand playback. Number keys outside live playback retain their existing behavior, including text entry. Colour shortcuts are the defaults. Custom playback mappings can be saved in Settings → Test remote buttons. Phone key handling is unchanged.

Both press and release of shortcut keys are consumed, with one action on a non-cancelled release. Holding OK in live playback opens controls without activating another control on release. Pending channel selection callbacks are removed when the live activity is destroyed.

## Verification

`TvRemoteKeysInstrumentedTest` checks shortcut consumption, repeats, cancelled releases, mixed top-row/numpad digits, OK confirmation, Back cancellation and long-OK release handling. This tests Android key interpretation, not whether a particular physical TCL remote forwards every button.

### 2026-09-06 validation status

- Regular ARMv7 TV verification app and instrumentation APK built successfully; TV lite Java compilation and the leanback remote checker passed.
- An update to `com.wuming.android.tv.verification` was rejected because the installed signing certificate differs. The existing app and data were preserved.
- `com.wuming.android.tv.verification.remotekeys` was initially installed at the user-authorized `192.0.2.10:5555`. That address subsequently became unreachable; no instrumentation results were obtained.
- The user supplied and authorized `192.0.2.10:5555`. The new remote-test build updated the `.verification.remotekeys` package successfully with `adb install -r`. Its visible label is `無名(testing)`. Keep this package installed: the user requested it for use, superseding the earlier temporary-package cleanup plan.
- On-device smoke check: the app opened after a restart, the Settings entry was reached with D-pad Down and opened with OK, and the test screen captured an ADB-injected `KEYCODE_1` (Android code 8), including `ACTION_DOWN`, `ACTION_UP`, repeat 0 and input device `Virtual`. Focus moved to assignment, and D-pad Up returned to the test button.
- Physical TCL remote forwarding and the full instrumentation suites remain unverified. An ADB-injected key is not a physical HDMI-CEC test. No custom assignments were saved during the smoke check.

## Test remote buttons and custom mappings

Open **Settings → Test remote buttons** (below video capability checks).

1. Select **Test next button**, then press and release a button on the TV remote.
2. The screen reports the raw Android key name/code, input-device name, source, press/release and repeat events. Back cancels capture; ten seconds without a complete press ends the test with a retry message.
3. Select **Assign captured button**, then choose a playback action or a live-channel digit. Choose **Use original button action** to remove that override.
4. **Reset all mappings** restores defaults after confirmation.

Assignments persist across app restarts and apply to every remote in this app. They are used in live/cast playback and full-screen on-demand playback; Settings and the diagnostic screen always receive original keys. Mapping is a single translation, so mapping F1 → 1 and 1 → Menu still makes F1 produce 1. Navigation, OK, Back, volume and power cannot be reassigned.

Input-device names and sources are diagnostic hints, not proof of the TV brand or HDMI-CEC connection. This screen cannot detect buttons handled entirely by the TV. It does not request privileged HDMI permissions.

`TvRemoteMappingsInstrumentedTest` covers persistence, reset, reserved keys, preserved event metadata, raw capture and D-pad/Back navigation. The suites were compiled but not executed. The limited on-device smoke check is recorded above.

## Lessons learned

### CEC support, connection and received keys are separate checks

The earlier `dumpsys hdmi_control` output showed `mHdmiControlEnabled: true`, a CEC-capable port, and received TV messages. Those observations establish that CEC communication existed at that time; they do not identify every forwarded button or guarantee that the TV is currently connected. The reported local CEC device `Q601B` identifies the box, not the connected TCL TV.

Map standard Android `KeyEvent` values without requiring a TV-brand profile. A regular app cannot rely on privileged HDMI control APIs to discover the TV. The remote-test screen reports input-device hints without claiming they prove a brand, connection or CEC transport. Capture raw keys before mapping, and distinguish Android key codes from the numbers printed on buttons: button 1 was `KEYCODE_1`, integer 8.

### Preserve navigation and key-event semantics

Apply custom mappings only in playback. Settings, dialogs and the capture screen must remain navigable with original keys. Protect navigation, OK, Back, volume and power from reassignment. Consume both edges of handled shortcuts, execute actions once on a non-cancelled release, and preserve repeat count, source, device ID, timing and flags when translating events. Translate once rather than recursively chaining assignments.

Capture needs an exit even when the TV forwards nothing: Back cancels, a ten-second timeout offers retry, and a late release of a timed-out held key must not activate the focused control. Remove capture callbacks when the screen stops. Handle both key-based Back and the activity Back callback. `KeyEvent.actionToString` was unavailable in the public SDK used here; explicit action labels compiled successfully.

### Check signing identity before attempting an update

A package name and version are not sufficient for `adb install -r`: the signing key must also match. Both the configured signing key and the available local Android debug key differed from the older installed verification app. Do not uninstall that app merely to bypass the mismatch; that can erase its private data.

The separately identified `.verification.remotekeys` package provided an updateable testing installation while preserving the normal app and older verification app. The same visible testing label can exist on more than one verification package, so verify the exact package ID and launch that package explicitly during diagnosis. Keep the normal launcher label `無名` distinct from `無名(testing)` and label an instrumentation runner `無名(test runner)`.

The extra package suffix was supplied by a temporary Gradle init script, not a permanent change to the normal application ID. Recheck APK output metadata, certificate and label before installation; another build can overwrite the APK path with a different suffix. Do not install a test runner unless it is needed, and explain its purpose first.

### Treat a network ADB address as session-specific

The box moved from `.118` to `.59`. Use only the endpoint explicitly authorized by the user and put `adb -s SERIAL` on device operations. A stale ADB `device` state is not proof that shell commands will complete. After a bounded reconnect fails, ask for the current address instead of repeatedly retrying or selecting another connected device. Do not kill the shared ADB server to recover one transport.

### Verify foreground state and fresh UI evidence after launch

A vendor advertising overlay covered the first app launch. The crash log then showed `DLNARendererService.start` throwing `IllegalStateException` because the app was in the background. Restarting opened the app successfully. This smoke check did not establish a fix for that lifecycle issue; review it separately and do not attribute it to remote-key mapping without evidence.

`uiautomator dump` once returned `null root node` during restart while the previous XML file remained on the device. Check the dump result before using the pulled XML; a stale file can falsely suggest the crash screen is still visible. A subsequent successful dump showed the app home screen and allowed navigation to the new Settings entry.

### Report the scope of validation precisely

A successful build or static focus checker is not a device test. The completed smoke check proves installation, opening the screen, D-pad access and capture of a simulated number key on the box. It does not prove physical TCL button forwarding, mapping persistence after restart, every playback action, or a successful instrumentation run. Keep those remaining checks explicit.
