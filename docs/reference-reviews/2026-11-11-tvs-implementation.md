# TVs adaptations — 2026-11-11

Current follow-up: [the double-check audit](2026-11-11-tvs-double-check.md)
records six additional regression corrections and the latest validation. Counts
below record the initial implementation run.

## Scope and provenance

Implements all five candidates from [the bounded scan](2026-11-11-tvs-ideas-and-contracts.md),
following the user's “implement all” instruction. Earlier M3U, JSON and search
work, and all other pre-existing edits, remain intact. No commit, publication,
reference update or device installation was requested or performed.

Reference evidence is unchanged: `q215613905/TVBoxOS` at
`89ec1b7504426b3ff5e9d0996c3bbf50c9d2eff0` (duplicate checkouts `TVBoxOS` and
`q215613905`) and `kukuqi666/TVboxOSC` at
`0130b94a345347b40bba832189e8c1a832ca0b08`. Exact reference paths and the selected
commit diffs are recorded in the scan. Both reference roots have AGPL-3.0
licences. Implementation is independent Kotlin policy and existing platform
adapters; no reference code files, dependencies, assets or modules were copied.

## Implemented behavior

### Subtitle preferences and default inference

`media-core/.../subtitle/SubtitleSelectionPolicy.kt` supplies locale-aware language
preferences and scores external subtitles. It preserves exact locale and explicit
Chinese script before falling back to region-derived script and generic language.
Underscore tags normalize to hyphens. Unknown/unmatched candidates retain a
stable first-entry fallback. This is a bounded preference policy, not a full
BCP 47 conformance claim.

Android `ExoUtil` passes the ordered languages to Media3. For multiple external
subtitles it infers one default only when the provider supplied no explicit
flags. Explicit forced/default combinations survive; unflagged siblings become
autoselect. Single unflagged subtitles retain their previous default behavior.
The provider's raw flags are not mutated. These defaults are built at player/item
creation; manual track-selection paths are unchanged.

Desktop mpv receives one `--slang-append` argument per language. Existing user
language preferences stay ahead of the automatic fallbacks, and the app's extra
configuration may replace the list. An effective-value IPC check revealed that
one append with commas creates a single literal item; the maintained real-mpv
regression now covers this distinction.

### Runtime and method capability reporting

`source-core/TvBoxSourceRuntimePolicy` recognizes `node:`, `nodejs:`, `.cjs` and
`.mjs`, including query/fragment suffixes. It classifies type-3 Node sources as
`NODE_RUNTIME_REQUIRED`. Ordinary `.js` remains the existing JS route; HTTP API
source types retain their existing semantics. Android's BaseLoader rejects the
unavailable Node runtime explicitly before choosing a provider engine. Desktop
Settings, Home's unavailable-source reason and the plugin analyzer identify Node
requirements rather than suggesting an ordinary JS or JVM adapter.

`catalog-core/CatalogHostContract` reports the public JVM and guarded catalogue
host dispatch surfaces. Catalogue audit output lists exposed and unexposed
methods, independently from successful provider verification. The JVM catalogue
host does not expose paged search, action, live or proxy. The guarded host has a
separate proxy server but still does not expose paged search, action or live.
Cache, verification and cloud wrappers preserve the host report; a supplied
specialized guarded adapter reports its own contract. Unspecified hosts stay
unspecified. No Node interpreter or new Node compatibility promise was added.

### Nearby episode pickers

`user-library-core/EpisodeWindow` computes bounds and index mapping around the
current displayed episode, with empty/end/large-radius behavior. Leanback's main
Episodes action opens a Nearby tab with up to 60 preceding and 60 following
entries. Existing full-series page tabs and the More route remain available.
The dialog snapshots list membership, retains episode object identity, waits for
layout before moving focus and invalidates pending focus when the page changes
or the view is destroyed. Mobile does not enable the nearby tab.

Desktop adds a Nearby episodes button beside the playback-line selector. Its
modal picker preselects and focuses the current episode, accepts Enter or the
Select button, closes with Escape, and rejects a choice if the detail or line
changed. The full episode browser remains available. The native test verifies the
focused JList's selected item and index mapping across the old bucket boundary.

### Provider recommendation seed bridge

`user-library-core/recommend/ProviderRecommendationSession` bounds seed lists to
ten distinct nonblank names and retains first-page ordering for later pages.
It rejects control characters/oversized names, bounds retained scopes, removes
names no longer visible and returns empty seeds for private viewing. A new first
page refreshes the snapshot. It deliberately does not reintroduce names from an
old provider preference after local history was deleted.

Only the observed `guess_you_like` category on a Douban-like API/name uses this
compatibility bridge. Android SiteApi obtains visible history through UserLibrary
and carries the seed list separately through RemoteSpider's Bundle to the provider
process; local providers use the same scoped preference port. Both branches keep
provider filters unchanged. The provider-process and guarded-desktop ports expose
the list through package-default `historyvodname` during category invocation and
restore the previous value (or absence) in finally, including provider failure.
Private viewing sends an intentional empty list rather than omitting the context.

Desktop obtains visible names from DesktopUserLibrary, scopes the session to the
loaded configuration/source and supplies context through CatalogGateway and
CatalogSourceExecutor. Cache, evidence and cloud decorators forward that context;
recommendation pages bypass the ordinary metadata cache. Guarded-host JSON carries
an optional list. Portable JVM providers can explicitly opt in with a public
`setRecommendationSeeds(List)` method; providers without it retain their ordinary
category behavior. That optional hook is a Wuming host extension, not an observed
universal CatVod method. Arbitrary private bootstrap hooks such as saveConfig were
not introduced. The actual reference compatibility contract is the Android-style
preference consumed by the reviewed provider family.

### mpv configuration editing and import

Desktop Playback settings now has an mpv configuration editor and Load file
button. Loading stages text; Cancel does not publish it. Save uses the existing
AtomicFilePublisher and an owner-only temporary sibling. Imports/reads are bounded
to 1 MiB, decode UTF-8 strictly, and failed reads/saves leave the prior file intact.
The app owns only `$XDG_CONFIG_HOME/wuming/mpv.conf` (or
`~/.config/wuming/mpv.conf`), preserving the normal user mpv configuration.

`playback-core/MpvConfigurationPolicy` recognizes default-profile option names,
BOM/comments, named-profile boundaries and `no-` aliases. The editor reports
options that playback settings may override. It is a diagnostic scanner, not a
full validator: named/automatic profiles and included files remain mpv's grammar.
The player includes this file before its app-owned window, IPC and transport
arguments. A real mpv test verifies imported language precedence and the final
border option. Conditional options such as speed/fullscreen may be supplied only
when the corresponding playback setting requires them.

## Platform/contract coverage

| Contract | Mobile | Leanback / Lite | Desktop |
| --- | --- | --- | --- |
| Ordered subtitle languages and inferred external defaults | Shared policy and adapter unit test | Same main policy/adapter; unit test and compilation | Shared language policy through mpv argv and effective IPC check; subtitle rendering itself external |
| Node-required source recognition | Shared detection and explicit loader rejection | Same path | Configuration classification and visible unavailable reason; no Node runtime |
| Catalogue host method reports | Existing Android provider ABI retained | Same | Public JVM/guarded reports reach catalogue audits; other hosts unspecified |
| Current-centered episode window | Full-series UI retained | Nearby tab plus full-series pages | Nearby keyboard picker plus full browser |
| Recommendation seed policy | Shared session and scoped preference/Bundle ports | Same main implementation | Shared session, catalogue wrappers and guarded/public-JVM ports |
| `historyvodname` compatibility | Provider-dependent | Provider-dependent | Guarded Android-shim preference port; portable JVM optional explicit hook |
| mpv extra configuration | Not applicable to Media3 | Not applicable to Media3 | Editor, bounded UTF-8 import, atomic publication, diagnostic scanner and launch inclusion |

Capabilities describe exposed host methods and tested offline fixtures, not
successful operation of every provider or conformance to every TVBox dialect.

## Validation

Final core/runtime suites: media-core 61 tests; playback-core 312 (one existing
optional media fixture skipped); source-core 155; user-library-core 36;
catalog-core 119; jvm-spider-runtime 3; guarded-runtime 89; guarded-catvod-host 23
(one existing opt-in hardware test skipped). No failures or errors.

Selected desktop player/configuration/provider-status suites: 108 tests, no
failures or skips. Combined with the core/runtime suites, coverage includes a real
portable provider child process, raw/empty seed transport, cache/evidence forwarding,
strict import failure and command precedence.
The guarded-host fixture separately round-trips JSON context into the real
category dispatcher and observes/restores preferences after success and failure.

Mobile ARM64 verification: 7 selected unit tests. Leanback ARM64 verification:
7 selected unit tests. Both pass without skips. These cover the raw subtitle-flag
adapter, scoped Android preference port, existing built-in loader controls and
Java-facing episode-dialog API. Mobile, leanback and leanback-lite ARM64
verification Kotlin/Java compilation passed using the existing ephemeral signing
flag, without changing signing keys/configuration.

Native Xvfb lane: NearbyEpisodePickerIntegrationTest and
MpvConfigurationIntegrationTest, two tests, no failures/skips. The former asserts
focus on episode 121 and selecting episode 120 from the 61–181 window. The latter
queries effective slang/border over actual mpv Unix-socket IPC, including existing
French preference, separate locale appends, imported Japanese override and
app-owned border precedence. Both regressions are included in desktopSmokeTest.

Removed-fix control: substituting the old independent Sub.getFlag mapping in the
Android subtitle adapter fails SubtitleSelectionAdapterTest on an assertion.
Restoring the adapter and rerunning the selected mobile/leanback suites passes.

verifyGenericLibraryBoundaries, verifyDesktopBoundaries,
verifyCodeLifecycleIsolation and verifyJavaRetention passed. The retention
inventory still protects 94 files. check-leanback-remote passes its 124-layout,
302-focus-target and three-label-locale checks. git diff --check passes.

No physical Android device testing, live provider activation, subtitle rendering
or full provider-ABI conformance was established. Native tests use offline data
and mpv property IPC rather than rendered media. The optional hardware and media
fixture skips remain separate from passes. Unreviewed reference domains remain
pending; these are all five selected adaptations, not an exhaustive reference port.

## Reproduce and continue

Run core/runtime module tests, selected Android verification tests and the four
boundary tasks with `./gradlew-safe -PwumingEphemeralSigning=true`.
For native regressions:

```sh
xvfb-run -a ./gradlew-safe :desktop:desktopSmokeTest \
  --tests '*NearbyEpisodePickerIntegrationTest' \
  --tests '*MpvConfigurationIntegrationTest'
```

Used [tv-reference-abstraction](/home/eli/.codex/skills/tv-reference-abstraction/SKILL.md).
Example continuation: “Use tv-reference-abstraction to review a new bounded domain
from ~/git/TVs for Wuming; read the scan, implementation report and status.json,
verify existing adaptations, and avoid redoing these five candidates.”
