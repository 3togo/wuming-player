# Box practices and abstraction review — 2026-10-12

Review-only request: identify worthwhile practices or code in the former
`~/git/Box` checkout. The workspace manifest now resolves it to
`../references/Box`. No implementation, reference update, provider execution,
device access or scheduling was performed.

## Evidence and scope

- Box: `258a5fef61578869ae905ca230bdde9e99fc19a8`, clean, latest local
  commit dated 2026-02-27, “系统播放器增加音轨选择”.
- TV: `099a5b1928ff0c087451db895a904ff60c2a2e85`, with the preceding
  regression fixes still uncommitted. Those changes were preserved.
- The workspace update helper ran with `--dry-run`. Its Box entry had identical
  before/after commits and `would-update`; this is an inventory/planned action,
  not evidence of new upstream commits. No update was performed.
- The September review already sampled this Box revision. This review revisits
  the following bounded scopes against current TV; it does not claim exhaustive
  repository coverage or a new upstream delta.

Box files examined:

- `app/src/main/java/com/github/catvod/crawler/{Spider,JarLoader}.java`
- `app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java`: loader and proxy dispatch
- `app/src/main/java/com/github/tvbox/osc/bean/VodInfo.java`: episode construction/order
- `app/src/main/java/com/github/tvbox/osc/ui/activity/PlayActivity.java`: next/previous
- `app/src/main/java/com/github/tvbox/osc/player/thirdparty/Kodi.java`
- `app/src/main/java/xyz/doikki/videoplayer/player/AndroidMediaPlayer.java`: audio tracks
- `app/src/main/java/com/github/tvbox/osc/subtitle/{SubtitleLoader,SubtitleFinder}.java`
- `LICENSE` and recent commit summaries

TV comparisons include `ExternalPlayer.kt`, desktop `ExternalPlayerAdapter.kt`,
`EpisodeAdvancePolicy`, `EpisodeMatchPolicy`, Android `Flag`/`SiteApi`/`Spider`,
`JvmSpiderHostMain`, `JvmCatVodCatalogSourceExecutor`, the guarded host/executor,
`TrackUtil`, and current plain-JVM provider tests.

## Recommended candidates

### 1. REF-013: turn Box's provider dialect into offline conformance fixtures

**Highest priority.** Reuse contract knowledge and write inert synthetic
providers, rather than import Box's loader. Its `Spider.java` exposes separate
`homeContent(boolean)` and `homeVideoContent()` methods, two- and three-argument
`searchContent`, category filters as `HashMap`, detail IDs as `List`, and
`playerContent(flag, id, vipFlags)`.

There is a concrete home-recommendation gap: TV Android's `SiteApi.java` invokes
`homeVideoContent`; the desktop guarded executor also requests `homeVideo` and
merges recommendations. The desktop plain-JVM executor's `browseHome()` only
decodes `home`, and `JvmSpiderHostMain` has no `homeVideo` operation. A portable
provider returning categories from `homeContent` and recommendations only from
`homeVideoContent` loses those recommendations through that runtime.

The smallest future adaptation is an optional plain-JVM `homeVideo` operation,
with the existing guarded merge behavior as the TV-owned precedent. Tests should
cover absent/empty methods, duplicate IDs, category preservation, exceptions and
destruction. Record search overload support explicitly: the public catalogue
search workflow currently supplies a query, not a page, so a three-argument
search-only provider is a dialect/profile limitation rather than proof that TV
promises paginated search.

Keep Box's Android `Context`, `safeDns`, WebView hooks and optional methods out of
a universal plain-JVM promise. This extends the existing REF-013 proposal; it is
not a new runtime or a completed implementation.

### 2. REF-018: share external-player request policy and test each encoding

Box's Kodi adapter demonstrates an ordered activity fallback and the
`url|Header=encoded-value&...` transport spelling. TV already supports
`org.xbmc.kodi.Main` then `Splash`; that fallback does not need copying.

The useful abstraction is in TV's current code: Android `ExternalPlayer.runMX`
and `runKodi` duplicate header-URL construction, while the desktop adapter owns
header validation/default-UA policy and mpv/VLC argument encoding. A shared
`playback-core` policy can validate portable request fields and centralize
explicit transport encoders, reusing existing media/playback contracts where
possible. Android intents and package discovery, desktop process/IPC lifecycle,
and each player's supported options remain ports.

Do not force mpv, VLC, MX and Kodi through one serialization format. Preserve
mpv's dedicated User-Agent argument and its comma/backslash escaping, and expose
unsupported headers rather than assuming every external player transports them.
Regression cases: Unicode, spaces, `&`, `=`, commas/backslashes, control characters,
missing player components and fallback success. Both platforms must consume any
new portable policy; no additional module is warranted.

### 3. REF-019: conservative descending-episode detection

Box `VodInfo.isReverse()` samples up to six adjacent pairs and reverses after
two decreases. It illustrates a useful behavior: a newest-first provider list
can become a predictable episode sequence.

TV's shared `EpisodeAdvancePolicy` correctly walks the displayed order, but
Android `Flag.setEpisodes()` and the inspected shared catalogue path preserve
provider order without this automatic detection. Consider an optional shared
catalogue policy using TV's existing episode-number parser and stable episode
identities. Require unambiguous monotonic evidence, preserve explicit user
ordering and saved episode selection, and leave movies/specials/unknown or mixed
labels unchanged.

Do not transplant Box's first-digit regex, overflow behavior, two-vote threshold,
three-line restriction or 300-episode cutoff. Years and quality tokens can look
like episode numbers, and line count does not establish ordering. Validate
`12,11,10`, ascending and mixed sequences, years/quality labels, specials,
duplicates and cross-line resume. This is proposed behavior, not a current TV
regression or permission to reorder lists in this review.

## Practices already covered or unsuitable for copying

- **Displayed-order next/previous:** already shared by Android and desktop in
  `EpisodeAdvancePolicy`, with boundary tests. Box's activity-level duplication
  is weaker than the current TV boundary.
- **Kodi activity fallback:** already present in TV's Android adapter.
- **Audio-track enumeration:** Box correctly retains the original native track
  index while filtering audio tracks and handles missing language metadata.
  TV's active Media3 engine already exposes track groups and selection; importing
  Box's separate system/IJK engine would not solve a demonstrated TV problem.
- **Provider loading:** do not copy Box's thread-plus-join initialization or
  direct cache overwrite. TV already has isolated runtimes, artifact integrity,
  last-good JAR recovery and cancellable atomic downloads.
- **Subtitle lookup/loading:** the sampled binary search is not an improvement
  over player-owned subtitle decoding. Box's async loader posts callbacks without
  the cancellation ownership expected by current TV. STL support would require
  a demonstrated format requirement and platform decoder verification first.

## Observed protocol/platform coverage

These classifications describe this observed Box dialect, not universal CatVod
or player conformance. Third-party provider bodies and media decoders remain
external dependencies.

| Observed contract | Android mobile/leanback | Desktop | Concrete boundary |
| --- | --- | --- | --- |
| Classic Java catalogue method/JSON family at this revision | Covered for principal catalogue calls; bundle-dependent for optional hooks | Partial; portable JVM and guarded profiles differ | Plain JVM lacks separate home-video dispatch; category/detail/play signatures are explicit reflection contracts |
| Android `init(Context, ext)`, DNS and browser video hooks | Covered host surface; provider implementation external | Bundle-dependent/external through reviewed guarded shims; not a plain-JVM guarantee | Plain JVM accepts `init(String)` or no-arg init; arbitrary Android dependency compatibility is not implied |
| Three-argument search and optional `proxyLocal`/`cancelByTag` | Partial observed dialect surface | Partial/profile-dependent | TV Spider exposes both searches but uses `proxy`, not Box's `proxyLocal`, and lacks `cancelByTag`; Box's sampled host proxy path dispatches via its loaders, so method spelling alone is not evidence of a playback failure |
| Kodi ACTION_VIEW extras, pipe headers and Main/Splash fallback | Covered | Not applicable/product-scope for this Android component protocol | Desktop uses mpv/VLC process arguments; those encodings need separate tests |
| System MediaPlayer audio-track indexing | Not applicable to TV's current Media3 engine; track selection covered through Media3 | External-player-dependent | Box uses native integer indices; TV Media3 uses track groups/overrides |
| Episode next/previous in displayed list order | Covered | Covered | Shared `EpisodeAdvancePolicy`; inferred order is optional policy, not a media standard |
| Subtitle SRT/ASS/STL dispatch in sampled Box loader | SRT/ASS player-dependent; STL not established by this review | External-player-dependent; STL not established | No decoder conformance test was performed; an upstream parser name does not prove TV coverage |

## Validation and remaining work

Source inspection and read-only reference inventory only. No code was copied or
executed from Box, and no new production code or runtime tests were introduced.
Documentation JSON parsing and `git diff --check` passed; Box remained clean at
the same commit. The preceding task's runtime test results are not evidence for
these proposed adaptations.

Box's top-level license is AGPL-3.0. No copied-code attribution or dependency was
introduced here; any future code reuse must record the actual source and license
provenance rather than assume all vendored files share one license.

Remaining review: other configuration/parser dialects, live/EPG, subtitle format
implementations, JS/Python host globals, and other player lifecycle paths. No
repository-wide completion is recorded.

Suggested next invocation: “Use tv-reference-abstraction to implement the Box
plain-JVM homeVideo compatibility gap with offline fixtures; preserve existing
edits and validate Android/desktop boundaries.”
