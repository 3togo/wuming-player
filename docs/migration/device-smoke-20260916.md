# Device smoke tests — 2026-09-16

Follow-up: [loader fix and completed device reruns](aliso-loader-fix-20260916.md).
The results below preserve the initial failed attempt.

**Initial overall: not passed.** The TV has a reproducible native startup crash; the phone requires its first pattern unlock after a restart.

Both devices were explicitly authorized for this smoke run. Built and installed the current working tree, including the Kotlin migration, as `無名(testing)` (`com.fongmi.android.tv.verification`) and its separate `無名(test runner)` package. All four APK installations succeeded with `adb install -r`. The normal application was not replaced; no application data was cleared or packages uninstalled.

| Device | Build | Result |
| --- | --- | --- |
| `7e34269d` — model 22021211RC, Android 14 | Mobile ARM64 verification | Installed. Secure first-unlock pattern screen prevented foreground launch; no instrumentation tests executed. User unlock requested. |
| `192.0.2.10:5555` — model 7AA49_P3D, Android 9 | Leanback ARM32 verification | Seven runtime tests passed. One of 13 UI tests passed before the process crashed; the remaining 12 did not complete. Standalone cold launch also failed. |

## TV results

The seven-test runtime run completed successfully with exact method/status matching and final instrumentation code `-1`:

- Verification-package isolation.
- Value parcel field order and activation byte.
- Filter nullable fields and independent value list.
- VOD result parcel restoration.
- Nested folder bundle restoration.
- Adaptive-window gain thresholds.
- Continuous reads across bounded ranges using an in-memory upstream fixture.

The UI run passed `nestedSearchResultsAlwaysUseMultiColumnTvGrid`. It then reported `Process crashed` while `settingsCloudShareRouteAcceptsRemoteFocusAndToggles` was active. This is an incomplete run, not 13 passing tests or an assertion failure for each unexecuted method.

Native crash evidence identifies `SIGABRT` on `TV-io-1` with:

```text
JNI DETECTED ERROR IN APPLICATION: can't call java.lang.Class
java.lang.ClassLoader.loadClass(java.lang.String) on null object
```

The stack includes the dynamically loaded provider's `Init.getSpider`, `BaseSpiderGuard` and `AlisoGuard` constructors, followed by `JarLoader.getSpider`, `Site.spider`, `SiteApi.homeContent` and `SiteViewModel.homeContent`.

A separate cold launch, without instrumentation, reproduced the same abort. The app initially appeared, then lost its process/foreground state during the observation window. Background-recovery and process-restart checks were not continued after that failure. This establishes a startup failure with the current configuration; it does **not** establish whether the Kotlin migration introduced it. Provider configuration and caches were left intact for diagnosis.

## Cleanup and limits

The wake loops stopped, testing processes were stopped, and devices returned to their launchers/lock screen. Saved `stay_on_while_plugged_in` values were restored and independently checked: phone `15`, TV `0`.

The phone needs an on-device pattern unlock before its pending seven runtime and four UI checks can run. TV UI/navigation validation remains incomplete pending diagnosis of the provider native crash. No real-provider playback or end-to-end DLNA playback success is claimed. No production code was changed during this smoke investigation.

Evidence, exact selections, APK SHA-256 hashes, per-method runner output and cleanup results: [measurement summary](../../PUBLIC_PREPARATION.md). [TV native crash log](../../PUBLIC_PREPARATION.md).
