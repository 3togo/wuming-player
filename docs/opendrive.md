# Built-in OpenDrive

OpenDrive is compiled and signed with TVBox. It is not loaded from the active
configuration's `spider` JAR and does not share a class name with an external
`com.github.catvod.spider.MyDriveGuard` implementation.

## Configuration

TVBox appends this reserved type-3 site to every loaded VOD configuration:

```json
{
  "key": "builtin_opendrive",
  "name": "OpenDrive (Built-in)",
  "type": 3,
  "api": "builtin_opendrive",
  "searchable": 0,
  "changeable": 0
}
```

The reserved key is replaced with the canonical local definition if a downloaded
configuration tries to define it. The site has no JAR or extension and ignores
the top-level `spider` value. Other sites in the same configuration continue to
use their configured provider JARs.

Leanback exposes the built-in provider as a first-class **OpenDrive** home action.
Opening it does not replace the selected VOD home provider; it loads the built-in
account and folder catalog with the same site key used by mobile.

Do not rewrite `csp_MyDriveGuard` automatically. That name can identify unrelated
implementations in different JARs, including Alibaba PDS and protected consumer
drive providers.

## Share Links

The mobile Settings page stores a **Share link route** choice, with **OpenDrive
built-in** as the default and **CatVod direct** as the alternative. For validated
supported Aliyun, Quark, UC, Baidu, 123 Netdisk, and Tianyi Cloud share links, the CatVod route invokes
the active configuration's external `csp_MyDriveGuard` (or matching downloaded
Quark spider) directly through its site key; it does not use the generic push site.
The same dialog offers **Open official app** for validated
HTTPS links belonging to Aliyun Drive, Quark Drive, UC Drive, Baidu Netdisk,
123 Netdisk, or Tianyi Cloud. That action targets the provider's Android package with the original
share URL. It is hidden for HTTP, malformed, user-info, lookalike-domain, and
unrelated links.

Quark share browsing can use public share metadata. Playback requires a Quark
account logged in through TVBox's Cloud Storage WebView. The adapter syncs only
WebView cookies into the app-owned OkHttp cookie jar; cookies are not accepted in
configuration JSON.
Logging out expires the Quark cookies in both stores without clearing cookies
for unrelated sites.

On Leanback, validated share links received through Android share/view intents or
TVBox's browser/phone Push page route directly to the built-in OpenDrive site.
The Leanback Settings screen exposes the same **Share link route** choice as
mobile: **OpenDrive built-in** or **CatVod direct**. CatVod direct invokes the
best matching downloaded cloud spider and reports when none is installed.
Unsupported text and URLs continue through the generic push player. Both mobile
and Leanback use the same extraction, HTTPS, and provider-host validation before
constructing a route target.

Quark and UC do not provide a stable direct playback URL for a public share. For
a selected video, the matching adapter creates or reuses a folder named
`TVBoxCache`, saves only that file, obtains a short-lived download URL, and
proxies it through TVBox. The proxy supports repeated byte-range requests and
deletes the saved file after 15 minutes. The adapter never clears the folder or
deletes files it did not record itself. Aliyun and 123 stream without creating a
cloud copy. Baidu first attempts its signed original link and, when Baidu rejects
that route, saves the selected file into `TVBoxOSC_OpenDrive` before playback.

The UC importer also recognizes the provider-attributed `优汐` route emitted by
current CatVod builds. Its payload uses the same UC share contract and is accepted
only when that attribution and the complete UC capability set are both present.

## Architecture

`OpenDriveEngine` owns the stable provider-neutral contract. `OpenDriveSpider` is
the thin adapter for the CatVod ABI compiled into this TVBox version. Historical
`proxyLocal`, no-argument `liveContent`, and void-returning `action` variants do
not enter the engine.

External cloud episodes are prepared lazily. Detail loading validates each payload once, caches
only the provider's parsed metadata behind a bounded process-local `opendrive-deferred:` handle,
and snapshots provider authentication once for the whole page. The provider item handle is
materialized only when an episode is selected and is reused for retries. After successful
resolution, the existing encrypted-vault and canonical catalog flow takes over. Deferred handles
are transient implementation IDs, not additions to the canonical OpenDrive URI standard.

Playback proxy URLs produced by a built-in provider must include both:

- `siteKey`, so TVBox returns the request to the authenticated built-in instance.
- `provider`, so `OpenDriveEngine` can dispatch the request without guessing from
  a URL or relying on the most recently loaded external JAR.

## Provider Status

The registry contains reviewed built-in adapters for Aliyun Drive, Quark Drive,
UC Drive, Baidu Netdisk, 123 Netdisk, Tianyi Cloud, and user-owned OpenList/WebDAV endpoints.
Manual account login opens the provider's allowlisted first-party WebView. UC,
Quark, and Baidu use provider cookies. Aliyun stores only its returned token object
and 123 stores only `authorToken` from their own trusted origins. Tianyi uses only
first-party `cloud.189.cn` cookies and proxies resolved streams through an opaque
local handle. The app never asks for or stores a 123 or Tianyi account password.

### Token authority

`OpenDriveTokenManager` is the only account-state authority used by 云端空间
and its provider windows. Those screens do not retain provider objects or infer
login state directly from WebView pages. The manager owns one provider registry,
one active QR session per provider, verification verdicts, and reset/logout.

A token moves through these explicit states:

1. `SIGNED_OUT`: no provider-specific cookie or token is present;
2. `PRESENT`: a candidate token exists locally but has not been accepted by
   the provider in this verification window;
3. `CHECKING`: a read-only first-party account request is in flight;
4. `VERIFIED`: that request succeeded (the only state displayed as logged in);
5. `INVALID`: the provider rejected the token; or
6. `ERROR`: verification could not complete, so the saved token is retained
   but is not reported as logged in.

`Auto` is available only for `SIGNED_OUT` and `INVALID` accounts which implement
QR login. It is hidden for `PRESENT`, `CHECKING`, `VERIFIED`, and `ERROR`, avoiding
both a second registration prompt for an existing account and destructive
replacement of a token during a temporary network failure. QR confirmation
must pass the same provider verification before becoming `VERIFIED`.

Quark verification reads one root entry from its first-party `file/sort` API; UC
uses its corresponding first-party endpoint. Baidu reads one root entry from
`/api/list` and requires `errno=0` with a `list` array; the mere presence of
`BDUSS`/`BDUSS_BFESS` is not enough to display “logged in”. These
checks do not create, save, rename, or delete cloud files.

Reset/logout is provider-scoped. It invalidates the manager verdict and QR
session, clears the provider adapter's in-memory handles, and clears only that
provider's token/cookie and WebView origins. It never performs a global WebView
or cookie wipe.

### Auto QR login

`Auto` is exposed only while the account is logged out and the built-in provider
implements a first-party QR session. It disappears after authentication and is
not a synonym for opening a web page. Its required sequence is:

1. request an expiring QR challenge from the provider;
2. render the provider-issued payload in TVBox;
3. capture/decode that rendered QR and save a clean QR-only PNG under
   `Pictures/TVBox`;
4. open only the allowlisted official Android package; and
5. continue polling until a real provider cookie or token is received and passes
   the adapter's authentication check.

The saved image is deleted after confirmation or expiry. Provider account names,
phone numbers, and the surrounding login page are not written to the gallery.

OpenDrive built-in routing is the default for new installations. A freshly
routed episode and a player selection from an already-open detail page both use
the authenticated built-in provider before consulting legacy playback history.
CatVod remains an explicit setting and a fallback for payloads that a reviewed
adapter cannot recognize.

For Baidu share playback, the built-in adapter requires the already verified
`BDUSS` session. It can use the signed preview playlist when Baidu authorizes it;
an exact `errno=71` response triggers the reviewed original-quality path. The
adapter first requests a signed original link without changing the account. If
that link is unavailable, it creates `TVBoxOSC_OpenDrive` when necessary, saves
only the selected shared file with `ondup=newcopy`, and resolves the saved file
through `/api/mediainfo`. It never deletes or overwrites an existing account
file as part of this fallback.

| Provider | Auto implementation | Confirmed credential |
| --- | --- | --- |
| Aliyun Drive | `passport.aliyundrive.com/newlogin/qrcode` generate/query flow | refresh token exchanged for the complete Aliyun token object |
| Quark Drive | `uop.quark.cn` QR token, service-ticket polling, then `pan.quark.cn/account/info` | `__puus` cookie |
| UC Drive | `api.open.uc.cn` QR token, service-ticket polling, then `drive.uc.cn/account/info` | `__pus` cookie |
| Baidu Netdisk | `passport.baidu.com` QR creation, channel polling, then QR-BDUSS confirmation | Baidu `BDUSS`/session cookies |
| 123 Netdisk | Not advertised; the reviewed reference uses password login rather than a QR session | Manual first-party WebView `authorToken` capture |
| Tianyi Cloud | Not advertised until its multi-step QR redirect/cookie exchange is implemented and live-tested | Manual first-party WebView cookies |

The protocol comparison was checked against the current
[`AndroidCatVodSpider` cloud APIs](https://github.com/lushunming/AndroidCatVodSpider/tree/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api)
and the provider-neutral boundary was compared with
[`CatVodSpider`](https://github.com/ihwf/CatVodSpider/tree/9d9aa7baf69e7dae2f9e930278958fd434008f5e). No reference code is loaded
at runtime and TVBox does not accept cookies or passwords from downloaded site
configuration for this flow.

Aliyun and 123 account tokens are encrypted with an Android-Keystore-held AES-GCM key. Provider
cookies stay in the same app-private cookie storage required by OkHttp and the
Android WebView; they are never copied into configuration JSON, logs, QR images,
resource IDs, or the token-state cache. All resource IDs exposed
to CatVod are random opaque handles; tokens, share tokens, provider file IDs,
and resolved playback URLs are not embedded in category IDs or proxy query strings.

Each provider implementation must supply account state, a login challenge and
polling, folder listing, playback resolution, logout, and optional local proxy handling. Secrets
must remain in host-owned storage and must never appear in resource IDs, action
strings, proxy query strings, logs, or JSON results.

115 remains on the downloaded CatVod route. The library review in
`opendrive-115-review.md` rejects bundling the legacy cookie/encryption protocol
and selects 115's documented Open API as the only acceptable built-in route.
That route still requires a 115-issued application ID and a live account test.
Built-in episode import deliberately does not claim 115 payloads, so selecting
the built-in route does not turn a previously playable external episode into a
broken built-in item.

## OpenList and WebDAV connectors

**Status: pending and disabled.** The implementation and tests are retained, but the production
feature gate is off because requiring users to operate an additional 115-facing server is not a
robust default integration. Existing connector records are preserved and no listing or playback
route is activated while the gate is disabled.

When enabled, Settings > Cloud Storage > OpenList / WebDAV accepts one or more user-owned WebDAV endpoints.
For OpenList, configure its WebDAV endpoint (normally `/dav/`), not its administration page.
Each endpoint appears as a folder under the built-in OpenDrive account. Directory responses are
accepted only when their resolved URLs remain on the configured origin and below its base path;
non-video files are omitted.

The connector stores its username and password in the Android-Keystore-backed credential vault.
The database contains only the endpoint, initial path, and an opaque vault reference. Playback
uses an opaque provider handle, then records the successfully resolved item through the same
universal `opendrive://` catalog and resolver used by the other built-in providers. Removing a
connector removes its protected connector credentials; existing history entries then fail closed
instead of retaining a playable credential.

## Live Provider Audit

The device-only `OpenDriveLiveProviderInstrumentedTest` accepts public share URLs
through instrumentation arguments. Test fixtures and account credentials are not
committed. On 2026-08-03 the audit established:

- Aliyun public share traversal succeeds; built-in account playback awaits login.
- Baidu account root listing returned 72 items and a personal video range request
  returned HTTP 206. Public-share playback authorization also succeeded.
- 123 public share traversal returned six videos; account playback awaits login.
- Tianyi public share traversal returned three videos. The current share-info API
  omits `shareId`, so the adapter now uses its returned root `fileId`, matching the
  value accepted by `listShareDir`; account playback awaits login.

The live audit logs only provider IDs, authentication state, counts, and HTTP
status. It does not log cookies, tokens, provider file IDs, or playback URLs.

## Verification

Run the complete JVM build and produce the application plus device-test APKs:

```sh
./gradlew test assembleDebug assembleMobileArm64_v8aDebugAndroidTest
```

After installing both APKs, run the credential-aware device audit:

```sh
adb shell am instrument -w -r \
  -e class com.wuming.android.tv.cloud.opendrive.OpenDriveLiveProviderInstrumentedTest \
  com.wuming.android.tv.test/androidx.test.runner.AndroidJUnitRunner
```

Connected tests target the isolated `com.wuming.android.tv.verification`
application, never the user's installed application or its credentials. A provider
without credentials in that isolated target is reported as skipped, not passed.
Public share fixtures can be supplied through the `provider` and `share_url`
instrumentation arguments; fixture URLs and credentials must not be committed.

The 2026-08-03 release-branch verification completed the Gradle command above,
installed both ARM64 APKs, passed the six-test device audit with expected
credential skips, and independently repeated the Tianyi public-share test after
the compatibility fix. Baidu's authenticated playback check received HTTP 206.

## Trust Boundary

The built-in route is instantiated directly by `BaseLoader`; `DexClassLoader` is
not involved. Its code is protected by the APK signature and normal application
update path. This does not make external provider JARs trusted, and it does not
change their existing process or credential access.
