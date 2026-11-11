# Desktop episode prewarming

Desktop prewarming defaults on for **reviewed non-cloud resolvers** while
“Play next automatically” is enabled. It resolves the next episode once during
active playback at the shared 80% progress threshold. It does not start another
player, fetch media segments, or download the episode. Paused/buffering playback
and unknown durations do not trigger it. Feedback currently comes from mpv;
players without playback-state feedback cannot trigger this workflow.

## Classification and evidence

The shared `catalog-core` contract distinguishes `READ_ONLY_STREAM`, `CLOUD`,
and `UNKNOWN` through `CatalogSourceExecutor.playbackPreparationSafety(flag,id)`.
The classification belongs to the executable resolver, per episode. It is not
inferred from a source's display name, a subscription declaration, an HTTP URL,
or lack of a recognized cloud label.

The separate `CatalogGateway.prewarm` operation defaults to no result. The local
executor gateway forwards this operation; its executor refuses cloud/unknown
requests before calling normal resolution. Remote/older gateways, downloaded
JVM spiders, JavaScript/Python runtimes and other adapters remain unknown unless
reviewed code explicitly implements the contract. Their ordinary playback is
unchanged. Subscription metadata cannot turn this permission on.

Initially reviewed implementations:

| Resolver | Evidence for opt-in | Rejected routes |
| --- | --- | --- |
| Built-in MacCMS | `play` validates and returns an existing HTTP(S) location; it performs no provider or account request | Non-HTTP identifiers and recognized cloud evidence |
| Built-in Libvio | A validated first-party `/w/<id>-<line>-<episode>.html` page is read and direct media data decoded; this path has no cloud-save operation | Foreign/invalid pages and recognized cloud evidence; the detail parser excludes netdisk panels |

Cloud evidence is a **veto**, never evidence that other routes are safe. The
shared policy uses the existing `CloudDriveDetector` plus the OpenDrive scheme.
The desktop cloud-routing decorator also vetoes episodes it recognized from
cached or fresh detail metadata, including a cloud line name with an opaque ID.
The metadata-cache wrapper preserves the underlying resolver classification.
Thus mixed sources can prepare a reviewed direct route without preparing a
cloud route. Labels are used only to exclude; positive permission comes from
the reviewed implementation.

For more reliable coverage, audit a new adapter's actual resolution path,
including wrappers and network calls, then opt in only its supported read-only
routes. Add offline tests proving the allowed path and proving that cloud and
unknown routes execute neither the provider nor cloud-save APIs. Changes to a
reviewed resolver must recheck this contract. For downloaded providers, a future
positive classification would require a reviewed artifact/version identity and
per-route contract; a source name or self-declared `safe` field is insufficient.

## Lifetime and recovery

`EpisodePrewarmSession` in `catalog-core` owns one memory-only result with a
60-second monotonic lifetime. Its key includes catalogue/account revisions,
source, series, line and episode. It preserves the complete playback result and
a snapshot of its headers. A matching result is consumed once before the next
selection resets the session. Expired, missing, failed or unfinished preparation
falls back to ordinary foreground resolution; it never holds up advancement.

Stop, cancellation, player closure/failure, catalogue replacement and a new
playback selection invalidate preparation. Superseded queued work is skipped;
already running read-only work may finish, but its stale result is discarded.
Account/cache-generation changes prevent reuse. Browsing to a different series
or line cannot prepare from the previous timeline or reuse its result.

This supersedes the blanket desktop deferral in the September desktop review
and the initial Hongguo review. Cloud and unknown resolution remain excluded.
Android's existing provider prewarming remains governed by its current workflow;
the shared progress gate continues to prevent paused/finished speculation there.

## Validation

Validation results are recorded in the 2026-11-04 Hongguo review follow-up.
No real provider, account or physical-device request is needed for these tests.
No measured network-latency improvement is claimed: MacCMS avoids no network
request, while a successful Libvio preparation can remove its page lookup from
the next episode's foreground path. Expiry/failure deliberately trade that
potential saving for a fresh lookup.
