# DLNA QR pairing

> Package rename note (2026-09-25): historical `com.fongmi` commands and results
> below retain their original identity. Current app/test packages use `com.wuming`;
> use the current smoke runner and explicit device authorization for new tests.

On the TV, open **Settings → DLNA pairing** (`设置 → DLNA 扫码配对`).
Keep the phone and TV on the same Wi-Fi. Scan the TV QR code using the phone
camera, open the link in its browser, and tap **Pair this phone**. Return to the
phone's casting app and select this TV. No custom phone app is required for this
flow. The existing 無名 scanner is for the separate remote-control pairing flow.

The TV shows the paired phone's local IP address. **New QR code** replaces the
pending code without revoking the current phone. Each code expires after five
minutes, is usable once, and stops working when the pairing screen is left.
Pairing a new phone replaces the previous phone. **Disconnect phone** revokes
future DLNA control requests; it does not stop an already playing video.

DLNA access expires two hours after pairing, when the renderer service stops,
or when the TV app process restarts. Re-pair if the phone's IP changes. Access
is not saved to preferences or backups.

## Access enforcement

- The TV's UPnP HTTP transport rejects unpaired requests with HTTP 403 before
  handing them to the UPnP dispatcher. This covers SOAP playback/volume commands
  and event subscriptions. GET/HEAD discovery descriptions remain available.
- The peer is taken from the actual TCP socket, never `X-Forwarded-For` or other
  caller-supplied address headers. Phone-side UPnP callbacks are unchanged.
- `/dlna-pair` requires POST with a random 256-bit, single-use QR secret in
  `X-Pairing-Code`. Merely opening a page or discovering the TV cannot pair.
- The QR secret is carried in a URL fragment, removed from browser history on
  page load, and never embedded in the static pairing page. Pairing responses
  use `Cache-Control: no-store`. The browser page has no external dependencies.
- The separate remote-control `/pair` route now also requires a one-use QR
  secret instead of issuing credentials to anyone during an open time window.
  Updated web and mobile clients exchange that secret; previously paired
  remote-control tokens remain valid until revoked through the remote QR page.
  Press OK on the remote QR to refresh; long press revokes remote tokens.

This is temporary LAN IP authorization for compatibility with ordinary DLNA
senders, not cryptographic phone identity. A reassigned/shared IP can inherit
remaining access; on-path attackers on an untrusted network are outside this
protection. Use a trusted Wi-Fi network and revoke access when finished. The
DLNA grant does not give the browser configuration/file-management access.

## Local verification (2026-09-06)

- Leanback, leanback_lite, and mobile Java compilation succeeded offline.
- JVM tests cover invalid/expired/replayed codes, replacement/revocation,
  HTTP method requirements, forged forwarding headers, socket-level dispatch
  rejection, discovery, and unchanged mobile transport behavior.
- `node scripts/test-dlna-pairing-web.cjs` passed with local DOM/fetch doubles.
- `node --check app/src/main/assets/js/script.js` passed.
- `python3 scripts/check-leanback-remote.py` passed.

No device was connected, installed, or tested. Real camera/browser scanning,
rendered TV focus/navigation, and casting with a phone app remain unverified
on hardware, per the user's explicit prohibition while video is playing.

## Authorized TV verification (2026-09-06)

The user later authorized `192.0.2.10:5555` (Q601B, Android 10,
armeabi-v7a, 1920×1080). Installed isolated package
`com.fongmi.android.tv.verification.dlnapairing` (`無名(testing)`) and its
`無名(test runner)` instrumentation package. The normal app was not updated.

Device testing found a LAN HTTP delay: a page request took 20.28 seconds over
Wi-Fi and 0.08 seconds through an ADB localhost tunnel. NanoHTTPD performed a
reverse-DNS lookup in its session constructor. The custom client handler now
supplies a numeric hostname while preserving the real socket IP. Settings now
focuses the DLNA pairing entry at the top when opened.

The final `DlnaPairingInstrumentedTest` passed (`OK (1 test)`, 10.088 seconds):
rendered QR decoding, page HTTP 200, unpaired SOAP 403, valid claim 200,
replay 403, paired SOAP 200, D-pad disconnect restoring 403, Up navigation,
and Back closing the pending code. The input test uses UiAutomation so events
pass through the actual Android window focus/navigation pipeline.

This confirms the TV receiver and remote navigation. It does not independently
identify or certify a particular physical phone's historical pairing attempt.

## Editable discovery name

Open **Settings → DLNA pairing → Edit DLNA display name**. The suggested and
initial default is `_私人电视-非公共投屏设备-请勿连接或投屏-正在使用中`.
Save stores a receiver-only preference; Cancel leaves it unchanged. Restore
default fills the default into the editor, and Save applies it. Blank input
uses the default. Control characters are stripped and names are bounded.

The new discovery name takes effect on the next TV app/renderer start. Saving
does not restart playback, change Android's device name, rename the launcher,
or revoke pairing. Phone casting lists may cache the old name until refreshed.
The text is a warning to other users, not an access control mechanism; QR
pairing remains the protection against unpaired DLNA commands.

## Lessons learned

1. A QR screen alone cannot secure casting. Enforce pairing at the actual UPnP
   transport before any playback, volume, or subscription command is dispatched.
2. Never issue a pairing credential merely because the pairing screen is open.
   Require the random secret displayed on-screen, with expiry and one-use claims.
3. Name the intended scanner explicitly. This QR opens a browser pairing page
   with the phone camera; developer mapping/remote-control scanners can expect
   a different payload and report an incorrect format.
4. Test the real LAN path. The localhost tunnel hid a 20-second reverse-DNS
   stall. Use numeric socket peers without trusting forwarding headers.
5. Put the entry where TV users will see it. A top-of-settings entry was easy
   to miss when focus and scrolling started elsewhere; explicitly focus it.
6. Android Activity.dispatchKeyEvent bypasses ViewRoot's focus navigation.
   Device tests must wait for the activity's window focus and inject through
   UiAutomation, and must distinguish harness failures from product failures.
7. Keep verification APKs and their runner visibly separate from the normal
   app. Respect the exact authorized ADB serial and retain the final test logs.
8. Distinguish IP-based LAN access from cryptographic device identity, and
   device test results from a particular user's historical phone connection.

### Pre-push verification for the name editor

Exported the exact staged Git tree to a separate directory, excluding unrelated
working edits. Offline leanback and mobile compilation, Android test-runner
compilation, and 19 focused JVM tests passed. Browser pairing checks and the
working-tree TV focus checker also passed. The name editor itself was checked
locally; the earlier Q601B run verifies the pairing receiver and remote flow,
not an on-device run of the newly added editor.
