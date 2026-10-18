# Linux desktop site verification — 2026-09-16

The initial Settings → Sites and required runtimes status used only
`GuardedDesktopSupportPolicy`. This audit rechecked every unverified JVM provider entry in
the user's saved 饭太硬 configuration (49 total sites).
The configuration was revalidated over the network; its decoded fingerprint was
`807b794774083b1aa38c71c21a75c2c843cc8ac7b6c6bc4e74c889f4fd5947e4`.
The downloaded spider SHA-256 was
`832faaf195f8e209ac464b4d6f07882f25c1d17e1cfa6410167e37da48fa87f9`.

## Method

Used the desktop distribution's `DesktopTvBoxConfigurationLoader` and
`GuardedSourceActivator`: inspect the artifact, activate its exact fingerprint
with `armeabi-v7a`, and run each provider in the isolated Linux JVM host.
No Android bridge serial was configured and no Android device was accessed.
Requested home, the first two categories, search for `仙逆` when searchable,
and details from up to three returned items. Closed every executor afterward.
For the two successful provider classes, also requested the first episode of
up to two playback lines. This was playback URL resolution, not media rendering.

## Results

| Configured key / provider | Home types / items | Category items (first two) | Search items | Detail lines / episodes | Decision |
| --- | --- | --- | --- | --- | --- |
| 糯米 / Nmyswv | 5 / 12 | 30, 30 | 4 | 2 / 24 | Verified catalogue |
| Bili / Bili | 41 / 20 | 2, 20 | 19 | 1 / 4 | Verified catalogue |
| MTV / Bili | 129 / 20 | 2, 20 | Not configured | 1 / 4 | Same measured provider; not browse-enabled by configuration |
| Biliych / Bili | 9 / 20 | 2, 20 | Not configured | 1 / 4 | Same as above |
| 少儿教育 / Bili | 15 / 20 | 2, 20 | Not configured | 1 / 4 | Same as above |
| 小学课堂 / Bili | 19 / 20 | 2, 20 | Not configured | 1 / 4 | Same as above |
| 初中课堂 / Bili | 25 / 20 | 2, 20 | Not configured | 1 / 4 | Same as above |
| 高中教育 / Bili | 29 / 20 | 2, 20 | Not configured | 1 / 4 | Same as above |
| 海绵 / Hmys | 0 / 0 | — | 0 | — | Remains unverified |
| 播客, 剧圈, 咕咕 / AppSx | 0 / 0 each | — | 0 each | — | Remain unverified |
| YGP / YGP | 0 / 0 | — | Not configured | — | Remains unverified |
| 吃瓜 / LiveGz | 0 / 0 | — | Not configured | — | Remains unverified |
| push_agent / Push | 0 / 0 | — | Not configured | — | Remains unverified; push is not a catalogue probe |
| cc / XPath | — | — | — | — | Provider class missing from bundle |

Both sampled 糯米 lines and the sampled Bili line returned `supported=false`
with no usable HTTP(S) playback URL. Diagnostics included connection-refused
errors; this audit does not establish their root cause or permanent lack of
playback support. Accordingly, both classes are labelled **verified desktop
catalogue · playback unverified**, with catalogue activation allowed. Their
playback is not promoted to verified. Existing searchable/index-provider and
exact-artifact trust gates remain in effect.

The generic verified status includes older home/category-only measurements,
so its selection message no longer claims that every such provider passed
detail and playback tests. Discovery and cloud-search labels remain distinct.
These are dated capability measurements, not an availability guarantee for
other artifacts, provider configurations, episodes or future upstream changes.

## Follow-up: runtime adapter and measured verification

`DesktopSpiderRuntime` now selects the existing guarded/plain-DEX or portable-JAR
host behind `CatalogSourceExecutor`. Artifact trust is checked before execution.
The portable artifact remains available until its executor closes. Batch activation
continues to reuse DEX preparation and closes partially created portable executors
if activation fails.

`catalog-core` owns the evidence model, observing executor and bounded probe. Ordinary
successful requests update capability timestamps without additional network requests.
Catalogue verification requires actual media listings and nonempty episode details;
discovery requires home types and nonempty categories. Empty results, exceptions and
unsupported playback cannot promote a provider. The manual check uses evidence from
that check, so an old success cannot make a failed recheck report success. It never
requests playback. Successful user-requested playback records URL resolution only.

Settings exposes **Check selected spider…** even for providers absent from the static
allow-list. After exact-artifact approval, successful checks enable that source without
replacing other enabled sources. Saved evidence is hashed over the provider key/API,
artifact location and digest, extension, categories, ABI and runtime revision. Cosmetic
renames do not invalidate it. Only hashes and capability timestamps are persisted, with
a maximum of 256 records and at most hourly refreshes per capability. Each capability
expires after 30 days; a new listing cannot renew old detail/playback evidence. Bump
`DesktopSpiderVerificationStore.RUNTIME_REVISION` when changing host compatibility or
verification criteria. Existing measured providers remain startup defaults; this record
does not turn their historical allow-list status into a current live health guarantee.

Settings restores previously approved providers through the adapter after restart and
observes their normal use again. Newly downloaded/changed artifacts must still match
their approved digest. Results from a superseded configuration are closed instead of
being installed into the new catalogue. Unsupported packages, missing classes and
network/provider failures remain failures that can be retried, not fictitious support.

Configuration reloads now restore approved runtimes even when the JSON is unchanged:
the catalogue's reload path retires its previous executors. Adding or rechecking a
spider replaces only that source key and preserves the other runtime types already
enabled in the catalogue.

Validation: 54 `catalog-core` tests and 25 targeted desktop tests passed, including
an unknown portable-JAR provider executing through the adapter, evidence persistence
and expiry, digest rejection, and the unknown-provider Settings action. All three
architecture boundary checks and the desktop distribution build passed.
An additional live Linux check routed 糯米 through `DesktopSpiderRuntime` as a
guarded DEX package and earned `VERIFIED_CATALOG` from current responses (including
two detail lines and 16 episodes). It used temporary verification preferences,
closed its host, and did not access an Android device or request playback.

## Bili playback repair — 2026-09-16, 20:02 log investigation

The reported `operation success label=Resolving …` was completion of a background
request, not successful playback. The preceding resolver log had `supported=false`.
Inspection of the actual response showed a quality/URL array containing
`http://127.0.0.1:-1/proxy?do=bili&…&type=mpd`: the Android app normally supplies
the local proxy, but the desktop child host had not supplied that contract.

The guarded host now owns a per-provider loopback HTTP proxy for the lifetime of
its child process. Only the known unbound CatVod endpoint is rewritten to an
ephemeral port and unguessable path. Requests dispatch to the bundle's static
`Proxy.proxy` / prepared `ProxyOrigin.proxy`, or the provider's instance callback.
The shared runtime implements streaming responses, Range headers, status/MIME and
response headers, GET/HEAD, bounded manifest rewriting, and shutdown. Other paths
and methods are rejected. Remote URLs are unchanged. This preserves the provider's
own DASH resolution logic instead of adding a separate Bili API implementation.

The UI logs task completion separately from an explicit playback rejection, and
the runtime verification revision was bumped. Live checks received HTTP 200 DASH
manifests and decoded five video frames with audio through headless mpv (exit 0).
These were short startup checks, not full-episode viewing or Android device tests.

## Settings runtime visibility correction

Runtime inspection and displayed verification no longer depend on `searchable` or
`indexs`. Previously, configuration-disabled music, sports, and Bili education
entries skipped both the desktop support label and the check action, incorrectly
falling back to “spider runtime required.” Every valid JVM spider specification
now shows its adapter and verification state and can be checked. Configuration
visibility remains separate: checking a disabled source saves its evidence and
closes the probe executor without silently enabling that source. Invalid or absent
JVM specifications show “no supported desktop spider adapter” with an explanation.

## VOD and Settings site roster

VOD previously derived its choices from the executor map, dropping inactive sites
and showing runtime activation order instead of configuration order. Both screens
now retain the configured site roster. `CatalogSourceRoster` in `catalog-core`
separates displayed sites from runnable targets and keeps configuration names,
capabilities and order. VOD annotates inactive sites with their reason and clears
stale results when selected; it never dispatches their requests. Search All uses
only active searchable providers. Runtime activation preserves the saved selection.
Validated with 56 catalogue-core tests, 16 desktop roster/filter tests, all three
architecture checks and the desktop distribution build.

## Empty browse-only sites: corrected configuration semantics

The subsequent log showed selecting `Biliych` produced no Home request, while
`Bili` returned 20 items. The desktop had incorrectly interpreted `searchable: 0`
and absence of `indexs` as disabling the catalogue. This supersedes the earlier
visibility interpretation above: search and index flags do not disable Home or
categories. Shared `TvBoxSiteConfig.browsable` now excludes only `hide` and push
handlers. The desktop carries that explicit capability through loading, activation,
Settings and VOD, retaining `searchable=false` for search targeting.

Default-source startup now includes newly eligible verified providers even if
other approvals already exist; it preserves previously approved providers for the
same fingerprint. Custom bundles retain their approval flow. An empty-state message
is visible in the content area, and a home response with no catalogue suggests search
when supported. `push_agent` is described as an input handler, not an empty VOD site.

Live Linux checks returned: Bili concerts (`Biliych`) 9 categories/20 Home items,
primary-school lessons 19 categories/20 items, and music (`MTV1`) 8 categories/24
items. Each also returned category data and episode details. These were catalogue
checks without playback or Android-device access.

## Escaped playback proxy URLs (奥特)

The 20:31 playback rejection for 第06集 was reproduced with the same detail and
episode ID hashes. Auete returned `http:\/\/127.0.0.1:-1\/proxy` in JSON; the earlier
literal replacement only covered unescaped URLs. The host now parses playback
JSON and rewrites decoded `url`, `playUrl`, and `danmaku` values, including quality
arrays and objects, before returning the response. Headers and other metadata
remain intact. Request diagnostics are cleared before dispatch so previously
buffered startup connection errors do not explain a later playback rejection.

Four proxy regression tests and two host tests pass, along with all three
architecture checks and `:desktop:installDist`. A live Linux probe of the reported
第06集 returned HLS HTTP 200 and decoded five frames of 1920×1080 H.264 plus AAC
audio using mpv (exit 0). This is a short playback startup check, not full-episode
or Android-device testing. The running desktop uses a runtime snapshot and must
be restarted to load the rebuilt distribution.

## mpv header-list serialization (奥特 follow-up)

The 20:46 report was a second failure after resolution succeeded. Live mpv IPC
showed no video format or time position, and debug events exposed HTTP 400 for
every HLS segment. The earlier headless probe omitted the default UA that the
desktop launcher adds, so it did not exercise the failing launch arguments.

A local HTTP capture demonstrated that `--http-header-fields=User-Agent: ...`
split the comma in `KHTML, like Gecko` into a malformed header continuation.
Using the same UA via `--user-agent` preserved it and played 第03集 with video and
audio. The adapter now uses that dedicated option and escapes commas/backslashes
in the remaining generic headers. Tests cover lowercase UA keys and comma-bearing
header values. HTTP error diagnostics now recognize FFmpeg's `HTTP error 400`
wording without logging media URLs. Superseded playback resolutions are ignored
before launching a player or changing status; a delayed-response regression test
verifies that only the latest selection launches.

Both headless corrected-UA playback and the existing visible player's IPC reported
playback after correction (the latter advanced to 1.34 seconds before being closed).
These remain startup checks, not uninterrupted full-episode validation. Desktop
player/UI tests, boundary checks, and the rebuilt distribution pass.
