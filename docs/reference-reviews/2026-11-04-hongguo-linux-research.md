# Hongguo research adaptation — 2026-11-04

This bounded review uses the user-specified sibling checkout
`~/git/hongguo-linux-research`, at
`314004435ecb0b3f4420655fb2515292803693cf`. No reference update, live provider
request, account operation, emulator export, device installation, or publication
was performed. Wuming started clean at
`cf8ba7f94caf9a209fff7a6cc88ba69c5ca4a2cd`.

**Follow-up:** desktop preparation is now enabled for explicitly reviewed non-cloud resolvers, as requested later in this chat. See the follow-up below and [the current contract](../desktop-prewarming.md). The initial deferral below records the earlier review decision.

## Reviewed scope

The research root README predates its newer guest client. Read both before
interpreting the project's capabilities: the early login prototype and the
current guest short-drama client have different contracts.

Examined scopes:

- Root README and recent commit summaries.
- `hongguo-guest-tv/README.md`, Android `Catalog.java`, preparation/completion
  portions of `TvActivity.java`, and the event bridge in `EpisodeBrowser.java`.
- `src/main/assets/transport/{episode-events,account}.js` and transport tests.
- Desktop `continuation.py` and `continuation.js`.
- The independent `hongguo-tv/backend/linux_login.py` account verification
  contract and its test cases.

This is not an exhaustive review of the Xposed module, creator platform,
export-helper patches, UI, authentication system, or media standards support.
Reference descriptions of past device/login successes were not reproduced.

## Ideas and decisions

**Adopted: speculative work requires active playback.** The guest client's
`episodeNearEnd` rejects paused, seeking, errored, and finished media. Wuming's
existing shared `PlaybackSessionProgress` used position alone, so a paused
Android timeline at 80% could initiate a provider request and spend its one-shot
attempt. `VideoPlaybackSession` already receives `player().isPlaying()` but
previously did not pass it into that policy.

The policy now accepts that existing playing signal. Non-playing and completed
or out-of-range positions do not prewarm; resumed playback can still spend the
attempt once. Mobile and both leanback flavors use the same Android adapter and
core policy. Keep the existing 80% boundary rather than importing the reference's
20-second window. Ending-skip behavior is preserved, including paused ticks, and
the three-argument JVM overload remains available. This is conceptual adaptation;
no reference implementation or dependency was copied.

**Deferred: desktop speculative resolution.** Desktop has an uncalled
`prewarmNextEpisode` and a generation-tagged value that would not survive the
next selection increment. Activating it initially looked useful. However,
`docs/desktop-review-2026-09-19.md` explicitly leaves it disabled because provider
resolution can save cloud files. Preparation/cache experiments were removed from
the final change. Define which providers permit side-effect-free speculation
before enabling it; a generic `CatalogGateway.play` call is not such a guarantee.

**Deferred: bounded, exact-identity, expiring preparation.** The reference's
Android preparation retains one entry, checks series/episode identity, expires
it after 60 seconds, and invalidates asynchronous work with tickets. These are
useful requirements for any future supported preparation workflow. Existing
`cache-core` supplies bounded monotonic expiry; avoid another cache mechanism.

**Deferred: native guest Hongguo catalogue integration.** Catalogue data is
embedded in an observed `_ROUTER_DATA.loaderData` dialect, not an established
versioned API. A future adapter should validate returned series/episode identity,
respect `accessible_episode_cnt`, and keep comics distinct from videos. This
requires a shared catalogue parser with offline fixtures and separate platform
transport/player ports. Do not reinterpret public share codes as CatVod vod IDs;
see the existing `docs/hongguo-testing-notes.md` investigation.

**Do not transplant authentication/export assumptions.** Cookie names are not
proof of login; the independent prototype checks explicit endpoint success and
a positive user ID. Its manju/duanju profiles use different hosts and aids.
The guest client uses its official browser profile instead. Neither supplies a
generic session interchange contract for Wuming. Creator onboarding, Xposed,
and emulator export are separate product and lifecycle decisions.

## Observed contracts and platform coverage

These statuses describe built-in support established by the compared code, not
capabilities that an arbitrary external spider might provide.

| Reference contract / observed dialect | Mobile | Leanback / lite | Desktop | Evidence / limit |
| --- | --- | --- | --- | --- |
| Active, one-shot near-end preparation | Covered after change | Covered after change | Not applicable to current enabled workflow | Shared `PlaybackSessionProgress`; Android `BaseVideoActivity` → `VideoPlaybackSession`; desktop speculation deliberately disabled |
| Hongguo `_ROUTER_DATA` home/series/player shapes | External/bundle-dependent | External/bundle-dependent | External/bundle-dependent | Reference `Catalog`: `homeSections[].video_list`, `seriesDetail.vid_list`, access count and player identity; no built-in matching provider added |
| Official browser episode player and account profile | Not covered by a dedicated Hongguo adapter | Not covered by a dedicated Hongguo adapter | Not covered by a dedicated Hongguo adapter | Reference uses GeckoView or WebKitGTK; Wuming's generic WebView/provider facilities do not establish this site-specific contract |
| Passport `/passport/account/info/v2/`, aids 8704 / 8662 | Not covered as a built-in Hongguo account flow | Same | Same | Prototype requires explicit success plus positive user ID; no Wuming profile/login bridge established |
| Direct HTTP(S) media playback | Covered, format-dependent | Covered, format-dependent | External player-dependent | Wuming Android Media3 and desktop external-player ports already handle direct media; no universal codec guarantee inferred |
| Emulator export and creator-platform sessions | Not applicable/product scope | Not applicable/product scope | Not applicable/product scope | Not part of this adaptation; no access/export compatibility claim |

## Validation

Passed:

- `./gradlew-safe :libraries:playback-core:test --offline --console=plain`:
  292 tests, zero failures/errors/skips, including six progress-policy tests.
- Removing only the new active/unfinished guards made two of the six policy
  tests fail (paused preparation and completed/out-of-range preparation).
  Restoring the guards returned the complete playback-core suite to green.
- `verifyGenericLibraryBoundaries`, `verifyDesktopBoundaries`, and
  `verifyCodeLifecycleIsolation`.
- Kotlin and Java compilation for mobile, leanback and leanback-lite ARM64
  verification variants, using the documented `-PwumingEphemeralSigning=true`
  verification route. No signing configuration or key was changed. Ordinary
  debug compilation was blocked by the local identity not matching the public
  production certificate pin. The initial offline dependency miss was resolved
  with normal dependency resolution before verification compilation.
- Final diff whitespace check.

The production path was traced from `PlayerManager.isPlaying()` through
`BaseVideoActivity.onTimeChanged` and `VideoPlaybackSession.update` to the shared
policy. Compilation and core tests do not establish Android runtime behavior.
No physical playback or account testing is claimed. No Java/Kotlin/Rust migration,
launcher-label or TV layout change was made.

Resume example: “Review the Hongguo guest catalogue dialect using synthetic
fixtures, keeping desktop speculation disabled and providers outside the build.”


## Follow-up: selective desktop prewarming

The user explicitly requested default-on desktop preparation for known non-cloud
streams, with cloud and unknown resolution excluded. This supersedes the initial
blanket deferral without enabling arbitrary provider execution.

- Added `PlaybackPreparationSafety` and the optional gateway `prewarm` operation
  in `catalog-core`. Unknown/remote implementations default to no preparation.
- Audited and enabled the built-in MacCMS direct-URL and Libvio player-page
  resolvers. Capability comes from reviewed executable code, per episode,
  rather than source display names or configuration declarations.
- Kept cloud detection authoritative as a veto, including mixed-source cloud
  episode metadata and opaque IDs. Metadata-cache/cloud wrappers preserve
  capability and deny cloud routes before playback resolution.
- Added shared `EpisodePrewarmSession`: one active-playback lookahead, one result,
  60-second monotonic expiry, exact identity/revision matching, header snapshots,
  single consumption, and rejection of cancelled/superseded work.
- Wired desktop mpv state feedback to this session and fixed consumption across
  episode-generation changes. Failed or unavailable preparation falls back to
  ordinary resolution. The existing prewarm preference defaults true; automatic
  next playback must also be enabled.
- Preserved Android's existing provider workflow and the earlier shared active
  progress guard. No downloaded runtime, account, or real provider was executed.

Validation: 440 tests across catalog-core, playback-core, maccms-core and
libvio-core; 60 selected desktop tests covering catalogue playback, cloud
routing and external-player behavior. All 500 passed with no skips, failures or
errors. Architecture checks passed. Disabling only the desktop state-to-prewarm
trigger made the end-to-end reuse regression test fail; restoring it returned
the selected suites to green. All three ARM64 Android verification variants
compiled Kotlin/Java with the documented ephemeral verification flag. Physical
playback and actual service latency remain untested.

For extending the positive classification safely, follow the implementation
review and offline-test requirements in [desktop prewarming](../desktop-prewarming.md).
