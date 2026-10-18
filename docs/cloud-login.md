# Cloud-drive QR login handoff

## Token manager contract

云端空间 and every built-in provider window use
`OpenDriveTokenManager` as their account-state authority. A cookie or token
found on disk is only `PRESENT`; it becomes `VERIFIED` only after a read-only
request to the provider succeeds. A transport failure retains the token but
does not display it as logged in. A provider rejection marks it `INVALID` and
allows a new QR session. QR sessions and reset/logout are provider-scoped and
owned by the manager, not by an Activity instance.

Token ownership is also separate from media identity. A canonical
`opendrive://<catalog-authority>/<kind>/<resource-id>` URI never contains a
provider cookie or token. Its catalog record carries a provider ID and opaque
restart-safe handle; resolution asks the process-wide `OpenDriveRuntime` for
that provider, and the provider loads its token from the encrypted vault.
The Settings manager, built-in `OpenDriveSpider`, and default URI resolver all
share that runtime, so a login completed in 云端空间 is immediately available to
URI listing, read/write and streaming operations. Built-in OpenDrive is the
default route. Once an external episode is accepted by that route, resolution
fails visibly rather than falling back to MDrive or opening a second provider
login. The original external payload is not retained after preparation.
Upgrades migrate the former encrypted `credential` vault handle to the current
`token` handle only after decrypting and successfully re-encrypting the same
value. This prevents existing UC sessions from appearing signed out and being
routed back to a legacy provider JAR after the manager rename.

The protocol facts are pinned to the current upstream heads reviewed on
2026-08-04:

| Manager rule | `AndroidCatVodSpider` source | TVBox enforcement |
| --- | --- | --- |
| Quark credential and refresh | [`QuarkApi.java`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/QuarkApi.java) requests a token as client `386`, renders/polls it as client `532`, treats a cookie containing `__pus` as an initialized session, exchanges a service ticket at `pan.quark.cn/account/info`, and later consumes refreshed `__puus` from `Set-Cookie` | Preserve the `386`/`532` split, accept either reference session cookie (`__pus` or `__puus`), then require a successful read-only `file/sort` request; the shared cookie jar accepts first-party refresh cookies |
| UC credential and refresh | [`UCApi.java`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/UCApi.java) collects the QR-token response cookies into one explicit string, polls client `381` with that string, appends cookies from `drive.uc.cn/account/info`, requires `__pus`, and later replaces/appends `__puus` from drive responses | Keep a provider-owned cookie accumulator for the complete QR session; persist it through the Keystore-backed credential vault only after `account/info` succeeds with `__pus`; send it explicitly on UC API requests and refresh only `__puus` |
| Baidu QR credential and account proof | [`BaiDuYunHandler.kt`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/BaiDuYunHandler.kt) requests `/v2/api/getqrcode`, polls `/channel/unicast`, completes `/v3/login/main/qrbdusslogin`, and persists its returned cookies. [`BaiduDrive.kt`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/BaiduDrive.kt) uses those cookies with `/api/list` for personal-drive reads | Require `BDUSS` or `BDUSS_BFESS`, then require an `errno=0` root-list response containing a `list` array; cookie presence alone is never “logged in” |
| Host ABI and HTTP integration | [`Spider.java`](https://github.com/ihwf/CatVodSpider/blob/9d9aa7baf69e7dae2f9e930278958fd434008f5e/app/src/main/java/com/github/catvod/crawler/Spider.java) and [`OkHttp.java`](https://github.com/ihwf/CatVodSpider/blob/9d9aa7baf69e7dae2f9e930278958fd434008f5e/app/src/main/java/com/github/catvod/net/OkHttp.java) define the provider ABI and host-client handoff | Keep `OpenDriveSpider` as a thin ABI adapter and use the host cookie jar, but retain normal Android certificate/hostname verification for credential traffic |

Baidu share playback first follows `BaiduDrive.kt`'s signed read-only original
link request. If Baidu does not authorize that route, the adapter follows the
reference save-and-play pattern: it creates `TVBoxOSC_OpenDrive` in the logged-in
account when needed, saves only the selected shared file with `ondup=newcopy`,
then requests that saved file's media link. It does not clear the folder or
delete existing account files. Preview streaming remains available where Baidu
returns a valid playlist; an exact provider `errno=71` is the only condition
that activates the original/save fallback.

The last TLS rule is a deliberate security deviation: the reference
`CatVodSpider` compatibility client can trust all certificates. Built-in
OpenDrive credential requests do not inherit that behavior.

### Device/live verification snapshot (2026-08-04)

No credential values were printed or copied during these checks. Only cookie
names and response envelopes were inspected.

- A stored Quark session contained `__pus` but not `__puus`. The first-party
  personal `file/sort` request returned HTTP 200 with provider code `0` and a
  data list. This proves that requiring only `__puus` was a false-negative gate
  and agrees with `QuarkApi.initUserInfo()` accepting `__pus`.
- A stored Baidu QR session contained `BDUSS` and `BDUSS_BFESS`.
  `/api/gettemplatevariable` returned `errno=-6`, while the same session's
  first-party `/api/list` request returned `errno=0` with a populated list.
  Verification therefore uses the read operation demonstrated in
  `BaiduDrive.kt`, rather than treating the token-helper endpoint as a universal
  login test.
- The UC cookie jar contained only pre-login `_UP_*` cookies and neither
  `__pus` nor `__puus`. UC therefore remains logged out until the official UC
  scan flow actually confirms the issued client-`381` challenge.

### UC protocol boundary

UC is intentionally not authenticated through the shared OkHttp cookie jar.
The reviewed `UCApi` carries one cookie string across `api.open.uc.cn`,
`drive.uc.cn`, and `pc-api.uc.cn`; normal RFC host matching cannot reproduce
that behavior for host-scoped cookies. TVBox therefore uses a no-cookie-jar
client for this flow and supplies the provider-owned `Cookie` header itself.

The sequence is fixed to the reference:

1. POST `getTokenForQrcodeLogin` with client `381` and collect each first
   `name=value` pair from `Set-Cookie`.
2. Render the `su.uc.cn/1_n0ZCv` challenge and poll
   `getServiceTicketByQrcodeToken` with the accumulated cookie string and one
   polling `request_id`.
3. Keep polling non-success intermediate states until provider confirmation or
   the local session TTL. On confirmation, exchange the service ticket at
   `drive.uc.cn/account/info` with the same cookie string.
4. Append the account response cookies, require a non-empty `__pus`, and only
   then write the complete string to the Android-Keystore-backed vault.
5. Send that string on `pc-api.uc.cn` reads/writes. From ordinary drive
   responses, import only the reference's refresh cookie, `__puus`.

UC save-folder creation also preserves the reference request types exactly:
`dir_init_lock` is the JSON string `"false"`, not a boolean. UC may reject the
otherwise equivalent-looking boolean body with HTTP 400. UC HTTP failures name
the rejected endpoint so a folder, save-task, and download failure are not
collapsed into the same generic cloud error.
The UC API query is emitted in the reference's literal order (`pr`, `fr`, `sys`,
`ve`, `ut`, then endpoint fields); the Base64 `ut` value is retained as an
already encoded component. In particular, task polling appends `task_id` before
`retry_index`, matching `UCApi.java` instead of relying on `Map.of` iteration.

The optional `UCTokenHandler` path is not used: it contains embedded signing
material and routes token exchange through a third-party plain-HTTP endpoint.

Settings > Cloud Storage is the primary built-in login surface. Selecting a
logged-out provider requests a fresh first-party QR challenge and renders it in
a page-owned dialog; it does not open a generic provider WebView first. The
normal QR remains available for scanning from another device. The row's
**Auto** action requests the same fresh challenge and then:

1. render the provider-issued QR in the Cloud Storage dialog;
2. screenshot that QR view into memory and decode it locally;
3. Require both the selected provider and the decoded HTTPS origin to match the
   built-in allowlist.
4. Save that QR-only screenshot as a short-lived image in `Pictures/TVBox`.
5. Open the corresponding allowlisted official application so the user can
   select the newest image and confirm the login.
6. Delete the temporary image after confirmation, when the login screen closes,
   or after ten minutes. An expiry record removes stale images after a process
   restart as well.

The dialog keeps polling the exact session that produced the saved image. A QR
confirmation becomes logged in only after `OpenDriveTokenManager` performs
the provider verification described above. Already verified rows have no Auto
button and open the provider file manager instead. Providers without a reviewed
QR API continue to use their first-party manual WebView.

The legacy MDrive `LoginShow` dialog retains the same optional shortcut:

1. Ask the provider to create a fresh QR challenge.
2. Ignore any QR that was already visible before the request.
3. Decode the new QR locally and validate its HTTPS origin.
4. Save only the provider QR image to `Pictures/TVBox`.
5. Open the corresponding official application so the user can select the
   newest image and confirm the login.
6. Leave the provider instance running so it can poll and complete its own
   authentication protocol.

Neither helper extends the provider's token lifetime, copies cookies, or injects
credentials into another application. OpenDrive never imports an MDrive session.
The legacy five-minute watcher is only the maximum time TVBox waits for a newly
rendered QR image; the provider may impose a shorter challenge expiry.

## Supported handoff targets

| Service | Accepted HTTPS hosts | Android package | URL fallback |
| --- | --- | --- | --- |
| Aliyun Drive | `aliyundrive.com`, `alipan.com` and subdomains | `com.alicloud.databox` | Validated official web page |
| Quark Drive | `quark.cn`, `myquark.cn` and subdomains | `com.quark.browser` | None |
| UC Drive | `uc.cn`, `ucweb.com` and subdomains | `com.UCMobile` | None |
| Baidu Netdisk | `baidu.com`, `baidubce.com` and subdomains | `com.baidu.netdisk` | Installed app launcher |
| 123 Netdisk | `123pan.com`, `123pan.cn` and subdomains | `com.mfcloudcalculate.networkdisk` | Installed app launcher |
| Tianyi Cloud | `189.cn` and subdomains | `com.cn21.ecloud` | Installed app launcher |

Origins must use HTTPS. User-info authorities, ports, backslashes, control
characters, lookalike suffixes, and non-allowlisted hosts fail closed. Provider
query strings are treated as opaque because some official UC challenges contain
percent escapes that are not valid generic URI syntax.

The saved QR is an authentication challenge. It may briefly be indexed by a
gallery or photo-backup application, so TVBox keeps only one handoff image at a
time and automatically removes it. A provider may retain its own copy after the
user selects the image; that storage remains under the official app's control.

## MDrive trust boundary

MDrive is not implemented or built by this repository. It is a `type=3` CatVod
provider loaded from the active external configuration with `DexClassLoader`.
TVBox can contain failures during normal browsing by running provider calls in
the `:provider` process. That process is **not** an Android `isolatedProcess`: it
shares TVBox's UID, permissions, cookie store, networking, and private
credential-file access. Interactive operations such as `LoginShow` intentionally
run in the main process because they need the current Activity and provider
window. Process separation therefore improves crash resilience but is not a
security boundary.

The legacy QR helper narrows only its own behavior: it inspects a provider login window,
accepts an allowlisted official HTTPS origin, saves the QR, and launches an
official package. It does not make the provider JAR itself trusted.

### Provenance audit snapshot (2026-08-02)

The active 饭太硬 configuration was loaded over plain HTTP from
`http://fty.888484.xyz/tv`. The cached provider artifact containing MDrive was
downloaded from a random `.jpg` path on `zl.wpscdn.cn`, but its body is a JAR:

- SHA-256: `4ea2ae69eb1b5d403a0418a785d62f9fbad4783a6934e19d2298ab69f56b0fea`
- Entry point visible in the DEX: `com.github.catvod.spider.MyDriveGuard`
- Native components: `ftyguard_v7.so`, `ftyguard_v8.so`
- Protected payload: `ftyshinidie.guard`
- Manifest metadata: only `Created-By: dx 1.11`

No source URL, license, individual author, publisher certificate identity, or
reproducible-build information was present. The `fty` naming and delivery through
the 饭太硬 configuration identify the distributor/operator, not a verifiable
individual author. The safest accurate assessment is therefore **unknown and
unauditable**, not “confirmed malicious” and not “confirmed safe.” A changed
remote configuration can also replace the JAR without an application update.

For sensitive accounts, prefer a reviewed open-source provider with a pinned
cryptographic digest. Do not reuse important passwords, review provider sessions
regularly, and revoke cloud sessions if the external configuration or cached JAR
changes unexpectedly.

## Regression checks

`ProviderQrLauncherTest` covers QR decoding, built-in provider-to-official-app
mapping, official-domain classification, malformed UC query handling, lookalike
hosts, authority injection, non-HTTPS schemes, and per-provider launcher policy.
`CloudStorageWebActivityInstrumentedTest` covers built-in Auto-button visibility.

Run:

```bash
./gradlew :app:testMobileArm64_v8aVerificationUnitTest assembleDebug
```

## Upstream implementation references

The two authoritative research baselines for this implementation are
[`ihwf/CatVodSpider`](https://github.com/ihwf/CatVodSpider/tree/9d9aa7baf69e7dae2f9e930278958fd434008f5e)
for the CatVod host ABI/network integration and
[`lushunming/AndroidCatVodSpider`](https://github.com/lushunming/AndroidCatVodSpider/tree/ec6e36c76d2cc99274589ce4bb2aa887f91020de)
for Quark, UC, and Baidu provider protocols. Both hashes were still their remote
`HEAD` on 2026-08-04.

They are research references, not runtime dependencies. Re-check the current
source and live first-party endpoints before adopting a protocol change. Never
copy logged secrets, accept credentials from downloaded site configuration, or
route credentials through a third-party token exchange.

## Password-protected account backup

Settings → Cloud Storage → Export accounts saves an encrypted JSON file. Choose the
file destination, then enter and confirm a passphrase of at least eight characters.
Import accounts opens that file and asks for the same passphrase; it replaces the
saved cloud drive, Bilibili and WebDAV connector accounts. A wrong password or
invalid payload is rejected before account state changes. Passwords are not saved in
activity state; a recreated password dialog requires re-entry.

`CloudStorageActivity` calls `AccountCredentialBackup`, which encrypts/decrypts through the
shared `com.wuming.tv.core.cloud.CredentialBackupCodec` in `libraries/cloud-core`
(AES-256-GCM with a random salt and nonce, using PBKDF2-HMAC-SHA256). The desktop client uses the
same codec and the same naming/migration rules (`CredentialBackupFiles`), so a backup taken on one
platform restores on the other. `AccountCredentialBackupState` gathers the token vault, cookies
for the explicit account domain allowlist, and WebDAV connector definitions.
All three are inside the encrypted payload.

The allowlist is deliberately *not* cloud-drive only: Bilibili sessions are included
because the settings backup carries preferences and database rows and cannot restore
them. Unrelated website cookies stay excluded. Archives written before the rename
(`tvbox-opendrive-credentials`) still import. Imported tokens are encrypted again with
this device's Android Keystore; provider memory and cached verification verdicts are
invalidated.

Version 2 exports include cookie-based accounts such as Quark and Tianyi, and the
WebDAV address/path/name required to use restored connector passwords after a
reinstall. Version 1 vault-only files still import, preserving existing cookies and
connector definitions because those files did not contain either. An old file
cannot recover cookie sessions or connector definitions that it never saved.
Provider-expired sessions may still require signing in again after restoration.

Focused regressions: `CredentialBackupCodecTest` and `CredentialBackupFilesTest` (cloud-core),
`OpenDriveTokenManagerTest`, and `AccountCredentialBackupInstrumentedTest`. The device suite uses synthetic
secrets in the isolated `.verification` app and covers cookies, connector recovery,
unrelated-login isolation, invalid imports, legacy files, password clearing, and
activity recreation.
