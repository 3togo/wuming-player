# Local package collection integration review — 2026-09-28

## Recommendation

Integrate this collection as separately versioned, optional source packages in the existing depot. Preserve each package's configuration, provider binaries and resource tree. First improve package import and validation; only then offer a curated cross-package search view. Do not concatenate all site arrays or transplant these binary providers into the built-in cloud implementations.

## Scope and evidence

User-supplied collection: `/home/developer/git/TVBoxOSC/推荐接口/本地包合集/本地包合集260728`.
TV HEAD: `e166da13cbb89ce8af6a04ed0e8a73a574705220`, plus the current working tree, which already contains unrelated changes, including depot synchronization and native decoder work. No references were pulled. This collection is an explicitly requested external input, not a new managed reference repository.

Reviewed all file names/hashes, nine plain top-level configurations, eight paired `origin.json` files, all ZIP directory structures, and the encoded envelope in 雷蒙. Compared selected Android/desktop configuration, loader, identity, upload and runtime code. This is a bounded configuration/dependency review, not a provider implementation audit or proof of live service availability. No downloaded provider code was executed, no endpoints were contacted, and no device was accessed.

The [inventory](2026-09-28-local-package-inventory.json) records every file's SHA-256 and archive entries without copying endpoint secrets or provider bodies.

## Inventory

193 files; 34,269,095 bytes (32.68 MiB). Nine plain entry configurations contain **705 site entries**, not 705 unique services. Their types are 686 type-3, 11 type-1 and eight type-4. API strings identify 636 CSP entries, 42 JavaScript entries and eight Python entries; the other 19 are the HTTP API entries. Eight `origin.json` files duplicate the respective configuration's site collection and must not be counted as additional packages.

| Package | Sites | Integration assessment |
| --- | ---: | --- |
| 嗷呜 | 85 | Root DEX includes `woshinidie.bin`; also has a separate per-site DEX archive under `img/`. Inspect both artifacts. |
| 小米 | 38 | Compact plain-DEX candidate for a first Android package pilot. Runtime behavior remains unverified. |
| 摸鱼 | 85 | FishGuard ARM32/ARM64 libraries; outside the desktop inspector's current ftyguard contract. |
| 无意云 | 126 | Best broad compatibility fixture: CSP, direct JS, five Python entries, seven MacCMS entries and one type-4 entry. |
| 王二小 | 87 | wexguard ARM32/ARM64 libraries; outside the current desktop ftyguard contract. |
| 肥猫 | 39 | Compact plain-DEX candidate; its children's JS entry still depends on a remote drpy engine. |
| 虎斑 | 66 | Plain DEX, direct drpy rules and seven remote type-4 entries; useful HTTP compatibility fixture. |
| 雷蒙 | Not counted | `raw/source.txt` starts with PNG data. Base64 envelope decodes to `2423…` AES-CBC text, not JSON. |
| 雷蒙影视WP | 130 | Named entry file rather than `index.json`; multiple unresolved assets and compound extension paths. Repair before offering it as ready. |
| 饭太硬 | 49 | ftyguard package matches the desktop inspector's recognized naming contract; execution still needs hash/ABI-specific verification. |

All eight standard packages' JSON string values beginning `./` refer to existing files when interpreted as ordinary paths with integrity suffixes removed. All eight root MD5 declarations match the files; 嗷呜's per-site MD5 also matches. This check does not establish nested script dependency closure, URL-decoding behavior or service health.

There are **13 ZIP-format plugin artifacts**, all containing DEX and no JVM `.class` entries. Some use `.png`, `.jpg` or `.php` extensions; sniff file contents rather than extensions. For example 嗷呜's extra plugin is under `img/`, so scanning only `jar/` misses a required artifact.

雷蒙影视WP has demonstrably missing direct references including `./lib/雷蒙资源.js`, `./lib/bililive.m3u`, `./lib/huyayqk.m3u`, `./lib/douyuyqk.m3u`, and `./N3RD/W/ICONSGIF.gif`. Many `ext` values start with the absent `./N3RD/T/pg.json` and contain `$$$`-separated arguments. A naive file checker must not interpret that entire argument string as a filename. Preserve the grammar and validate each documented resource position.

## Existing app behavior and concrete gaps

1. **The loading infrastructure already exists.** Android `api/Decoder.kt`, `api/config/VodConfig.java`, and `api/loader/BaseLoader.java` support configuration loading and runtime dispatch. `server/process/Local.java` already accepts ZIP uploads; `utils/FileUtil.kt` rejects extraction paths outside the destination. Extend this workflow with validation and atomic installation instead of building another loader. Extraction currently logs failures internally and upload returns success, so successful upload is not evidence of a valid installed package.
2. **Local desktop references are incomplete.** `desktop/.../net/DesktopResourceLoader.kt` accepts paths/file URIs, but `DesktopTvBoxConfigurationLoader.resolveLocation()` only resolves dependencies when the base is HTTP(S). A local `index.json` leaves `./jar/...` relative to the wrong context. Its JS execution mapping also requires HTTP(S), so simply fixing file resolution does not activate local JS.
3. **Resource grammar needs explicit handling.** Desktop resolution encodes path segments including query-bearing text and existing `%` escapes, and does not collapse `..` segments. Android's `Decoder.fix()` uses broad text replacements with a special case for JS query strings. This collection supplies useful fixtures for Chinese paths, literal percent-encoded filenames, `;md5;`, nested object extensions and `$$$` arguments. Centralize resource-reference rules in `source-core`, leaving provider-specific opaque data intact. Do not recursively rewrite every arbitrary string.
4. **Raw site keys are insufficient across packages.** The nine configurations have 634 distinct keys and 49 keys repeated across packages, including `push_agent` in all nine. Android `Site.java` uses `key` as the Room primary key; `VodConfig` syncs preferences by key; JS/Python caches and interactive CSP tracking also use site keys. JarLoader's stronger jar/API/ext cache identity does not solve those other collisions. Depot separation alone is therefore only an interim organizational improvement, not complete state isolation.
5. **One giant config loses execution context.** Different packages use different root spiders, parsers, flags, rules and live definitions. Android also has a recent-loader parser/proxy path. A merge that just chooses one root spider will instantiate the wrong provider; adding per-site jars alone still leaves parser/proxy ownership unresolved.
6. **Runtime support must be honest.** `gradle.properties` currently has `chaquopyEnabled=false`; Python entries are unavailable in this build despite the presence of `PyLoader`. Shared classification treats non-JS type-3 entries as spider-runtime-required, so desktop needs an explicit Python capability result rather than trying to activate them as CSP classes.
7. **Desktop is more capable than an ordinary JVM JAR loader.** `DesktopSpiderRuntime` already routes plain DEX and recognized guarded DEX through a separate preparation/host pipeline. Extend verified coverage there. `GuardedSpiderPackageInspector` recognizes ftyguard names only, excluding the observed FishGuard/wexguard packages. `SpiderArtifactFingerprint.isPlainDex` does not identify `woshinidie.bin`; classification of 嗷呜 as plain DEX would not prove that its payload is usable.
8. **Envelope support differs.** Android `Decoder` includes the `2423` AES-CBC decoding branch. Shared `TvBoxConfigPayloadDecoder`, used by desktop, requires JSON immediately after Base64 decoding, so it rejects 雷蒙's observed envelope. This is a concrete cross-platform configuration gap; the inner configuration was not decrypted in this review.
9. **Existing depot sync is complementary.** The current working-tree `RemoteDepotSync.kt` appends entries by URL. Reuse it for discovery, but package identity/version/content and activation belong in a package layer. Different paths or mirrors may identify the same package; the same URL may acquire different executable bytes.

## Observed protocol coverage

Covered means an implementation path exists, not that these endpoints or every dialect passed a test.

| Observed contract | Android | Linux desktop |
| --- | --- | --- |
| TVBox config, relative files and MD5 suffix | Partial: existing HTTP/file bridge and rewriting; nested/encoded grammar needs fixtures | Partial: parses schema; local-base resolution gap |
| Type-1 MacCMS JSON HTTP | Covered runtime path | Covered runtime path |
| Type-4 remote CatVod HTTP | Covered runtime path | Covered runtime path; endpoint behavior external |
| Type-3 CSP Android DEX | Bundle-dependent/external; existing DexClassLoader | Partial: plain DEX conversion/host exists; provider Android API needs vary |
| ftyguard DEX + ARM assets | Bundle-dependent/external, device ABI dependent | Partial: recognized preparation route; exact hash/ABI must be verified |
| FishGuard / wexguard / woshinidie payloads | Bundle-dependent/external | Not covered by the inspected ftyguard contract; do not imply equivalent support |
| drpy JS engine + rule extension | Partial: QuickJS path exists; nested imports external | Partial: GraalJS path exists; local script activation gap, host dialect still needs checks |
| Python Spider entries | Not covered by current build setting | Not covered by inspected desktop execution routes |
| Image + Base64 + `2423` CBC | Partial: decoder branch exists; this fixture not run through app | Not covered by shared envelope decoder |
| M3U / TVBox TXT live playlists | Covered parsing path; stream services external | Covered parsing path; stream services external |
| Package-specific config/cloud-login/market UI | Bundle-dependent/external | Partial Android host surface; do not promise arbitrary UI parity |

No explicit upstream protocol version accompanies these configurations. Classify them as observed TVBox/CatVod dialects, not a single versioned standard. Embedded app-market and ebook functionality is not part of this proposed video-source integration.

## Proposed implementation order

### P0 — Package import, identity and diagnostics

Add a small package manifest and validation policy to the existing `source-core`; keep file selection, copying, ZIP extraction and persistence in Android/desktop adapters. Suggested records: stable package ID, version/content digest, entry config, original provenance, artifact SHA-256s, dependency list and per-platform capability outcomes. Use app-managed version directories with staged validation, atomic activation and previous-version rollback. Preserve `origin.json` as provenance; activate the local entry file. Do not treat a matching MD5 as publisher trust.

Present: import → inspect dependencies/runtime requirements → select package/sources → activate. Reuse depot UI and Android upload surface; keep TV controls D-pad accessible. Avoid copying all 32.68 MiB into every APK. Package files can be local while providers still require network services; label that distinction clearly.

Use a stable host identity `(packageId, originalSiteKey)` throughout settings, caches, history, search provenance and proxy routing. Retain the original key separately for provider contracts; do not blindly rename keys passed to plugins that may depend on them. Content hashes identify versions/executables, not the persistent user-facing source identity. Migrate existing preferences deliberately.

### P1 — Fix local compatibility and select a small usable set

Unify base-aware resource resolution and add a package-scoped resource port for local JS, without exposing arbitrary filesystem access to scripts. Test percent-encoded filenames, Chinese names, query parameters, `..`, MD5 suffixes, object extensions, compound arguments and per-site jar overrides. Add explicit capability outcomes for Python and unknown guard formats. Extend shared envelope support only with a bounded regression fixture.

Pilot 小米 or 肥猫 on Android because their dependency/DEX packaging is comparatively simple. Use selected 无意云 MacCMS and 虎斑 type-4 entries to exercise both platforms, followed by selected drpy sources after local resolution is fixed. Prioritize 饭太硬 for guarded desktop verification because the existing runtime recognizes its package shape. These priorities reflect integration effort, not measured reliability or content quality. Defer 雷蒙影视WP until its dependency tree is repaired.

### P2 — Curated discovery using existing app policies

Keep package boundaries underneath an optional unified search view. Use existing `source-core` health/performance policies and `search-core` aggregation to rank verified entries, retain origin attribution and collapse duplicate results without deleting alternate providers. Start with a small enabled set, expand after discovery → search → detail → playback audits. Configuration parsing alone is not a health check.

For cloud discovery, accept structured share links into the existing built-in OpenDrive resolver where that contract is available. Leave opaque plugin playback routes in their provider runtime. Do not import plugin cookies, QR-login state or cleanup code into built-in accounts; preserve the existing Ali/UC/Quark route and cleanup contracts. Broad cloud fallback changes are deferred because binary inventory gives no behavioral evidence supporting them.

### Deferred or rejected

- Concatenating all 705 entries: key collisions, wrong root jars and ambiguous parser/proxy context.
- Reimplementing providers from names or binary strings: insufficient evidence of behavior, ownership and licenses.
- Broad native guard emulation expansion: assess one format/hash/ABI at a time after simpler integration succeeds.
- Replacing the app's UI/theme, DNS/proxy policy or cloud accounts with package defaults: not needed to integrate sources; scope settings to the selected package.
- Automatically running all imported providers or following `origin.json` remote updates: import inspection and executable activation/version changes are distinct actions.

## Validation and remaining work

Completed: static JSON inventory, local-reference existence checks described above, declared MD5 comparison, all-file SHA-256 inventory, ZIP member inspection, envelope prefix decoding and source-level platform comparison. No production implementation changed. No build, playback, endpoint or device test was performed.

Implementation should add targeted shared resolver/import/identity tests and desktop capability tests, run affected module checks and `verifyGenericLibraryBoundaries`, `verifyDesktopBoundaries`, `verifyCodeLifecycleIsolation`; run `scripts/check-leanback-remote.py` if import TV UI changes. Later provider execution requires staged results, separate ABI evidence and explicitly authorized device access. Remaining scopes: nested JS/Python dependencies, decrypted 雷蒙 config, runtime provider/API behavior, redistribution provenance, and exact credential/settings interactions.

Workflow: [tv-reference-abstraction skill](/home/developer/.codex/skills/tv-reference-abstraction/SKILL.md).
Reusable request: “Use tv-reference-abstraction to implement P0 from the 2026-09-28 local-package review, preserving package boundaries and desktop/mobile shared policy.”
