# Cloud diagnostics and cleanup outcomes

The September 10 cloud playback investigation exposed two useful test-harness behaviors
that now also belong to the app: safe request diagnostics and explicit cleanup outcomes.

## Request diagnostics

`OpenDriveDiagnostics` is installed on the shared OpenDrive HTTP clients (including the
explicit-cookie client) and Quark's API/streaming clients. The Android log tag is
`OpenDriveDiagnostics`; no test runner is required. It records fixed provider labels,
request phase, HTTP status when headers arrive, and elapsed milliseconds. Parsed JSON
responses additionally expose bounded numeric `code` / `errno` values. String codes
are represented as `non_numeric`, and unknown hosts as `other`.

The component never logs URLs, hostnames, paths, query strings, cookies, request/response
bodies, or exception messages. It neither probes media nor reads response bodies for
logging, and leaves timeouts, retries, and route selection unchanged. Diagnostic sink
failures cannot interrupt requests. Other HTTP stacks are outside this coverage.

`headers` means a response arrived; `complete` means the HTTP call ended. Neither proves
that a decoder rendered a frame or that playback was smooth. The live harness retains
its fixture-specific probes and actual HLS segment checks; those are not automatic
production preflight requests.

## Cleanup outcomes

The existing `supported` flag describes adapter support, not cleanup success.
`CloudStorageManager.Result` now carries immutable `deletedIds`, `failedIds`, and
`skippedIds`. The manager validates that outcomes are tracked and non-overlapping.
A failed deletion remains tracked for a later user-initiated retry. Cleanup continues
with the remaining files and its audit records failure when any deletion failed.

Ali verifies the saved copy is still in its recorded parent before trashing it. An absent
or moved copy is skipped, its obsolete tracking record is retired as before, and it is
not counted as a deleted file. Account and ownership checks remain in the provider.
No new file discovery, global recycle-bin purge, or automatic retry is introduced.

The UI reports failure/skip counts in English, Simplified Chinese, and Traditional
Chinese. The legacy `freedBytes` field is the size of removed files; the UI no longer
claims verified quota recovery. In particular, Ali trashing is not permanent deletion.

## Offline regression coverage

- `OpenDriveDiagnosticsTest`: secret-bearing requests/errors are omitted, response bytes
  remain intact, and a broken diagnostic sink does not fail a request.
- `OpenDriveCacheCleanerTest`: partial failure, continuation, skipped files, and immutable
  outcome snapshots.
- `CloudStorageManagerTest`: conflicting and untracked outcome rejection.
- `AliSharedOriginalCacheTest`: moved copies report no deletion; verified trash reports
  deletion while preserving account and ownership boundaries.

## Structured playback failures and fallbacks

`cloud-core` now defines `OpenDriveFailure` with a provider label, reason, HTTP status,
and numeric API code. Its message contains only a stable provider/reason code so the
existing spider JSON boundary can carry it safely. The original cause remains available
to code, but is not included in the UI message or the new diagnostic event.

Both normal and prepared OpenDrive playback resolve through `OpenDriveFailures` after
the provider has completed its own recovery. HTTP 401 is authentication failure; 403 is
access denial; 404/410 is an unavailable resource (not proof that a disposable copy was
deleted). Known network failures are separate from provider failures. Cancellation and
programming errors retain their existing control flow. Suppressed errors, such as quota
exhaustion preceding a failed UC direct fallback, do not replace the final failure.

UC/Quark quota exceptions and Quark/Ali missing-rendition exceptions now carry explicit
reasons. Quark retains its specialized exception and legacy-message recognition so a
missing smart rendition still protects the saved original and stops its existing
source-skip flow. Generic classification grants no new retry or fallback permission.

The shared playback boundary no longer logs accounts out after HTTP errors. Listing
also no longer deletes credentials on 401 or mislabels 403 as expiry. Ali's existing
bounded refresh behavior remains inside its provider; a failed request does not justify
removing its refresh token or UC/Quark cookies.

Successful fallback results carry `OpenDrivePlaybackHints.FallbackReason` through an
internal header. Existing branches emit these reasons only after resolving media:

- Quark HTTP 400/API 21005: normalized original because the provider cannot transcode it.
- UC quota exhaustion: shared original without saving a copy.
- Ali shared-original HTTP 403: original through the tracked cloud-copy path.
- Baidu preview errno 71: original after the existing preview rejection check.

The player maps these notices and structured failures to English, Simplified Chinese,
and Traditional Chinese messages. `PlaybackTransportPlan` removes the new header
case-insensitively before creating media request headers, including unknown values.
The legacy Quark notice remains compatible. These descriptions do not change route
labels, selection quality, cleanup, or decoder verification requirements.

## Saved-copy lifecycle and stream tools

`SavedCopyPolicy` supplies shared ownership/age predicates; Ali's original-copy cache
uses it while retaining exact parent membership checks and provider-specific requests.
A process-wide Ali copy lock serializes resolve/copy/cleanup across adapter instances,
so simultaneous resolution of the same tracked source reuses one copy. Quark account
restoration now clears its saved-source/account maps along with handles and streams.
UC keeps its same-source reuse and previous-source cleanup contracts.

`SavedCopyLeases` and `OpenDriveCopyUsage` protect copies from save completion through
player startup. Startup holds expire after 15 minutes using a monotonic clock. An active
player or stream diagnostic pins its opaque token; active pins survive expiry and are
reference counted. The player releases protection after stop/release and replaces it
when starting another resource. Clearing metadata alone does not release a running
player's protection. Internal tokens are removed from media request headers and hidden
from the playback-info header display.

Ali, UC, Quark, and manual cleanup consult the shared protection. Deletion reserves the
copy identity before performing network I/O; a concurrent handoff cannot acquire that
identity during deletion. Failure releases the reservation for a later retry. UC/Quark
cleanup no longer forgets reuse information before deletion succeeds. Manual cleanup
also requires the current account snapshot and the exact built-in source marker.
Older records with a different account-cookie snapshot are conservatively skipped;
this batch does not infer ownership across cookie rotation or migrate unknown records.

Protection is process-local, not a durable proof that an external cast client is active.
After process death the providers' existing tracked-copy/expiry rules still apply.
Existing proxy activity protection remains in place. No background scheduler or account
sweep is added.

On phone, open playback info and choose **Check stream**. On TV, the D-pad-accessible
**Stream tools** control opens the same dialog. Back/Cancel closes the check and cancels
its active HTTP call. The check uses the currently resolved resource and never saves a
copy, resolves another route, or performs cleanup. It pins any existing copy lease while
running. Checks are bounded to 20 seconds, eight requests, four playlist levels, four
redirect attempts per fetch, 256 KiB per playlist, and 1 KiB for a media sample.

An HLS result requires bytes from a referenced media resource, not just playlist text.
The check rejects text/JSON/XML error responses and reports HTTP failures without
printing URLs or credentials. Cross-origin requests drop explicit credentials and all
headers except User-Agent/Accept; a credential-dependent cross-origin stream may fail
this conservative diagnostic even when another playback transport succeeds. HTTPS and
the app's current local proxy are supported; unsupported addresses report that limit.
The diagnostic does not verify decryption, decoding, performance, or full-video stability.
