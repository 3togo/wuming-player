# Cloud Login Consolidation — Design (for review, no code yet)

Status: **design only**. Goal: eliminate the duplicated QR-login flows for
AliPan / Baidu / Quark / UC by making the Android app reuse the single Kotlin
implementation in `libraries/runtime-core`, instead of carrying its
own parallel copy in `app/.../cloud/opendrive/*OpenDriveProvider.java`.

---

## 1. Current state (verified)

| Concern | Android (`app`) | Shared / desktop (`runtime-core`) |
|---|---|---|
| Module dependency | `:app` → `:libraries:cloud-core` only | `:desktop` → `cloud-core` + `runtime-core` |
| Login code | `AliPan/Baidu/Quark/UcOpenDriveProvider.java` (storage **+** login) | `QrCloudAccountProviders.kt` (`AliPan/Baidu/Quark/UcCloudAccountProvider`, login only) |
| Contract | `OpenDriveProvider extends CloudStorageProvider extends CloudAccountProvider` | `QrCloudAccountProvider extends CloudAccountProvider` |
| Storage | list / resolve / importExternal / proxy | none |
| Credential store | `OpenDriveTokenStore` (AliPan JSON) + `PersistentCookieJar` (Baidu/Quark/UC cookies) | `CloudCredentialStore` (`read/write/remove(providerId)`) |
| HTTP | `OpenDriveHttp` (OkHttp) | `CloudLoginTransport` (`get/postForm/postJson` → JSON + Set-Cookie) |

Both sides implement the **same** `CloudAccountProvider` methods:
`id()`, `displayName()`, `available()`, `availabilityMessage()`, `authenticated()`,
`verifyAuthentication()`, `supportsQrLogin()`, `beginLogin(): OpenDriveLoginChallenge`,
`pollLogin(String): OpenDriveLoginState`, `logout()`. The Kotlin module is already
parameterized by two platform-agnostic seams — `CloudCredentialStore` and
`CloudLoginTransport` — which is exactly what makes sharing possible.

---

## 2. Why it is safe to consolidate

1. **Contract identity.** Same interface, same return types. A Kotlin
   `CloudAccountProvider` instance is directly substitutable for the auth half of
   an Android `OpenDriveProvider`.
2. **Session orchestration already matches.** `OpenDriveTokenManager.beginLogin`
   stores `challenge.id()` as the active session; `pollLogin(providerId, sessionId)`
   forwards that `sessionId` to the provider. The Kotlin providers emit a UUID
   `sessionId` inside `OpenDriveLoginChallenge`. No orchestration change needed.
3. **AliPan credential format already identical.** Kotlin writes the OAuth token
   JSON via `credentials.write(id, json)`; Android storage reads it via
   `OpenDriveTokenStore.get(id)` + `Token.parse`. Same string. ✅
4. **HTTP seam maps 1:1.** `OpenDriveHttp.get/postForm/postJson` wrap `CloudLoginTransport`.

---

## 3. The one real mismatch: Baidu / Quark / UC credentials

Kotlin writes a single **`Cookie` header string** (`credentials.write(id, cookieHeader())`).
Android storage reads cookies from `PersistentCookieJar.get().loadForRequest / loadForStorageDomain`.
These must be bridged by an adapter (Section 5).

---

## 4. Build change

`app/build.gradle` (~line 366, next to `cloud-core`):

```gradle
implementation project(':libraries:cloud-core')
implementation project(':libraries:runtime-core')   // NEW
```

This is harmless until a provider actually references it, so it can land first.

---

## 5. New Android adapters (live in `:app`)

Both adapters reference Android-only classes (`OpenDriveTokenStore`,
`PersistentCookieJar`), so they are authored in `:app`, not in the shared module.
They are constructed once (e.g. in `OpenDriveEngine`) and injected into each
Android provider.

### 5.1 `AndroidCloudCredentialStore implements CloudCredentialStore`

```java
String read(String providerId);      // returns raw credential the Kotlin login wrote
void  write(String providerId, String credential);
void  remove(String providerId);
```

- **AliPan** (`id == "alipan"`): delegate to `OpenDriveTokenStore.get/put/clear`.
  Format is the OAuth JSON — already what Android storage expects. ✅
- **Baidu / Quark / UC**: keep `PersistentCookieJar` as the **authoritative**
  store (so the existing `AccountCredentialBackup` export/import — which already
  backs up `PersistentCookieJar` for these hosts — keeps working unchanged):
  - `write(id, cookieHeader)`: parse `k=v; k=v` into `okhttp3.Cookie`s and call
    `PersistentCookieJar.get().saveFromResponse(loginUrl(id), cookies)`.
  - `read(id)`: `PersistentCookieJar.get().loadForStorageDomain(host(id))` →
    rebuild the `Cookie` header string (name=value joined by `; `).
  - `remove(id)`: `PersistentCookieJar.get().clear(host(id))` + `clearWebView(...)`.

`host(id)` mapping: baidu→`pan.baidu.com`, quark→`pan.quark.cn`, uc→`drive.uc.cn`.
Verify `PersistentCookieJar.loadForStorageDomain` returns name/value pairs that
round-trip losslessly through a header (no reliance on expiry/secure flags for
`authenticated()` — those only check presence of `__pus` / `BDUSS*`).

### 5.2 `AndroidCloudLoginTransport implements CloudLoginTransport`

Wrap `OpenDriveHttp`. Each method returns `CloudLoginResponse(bodyJson, setCookies)`
(resp. `CloudLoginTextResponse`), collecting `Set-Cookie` response headers into the
`List<String>` the Kotlin providers accumulate via `cookiePairs(...)`.
Use the **explicit-cookie** path of `OpenDriveHttp` (it already supports passing an
explicit cookie string / headers) so the transport does not double-apply its own
cookie jar during login — the Kotlin providers manage cookies in `session.cookies`
themselves.

> Note: Kotlin default args are not visible from Java, so construct with both
> params: `new AliPanCloudAccountProvider(credentialStore, transport)`.

---

## 6. Per-provider refactor (incremental, one at a time)

For `AliPanOpenDriveProvider`, `BaiduOpenDriveProvider`, `QuarkOpenDriveProvider`,
`UcOpenDriveProvider`:

1. Add a `CloudAccountProvider loginDelegate` field, injected at construction:
   `new AliPanCloudAccountProvider(androidCredentialStore, androidTransport)`, etc.
2. Delegate the auth surface:
   - `beginLogin()` → `loginDelegate.beginLogin()`
   - `pollLogin(sessionId)` → `loginDelegate.pollLogin(sessionId)`
   - `authenticated()` → `loginDelegate.authenticated()`
   - `verifyAuthentication()` → `loginDelegate.verifyAuthentication()`
   - `logout()` → `loginDelegate.logout()` (adapter clears the stores)
3. **Delete** the now-dead login bodies: QR generate/poll/confirm, `validQrValue`,
   token/session fields, AliPan `aliRefreshToken`, WebView/QR specifics.
4. **Keep** all storage: `list`, `resolve`, `importExternal` / `prepareExternal`,
   `proxy`, `persistHandle`, `credentialsRestored`.
5. Keep `id()`, `displayName()`, `available()`, `availabilityMessage()`,
   `supportsQrLogin()` on the Android side (do **not** delegate `available()` —
   verify it has no Android-specific gating; if it is just `return true`, delegating
   is also fine). Only the five auth methods are delegated.

`credentialsRestored()` currently clears the in-memory `token` field +
`OpenDriveTokenStore` + `PersistentCookieJar`. After refactor, persisted credentials
are the source of truth and must survive restore; change it to only drop the
in-memory snapshot (the Kotlin provider instance is long-lived and its `sessions`
map is already empty between logins), and stop wiping `PersistentCookieJar` /
`OpenDriveTokenStore` on restore.

---

## 7. Secondary cleanup (optional, can precede consolidation)

`QrCloudAccountProviders.kt` repeats boilerplate across the 4 classes
(`sessions` map, `logout()`, cookie-based `authenticated()`/`headers()`/
`hasAccountCookie`, `validValue`). Extract a `AbstractQrCloudAccountProvider` /
shared helpers (AliPan differs — JSON token — so keep it separate). Low risk,
desktop-only, and shrinks the surface that Android will depend on.

---

## 8. Test plan

- Desktop: `./gradlew :libraries:runtime-core:test :desktop:test`
  (existing `DesktopCloudAccounts` behavior must be unchanged — it still uses
  `desktopQrCloudProviders` with `JdkCloudLoginTransport` + `SecretServiceCredentialStore`).
- Android unit: `OpenDriveQrCapabilityTest` (4 providers `supportsQrLogin()`) must
  still pass. `Baidu/Uc/QuarkOpenDriveProviderTest`, `ExternalProviderImportTest`
  must still pass.
- Because the Kotlin providers accept `CloudCredentialStore` + `CloudLoginTransport`
  via constructor, login can be unit-tested on Android **without** network by
  injecting fakes — add a test constructing
  `new AliPanCloudAccountProvider(fakeStore, fakeTransport)` and asserting
  `beginLogin/pollLogin/CONFIRMED` writes the expected credential.
- Review instrumented tests that reached into old login internals
  (`QuarkSessionInstrumentedTest`, `OpenDriveTokenVaultInstrumentedTest`,
  `QuarkProbeDeadlineInstrumentedTest`) — adjust or drop the parts tied to removed
  `NetworkTransport` login code.
- Manual (TV device / emulator): QR-login each of the 4 providers; list + play a
  file; logout clears state; relaunch restores the session.

---

## 9. Rollback / blast radius

- Each provider is refactored independently and revertible file-by-file.
- The `runtime-core` dependency is inert until a provider uses it.
- No shared-module behavior changes for desktop (it keeps `desktopQrCloudProviders`).
- If a provider regresses in QA, revert that single `*OpenDriveProvider.java`.

---

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| `Cookie` header ↔ `PersistentCookieJar` round-trip loses flags needed by storage | Keep `PersistentCookieJar` authoritative for cookie providers; `read` rebuilds header from it (lossless for presence checks). |
| `AccountCredentialBackup` stops covering cookie providers | It already backs up `PersistentCookieJar`; since we keep that authoritative, backup/restore is unchanged. |
| `available()` has hidden Android gating | Keep Android's `available()`/`displayName()`; only delegate auth methods. |
| Java can't see Kotlin default transport param | Always pass both constructor args from Java. |
| `OpenDriveHttp` auto-cookie-jar double-applies cookies in login | Use its explicit-cookie/header path so Kotlin owns cookie state. |

---

## 11. Suggested sequencing

1. `app/build.gradle`: add `runtime-core` dependency.
2. (Optional) Section 7 dedup inside `QrCloudAccountProviders.kt`.
3. Add `AndroidCloudCredentialStore` + `AndroidCloudLoginTransport` in `:app`.
4. Refactor **AliPan** first (format already compatible — lowest risk); build +
   test + manual login.
5. Refactor **Baidu**, then **Quark**, then **UC** (cookie-adapter path); each
   build + test + manual login.
6. Remove dead login code as each provider is verified.
7. Final full Android + desktop test pass; manual regression on all 4.

## 12. Implementation log (2026-09-08)

Implemented as designed. Summary of changes:

- `app/build.gradle`: added `implementation project(':libraries:runtime-core')`.
- New `app/.../cloud/opendrive/AndroidCloudCredentialStore.java`: bridges `CloudCredentialStore`
  onto `OpenDriveTokenStore` (authoritative for all 4 providers) and mirrors cookie providers
  (baidu/quark/uc) into `PersistentCookieJar` so `AccountCredentialBackup` export/import still covers
  them.
- New `app/.../cloud/opendrive/AndroidCloudLoginTransport.java`: wraps `OpenDriveHttp` (explicit-
  cookie client) and translates socket timeouts to `CloudLoginTimeoutException`.
- `OpenDriveHttp`: added `getTextExplicit(String, Map, Map)` + `TextResponse` (raw text + Set-Cookie)
  so Baidu's `getText` confirmation can observe cookies.
- New `libraries/runtime-core/.../CloudLoginTimeoutException.kt`; Baidu `pollLogin`
  now treats `CloudLoginTimeoutException` (and `HttpTimeoutException`) as `WAITING`, preserving the
  "keep polling" semantics on the Android OkHttp transport.
- `AliPan/Baidu/Quark/UcOpenDriveProvider`: each holds a `CloudAccountProvider loginDelegate`
  (the shared Kotlin provider) wired in its constructor; `beginLogin`/`pollLogin`/`authenticated`/
  `verifyAuthentication`/`logout` delegate to it. Storage (list/resolve/importExternal/proxy),
  `QuarkTransport`/`NetworkTransport`/`QuarkHttpException`, and credential backup/restore are
  unchanged.
- Kept `BaiduOpenDriveProvider.parseBaiduJsonp` because `BaiduOpenDriveProviderTest` uses it.

Verification:
- `:app:compileMobileArm64_v8aDebugJavaWithJavac` → BUILD SUCCESSFUL.
- `:app:testMobileArm64_v8aVerificationUnitTest` → BUILD SUCCESSFUL (all cloud unit tests pass).
- `:libraries:runtime-core:test` → BUILD SUCCESSFUL.

Residual (intentional, harmless warnings): a few login-only constants/helpers (`QR_*`, `qrContent`,
`loginHeaders`, `nonceQuery`, `validLoginValue`, and the `loginState`/`clearLogin` fields) remain in
the Android providers — still referenced by retained helpers or test constructors — but no longer
participate in login.
