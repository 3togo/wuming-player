# TV migration stabilization — 2026-09-16

## Result

**All 26 selected instrumentation tests passed in one run**, with zero failures or
skips, in **43.215 seconds** on `192.0.2.10:5555`: Skyworth/Coocaa model
`7AA49_P3D`, Android 9/API 28, armeabi-v7a.

No production code was changed and no further Java classes were converted.
Bulk conversion remains paused. This completes focused TV validation of the
selected navigation, recreation and lifecycle behavior; it does not prove every
migrated class, physical remote model, playback provider or casting flow works.

## Changes needed to run reliable tests

- Added version code/name to the instrumentation manifest. The OEM installer
  crashed on its missing version name, initially returning
  `INSTALL_FAILED_USER_RESTRICTED`. Normal `adb install -r` succeeded after the
  metadata fix and the ordinary Continue installation prompt. No system policy
  was disabled.
- Guarded shell-permission identity adoption and release in `TestActivityLauncher`
  at API 29. The local Android SDK API history confirms these methods are absent
  on the Android 9 TV. Modern Android keeps the existing behavior.
- Corrected `dynamicDialogActionsReceiveFocusAndRemainReachable` to send keys
  through `Instrumentation.sendKeyDownUpSync` from the test thread. Direct dialog
  dispatch bypassed ViewRootImpl's directional focus navigation. The test still
  verifies dynamic-action activation, all three buttons being reachable, stateful
  focus decoration, and Back dismissal.

## Evidence and attempts

[Raw evidence](../../PUBLIC_PREPARATION.md) preserves the failed
installation and test run as well as the final successful run.

| Attempt | Outcome |
| --- | --- |
| Original runner installation | App installed; runner rejected; no tests started |
| Runner metadata and API compatibility corrected | 26 tests completed: 25 passed, one synthetic-navigation test failed |
| Navigation test uses real input | **26 passed in one run**, no failures/skips |

The final selection includes:

- Six shared view/resource lifecycle tests, including the packaged TextScale tag.
- WebView ownership and three dialog cleanup/cancellation tests.
- Leanback adapter observer teardown.
- Eight TV critical-flow checks: selected playback-source styling, settings focus,
  search/category/nested grids and recreation, refresh, and activity declaration.
- Three TV dialog tests: editor escape, dynamic actions/OK/Back, and focus decoration.
- Four remote-key/mapping tests, including raw-key capture and Back escape through
  Android's input pipeline.

See the [exact selection](../../PUBLIC_PREPARATION.md),
[raw successful output](../../PUBLIC_PREPARATION.md)
and [checked per-method results](../../PUBLIC_PREPARATION.md).
The reported method set was matched against the selected source methods, not just
the runner's exit code or test count.

Direct smoke checks also verified settings launch, D-pad focus on Player settings,
OK opening it, and Back returning to settings. The exploratory home-navigation
sequence lost its expected focus state, so it is not counted as a passing scenario.
A direct shell launch of the non-exported remote-test activity was denied as
expected; the final instrumentation run exercised that screen successfully.

## Validation and cleanup

- Leanback ARM32 verification app and runner builds passed.
- Mobile ARM64 runner build passed after the shared manifest/launcher changes.
- TV source navigation check passed: 121 layouts, 282 explicit focus targets,
  26 action contracts, 150 listener checks and three app-label locales.
- `git diff --check` passed.
- All builds used `-PchaquopyEnabled=false`.

Installed `無名(testing)` and `無名(test runner)` with replacement installs; no
uninstall or data clear. Used wake events without a repeated HomeActivity launch
loop during UI tests. Restored `stay_on_while_plugged_in` to its original value
`0` after every attempt, returned to the TV launcher/input, and removed the
session's temporary UI hierarchy file. Test preference changes use the existing
finally-block restoration; remote mapping tests use a separate test preference file.

## Next development policy

Resume ordinary feature and maintenance work. Convert remaining Java only when
that work supplies a concrete reason and suitable validation. These selected TV
and earlier phone passes support stabilization; they do not justify another bulk
conversion batch.
