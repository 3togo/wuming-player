# Android instrumentation: avoid repeating the background-freeze investigation

## Android 14 tablet: visible activity can retain a stopped package (2026-10-07)

On the authorized iPlay tablet (`AUTHORIZED_TABLET_SERIAL`), after force-stopping
`com.wuming.android.tv.verification` to update its Bilibili cookie preferences,
direct `adb shell am start -n ...HomeActivity` launches displayed the UI but left
the package's User 0 state at `stopped=true`. Reinstalling the same APK with `-r`
and an explicit MAIN/LAUNCHER `am start` did not clear that state. Provider binds
failed repeatedly with Android's `process is bad` message. The exit history
recorded force-stop/package-update exits, not a provider crash; that message alone
does not establish repeated crashes.

Check `dumpsys package` and `dumpsys activity exit-info` before diagnosing the
provider. Opening the verification app's Android App info page and selecting
**Open** cleared `stopped` to `false` and started its `:provider` process without
clearing data, changing permissions, or replacing credentials. Reopening the same
Bilibili history item then logged `Authenticated qualities=80,64,32,16`, decoded
1920×1080, and reached `isPlaying=true`. The media session confirmed PLAYING at
speed 1.0 with no error. Its five Bilibili session cookies had been copied with
explicit user authorization from the normal app; other accounts were preserved.

Use the normal launcher/App info Open path when this measured stopped-state
failure occurs. Do not repeatedly rebind or reinstall and infer a new provider
crash from each failed bind.

> Package rename note (2026-09-25): historical `com.fongmi` commands and results
> below retain their original identity. Current app/test packages use `com.wuming`;
> use the current smoke runner and explicit device authorization for new tests.

Recorded 2026-09-11 after testing on an authorized Vivo Android 15 ARM64 phone.
Read this before device instrumentation. Device authorization still comes from
the current task; an old serial or wireless-debugging port is not blanket permission.

## What failed

The 20-test optimization selection repeatedly passed 19 tests and then appeared
to hang in QuickJS HTTP ownership testing. That HTTP test passed alone in 1.492 s.
The combined run stopped logging after 11 synchronous replies. A 120-second
in-process JUnit timeout did not report while the process was suspended.

Android's ordinary freezer field was `isFrozen=false`, but the same process had
separate Vivo `isFrozen=true` / `allowFreeze=true` fields. Keeping the screen awake
through ADB did not by itself keep the instrumentation process running. Bringing
the verification app to the foreground restored progress, including a previously
unresponsive debugger connection. Do not diagnose a native leak or deadlock from
the last test name alone, or conclude the process is active from only one freezer
field.

We also introduced an ineffective workaround: opening `MemoryTestActivity` from
QuickJS class setup. The synchronous ActivityScenario version hung inside
`Instrumentation.startActivitySync` while the main thread was idle. A subsequent
asynchronous version failed its resumed-lifecycle deadline. Both hooks were
removed. Do not reintroduce them as a verified fix or lengthen timeouts indefinitely.

## Verified procedure

1. Use the explicitly authorized serial on **every** ADB command. Verify ABI and
   choose the matching mobile/leanback testing build. Explain that `無名(testing)`
   is the app and `無名(test runner)` is its separate instrumentation runner.
2. Install with `adb -s "$test_serial" install -r "$apk"`. Preserve application
   data. Do not uninstall, clear data, or silently replace signing identity to
   overcome installation errors. Vivo may require its on-screen installation
   confirmation; inspect it promptly rather than leaving ADB waiting indefinitely.
   `INSTALL_FAILED_ABORTED` is an installation failure, not a test failure.
3. Save `settings get global stay_on_while_plugged_in`. During the run, use
   `svc power stayon true` and periodically send `input keyevent KEYCODE_WAKEUP`.
   Stay-on-while-plugged-in alone does not cover an unplugged phone.
4. Keep the **verification app** foregrounded too. The successful mobile run sent
   wake events and this command every five seconds while instrumentation ran:

   ```sh
   adb -s "$test_serial" shell am start -n \
     com.fongmi.android.tv.verification/com.fongmi.android.tv.ui.activity.HomeActivity
   ```

   Start the foreground loop immediately after instrumentation starts. This was
   verified for the non-UI correctness selection below; repeatedly foregrounding
   HomeActivity can interfere with UI tests and must not be copied into those runs
   without adapting the active activity. HomeActivity may start ordinary background
   requests, so this setup is not a clean performance benchmark.
5. Use the explicit method selection and a host-side deadline. A timeout inside
   a frozen process cannot reliably protect the host from an indefinite wait.
   On expiry, save results, stop only the authorized testing process, and report
   incomplete execution. An operator stop producing “Process crashed” is not proof
   of a spontaneous crash. If debugging, resume threads, detach and remove only
   the ADB forward created for that session.
6. Validate the exact finished method set, per-test statuses and final runner
   result. ADB exit code zero is insufficient. Do not count a class-setup failure
   as its tests passing, or combine isolated passes into a claimed successful
   single run. Keep failed attempts in the evidence record.
7. Stop the wake/foreground loop and restore the saved power value in cleanup,
   including failure paths. Return to the launcher when done. Do not disable OEM
   battery management globally or leave the device permanently awake.

The user explicitly requested keeping the phone awake through ADB. Do that
proactively within authorized testing; do not make the user repeatedly unlock
the phone while waiting on stalled tests.

## Result and limits

With ADB wake/foreground control and the original 20 methods, **all 20 passed in
one run in 10.324 seconds**, with zero failures/skips and no debugger attached.
The original power setting (`0`) was restored. The runner retains the conversion
ownership regression, HTTP progress markers and its 120-second timeout, but no
activity-host hooks.

This validates the selected SAX, range, danmaku and QuickJS correctness cases on
that phone. It does not prove Android performance improvement, native heap savings,
TV behavior, real-provider throughput or casting media compatibility. Treat the
observed process freezing as a test-environment finding, not a general diagnosis
of every possible future stall.

- [Exact 20-test selection](optimization/android-runtime-tests.json)
- [Successful raw output](../PUBLIC_PREPARATION.md)
- [Checked method/result summary](../PUBLIC_PREPARATION.md)
- [Full investigation and failed attempts](optimization/android-runtime-validation.md)

## Android 9 TV runner compatibility (2026-09-16)

On the Skyworth/Coocaa Android 9 TV, installing the instrumentation APK initially
returned `INSTALL_FAILED_USER_RESTRICTED`. The crash buffer showed the OEM's
`AdbInstallActivity` throwing `NullPointerException: pkgInfo.versionName must not
be null` while handling its installation dialog. The runner manifest lacked version
metadata. Adding `android:versionCode="1"` and `android:versionName="1.0"` to
`app/src/androidTest/AndroidManifest.xml` allowed the ordinary `adb install -r`
flow to succeed after selecting Continue installation in the TV prompt. No system
installation policy was disabled. Inspect installer crash evidence before assuming
this error always means a persistent permission restriction.

`UiAutomation.adoptShellPermissionIdentity` and its matching drop call require
API 29. `TestActivityLauncher` now guards them on Android 9 and older; the existing
foreground launch remains in place. Keep this guard when changing the shared test
launcher. The SDK's local `data/api-versions.xml` confirms the API 29 introduction.

The dynamic-dialog navigation test originally called `Dialog.dispatchKeyEvent`
on the main thread. It failed to traverse the native button row on this TV.
Inject directional keys through `Instrumentation.sendKeyDownUpSync` from the test
thread, then wait for idle; ViewRootImpl performs the normal focus search. Keep
assertions for every button and Back dismissal. After this correction, all 26
selected TV tests passed in one run. See
[TV stabilization](migration/tv-stabilization-20260916.md) for both attempts.
