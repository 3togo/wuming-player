# Leanback filter lifecycle test hardening (2026-11-15)

Follow-up to 3ebc164 ("Keep movie and TV filters visible across category
loading"). The regression test added there passed offline and on warm devices
but failed intermittently on cold instrumentation starts of the Q601B Android
10 TV box (192.168.123.94:5555), and the failure signatures changed between
runs.

## Failure evidence (Q601B, logcat + in-test state dumps)

1. `expected:<1> but was:<7>` — the firmware's keep-alive path relaunches the
   app's launcher `HomeActivity` (shell-uid `am start`) seconds after a
   force-stop or reinstall. Its home load runs `FilterCache.clear().put(real)`
   inside the same process as the instrumentation and overwrote the fixture's
   "movie" entry with the real site's seven filters.
2. `expected:<1> but was:<0>` with `mFilters=0` while the fixture entry was
   verified present immediately before the child fragment read it — the same
   keep-alive load clearing the cache inside the read window.
3. `shortMsg=Process crashed` — the vendor ActivityManager SIGKILLs the target
   process during instrumentation activity gaps (`app not allow running
   background`), independently of the test logic.

## Changes

- Fixture-specific type ids (`filter-fixture-movie` / `filter-fixture-tv`) so
  real configuration cannot collide with the fixture in the global cache.
- Reseed `FilterCache` immediately before each transaction that can create the
  child fragment view (the child reads the cache once in `initView`; the
  parent reaching RESUMED can create it before `executePendingTransactions`).
- Finish any live keep-alive `HomeActivity` at test start, at each seed and at
  each assertion checkpoint; its `onDestroy` removes the LiveData observers
  that own the cache write, so a finished instance cannot touch the cache.
- Assertion failures now carry the child state (`mFilters`, `filterVisible`,
  `typeFilterFlag`) and adapter contents for future diagnosis.

The device-workaround portion is the same category as
`ForegroundSmokeRunner.waitForActivitiesToComplete=false`: a documented
accommodation for restrictive firmware, not production behavior.

## Device configuration applied (re-apply after reboot/reflash)

Standard vendor-ROM mitigations for the test devices:

```
cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow
am set-standby-bucket <pkg> active
dumpsys deviceidle whitelist +<pkg>
```

for `com.wuming.android.tv.publicedition.verification`, its `.test` package and
the private-edition equivalents.

## Validation on 2026-11-15 UTC

- Environment: Linux host, JDK 21, Gradle 9.7.1; source revision 3ebc164 +
  this change; clean `gh repo clone` worktree.
- Build: `:app:assembleLeanbackArmeabi_v7aVerification` and
  `:app:assembleLeanbackArmeabi_v7aVerificationAndroidTest` with
  `-PwumingEphemeralSigning=true -PnativePythonEnabled=false`.
- Device: Q601B (192.168.123.94:5555, Android 10, armeabi-v7a), app APK sha256
  b4ce422ba6da1c3f650b39689efafea62682bc0d28e9726f000fae180d270f8b.
- Warm-process run: PASS — full lifecycle (early toggle before attach, lazy
  initialization restore, refresh keeps filters visible, double-toggle close,
  detach/attach recreation) for both fixture ids.
- Cold-process runs: PASS when the firmware allowed the process to survive;
  remaining cold-run failures on this box are exclusively the vendor SIGKILL in
  activity gaps, not test assertions. Repeated cold coverage on this class of
  firmware should go through the smoke runner's foreground handshake.
- Not covered: devices without the keep-alive firmware behavior were not
  available; the fixture isolation and reseeding are expected to be no-ops
  there.
