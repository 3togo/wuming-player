# TVs reference scan and abstractions — 2026-11-11

## Scope and evidence

This is a bounded, local review and design abstraction, not an implementation or
an exhaustive audit. No references were pulled and no application code changed.
The comparison uses Wuming HEAD `b55f3b3bd2aed0646a7c11745821b426c19c01e7`
plus its existing uncommitted work. Existing edits were preserved.

| Directory under `~/git/TVs` | Origin | Inspected revision |
| --- | --- | --- |
| TVBoxOS | q215613905/TVBoxOS | 89ec1b7504426b3ff5e9d0996c3bbf50c9d2eff0 |
| q215613905 | q215613905/TVBoxOS | 89ec1b7504426b3ff5e9d0996c3bbf50c9d2eff0 |
| TVboxOSC | kukuqi666/TVboxOSC | 0130b94a345347b40bba832189e8c1a832ca0b08 |

The first two are duplicate clean checkouts of the same origin and revision;
reviewed their behavior once. All three checkouts were clean at inventory.
TVboxOSC is the same revision previously reviewed from `~/git/temp/TVboxOSC`:
[the earlier report](2026-11-11-tvboxosc-shared-contracts.md) covers M3U, JSON
playback responses and search lifecycle. Their shared implementations and Android/
desktop consumers remain present. This pass does not repeat or claim ownership
of those existing adaptations. Both distinct reference roots contain AGPL-3.0
licences. No source or assets were copied.

## Ranked abstractions

### 1. Shared subtitle language and default-selection policy — strongest candidate

Reference paths, relative to TVboxOSC:

- `engine/core/src/main/java/com/kukuqi/tvbox/engine/player/track/LangUtil.java`
- `engine/core/src/main/java/com/kukuqi/tvbox/engine/player/media/MediaItemFactory.java`
- `engine/core/src/main/java/com/kukuqi/tvbox/engine/player/exo/ExoUtil.java`
- `engine/core/src/main/java/com/kukuqi/tvbox/engine/player/mpv/MpvUtil.java`

The useful rule is ordered preference: exact locale, matching Chinese script,
generic language, then related variant. Explicit script takes precedence over
region; TW/HK/MO imply Traditional only when no script is present. Normalize
underscore tags and use deterministic first-match ties. The reference supplies
preferences to both players and ranks external subtitle candidates.

A second useful rule is choosing at most one inferred default among multiple
unflagged external subtitles. Preserve explicit default/forced flags; when any
explicit flags exist, do not manufacture another default. Keep the single-track
fallback separate. These are observed reference rules, not a claim of complete
BCP 47 language matching.

Concrete Wuming evidence: `app/.../player/exo/ExoUtil.java` sets only
`Locale.getDefault().getISO3Language()`, losing script/region information.
Its `buildSubtitleConfigs` independently calls `Sub.getFlag()` for each entry;
`app/.../bean/Sub.kt` maps every raw zero flag to DEFAULT. Thus multiple
unflagged subtitle configurations are all marked default. This establishes a
configuration-level gap; no wrong rendered subtitle was reproduced on a device.
Desktop `ExternalPlayerAdapter.command` sets no app-owned `--slang` preference;
mpv's own configuration/defaults may still select languages.

Proposed adaptation: place a locale-input, framework-free language ordering and
subtitle-default policy in existing `media-core`, with Android Media3 and desktop
mpv adapters. Retain provider flags and manual user selection. Do not mutate the
process locale, introduce a module, or put Media3 types in core. Before applying,
check actual Media3/mpv matching semantics and product precedence for user mpv
configuration. Test zh-Hant, zh-Hans, conflicting script/region, generic zh,
non-Chinese regional tags, unknown tags, ties, explicit forced/default flags,
multiple unflagged tracks and manual selection persistence. This candidate is
proposed, not implemented or player-validated.

### 2. Explicit runtime capability reporting — useful compatibility work

Reference paths: TVboxOSC `engine/core/src/main/java/com/kukuqi/tvbox/engine/api/loader/NodeLoader.java`
and `NodeSpider.java`. Observed Node dialect admission is `nodejs:`, `node:`,
`.cjs` and `.mjs` (query/fragment stripped for suffix checks); ordinary `.js`
remains the other JS runtime. RPC carries `{key, method, args}` to `/rpc` with a
Bearer token, returning `result` or `error`. The adapter forwards paged search,
`action`, `liveContent`, video checks, proxy and destroy as well as catalogue calls.
Proxy accepts an object with base64 body or an array with a UTF-8 string body.
Neither shape implies support for arbitrary streaming or unlimited bodies.

Wuming's common `CatalogSourceExecutor` intentionally models home/category/search/
detail/play and cancellation. Its public JVM host operation switch in
`libraries/jvm-spider-runtime/.../JvmSpiderHostMain.kt` has init, home, category,
search, detail, play and close; search invokes the two-argument provider method.
That contract does not establish paged search, action, live or proxy support.
The Graal JS executor has a separate bounded proxy implementation; that does not
establish Node.js compatibility. No dedicated Node loader was found in the
reviewed Wuming routing scopes.

Abstract the distinction between catalogue capability and complete provider ABI.
A future source audit should report missing runtime/method capabilities explicitly
rather than route all JS-shaped inputs to one engine. First obtain an actual
required provider and offline fixtures. Defer adding a Node runtime, dependencies
or widening the common interface without that evidence. A new bridge needs
binary proxy fixtures, timeouts, process restart, authentication and cleanup tests.
The Node service/runtime implementation itself was not audited in this pass.

### 3. Current-centered quick episode navigation — optional UX proposal

TVBoxOS commit `aa63926130320c677618623357587882d06096f1`,
`app/src/main/java/com/github/tvbox/osc/ui/fragment/PlayFragment.java`, replaces
fixed episode buckets with up to 60 preceding and 60 following entries.
The callback maps local index back to the displayed list's index. This makes
nearby episodes accessible across old bucket boundaries.

Wuming's `EpisodeGridDialog.kt` already has tabs, pages and current-episode
selection; `EpisodeListDialog.kt` scrolls to the selected item. Therefore this
is an alternative quick-picker workflow, not evidence that current navigation is
broken. Keep full-series navigation available. If requested, own the window and
index mapping in `user-library-core`, while the TV adapter owns focus. Validate
actual focused item identity, reverse order, list replacement, first/last episode
and D-pad/OK/Back on the actual TV route. Do not transplant the reference's
hard-coded window size as a universal rule.

### 4. Provider history bridge — conditional, provider-specific

TVBoxOS commit `498645290ef0485e3812119ca3ed53bcb5ef8cc9` adds
`syncDoubanHistory` to `.../viewmodel/SourceViewModel.java`: only first-page
`guess_you_like` for a Douban-like source receives a bounded, deduplicated history
list via package SharedPreferences key `historyvodname`. Existing provider names
are appended after local records, to ten entries. Later pages retain that snapshot.
`.../crawler/ProtectedInitJar.java` also calls optional private `saveConfig`
before other protected-init hooks.

The reusable idea is an explicit, stable recommendation seed snapshot per browse
session. The preference key, name-based provider detection and reflection hooks
are bundle-specific contracts, not CatVod guarantees. Wuming already owns local
recommendation policy in `user-library-core/.../recommend`; this is a different
provider integration. Do not export history or add global hooks merely because
the reference does. Defer until an intended provider/runtime requires it and
history/privacy semantics, account scope and deletion behavior are defined.

### 5. Player configuration ownership — useful if an editor is introduced

TVboxOSC `.../player/mpv/MpvConfigFile.java` writes/imports atomically and detects
options managed by the app. Detection distinguishes default from named profiles,
handles BOM/comments and recognizes `no-` aliases. The abstraction is to explain
which app settings override imported configuration and preserve the old file on
failed import. Wuming's desktop player already supplies explicit launch options
for embedding, window behavior, IPC, speed and headers; no corresponding mpv
configuration editor was found in the reviewed command path. Defer the editor
and parser; audit precedence if one is added. The reference scanner is not a
complete mpv configuration parser and should not become one by assumption.

## Ideas inspected but not selected

TVBoxOS `e3e4804ae59c662ce96bb10a0d1525e1a5093053` serializes spider
creation and makes MD5 digests per-call. Wuming Android `JarLoader.getSpider`,
`parseJar` and `clear` already synchronize; inspected core digest helpers create
per-operation digests. This gives no justification to add another locking/cache
abstraction or mechanically replace the existing lifecycle.

TVBoxOS `c68a97d7f6abc55bf344ce23867edf4a230d683e` adds the configured UA
to configuration fetching. Wuming desktop `DesktopResourceLoader.download`
already honors a supplied UA and otherwise supplies a default. Android
`VodConfig.load -> Decoder.getJsonObject -> getJsonDirect -> OkHttp.newCall`
uses the shared HTTP path. This pass did not audit that path's complete interceptor
configuration or capture its wire request, so Android UA propagation remains
unverified rather than a demonstrated defect. Test the actual request with an
in-process server before proposing a change; preserve direct/relay boundaries.

## Protocol and platform coverage (bounded observations)

| Observed contract | Mobile | Leanback / Lite | Desktop |
| --- | --- | --- | --- |
| Locale/script-aware subtitle policy | Partial: ISO3 preference loses script/region | Same main ExoUtil path | External: mpv configuration/defaults; no reviewed app-owned language option |
| Multiple external subtitle default inference | Partial: every raw zero becomes DEFAULT | Same main subtitle configuration path | External player behavior; no parity established |
| Basic CatVod catalogue operations | Existing Android loader | Shared Android loader | Covered by explicit common executor/JVM host methods, runtime activation dependent |
| Reviewed Node admission and RPC dialect | Not covered by a dedicated loader in reviewed routes | Same scope limitation | Not covered by reviewed JVM/Graal contracts; Graal is not Node |
| Node paged search/action/live/proxy ABI | Runtime-specific, not established by common catalogue contract | Same qualification | Partial: separate JS proxy exists; common catalogue/JVM switch lacks these methods |
| `historyvodname` / protected init hooks | Bundle-dependent; not established | Bundle-dependent; not established | Not covered by reviewed public JVM init contract |
| Configuration HTTP UA | Unverified shared HTTP interceptor path | Same path | Covered for caller-supplied/default UA in resource loader |
| mpv configuration profile grammar | Not applicable to existing Android Media3 path | Same qualification | External mpv parsing; app editor/scanner not present in reviewed path |

No formal protocol version was found for the provider extensions. These rows
record exact observed dialects and inspected paths, not universal conformance.

## Validation and resumption

Evidence is local source inspection, exact commit/diff inspection, clean-reference
inventory, duplicate-origin/revision comparison, and verification that prior
adaptation files/callers still exist. No application tests, builds, device tests,
live provider requests or reference updates were run for this documentation-only
pass. Existing uncommitted implementation and its report remain separate evidence.
All unlisted files/domains remain pending. Nothing was scheduled or published.

Used [tv-reference-abstraction](/home/eli/.codex/skills/tv-reference-abstraction/SKILL.md),
with the user's explicit `~/git/TVs` references and current Wuming product overriding
the skill's historical workspace defaults.

Continuation: “Use tv-reference-abstraction to implement candidate 1 from the
2026-11-11 TVs scan in Wuming, preserving manual subtitle choices and provider
flags; verify both Android and desktop adapters. Do not update references.”
