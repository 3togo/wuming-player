# 红果 (`csp_HongGuo`) on-device validation — blocked

Status: **DEFERRED** (documented 2026-09-08, not yet fixed). Testing is blocked on obtaining a
valid 红果 vod id from outside the app; the infra itself is validated.

## What we wanted to prove

`GuardedSourceActivatorHongGuoRealDeviceTest.desktopRoutesRealHongGuoDetailAndPlayToDevice`
(`desktop/src/test/kotlin/com/fongmi/tv/desktop/jvmspider/GuardedSourceActivatorHongGuoRealDeviceTest.kt`)
should route `detail` + `play` of a real 红果 drama through the GMS relay to device `7e34269d`
and assert episodes + a non-empty play URL come back.

## What is VALIDATED (infra works)

Running the test on `7e34269d` (serial authorized for WebView + GMS hardware tests) confirms the
whole plumbing reaches the device:

- `activateAll` → GMS tunnel `ready` (`AdbAndroidGmsProviderBridge`).
- `op=init`, `op=home`, `op=detail` all reach the device child (`GuardedCatVodHostProcess` logs).
- `dexArtifact` d8 cache hit; provider `com.github.catvod.spider.HongGuo` loads on-device.

So the GMS relay + guarded-catvod-host + device spider loader are all fine. The test does **not**
fail on infra — it fails on the **vod id**.

## The blocker (root cause)

The test cannot auto-discover a valid 红果 vod id:

1. **Real 红果 App share URLs are a different id space.** Two distinct real-红果-App share codes were
   tried and both NPE'd in the device spider:
   - `https://novelquickapp.com/s/Mf_wlDm4668/` → `Mf_wlDm4668` (drama 小邪爱空姐之欢喜对头误撞心动)
   - `https://novelquickapp.com/s/z51SB6uJbFA/` → `z51SB6uJbFA` (drama 废物翻译精通八国语言)
   - Device error: `NullPointerException: Cannot invoke "com.github.catvod.spider.merge.Ap.N.p()"
     because the return value of "com.github.catvod.spider.merge.Ap.N.n(String)" is null`
     (`GuardedCatVodHostProcess.request`, `op=detail`).
   - These short-link drama codes are NOT maccms vod ids; the `csp_HongGuo` spider can't resolve them.
     **Do not feed real-红果-App share links to this test.**

2. **Harness browse returns empty because it is not GMS-routed.** `home` / `category` / `search`
   on the device returned 0 items (`rawLen=11` / empty). In the real 无名的 app these ops are
   GMS-backed, but the test harness only routes `detail`/`play` through `AdbAndroidGmsProviderBridge`;
   `home`/`category`/`search` hit the device helper directly without GMS, so 红果 lists nothing and
   `deviceHomeIds()` harvests no id.

3. **The reliable id source is 无名的' own `detail` logcat**, but the user was mistakenly capturing
   from the real 红果 App (screenshot showed the "红果短剧 · 免费短剧，尽在红果" footer — wrong app).
   The `key=csp_HongGuo,id=<id>` line comes only from 无名的 (`SiteApi.detailContent`), not the real app.

## Fix options (pick one when we unblock)

- **A (preferred, no code change):** In 无名的 on `7e34269d`, open the 红果 drama detail, then:
  ```bash
  adb -s 7e34269d logcat -c
  adb -s 7e34269d logcat -s detail -d | grep csp_HongGuo
  ```
  Paste the `key=csp_HongGuo,id=<id>,...` line. The test already parses it via `-Dtvbox.hongguo.vodId=<line>`.
- **B (harness change):** Route `home`/`category`/`search` through the GMS relay too, so
  `deviceHomeIds()` can auto-harvest a real 红果 id (no manual capture). Larger change; only if the
  user can't surface 红果 in 无名的 either.

## Already-fixed test bugs (so we don't rediscover them)

- `resolveVodId` trailing-slash bug: `/s/Mf_wlDm4668/` used to fall back to the whole URL as the id.
  Fixed to take the last **non-empty** path segment.
- `desktop/build.gradle` `test {}` did not forward `tvbox.hongguo.vodId` / `tvbox.hongguo.jar` into the
  forked test JVM, so `System.getProperty("tvbox.hongguo.vodId")` was null (assertNotNull failure at
  line 74). Added both keys to the `test {}` forwarding list.

## Open question

Whether 红果 `detail`/`play` actually **work** on-device is still UNCONFIRMED — we never got a valid id
through, so the device spider never executed a real detail parse. The NPE could be (a) wrong id, or
(b) a genuine provider bug surfaced by GMS-device parsing. Confirm only after option A/B yields a real id.
