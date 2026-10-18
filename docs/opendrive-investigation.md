# MDrive replacement / OpenDrive investigation

Status: research record; no provider implementation is enabled by this document.

Date: 2026-08-04 (Asia/Hong_Kong)

## Executive finding

`mdrive.jar` and an OpenDrive share/endpoint URL are different kinds of object.
They are not interchangeable configuration values.

- A top-level CatVod `spider` value identifies a downloadable Android JAR (or a
  local JAR) which TVBox loads with `DexClassLoader`. The JAR must contain the
  CatVod entry classes named by each `csp_*` site and must match the host ABI.
- A cloud-drive URL identifies a share, file, WebDAV endpoint, or login origin.
  It is data consumed by a provider implementation; it is not executable JAR
  bytecode.
- A built-in OpenDrive route is a third thing: provider code compiled into and
  signed with the APK, exposed through a reserved local site. It must not be put
  into the top-level `spider` field.

Therefore, if the attempted replacement changed only this:

```json
"spider": "https://.../mdrive.jar"
```

to this:

```json
"spider": "https://.../share-or-webdav-url"
```

failure is deterministic, not an intermittent provider problem. TVBox will
download the response and try to construct a `DexClassLoader` from it.

There are two coherent replacement designs in the scope of this investigation:

1. **External JAR:** build and publish a compatible CatVod JAR, then configure a
   direct JAR download URL plus a pinned digest.
2. **Built-in provider:** compile the reviewed provider adapters into TVBox and
   route cloud items to a reserved `builtin_opendrive` site. The external JAR may
   still discover share links, but the host takes over login and playback.

The second design is the safer long-term direction because TVBox controls the
code, credential storage, proxy, cleanup policy, and update signature. It is not
yet justified to call it production-ready solely from source review and mocked
tests.

### Terminology: four values which look similar but are not interchangeable

| Value | Example | Meaning |
| --- | --- | --- |
| CatVod JAR location | `https://host/custom_spider.jar;md5;...` | Executable artifact consumed by `JarLoader` |
| Built-in site API | `builtin_opendrive` | Reserved local provider name consumed by `BaseLoader`; not a URL |
| Canonical media identity | `opendrive://...` | Internal opaque media identity used after import; not downloadable code |
| Provider input | `https://example.com/public-share` or `https://host/dav/` | Share/WebDAV data consumed by an already-running provider |

“OpenDrive URL” must be resolved to one of these precise meanings before a test
is interpretable.

## Scope and evidence

The following immutable snapshots were reviewed:

| Source | Snapshot | Role in this investigation |
| --- | --- | --- |
| [`ihwf/CatVodSpider`](https://github.com/ihwf/CatVodSpider/tree/9d9aa7baf69e7dae2f9e930278958fd434008f5e) | `9d9aa7baf69e7dae2f9e930278958fd434008f5e` | Baseline CatVod JAR, AList and WebDAV implementations |
| [`lushunming/AndroidCatVodSpider`](https://github.com/lushunming/AndroidCatVodSpider/tree/ec6e36c76d2cc99274589ce4bb2aa887f91020de) | `ec6e36c76d2cc99274589ce4bb2aa887f91020de` | Provider login, share traversal, account mutation and playback reference |
| This checkout | `00e7e364381a61f2495b5dc03ddabea8f57d4429` | Existing external-JAR and native drive-browser behavior |
| Local `origin/release` snapshot | commits through 2026-08-03 | Candidate built-in OpenDrive implementation and tests; not present at this checkout's `HEAD` |

Evidence labels used below:

- **S — source-confirmed:** directly visible in the pinned source.
- **T — test-confirmed:** exercised by local JVM tests with fakes/mocks.
- **R — recorded, not reproduced:** a prior branch document reports a device or
  live result, but this investigation did not repeat it.
- **U — unverified:** needs a current disposable account or public fixture.

On 2026-08-04, the candidate `origin/release` snapshot passed 68 targeted
OpenDrive JVM tests in 15 suites with zero failures, errors, or skips (**T**):

```sh
./gradlew :app:testMobileArm64_v8aVerificationUnitTest \
  --tests 'com.fongmi.android.tv.cloud.opendrive.*' \
  --tests 'com.fongmi.android.tv.media.*OpenDrive*'
```

These tests validate host routing, parsing, handles, identity, import behavior,
and proxy contracts under controlled responses. They do not prove that a
provider's current production endpoint accepts login or streams a real file.

## CatVod runtime contract

The reference project's [README configuration example](https://github.com/ihwf/CatVodSpider/blob/9d9aa7baf69e7dae2f9e930278958fd434008f5e/README.md)
uses a remote `custom_spider.jar`, optionally followed by `;md5;<digest>`, and
names JAR providers with `type: 3` and `api: csp_ClassName`.

This checkout's `JarLoader` performs the following contract (**S**):

1. Hash the full JAR configuration string for loader identity and last-good cache isolation.
2. Download `http(s)` content or resolve an `assets:` / `file:` JAR. Check the DEX entry and any configured MD5 or SHA-256 digest before loading.
3. Mark the artifact read-only and create a child `DexClassLoader`.
4. Optionally reflect `com.github.catvod.spider.Init.init(Context)`. After initialization succeeds, retain a copy with a recorded SHA-256. On a later download or initialization failure, use that copy only when its recorded hash and the current configuration digest still match.
5. Optionally reflect `com.github.catvod.spider.Proxy.proxy(Map)` for local
   streaming.
6. For `api: csp_Foo`, instantiate
   `com.github.catvod.spider.Foo`, set `siteKey`, and call `init(context, ext)`.

This contract has several consequences:

- The URL must return a valid Android/Dex-compatible JAR, not HTML, JSON, a
  provider share page, a redirect to a login page, or a WebDAV collection.
- A successful download is not proof of compatibility. The required class may
  be absent, fail linkage against the host's `com.github.catvod.*` classes, or
  require libraries/native ABIs the APK does not provide.
- `Proxy.proxy(Map)` is global static dispatch. Correct routing depends on the
  same loader and provider instance remaining available when the player sends
  byte-range requests.
- `Init` implementations which discover an `Activity`, start a local server, or
  access files couple the JAR to host-specific behavior beyond the public
  `Spider` methods.

The AndroidCatVodSpider README explicitly warns that host classes outside its
`spider` package can replace classes packaged in the JAR. Its
[`Init`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/spider/Init.java)
and [`Proxy`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/spider/Proxy.java)
show why this is an ABI-sensitive reference, not a universally portable JAR.

## What the two repositories actually implement

### AList

The two pinned repositories contain the same AList/WebDAV adapter source. The
[`AList` spider](https://github.com/ihwf/CatVodSpider/blob/9d9aa7baf69e7dae2f9e930278958fd434008f5e/app/src/main/java/com/github/catvod/spider/AList.java)
is an AList v2/v3 browser, not a Chinese cloud-provider login broker (**S**).

- **Login:** configuration supplies server, username and password. AList v3 uses
  `/api/auth/login`; the returned token is retained in memory as
  `Authorization`. The extension may itself be an inline JSON document or a URL
  which the spider downloads.
- **Read:** settings discovery, directory listing, item lookup and search. The
  endpoint switch is visible in the
  [`alist.Drive` bean](https://github.com/ihwf/CatVodSpider/blob/9d9aa7baf69e7dae2f9e930278958fd434008f5e/app/src/main/java/com/github/catvod/bean/alist/Drive.java).
- **Write:** none. There is no upload, copy, move, create-directory, rename or
  delete operation in the spider.
- **Streaming:** fetch item metadata and return its `raw_url`, with special
  user-agent handling for some mounted providers.

The sample configuration contains credentials in JSON. That is suitable as a
protocol example, not as an acceptable secret-storage design for production.

### WebDAV

The [`WebDAV` spider](https://github.com/ihwf/CatVodSpider/blob/9d9aa7baf69e7dae2f9e930278958fd434008f5e/app/src/main/java/com/github/catvod/spider/WebDAV.java)
uses Sardine with Basic credentials (**S**).

- **Login:** no interactive login; the endpoint, username and password come from
  JSON configuration.
- **Read:** `PROPFIND`/directory listing and subtitle discovery.
- **Write:** none, even though the Sardine library is capable of writes.
- **Streaming:** expose a TVBox local proxy (`do=webdav`), issue upstream `GET`,
  and forward `Range` plus selected headers. Subtitle reads use the same proxy.

This checkout already has separate native `AlistDriveViewModel` and
`WebDAVDriveViewModel` browser code. That UI path does not satisfy a CatVod
external site's `csp_*` contract and cannot replace its JAR URL.

### Cloud-provider bundle

[`Cloud`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/spider/Cloud.java)
routes recognized share links to Quark, UC, Tianyi, China Mobile, Baidu and 123
implementations. Ali routing is commented out there, although a direct `Ali`
spider exists. Provider sessions are initialized from dot-files such as
`.quark`, `.uc`, `.uctoken`, `.tianyi`, `.bd`, and `.pan123` (**S**).

The important word “write” needs precision: this bundle does not provide a
general file manager or upload API. Remote writes are playback side effects —
copying a shared file into the user's account, creating a cache folder, and
deleting cached files.

| Adapter | Login/session | Read | Remote write side effects | Streaming | Evidence |
| --- | --- | --- | --- | --- | --- |
| Aliyun | QR/refresh token | personal and public shares | may copy a share item, then delete it | direct download or preview/proxy | S/U |
| Quark | cookie or first-party QR/service ticket | personal and public shares | create/reuse `TV`, save share item, delete cached items | download/transcode through local proxy | S/U |
| UC | cookie or first-party QR; optional token helper | personal and public shares | create/reuse `TV`, save share item, clear its contents | download through local proxy | S/U |
| Baidu | QR/cookies | personal and public shares | delete/recreate `TVBOX_BD`, transfer share item | share dlink or account media URL through proxy | S/U |
| 123 | username/password to account token | personal and public shares | no cloud copy observed | authenticated download URL | S/U |
| Tianyi | QR code exists, but normal flow also asks for username/password | personal and public shares | no remote cache copy observed | resolved VLC/download URL | S/U |
| AList | configured credentials/token | list/search/get | none | direct raw URL | S/U |
| WebDAV | configured Basic credentials | list/get | none | range-aware local GET proxy | S/U |

No current live account test was performed during this investigation (**U**).

## Provider-specific findings

### Aliyun

The reference [`AliYun` API](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/AliYun.java)
uses first-party file APIs, but also sends OAuth work through
`api.xhofe.top/alist/ali_open` and an `alist.nn.ci` redirect, and asks for read
and write scope (**S**). It can copy a shared item and later call `/file/delete`.

Decision: the browsing/parsing behavior is useful research. Third-party token
exchange and copied embedded credentials must not be adopted.

### Quark

The reference [`QuarkApi`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/QuarkApi.java)
obtains a share token, recursively lists `share/sharepage/detail`, saves the
selected file with `share/sharepage/save`, polls the task, and requests either
transcoding or `file/download` (**S**).

Its cache directory is named `TV`. Cleanup is based on that name and can delete
items the code did not create if a user's existing folder collides. Its simple
M3U8 rewriting only identifies lines containing `.ts`, which is insufficient
for every HLS playlist form.

Decision: keep the first-party share/save/download sequence, but track exact
created file IDs, use a collision-resistant app folder, protect active streams,
and test playlists containing relative child manifests, encryption keys,
redirects and signed query strings.

### UC

The reference [`UCApi`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/UCApi.java)
is structurally similar to Quark: public listing, account save, task polling,
download and proxy (**S**). Its cleanup clears the contents of the `TV` folder.

The optional [`UCTokenHandler`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/UCTokenHandler.java)
uses plain HTTP at `api.extscreen.com/ucdrive` and contains client signing
material (**S**).

Decision: reject that token service completely. Use UC-owned HTTPS origins only,
and apply the same owned-file cleanup rules as Quark.

### Baidu

The reference [`BaiduDrive`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/BaiduDrive.kt)
supports public share verification/listing and account playback. Its fallback
deletes the path `TVBOX_BD`, recreates it, transfers the item, and requests media
information (**S**).

Decision: never delete a folder solely because its display name matches. Prefer
non-copy share playback; if a copy is unavoidable, persist ownership of every
created remote ID.

### 123 Netdisk

The reference [`Pan123Handler`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/Pan123Handler.kt)
asks for username/password, logs both values, and saves credentials/session data
in `.pan123` (**S**). Playback uses authenticated download metadata and does not
need a cloud copy in the reviewed path.

Decision: plaintext password entry, logging and persistence are blockers. A host
implementation should capture only a first-party web token and encrypt it with
an Android Keystore-held key.

### Tianyi Cloud

The reference [`TianYiHandler`](https://github.com/lushunming/AndroidCatVodSpider/blob/ec6e36c76d2cc99274589ce4bb2aa887f91020de/app/src/main/java/com/github/catvod/api/TianYiHandler.java)
has QR machinery but also prompts for and persists account username/password in
`.tianyi` (**S**). The reviewed share flow assumes `shareId`; observed provider
responses may instead expose the usable root `fileId`. Error-retry return values
also need explicit tests.

Decision: use first-party WebView cookies only. Test both `shareId` and
`fileId` response shapes and an expired-session retry.

## Candidate built-in OpenDrive on `origin/release`

The local release branch contains a candidate architecture not present in this
checkout's current `HEAD`:

```text
external site detail/search
          |
          | recognized provider share episode
          v
reserved builtin_opendrive site
          |
          v
OpenDriveSpider -> OpenDriveEngine -> provider adapter
          |                              |
          | opaque item handle           | first-party HTTPS / app cookie jar
          v                              v
player resolver <- local range proxy <- short-lived stream URL
```

Its provider-neutral contract covers availability, authentication, login start
and polling, personal/share listing, external-item preparation, playback
resolution, optional proxying, logout, and opaque persisted handles (**S**).

The candidate improves materially on the reference JAR (**S/T**):

- Direct instantiation by `BaseLoader`; no `DexClassLoader` for OpenDrive.
- Reserved `builtin_opendrive` identity independent of the active top-level JAR.
- Aliyun, Quark, UC, Baidu, 123 and Tianyi provider adapters plus a gated WebDAV
  connector.
- First-party HTTPS allowlists; the UC third-party token broker and Ali third-party
  OAuth exchange are not used.
- WebView/cookie or provider-token login instead of storing 123/Tianyi passwords.
- Android-Keystore-backed token/connector vaults and opaque media IDs.
- Quark/UC cache folder `TVBoxCache`, with exact created-file tracking rather
  than clearing arbitrary named folders.
- A local proxy carrying both `siteKey` and provider identity, avoiding
  “most recently loaded JAR” guessing.

The source-reviewed candidate capabilities are:

| Built-in adapter | Login/session | Read | Remote write side effects | Streaming | Evidence |
| --- | --- | --- | --- | --- | --- |
| Aliyun | first-party Alipan WebView; capture token object | personal and public shares | none in reviewed playback path | first-party personal/share download URL | S/T/U |
| Quark | first-party WebView cookies | personal and public shares | save one shared item into tracked `TVBoxCache`; delete owned ID | local byte-range proxy; cleanup waits for idle/inactive stream | S/T/U |
| UC | first-party UC QR/service-ticket flow and cookies | personal and public shares | save one shared item into tracked `TVBoxCache`; delete owned ID | local byte-range proxy; currently fixed 15-minute handle lifetime | S/T/U |
| Baidu | first-party WebView cookies | personal and public shares | none in reviewed playback path | account media URL or public-share HLS; reviewed public profile is limited | S/T/U |
| 123 | first-party WebView; capture `authorToken` | personal and public shares | none in reviewed playback path | authenticated first-party download URL | S/T/U |
| Tianyi | first-party `cloud.189.cn` WebView cookies | personal and public shares | none in reviewed playback path | opaque local byte-range proxy | S/T/U |
| WebDAV/OpenList | configured connector credentials in encrypted vault | list/get | none | authenticated local byte-range proxy | S/T/U; production gate off |

Limits still requiring proof:

- Provider APIs are reverse-engineered web endpoints, not stable public SDK
  contracts. Any endpoint, client ID, cookie name, response shape or anti-bot
  behavior may change (**U**).
- The 68 JVM tests use controlled transports. Real WebView login, redirects,
  cookies, short-lived URLs, byte ranges and account cleanup need device tests
  with disposable accounts (**U**).
- Prior branch notes record successful 2026-08-03 public traversal for Aliyun,
  Baidu, 123 and Tianyi, and an HTTP 206 Baidu account stream (**R**). They were
  not independently rerun here and must not be presented as current verification.
- WebDAV/OpenList connector code is feature-gated off in the candidate branch.
- No generic upload/write/file-management API is provided. Quark/UC remote
  writes exist only to make share playback possible.
- Cleanup while a long stream is active, app/process death between save and
  delete, pagination beyond the first personal page, rate limits, quota-full
  accounts and clock skew all need explicit tests.
- UC has a source-confirmed fixed cleanup timer: 15 minutes after resolution it
  removes the proxy handle and deletes the saved remote item without checking
  active/idle stream state. Quark has such protection; UC does not. A later seek
  or range reopen can therefore fail even when initial playback succeeded
  (**S**, release blocker).
- 115 is not implemented as a built-in provider; it remains an external route.

## Why a replacement can have “limited success”

Without the exact failing configuration and logs, these are ordered diagnostic
branches, not claims about a specific failure:

1. **Wrong artifact type.** A share/WebDAV/OpenList page was supplied where a
   Dex JAR was required. Check response bytes before all provider debugging.
2. **Correct JAR, wrong CatVod ABI.** Missing `csp_*` class, incompatible
   `Spider` signature, parent-first class collision, missing library, Android API
   level or native ABI.
3. **Split login state.** Login occurs in a WebView/main process, while browsing,
   playback or proxy runs in another instance/process/cookie jar.
4. **Proxy routed to the wrong loader.** Static `Proxy.proxy(Map)` is associated
   with the recent JAR rather than the site/provider which created the URL.
5. **Episode payload mismatch.** External sources encode share URL, provider,
   password and file identity in different JSON/base64/delimiter shapes. A
   generic URL regex can recognize the provider yet lose required metadata.
6. **Expired or changed provider protocol.** Cookie names, share tokens, client
   parameters, endpoint response fields or anti-bot requirements have changed.
7. **Streaming-only failure.** Listing works, but redirect headers, user agent,
   cookies, HLS child URLs, `Range`, 206/416 handling or signed URL expiry fails.
8. **Account mutation failure.** Quota, duplicate save, task polling, cache-folder
   collision or cleanup deletes/moves the item before the player finishes.

This ordering prevents a successful public listing from being mistaken for a
successful login or stream implementation.

## Security and data-safety requirements

The reference source contains behaviors which should be treated as explicit
red lines, not copied for compatibility:

- Never log passwords, cookies, refresh/access tokens, service tickets, provider
  file IDs, signed playback URLs or full response bodies containing them.
- Never store account passwords or tokens in source JSON or plaintext dot-files.
- Never send provider tokens to a third-party broker, especially over HTTP.
- Never fetch a token from an arbitrary user-supplied URL.
- Never delete a remote folder or its contents by display name.
- Record ownership of every remote cache file before it is eligible for cleanup.
- Use HTTPS and exact host allowlists; reject user-info authorities, lookalike
  suffixes, unsafe redirects and cross-origin WebDAV paths.
- Keep credentials in host-owned app-private storage protected by Android
  Keystore; pass only opaque handles through CatVod and proxy URLs.
- Treat an external JAR as same-UID code, not a sandbox. Process separation may
  contain crashes but does not protect the app's cookies or private files.
- Pin a digest for every external JAR and retain artifact provenance. A URL alone
  can silently change behavior without an APK update.

## Controlled verification plan

No more provider code should be changed until the following evidence is
captured. Use disposable accounts containing no personal files.

### 1. Freeze inputs

Record, without secrets:

- TVBox commit, APK variant, Android version and CPU ABI.
- Full configuration with credentials/tokens redacted.
- JAR final resolved URL, SHA-256, byte count, HTTP status/redirect chain and
  retrieval timestamp.
- Provider, public share URL host, share password presence, selected file type
  and approximate size.

### 2. Prove the artifact contract

For every external JAR candidate:

```sh
curl --fail --location --output candidate.jar 'DIRECT_JAR_URL'
sha256sum candidate.jar
unzip -t candidate.jar
unzip -l candidate.jar | grep -E 'classes.*dex|lib/.+\.so'
```

Then verify, on-device, that the named `com.github.catvod.spider.<Class>` can be
constructed and initialized. Log class and exception type only; do not dump
extension JSON or provider responses.

### 3. Test capabilities independently

For each provider, run these gates in order:

| Gate | Required evidence | Pass condition |
| --- | --- | --- |
| Public read | root + nested share fixture | stable item count/type/name; no login required unless provider mandates it |
| Login | first-party UI on disposable account | authenticated state survives activity close and app restart; logout/revoke clears it |
| Personal read | root + paginated folder | no missing/duplicated items across pages |
| Resolve | one small and one large video | URL/handle resolves without secret-bearing logs or IDs |
| Range stream | bytes at start, middle and near EOF | correct 200/206/416 behavior, content range and repeat seek |
| HLS stream | master, child playlist, segment and key where applicable | every relative/signed URI remains playable after rewrite/proxy |
| Expiry | reuse after signed URL expiry | controlled re-resolution, not an infinite retry |
| Mutation | snapshot account before/after | only recorded app-owned cache IDs are created/deleted |
| Recovery | kill app after save, before cleanup | orphan is found from ownership ledger and safely removed later |

### 4. Capture a safe failure taxonomy

Use structured events such as:

```text
provider=quark phase=resolve authenticated=true http=401 retry=1
provider=quark phase=proxy range=true upstream=206 bytes=1048576
provider=quark phase=cleanup owned=1 deleted=1 unrelated=0
```

Do not include raw headers, cookies, request bodies, IDs, query strings or URLs.

### 5. Release gate

Do not replace MDrive by default until all of these are true:

- The configuration boundary (`spider` JAR versus built-in site) is documented
  and covered by a regression test.
- Every enabled provider passes current public-read and device-login tests.
- At least one large-file seek/range test passes per streaming strategy.
- Remote-write providers pass mutation and crash-recovery audits.
- Logout and provider-side session revocation are verified.
- No credentials appear in logs, configs, proxy URLs, history, crash reports or
  test artifacts.
- Provider failure falls back explicitly; it never silently sends credentials
  back to an unknown external JAR.

## Immediate recommendation

Do not try more URL substitutions. Preserve the current MDrive path as a
controlled baseline, keep any OpenDrive route opt-in, and collect one failing
case through the gates above. The next useful artifact is a redacted failing
configuration plus phase/status logs, not another provider-code variation.

The candidate built-in route on `origin/release` is the right object to evaluate,
but only after rebasing/reviewing it as code and running the device/live matrix.
The two external repositories should remain protocol references, not binary or
credential-handling dependencies.
