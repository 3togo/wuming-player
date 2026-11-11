# TVs implementation double check — 2026-11-11

Reviewed all five adaptations against their production callers and additional
input/lifecycle families, rather than repeating only the original tests. Scope
and reference revisions remain those in [the initial implementation report](2026-11-11-tvs-implementation.md).
No reference updates, publication, commits or device operations were performed.
Existing unrelated work was preserved.

## Findings and corrections

### 1. Subtitle policy: generic language versus another regional variant

For an en-US user, generic `en` and regional `en-GB` both scored 200. When en-GB
came first, the inferred external-subtitle default selected it instead of the
closer generic prefix. The reference's non-Chinese scoring distinguishes that
prefix. The early generic-language special case now applies only to Chinese;
other languages use the existing exact/prefix/primary ordering. A new regression
asserts both score ordering and the resulting default flags. It failed before
the correction and passes afterward. Existing Chinese script/region, provider
flag, unknown-language and single-track controls still pass. Android and mpv
adapters continue to consume the shared policy; manual-selection paths were
not changed by this audit.

### 2. Runtime reporting: expanded routing controls

No new production defect was found in the reviewed runtime reporting paths.
Additional configuration-loader coverage verifies `nodejs:...js` and `.mjs`
with query/fragment do not acquire either a normal JS or JVM adapter, while
ordinary `.js#entry` and an HTTP type-1 endpoint named `.mjs` keep their respective
JS and collection-API classifications. Host contract forwarding, optional-method
reports and the distinction from observed provider success were rechecked.
Android's explicit unavailable-runtime exception still follows its normal
request-failure path. This is source/compilation evidence for Android routing,
not a physical-device Node-provider test or a Node interpreter implementation.

### 3. Nearby picker: teardown during a modal loop

The desktop picker checked detail and line identity but did not check whether the
catalogue had closed. A native regression opened the picker, closed the catalogue,
and selected another episode; the old code accepted that selection. The picker
now validates closed state and catalogue generation when opening and accepting.
The closing regression failed on an assertion before the correction. The original
keyboard/focus/current-episode mapping control also passes.

The shared EpisodeWindow bounds, empty/edge/reverse-order cases and Android
nearby-tab/page-offset/focus-generation code were rechecked. Android's actual
D-pad behavior remains untested on a physical device. No device access was inferred
from the request.

### 4. Recommendation bridge: cached representations and context lifetime

Four concrete gaps were reproduced:

- The desktop UI's CatalogResultPager could return existing cards without calling
  the seed policy at all. Bypassing the metadata cache did not bypass this second
  cache. Private-viewing toggles also did not reload the category.
- A saved browse-navigation snapshot could restore the old personalized cards
  after the private request had already returned anonymous results.
- A previous category request's late failure could overwrite the successor's
  anonymous result/status; completion could also clear the successor's loading
  flag. Guarding successful result publication alone did not cover these paths.
- The public JVM host supplied an optional seed setter before invocation but left
  that state on the provider afterward, including when the category failed.

`user-library-core/recommend/ProviderRecommendationPager` now reuses a paging
cache only when scope, effective seeds and private-viewing state match. It checks
that context before consulting cached pages. Changed/deleted seeds or privacy
reset paging; unchanged context still reuses it. When a context change requires
a later display page, provider pages are collected again from page one rather
than combining old and new recommendation cards.

Desktop snapshots API/name admission on the UI thread and supplies current visible
history/private mode in the worker. Toggling privacy replaces the recommendation
pager, clears old cards immediately and starts a new request, without waiting on
a provider that may still be running. Restoring a recommendation browse snapshot
revalidates through that pager instead of publishing saved cards. Ordinary
catalogue snapshots retain their behavior. Result, failure and loading completion
all check the request identity at delivery.

The optional public JVM setter is now scoped around the category invocation and
cleared in finally. Ordinary later requests cannot inherit app-supplied seed names.
The original provider failure remains primary if cleanup also fails. Android and
guarded-host scoped preference restoration and empty-list versus missing-context
behavior were rechecked through their maintained tests and source callers.

New regressions exercised the actual Swing category caller with persisted local
history, private toggles, deletion, cached pages, navigation restoration and an
old blocked provider failure. A logging latch confirms the old failure reaches
its delivery callback before checking that the current UI survived. Portable JVM
coverage uses the real child process and also checks a normal category after both
success and provider failure. These primary reproducers all failed on assertions
before their corresponding corrections. Shared pager tests retain an unchanged
cache control and verify replay from provider page one after invalidation.

### 5. mpv configuration: preserve data and verify actual editor actions

No additional production behavior defect was found in the reviewed configuration
store/launch paths. Expanded storage coverage rejects an oversized import and
checks the prior file remains intact, alongside existing malformed-UTF-8 and NUL
controls. A native editor test stages new text, observes the managed-option
message, cancels and verifies the original file, then accepts and verifies the
published text. The editor now accepts an injected configuration port so this
fixture never touches the user's real mpv file.

The real-mpv IPC regression still verifies per-tag list appends, pre-existing
language priority, imported language replacement and app-owned border precedence.
The scanner remains diagnostic; it does not claim full profile/include grammar
validation. Conditional application settings and manual runtime selection remain
separate from initial configuration loading.

## Final validation

| Suite | Tests | Failures/errors | Skips |
| --- | ---: | ---: | ---: |
| media-core | 62 | 0 | 0 |
| playback-core | 312 | 0 | 1 existing optional media fixture |
| source-core | 155 | 0 | 0 |
| user-library-core | 37 | 0 | 0 |
| catalog-core | 119 | 0 | 0 |
| jvm-spider-runtime | 3 | 0 | 0 |
| guarded-runtime | 89 | 0 | 0 |
| guarded-catvod-host | 23 | 0 | 1 existing hardware opt-in |
| Selected desktop player/config/provider/search/recommendation suites | 112 | 0 | 0 |
| Selected mobile ARM64 verification tests | 7 | 0 | 0 |
| Selected leanback ARM64 verification tests | 7 | 0 | 0 |
| Native nearby picker, mpv IPC and mpv editor tests | 4 | 0 | 0 |

Mobile, leanback and leanback-lite ARM64 verification Kotlin/Java compilation
passed, using the existing ephemeral signing flag. Generic-library, desktop,
code-lifecycle and Java-retention checks passed. The retention inventory still
protects 94 files; the leanback remote checker and git diff whitespace checks pass.
Native regressions are selected by the maintained desktopSmokeTest task. New
portable policy/state tests remain in ordinary unit suites.

The six primary failure cases were the subtitle ordering, closed picker,
recommendation pager/private toggle, stale navigation snapshot, stale failure
callback and persistent JVM seed context. Their failing controls were established
before correction; the corrected suites pass. Runtime reporting and mpv import/
editor behavior received additional controls without an invented defect claim.

This does not establish physical Android navigation/playback, live provider
activation, every provider's handling of a preference or setter hook, or full
protocol/standard conformance. The two unavailable prerequisites remain skips,
not passes. No new performance claim is made: recommendation refetches occur when
context changes, and unchanged-context reuse is regression-tested.

Used [tv-reference-abstraction](/home/eli/.codex/skills/tv-reference-abstraction/SKILL.md).
Continuation: “Use tv-reference-abstraction to extend the TVs review; read the scan,
implementation, double-check report and status.json before selecting new work.”
