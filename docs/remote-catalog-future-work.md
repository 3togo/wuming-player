# Remote catalog API — future-work handoff

Status: proposed, not implemented  
Reviewed: 2026-08-09  
Reference project: [kknifer7/TV-K](https://github.com/kknifer7/TV-K)

## Outcome of the TV-K review

TV-K's useful, project-specific idea is not WebSocket itself. It allows a
companion application to use the Android device as a catalog/provider gateway:
the companion can list sources, browse home and category results, search, load
details, resolve playback, read live sources, and synchronize history and
favorites.

TV-K implements this as 13 commits on top of its pinned `fongmi` branch. Its
custom delta is approximately 3,527 added lines, concentrated in the
`io.knifer.freebox` package. The relevant starting points are:

- [MessageCodes.java](https://github.com/kknifer7/TV-K/blob/73793d13a563a157a3400a602d83288a1decd064/app/src/main/java/io/knifer/freebox/constant/MessageCodes.java)
- [Message.java](https://github.com/kknifer7/TV-K/blob/73793d13a563a157a3400a602d83288a1decd064/app/src/main/java/io/knifer/freebox/model/common/Message.java)
- [WSService.java](https://github.com/kknifer7/TV-K/blob/73793d13a563a157a3400a602d83288a1decd064/app/src/main/java/io/knifer/freebox/websocket/service/WSService.java)
- [WSClient.java](https://github.com/kknifer7/TV-K/blob/73793d13a563a157a3400a602d83288a1decd064/app/src/main/java/io/knifer/freebox/websocket/WSClient.java)
- [WSHelper.java](https://github.com/kknifer7/TV-K/blob/73793d13a563a157a3400a602d83288a1decd064/app/src/main/java/io/knifer/freebox/websocket/WSHelper.java)
- [initial FreeBox integration commit](https://github.com/kknifer7/TV-K/commit/f08510e145fb1471cdaf845f475e258ebdf0c1f1)
- [search cancellation commit](https://github.com/kknifer7/TV-K/commit/4b6820c2c449461ac7eb1568c4fe2cd5295e4723)

The recommended direction is to borrow the catalog-gateway behavior and the
ideas of request correlation, cancellation, protocol versions, and capability
discovery. Do not cherry-pick TV-K's transport or service implementation.

## Product scope and decision gate

The recommended first use case is:

> A paired phone or browser browses and searches the catalog configured on a
> Leanback TV, then asks the TV to play the selected item or obtains a
> short-lived, scoped relay URL.

This fits the existing flavor policy and reuses the TV's local HTTP server,
pairing QR, and bearer credential.

Before implementation, confirm this scope. The exact TV-K topology is
different: the Android **mobile** flavor connects to a desktop FreeBox server
and acts as its provider backend. Supporting that topology would require a
separate product and security decision because
[`remote-access-security.md`](remote-access-security.md) currently requires the
mobile flavor to reject remote administration. It would also require a
deliberate foreground-service and background-network lifecycle design. Do not
silently broaden the first implementation to include it.

## What the project already has

Do not reimplement these capabilities:

| Existing capability | Reuse point |
| --- | --- |
| Five-minute QR pairing, 256-bit persistent bearer credential, credential rotation | [`RemoteAccess.java`](../app/src/main/java/com/wuming/android/tv/server/RemoteAccess.kt) |
| Local NanoHTTPD server and process routing | [`Nano.java`](../app/src/main/java/com/wuming/android/tv/server/Nano.java), [`Server.java`](../app/src/main/java/com/wuming/android/tv/server/Server.kt) |
| Push, UI search, player control, history/favorite synchronization | [`Action.java`](../app/src/main/java/com/wuming/android/tv/server/process/Action.java) |
| Provider home/category/detail/player/search operations | [`SiteApi.java`](../app/src/main/java/com/wuming/android/tv/api/SiteApi.java) |
| Search timeouts, cancellation, stale-result rejection, and task cleanup | [`SiteViewModel.java`](../app/src/main/java/com/wuming/android/tv/model/SiteViewModel.kt) |
| History identity including source, VOD, play line, episode, and config | [`History.java`](../app/src/main/java/com/wuming/android/tv/bean/History.java) |
| Short-lived, path-scoped cast media capabilities | [`CastMediaAccess.java`](../app/src/main/java/com/wuming/android/tv/server/CastMediaAccess.java) |

The net-new capability is remotely **returning catalog data**. The existing
`do=search` action only opens the app's search UI; it does not return search
results to the controller.

## Proposed API shape

All paths below are provisional. Keep them under one protected prefix so the
authorization boundary is easy to audit.

| Method and path | Purpose | Phase |
| --- | --- | --- |
| `GET /api/v1/info` | API version, device role, and supported capabilities | 1 |
| `GET /api/v1/catalog/sources` | Sanitized source identifiers and display names | 1 |
| `POST /api/v1/catalog/search` | Search selected source(s) and return or enqueue results | 1 |
| `GET /api/v1/requests/{requestId}` | Poll result/progress for an asynchronous request | 1 |
| `DELETE /api/v1/requests/{requestId}` | Cancel only the named request | 1 |
| `POST /api/v1/catalog/home` | Load a source's home content and categories | 2 |
| `POST /api/v1/catalog/category` | Load a category page with filters | 2 |
| `POST /api/v1/catalog/detail` | Load VOD details, play lines, and episodes | 2 |
| `POST /api/v1/catalog/play` | Start TV playback or create a scoped relay capability | 2 |
| `GET /api/v1/catalog/live` | Return sanitized live groups/channels | 3 |
| `GET/PUT/DELETE /api/v1/library/history` | Read or mutate history | 3 |
| `GET/PUT/DELETE /api/v1/library/favorites` | Read or mutate favorites | 3 |

Prefer normal HTTP request/response and polling for the first version. Add
server-sent events or WebSocket progress only after a real client requires
push updates; a second transport should not be introduced speculatively.

### Common response envelope

Use one envelope for successful and failed operations. Do not silently convert
provider failures to empty results.

```json
{
  "apiVersion": 1,
  "requestId": "client-or-server-generated-id",
  "ok": true,
  "data": {},
  "error": null
}
```

```json
{
  "apiVersion": 1,
  "requestId": "client-or-server-generated-id",
  "ok": false,
  "data": null,
  "error": {
    "code": "PROVIDER_TIMEOUT",
    "message": "The source did not respond in time",
    "retryable": true
  }
}
```

Suggested capabilities include:

- `catalog.sources`
- `catalog.search`
- `catalog.home`
- `catalog.category`
- `catalog.detail`
- `catalog.playOnTv`
- `catalog.playerRelay`
- `catalog.live`
- `library.history.read` and `library.history.write`
- `library.favorites.read` and `library.favorites.write`
- `requests.cancel`

Capabilities are more useful than a version integer alone. Clients must ignore
unknown response fields and disable UI for unsupported capabilities.

## Data and identity rules

Create dedicated wire DTOs. Do not serialize `Site`, `Result`, `History`,
`Keep`, database entities, or configuration objects directly.

The source DTO may contain a stable source key, display name, and safe feature
flags. It must not contain provider API URLs, `ext`, JAR paths, request headers,
cookies, proxy credentials, cloud credentials, or arbitrary configuration
fields.

Every catalog selection must retain enough identity to avoid the multi-line
resume bug that TV-K later fixed:

- configuration ID or a stable configuration identity;
- source key;
- VOD ID;
- play-line flag;
- episode name/flag and episode ID or URL where appropriate.

History writes must preserve incognito behavior. A remote request must not
create history while `Setting.isIncognito()` is active.

Support pagination and explicit result limits from the start. Bound request
body sizes, keyword lengths, source counts, concurrent jobs, per-source result
counts, and completed-job retention.

## Playback security

Returning a resolved upstream URL and headers can expose cookies, authorization
headers, signed URLs, DRM material, or provider implementation details. The
default operation should ask the TV to play the selection.

If companion-side playback is required, prefer a short-lived capability scoped
to one resolved media resource. Reuse or extend `CastMediaAccess` and the cast
relay rather than returning upstream secrets. Define expiry, invalidation,
range-request behavior, HLS child-resource scope, and DRM limitations before
advertising `catalog.playerRelay`.

Never put the persistent pairing credential in a URL. Continue sending it only
as `Authorization: Bearer <token>`.

## Suggested implementation structure

Names are provisional, but keep transport, business logic, and DTOs separate:

```text
app/src/main/java/com/wuming/android/tv/
├── server/process/RemoteCatalog.java       # NanoHTTPD route adapter
├── server/remote/RemoteApiResponse.java    # response/error envelope
├── server/remote/RemoteCatalogService.java # provider/library facade
├── server/remote/RemoteRequestRegistry.java
└── server/remote/dto/                       # sanitized request/response DTOs
```

Implementation notes:

1. Add the process to `Nano.addProcess()` before the asset fallback.
2. Add `/api/` to `RemoteAccess.isProtected()` before exposing any endpoint.
   Keep Leanback pairing behavior and mobile rejection intact.
3. Return `application/json; charset=utf-8` with meaningful HTTP status codes.
4. Validate every source key against the active `VodConfig`; never accept a
   caller-supplied provider URL, class name, JAR, `ext`, or headers.
5. Run provider work on bounded project executors, never NanoHTTPD or UI
   threads. Apply existing VOD/search timeouts.
6. Extract a shared search coordinator if necessary. Do not instantiate a UI
   `ViewModel` in the server layer, and do not copy the provider algorithms out
   of `SiteApi`/`SiteViewModel` into a second large service.
7. Track jobs by an unguessable request ID plus the paired controller identity
   if controller-specific tokens are introduced. Cancellation must affect only
   that request, not every search in the application.
8. Remove completed/failed/cancelled jobs after a short TTL and cancel all jobs
   when the server stops or the pairing credential is rotated.
9. Redact secrets and full media URLs from logs and error responses.

## Phased implementation checklist

### Phase 0 — contract and threat model

- [ ] Confirm Leanback-hosted companion browsing as the first use case.
- [ ] Decide whether the first client is the packaged web UI, the mobile flavor,
      or an external client; document its required operations.
- [ ] Specify DTOs, limits, error codes, request lifecycle, and capabilities.
- [ ] Add the new protected prefix and authorization tests before handlers.
- [ ] Document whether a pairing credential represents one controller or all
      paired controllers; decide whether per-controller revocation is required.

### Phase 1 — useful vertical slice

- [ ] Implement `info`, sanitized `sources`, and single/multi-source `search`.
- [ ] Implement request status, per-request cancellation, timeout, and cleanup.
- [ ] Reuse current query planning, strict-search behavior, result annotation,
      and merge rules where the remote contract requests aggregate search.
- [ ] Add a minimal client flow that proves pair → list sources → search →
      cancel/retry.
- [ ] Update [`local.md`](local.md) and
      [`remote-access-security.md`](remote-access-security.md).

### Phase 2 — browse and play

- [ ] Add home, category, and detail with pagination and filter DTOs.
- [ ] Add `playOnTv` without exposing raw provider credentials.
- [ ] Design and implement scoped media relay only if companion playback is a
      confirmed requirement.
- [ ] Confirm provider login/interactive actions are either explicitly modeled
      or rejected with a structured unsupported-action error.

### Phase 3 — library and live

- [ ] Add history and favorites using full playback identity and conflict rules.
- [ ] Preserve incognito mode and configuration boundaries.
- [ ] Add live browsing with sanitized channel DTOs and bounded EPG data.
- [ ] Consider incremental events only after polling behavior is measured.

## Test plan and acceptance criteria

At minimum, cover:

- `/api/` is denied without a valid bearer token and allowed after QR pairing;
- mobile still rejects remote administration;
- DTO serialization cannot expose `api`, `ext`, JAR paths, headers, cookies,
  credentials, pairing tokens, or raw database-only fields;
- unknown source keys and malformed/oversized payloads fail before provider
  execution;
- request IDs correlate concurrent searches and cancellation affects only the
  requested job;
- timeouts, provider exceptions, cancellation, and unsupported operations
  produce stable structured errors;
- completed jobs are removed and disconnected clients cannot leak work;
- history round trips preserve config, source, VOD, play line, episode,
  position, and duration;
- incognito mode blocks remote history writes;
- pairing-token rotation immediately rejects old credentials and invalidates
  outstanding remote jobs;
- relay URLs, if implemented, are resource-scoped, expire, and cannot traverse
  to sibling paths.

Run the proportional project gates while developing:

```bash
./gradlew unitAcceptanceBaseline
./gradlew assembleCriticalTests    # compile-only integration check
./gradlew connectedCriticalTests   # optional release-readiness evidence on an authorized device
```

The release candidate must pass `./scripts/verify-release.sh`. Its acceptance decision is based on
the offline unit baseline and release lint; connected checks remain separate hardware evidence.

The Phase 1 feature is complete only when a paired client can discover
capabilities, receive a sanitized source list, run concurrent searches, cancel
one without disturbing another, and receive structured results/errors, with
authorization and secret-redaction regression tests in place.

## TV-K implementation hazards to avoid

These findings explain why the reference code should not be copied wholesale:

- manual IP/port entry and cleartext `ws://` are used without an application
  credential;
- registration sends an Android identifier, client name/type, and protocol
  version, but does not authenticate the server or client;
- full source objects, resolved playback results, history, and favorites cross
  that connection;
- `WSHelper`'s `wss` URI expression constructs only the literal `wss`, while
  reconnect in `WSClient` uses the local socket address instead of the remote
  peer;
- cancellation is global to all remote searches, and completed futures remain
  retained until a later global cancellation;
- provider logic is duplicated in a large transport service and many
  exceptions are converted silently to empty results;
- the foreground service attempts indefinite wake/Wi-Fi locks, while its
  manifest does not declare the wake-lock permission;
- no tests under TV-K's test source sets reference the custom FreeBox package.

Both repositories carry GPLv3 license text, but compatible licensing does not
remove the architectural, security, and maintenance costs above. If a small
piece is eventually adapted rather than independently implemented, preserve
the appropriate authorship and license notices.
