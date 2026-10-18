# OpenDrive user credentials (non-netdisk services)

Part of the CatVod-plugin → OpenDrive improvement work. This note covers the
**Option A** deliverable: a generic account/credential registry for services that
are *not* cloud drives (danmaku, VIP parse sites, live OAuth, ...).

## Why a separate store, not a new OpenDriveProvider

`OpenDriveProvider` is a file-browsing contract (`list` / `resolve` /
`OpenDriveItem` / `OpenDrivePlayback`). A credential vault does not browse files;
it only stores, refreshes, and hands out secrets to other subsystems. Forcing it
into the provider interface would fabricate meaningless items, so it lives as a
standalone registry instead.

## What already existed (reused, not duplicated)

- `OpenDriveTokenStore` — encrypted, Android-keystore-backed secret store keyed by
  provider id; already covered by `AccountCredentialBackup` (references ending
  `/token` are exported/imported).
- `OpenDriveHandleVault` / `OpenDriveTokenVault` — the underlying encrypted vault.
- `OpenDriveTokenManager` — single account authority for built-in providers.

## New pieces

- `libraries/cloud-core/.../cloud/OpenDriveUserCredential.kt` — platform-neutral
  model (`UserCredentialMethod` enum + `OpenDriveUserCredential` data class). No
  Android imports, so it stays inside the `libraries/*-core` boundary.
- `app/.../cloud/opendrive/UserCredentialStore.java` — manages user-registered
  services:
  - metadata (serviceId, displayName, baseUrl, method) in app-private prefs
    `opendrive_usercred_meta`;
  - secret delegated to `OpenDriveTokenStore.put(serviceId, secret)` (encrypted +
    backup-covered).
  - API: `list()`, `get(serviceId)`, `getCookie(serviceId)`, `getToken(serviceId)`,
    `getSecret(serviceId)`, `save(...)`, `delete(serviceId)`.

## Backward compatibility / safety

- serviceId must match `[a-z0-9][a-z0-9_-]{0,31}` (the same rule
  `OpenDriveTokenVault.reference` enforces), so secrets land in valid vault keys.
- Secrets are stored under `vault:opendrive/<serviceId>/token` and therefore
  participate in account-credential backup/restore automatically.
- Metadata is non-secret; a backup/restore that loses it only loses display names,
  not the credentials themselves.

## Consumer wiring

### DanmakuApi (wired, v1)

`DanmakuApi.createCall` now looks up a stored credential via
`UserCredentialStore.findByUrl(apiUrl)` (host match on the credential's
`baseUrl`). When one matches, it replays the secret as a request header:

- `COOKIE` → `Cookie` header (raw cookie string from `getCookie`).
- `OAUTH_TOKEN` → `Authorization: Bearer <token>` (from `getToken`).
- `PASSWORD` → not replayable as a header; skipped.

This is **explicit credential replay** (the user saved the cookie/token in the
vault), not an in-app login flow, so it needs no per-service protocol evidence.
Behavior is unchanged when no credential matches the configured danmaku host.

To support the GET+headers+tag path, `OkHttp` gained a
`newCall(url, headers, tag)` overload (preserves `cancel(TAG)`).

## Management UI (done)

A settings screen lets users register, edit, and delete credentials without code:

- `ui/activity/UserCredentialActivity.java` — list + add/edit/delete, mirror of
  `WebDavConnectorActivity` but always enabled (no feature flag) and keyed by
  `serviceId` instead of a DB row id.
- `ui/adapter/UserCredentialAdapter.kt` — list adapter over
  `UserCredentialStore.list()` (`OpenDriveUserCredential`).
- Layouts: `activity_user_credential.xml`, `dialog_user_credential.xml`
  (main + `src/leanback` variant with `CustomEditText` focus outlines),
  `item_user_credential.xml`. The dialog has a `Spinner` for the credential type
  (`credential_method_entries` = Cookie / Bearer token / Password).
- Launch entry: a "Service logins" button in `CloudStorageActivity`, registered in
  `AndroidManifest.xml`.
- Strings added to `values/strings.xml` + `values-zh-rCN/strings.xml`.
- `check-leanback-remote.py` passes; `TvDialogFocus.form` takes only `EditText`s,
  so the `Spinner` is reachable via normal D-pad order, not the focus-form chain.

Editing keeps the saved secret when the secret field is left blank (mirrors the
WebDav "keep password" behavior). The `serviceId` field is locked while editing.

### Live / VIP-parse (auto-login still deferred — NOT planned near-term; replay available)

The management UI now lets a user **register** a `ctlook` (or any host) cookie /
token directly. Any future source/live consumer can replay it via
`UserCredentialStore.findByUrl(url)` exactly like `DanmakuApi` does — that is
explicit user replay, not auto-login.

**Decision (2026-09-20): we will NOT pursue unblocking real auto-login in the near
future.** The vault is therefore a user-replay registry only. Do not start an
auto-login implementation (e.g. the `ctlook` `/user/login.do` + `/WAF/VERIFY/`
sequence, or live OAuth token minting) without (a) fresh, readable protocol
evidence captured in `docs/provider-protocol-evidence.md` and (b) explicit user
direction to proceed. The corpus string-pool extraction is *not* evidence, and the
sequence must not be fabricated.

What remains deferred is the **in-app login flow** for `ctlook`-style VIP/parse
sites and live OAuth: it still requires **readable source or a device trace** of
the exact request order, headers, and cookie shape in
`docs/provider-protocol-evidence.md` before any consumer performs the login for
the user. The corpus string-pool extraction is *not* evidence. Do not fabricate
the `/user/login.do` + `/WAF/VERIFY/` sequence.

## Unit tests

- `libraries/cloud-core/src/test/.../OpenDriveUserCredentialTest.kt` — model
  invariants: non-blank `serviceId`/`displayName`, accessor correctness, and that
  `PASSWORD`/empty-`baseUrl` are allowed. Runs via `:libraries:cloud-core:test`.
- `app/src/test/.../UserCredentialHostsTest.java` — the host-matching rules used by
  `findByUrl`: exact host, subdomain, scheme/port-agnostic, blank/invalid never
  match, and **scheme-less stored base URLs** (a user may enter `lmteam.com` without
  `https://`) are tolerated. Runs via `:app:testMobileArm64_v8aVerificationUnitTest`.

Both suites are green (offline). They exercise the pure, JVM-safe logic; the secret
storage (Android keystore) and the full `UserCredentialStore` (needs `Init.context()`)
are intentionally not unit-tested on the JVM.

The tests surfaced a real bug: `HttpUrl.parse` returns `null` for scheme-less hosts,
so a user-entered `baseUrl` without a scheme would never match. `UserCredentialHosts
.hostOf` now retries with a default `https://` scheme.

## Not in scope

No catalog scraping, spider jars, or video parsing. Those belong to the source/parse
subsystem; this store only supplies credentials to them.
