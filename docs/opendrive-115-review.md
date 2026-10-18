# 115 Built-in Provider Review

Review date: 2026-08-03

## Decision

Use only the documented 115 Open Platform API for a future built-in provider.
Do not bundle or port the legacy cookie-based encrypted protocol. Keep 115 on the
downloaded CatVod route until the Open API integration passes a live account test.

This is a protocol and trust decision, not a claim that the downloaded route is
safe. A downloaded JAR remains outside the built-in trust boundary.

## Candidates

### OpenListTeam/115-sdk-go

- Reviewed main commit: `788107ec0545cf126ee9b22329678679cc6b3b2a`
- Version used by OpenList at review time: `v0.2.6`
- License: Apache-2.0
- API surface: documented `passportapi.115.com/open/*` and
  `proapi.115.com/open/*` endpoints for device authorization, refresh, listing,
  download URLs, and other account operations.
- Authentication: OAuth access/refresh tokens. Device authorization uses PKCE and
  requires a 115-issued client/application ID, but no client secret in the APK.
- Verification: `go test ./...` and `go vet ./...` passed on the reviewed main
  commit. A `govulncheck` run found a reachable advisory in the pinned `x/net`
  dependency, so the current Go module must not be shipped unchanged.
- Android fit: Go source, not a JVM library or maintained AAR. Wrapping it with
  gomobile would add native ABI, runtime, update, and dependency-audit burdens.

The SDK is accepted as a behavioral reference, not as an APK dependency. A Java
adapter should reproduce only the required documented calls using the app's
existing pinned OkHttp/Gson stack.

### SheltonZhu/115driver

- Reviewed main commit: `d89999540a6d35838582ac06e31f81312d1f66fd`
- Latest tag at review time: `v1.3.5`
- License: MIT, with the repository's additional non-binding policy notice.
- API surface: cookie login plus published and unpublished consumer endpoints,
  including custom RSA/ECDH protocol code and Aliyun OSS upload support.
- Verification: `go vet ./pkg/...` passed. `go test ./pkg/...` is not hermetic: its
  default tests made live requests without credentials, failed authentication,
  and one test subsequently panicked. Its vulnerability scan also inherited the
  current Go runtime findings and the reachable `x/net` advisory.
- Android fit: Go source with a substantially larger dependency and protocol
  surface. It would also conflict with 115's Open Platform rule against using
  unpublished interfaces for an approved application.

The legacy library is rejected for the built-in route. Its crypto implementation
must not be copied into TVBox merely to preserve compatibility with opaque CatVod
payloads.

## Required Integration

The future `115OpenDriveProvider` must meet all of these conditions:

1. Obtain a dedicated 115 Open Platform application ID for TVBox/OpenDrive.
2. Use the first-party device-code PKCE flow directly from the app. Do not send
   authorization codes, access tokens, or refresh tokens through OpenList or any
   other token relay.
3. Store tokens in app-private OpenDrive storage; never put them in configuration
   JSON, logs, resource IDs, action strings, or proxy query parameters.
4. Implement only account listing, token refresh, download URL authorization, and
   an opaque local range proxy against documented `open/*` endpoints.
5. Do not claim external 115 episode payloads until their exact capability fields
   are documented and can be converted without accepting cookies or encrypted
   blobs from a downloaded JAR.
6. Pass JVM contract tests, a device login/list test, and an authenticated
   `Range: bytes=0-1023` playback request before the provider is registered as
   built in.

The remaining external dependency is therefore concrete: a 115-issued
application ID. An application secret is neither required nor acceptable in the
APK for the selected device-code flow.
