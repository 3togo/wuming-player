# UC playback and cache regression lessons

Recorded 2026-09-10 after the user confirmed the fix. Read this before changing UC/`优汐` playback, save behavior, quota handling, or cache cleanup.

## Confirmed failure and fix

On authorized phone `AUTHORIZED_PHONE_SERIAL`, selecting `优汐智1` for episode 1 of `金特务：本色回归` was skipped. Logcat recorded:

```text
quota preflight required=2654207607 available=21635809 reclaimed=0
```

The account had about 21.6 MB available and could not save the 2.65 GB source. No disposable app cache remained to reclaim. The final smart-route fix catches `UcQuotaException` from the cloud-save path and resolves the signed share `file/download` URL directly. That endpoint does not need a saved personal-cloud copy, so playback can start even when UC cloud space is insufficient. The user confirmed that this fixed the bug.

Keep this fallback narrow. Only quota exhaustion may use direct share download. Other save failures can mean an expired share, invalid credentials, or another real error; hiding them behind the fallback makes diagnosis harder and can create incorrect playback behavior.

## Incorrect conclusions that caused repeated work

The first attempted fix assumed that the provider's 15-minute protected copy filled the account. It added same-source reuse and immediate cleanup of a previous selection. Those are useful safeguards, but a new trace after process restart still reported only 21.6 MB available and `reclaimed=0`. The cache-record audit also found no UC records. That evidence did not support the protected-copy explanation for this failure.

The next investigation found that interrupted or older app versions can leave more than one root-level `TVBoxCache` folder. Cleaning only the first matching folder can strand app-owned files, so cleanup now enumerates every matching folder. This is necessary defensive cleanup, but it cannot free space when all app-owned folders are already empty. Do not treat a cleanup change as a quota fix until logcat shows that it actually reclaimed enough bytes.

The repeated mistake was changing cache lifecycle code before separating these states:

- UC account free space reported by the quota API.
- Files currently present in every app-owned `TVBoxCache` folder.
- App-owned files in UC's recycle bin.
- Rows tracked by the local cache database.
- FIDs protected only in the current process while playback starts.

These sources can disagree after a restart, interrupted transfer, or an older app version. A zero count in one source does not prove the others are empty.

## Invariants that must survive refactors and reverts

1. Keep original and smart labels separate. UC/`优汐` original resolves the signed share download directly and must not create a personal-cloud copy. Smart uses the saved-file transcode path.
2. Preserve the smart route's `UcQuotaException` fallback in `resolve()`. It must call the signed share-download route so a full UC account does not cause an automatic source skip.
3. Do not broaden the smart fallback to generic save or playback errors. The quota type is the boundary that prevents bad shares and authentication failures from being hidden.
4. Never empty the user's whole UC recycle bin or delete arbitrary personal files. Automatic cleanup is limited to app-owned `TVBoxCache` folders, locally tracked FIDs, and their matching recycle-bin entries.
5. Preserve `savedSourceFids`. The player can resolve the same episode twice while opening it. Reusing the saved FID prevents previous-item cleanup from deleting the current file during startup.
6. Preserve cleanup of protected copies when a different source is selected. The 15-minute lease can block a small account, even though it was not the cause of the confirmed full-account failure.
7. Enumerate every root-level directory named `TVBoxCache`. First-match cleanup can leave old app-owned folders consuming quota.
8. Preserve share-download request behavior, including refreshed `__puus` cookies, media `Referer` and user agent, omission of a JSON `Content-Type` on the media GET, and expected-size/range hints used by playback.
9. Keep the regression comments beside mode selection, `savedSourceFids`, the protected-copy handoff in `save()`, duplicate-folder cleanup, and the quota catch in `resolve()`. They explain non-obvious ordering and safety rules and should not be removed as redundant commentary.

## Investigation procedure

Capture a fresh log for the exact title, episode, and route before changing code. Search for `quota preflight`, `cache reclaimed`, `cache handoff cleanup`, `quota fallback`, `share-download fallback`, and the final player error. Record `required`, `available`, and `reclaimed` separately.

When quota is the failure, confirm that the log reaches `quota fallback=share-download`. A cleanup attempt that ends with `reclaimed=0` is evidence that cleanup did not solve that reproduction. Do not report successful playback from routing logs alone; require player readiness or user confirmation on the reproduced item.

Use only the authorized device serial for this investigation:

```bash
adb -s AUTHORIZED_PHONE_SERIAL logcat -c
# Reproduce the exact UC source, then capture the relevant events.
adb -s AUTHORIZED_PHONE_SERIAL logcat -d -v threadtime | rg 'UcOpenDrive|quota preflight|cache reclaimed|cache handoff|quota fallback|share-download|PlaybackException'
```

The focused regression tests are in `app/src/test/java/com/wuming/android/tv/cloud/opendrive/UcOpenDriveProviderTest.java`. Run the UC provider tests and the applicable mobile build after behavior changes. The final 2026-09-10 implementation passed its focused tests and build before installation; device-side first-frame evidence was not captured by the development trace, so the user's confirmation is the final playback verification.

## Code locations

- `app/src/main/java/com/wuming/android/tv/cloud/opendrive/UcOpenDriveProvider.java`: quota fallback, signed share download, saved-source reuse, cache enumeration, and bounded cleanup.
- `app/src/test/java/com/wuming/android/tv/cloud/opendrive/UcOpenDriveProviderTest.java`: UC parsing, cleanup, and duplicate-cache-folder regression coverage.
- `app/src/main/java/com/wuming/android/tv/cloud/opendrive/OpenDriveCacheSupport.java`: local cache tracking and inactive-record reclamation.

## External-share regression found by the live fixture audit

On 2026-09-10, the unrelated public UC tutorial share
`https://example.com/public-share` exposed three request-contract traps:

- Token/detail requests with `pr=ucpro&fr=pc&uc_param_str=` returned HTTP 403 / code 41027 for this valid share. Using `entry=ft&fr=pc&pr=UCBrowser` allowed listing. Do not diagnose that response as an owner-account restriction without checking the query contract.
- `listShare` obtains a matching `stoken` and `share_fid_token`. Refreshing only `stoken` before either original download or smart save caused HTTP 403. Both operations must retain the pair from listing. If expiry handling is added, refresh the listing and both tokens together; never refresh just one token.
- Preserve the download response's playback cookies, including `__pugs`, for the signed media request. Keep these session cookies separate from the stored account credential, whose reviewed refresh remains `__puus`.

The final device audit on `AUTHORIZED_PHONE_SERIAL` listed the exact
`分卷压缩包解压教程.mp4` (32,421,119 bytes), resolved and read bytes from original,
then saved, resolved, and read bytes from smart. Instrumentation reported `OK (1 test)`
and `fixtureOriginal=bytes,fixtureSmart=bytes`; the test's finally block cleaned only
new disposable copies. This verifies live requests and initial media bytes, not decoded
first-frame playback. No broad fallback or label merging was needed.

Keep the comments at `SHAREPAGE_QUERY`, `resolveSharedDownload`, `saveOnce`, and the
playback-cookie handling. The offline query/header tests protect request construction;
the bundled UC live audit checks the token-pair interaction against the service.
