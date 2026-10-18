# Daily maintenance GUI

`./maintain.sh` (activates `~/venv`, then runs `python scripts/maintain.py`; requires `python3-tk`).

Buttons map to the repository's maintenance pipeline:

1. **Build APKs** — `./gradlew-safe` with one assemble task per selected variant, so any
   mode x ABI x build type can be built alone or together. Variant tokens mirror
   `~/.bin/install-apk` and add the lite flavor: `m64 meabi l64 leabi ll64 lleabi`, plus the
   groups `mobile`, `leanback`, `leanback_lite`/`lite` and `all` (commas and spaces both
   separate, duplicates collapse). The default `m64 leabi` is the old "minimal debug" pair and
   `all` + `release` is the old six-APK release build. `type` selects debug/release/
   verification; only release refuses a dirty tree unless the "allow dirty" box adds
   `-PallowDirtyRelease=true`. `tests/test_maintain_build_variants.py` pins the expansion.
2. **Update depot.json** — `python3 scripts/depot/update_depot.py` (optional
   `--refresh`), which rewrites `app/src/main/assets/depot.json` from two intake shapes:
   the scraped READMEs (`README_URLS`) and the structured catalogs in `FEEDS`. Validation
   runs inside the script; `scripts/depot/validate_depot.py` can re-check the file standalone.
   - A new candidate is only admitted when its body is a shape the app can act on: a TVBox
     config (`sites`/`lives`/`spider`/… keys), a warehouse index (`{"urls":[…]}`, followed
     through its **first** entry because `BaseConfig.parseDepot` keeps only `configs.get(0)`),
     or a playlist. HTML pages, `msg` error payloads, JSON-without-keys and binary bodies
     are rejected even on HTTP 200. These bodies are JSON-with-comments carrying raw control
     characters, so the parser is comment- and `strict=False`-tolerant the way Gson is; a body
     that opens with a brace but never decodes is rejected rather than rescued from a nested
     fragment.
   - A playlist is then judged on the channels inside it, not just on the file answering 200.
     `sample_streams` probes one URL per host (up to `STREAM_SAMPLE_HOSTS`, evenly strided so a
     44-channel block behind one dead domain counts once) and rejects when at least
     `STREAM_MIN_GONE` hosts are provably gone *and* they are half of what was testable. A
     failure is only evidence when it holds for every client — DNS death, 404/410, empty or HTML
     bodies; timeouts, refusals, 401/403/429 and IPv6-only routes are recorded as *untestable*
     and excluded, because region-locked Hong Kong streams read identically to a dead list from
     this machine and pruning on that would delete working sources. Sampling runs at admission
     (and for feed-owned entries), not over every shipped entry each run: a full re-probe would
     be ~700 requests per invocation.
   - An existing entry is never dropped by one failed fetch. Consecutive failures follow
     `DepotPrune.PRUNE_AFTER_FAILS` (3, reset on success), so a rate limit cannot delete a
     curated source. The counters live in the gitignored sidecar `.cache/depot_state.json`,
     never in `depot.json`: the asset seeds a fresh install, and shipping an entry already at
     2 failures would let the device's first failed probe delete a source it has never checked.
     Any `failCount` that leaked into the asset is migrated to the sidecar and removed.
   - Hosts no automated probe can answer for (non-ASCII/IDN domains, datacenter blocks) are
     recorded on the entry as `noCheck: true` instead of being counted as validated. This
     replaces the old hardcoded domain whitelist and matches `DepotManager.resolveNoCheck`.
     `assets://` entries (sources bundled in the APK) are read from `app/src/main/assets`
     instead of fetched — an HTTP probe of them always fails.
   - Feed-sourced entries carry `feed: <id>` and are capped by `FEED_BUDGET`. The harvest only
     ever expires entries carrying its own `feed` tag; an entry without one is hand-picked and
     untouchable, and a URL already in the depot is never relabelled or duplicated.
   - Feeds run script-side only, so the device copy gains them when the asset ships. This is
     the intended direction for `README_URLS`' "stay in sync" note in `DepotManager`: a
     third-party curator's list enters the product through a reviewed asset commit, not through
     a runtime scrape.
   - `--dry-run` reports every gate verdict without writing; `--feed ID` (repeatable),
     `--no-feeds` and `--list-feeds` select which feeds run. Upstream availability badges
     (a feed's own daily report) order candidates and are never an admission.
   - **Preview text links** and **Merge text links** take a local `.txt` collection through
     `scripts/depot/import_links.py`. The importer removes repeated URLs, unwraps GitHub
     accelerator links to publisher URLs, excludes credential-bearing/private addresses and
     formats the player cannot load, then applies the same content-shape gate as the updater.
     Existing depot entries keep their names and metadata. Preview is read-only; Merge writes
     at most 20 newly validated entries after checking at most 60 candidates. The CLI accepts
     `--max-check` and `--max-add` for a smaller batch. Unchecked candidates are not imported;
     subsequent runs can be reviewed in smaller curated files. A validated `urls` warehouse
     receives `warehouse: true`; existing sources whose names say 多仓/多倉/仓库/倉庫 are also
     recognized. Android's depot Use action and the desktop source picker present the children
     instead of silently opening only the first entry. Shared child parsing and relative-URL
     resolution live in `source-core`.
   - **Screen + build offline links** runs `python3 scripts/depot/build_link_catalog.py --screen
     <collection.txt>` from the GUI. The equivalent two CLI stages start with
     `python3 scripts/depot/check_link_catalog.py <collection.txt>`, which probes every distinct
     supported candidate once and saves status, HTTP code, `Content-Type`, and a content-shape
     verdict in the gitignored `.cache/link_health.json`. Redirects, access restrictions,
     timeouts, and oversized samples are uncertain, not dead. `python3
     scripts/depot/build_link_catalog.py <collection.txt>` then creates the separate
     `app/src/main/assets/link_catalog.json` from **reachable, compatible responses only**.
     Response body and headers classify VOD versus live, even if the filename suggests the
     other type. The screen recognizes the app's `2423` CBC config envelope (using OpenSSL when
     available) and TVBox `name,url` TXT playlists; missing OpenSSL leaves encoded entries
     uncertain. A GitHub proxy is not used to judge other hosts. The builder excludes existing
     curated depot links and query URLs without recognized credential keys, then divides the
     rest into groups of at most 50. Credential-bearing URLs remain in the asset so they can
     load, but the source browsers show only their names. Android's Depot
     dialog and the desktop VOD/Live panels merge the screened links with curated depot entries
     into a searchable source browser; filters distinguish VOD, live, curated and screened
     entries. Selecting a bundled source uses the normal loader without exposing an editable
     URL. The Android row and desktop Edit URL action open a manual override after the temporary
     local password `123..321`; the shipped catalog itself remains unchanged.
     This is a UI convenience, not a security boundary because the password and bundled URL
     can be extracted from the app. Browsing works offline.
     Reachability is a snapshot, not a playback test. The
     original collection and per-URL probe report are never bundled.
   - **Manage & test links** opens a Python table over the local screening report. Search and
     filter by status or type, select one or more rows to retest, or test the visible rows in a
     background job. The page shows HTTP status, content type, reason, and a masked address for
     credential-bearing links. **Open text collection** merges new candidates into the report
     as untested; **Add URL**, **Edit added URL**, and **Remove added** maintain the versioned
     `scripts/depot/managed_links.json`. Renames and include/exclude choices live in
     `scripts/depot/link_catalog_overrides.json`, keyed by URL hash so that file does not
     repeat credential-bearing URLs. **Rebuild app catalog** writes only reachable, included,
     compatible entries to `link_catalog.json`; curated depot entries are visible for testing
     but remain managed through `depot.json`. Testing or changing an override alone does not
     rebuild the app asset. The report stays in `.cache/link_health.json`; added URL records and
     the app asset contain full addresses, including any token a maintainer chooses to add.
3. **Post APKs** — `bash scripts/upload-apks.sh` with channel selection and extra
   flags. Dry-run is the default; publishing shows the exact command and requires
   typing `PUBLISH` because it runs `git push`, uploads APKs to
   the repository explicitly configured in `updates.json`, and advances its update feeds.
   The public preview uses its independent signing identity and the public
   `3togo/wuming-player` destination. TV checks remain pending. See
   [android-updates.md](android-updates.md) for the full pipeline.
   Only the newest build is ever posted: `publish-updates.py` validates exactly
   the `{mode}-{abi}-{stamp}.apk` files matching the current HEAD stamp (older
   APKs kept in `debug/` and `release/` are never uploaded), and
   `upload_github_assets` skips assets whose SHA-256 and size already match the
   remote, so reruns are no-ops. Uploading different bytes under an existing
   asset name aborts with "increment the version" instead of overwriting;
   `--resume` re-posts the same version and uploads only what is missing.
4. **Connect ADB device** — `~/.bin/adbconnect`. The picker defaults to `all`,
   which runs adbconnect's full scan of every local network; remembered hosts
   from `~/.adbconnect_known_hosts.json` and typed suffix/full hosts (optional
   port) connect directly. GUI jobs start in their own session, so `/dev/tty` is
   unavailable and adbconnect falls back to reading stdin: its device-selection
   prompt appears in the log and the input box next to Stop answers it.
5. **Install APK to device** — `~/.bin/install-apk <position|all> [variant]`,
   run from the repository root so it finds `app/build/outputs/apk`. The picker
   lists `adb devices` rows with positions and offers `all` (bare keyword;
   install-apk has no `--all` flag). Variant `auto` keeps install-apk's
   per-device default (TV -> leanback eabi debug, phone -> m64 debug).

Requires `python3-tk` plus `~/.bin/adbconnect` and `~/.bin/install-apk` for
actions 4 and 5.

The status panel shows branch, dirty state, `updates.json` version, depot entry
counts, and the newest APKs in `debug/` and `release/`.

## Curated live lists (offline builder)

The public snapshot bundles only `app/src/main/assets/live/public_samples.m3u8`
(`Config.PUBLIC_SAMPLE_LIVE_URL`), an attributed openly licensed movie link. The
historical HK workflow below is optional tooling, not the current bundled catalog.
Generating another list requires its own content-rights review; anonymous HTTP
availability alone is insufficient for inclusion in a public build.


`python3 scripts/depot/build_curated_live.py --profile hk` applies one region's curatorial policy,
held as data in `scripts/depot/live_profiles/hk.json`, to that region's source playlists and renders
a single bundled M3U. The script is the generic engine, so a second region is a new JSON file, not
a new script. The profile is now **our** policy (revision 2), seeded from
`sammy0101/hk-iptv-auto`'s `main.py` but deliberately different where our audience differs:
`provenance.notes` records that Phoenix (鳳凰) and 星河 were block words in upstream's list because
he serves terrestrial Hong Kong households, while our viewers reach the app through a proxy and
Phoenix is often the reason they open it; simplified-Chinese aliases were added because the
transcribed table was silently deleting real channels; and the 25 canonical names, viewing order,
`max_urls_per_channel: 4` and `mirrors_per_host: 2` are ours (upstream curated 14).

- The app does not aggregate anything. It reads the rendered asset
  (`app/src/main/assets/live/hong_kong_curated.m3u8`, referenced by `Config.CURATED_HK_LIVE_URL`)
  and `depot.json` cannot carry this policy anyway: `RemoteDepotSync.mergeDepots` rebuilds the
  file from its `vod`/`live` arrays only, so any extra top-level section disappears on the first
  weekly sync.
- Upstream's own validity test (HTTP 200 within two seconds) was deliberately not adopted.
  Probes go through `update_depot.stream_evidence`, so a stream only leaves the list when the
  failure holds for every client (dead DNS, 404/410, empty/HTML body). Timeouts, refusals and
  401/403 from this host are `untestable` and are kept — there is no HK route and no IPv6 here,
  and a Hong Kong stream looks exactly like a dead one. `--no-probe` skips the whole question.
- Group titles come from the profile's declared `host_groups` table, never from scraping the
  previous output. "This domain works without a proxy" is a human judgement about a viewer's
  network, and if the generator read it back out of its own last render every rebuild would be
  learning from a machine-written file while pretending to be curated. An unknown host is
  labelled fallback: unknown never means directly reachable.
- `official_channels` are injected even when no source lists them, are ranked ahead of the
  harvest, and sit outside the cap and the mirror allowance — the declared table exists to correct
  the aggregator, so it must not depend on it. The table also *owns* those URLs: one upstream
  sells `ch78/index-fhd.m3u8` (HOY 資訊台) under the name "HOY TV", and that copy is now dropped
  and counted as `mislabelled stream` instead of publishing one stream as two channels.
- `pinned_channels` holds the human choices the harvest structurally cannot produce — for
  instance the three `cdn3.indevs.in` streams and `iptv.4666888.xyz`, which answered content when
  probed from this box on 2026-10-01 and appear in no source playlist. A pin that probes as
  provably dead is kept and printed as a warning, because a pin was decided with information this
  run does not have; a pin the harvest already published is reported and not duplicated.
- Every run diffs itself against `reference_asset` and prints the result in the names and groups a
  viewer would see: shared, only-in-the-curated-asset (`would be lost:`), only-in-this-build.
  Measured on the shipped 29-entry list, the current build keeps 16, drops 13, and adds 58. The 13
  are `stream1.freetv.fun` mirrors whose hashes today's sources no longer carry, for channels the
  build does publish (it ships 10 other freetv.fun hashes). That host is not testable from here at
  all — its IPv4 address 111.243.214.169 is unreachable, and the hashes the build itself published
  fail identically, which is the control batch that makes this a replacement rather than a
  pruning. Numbers move with the sources; read them before using `--write`.
- `--keep-unmatched` is off by default. Names the alias table does not know are counted and the
  top ones printed, because that report is how the table gets extended; the same report is why
  an alias-less channel can never vanish silently.
- `--write` replaces the asset only after `validate_depot.backup_depot` has copied it aside; the
  default output is `.cache/live/hk_generated.m3u8`, which is gitignored. The shipped list has not
  been replaced. Its one remaining judgement the generator cannot reproduce is
  `http://r.jdshipin.com/0Rp07` (鳳凰資訊 as a direct shortlink): no current source lists it and
  this box only resets it. Pin it on purpose or drop it on purpose — do not let a rebuild decide.
- `tests/test_build_curated_live.py` (43 cases) covers alias resolution (longest alias wins, so
  `ViuTVsix` is not absorbed by `ViuTV`), block keywords beating alias matches, gone-vs-untestable,
  the per-channel cap and per-host mirror allowance, group resolution from the declared table,
  official streams surviving a full channel of mirrors, mislabelled duplicates losing to the
  declared table, pins surviving a dead probe, the reference diff and its labels, and output
  round-tripping — all with stubbed fetches and no sockets.

## Root layout after the 2026-10-01 tidy

Maintenance scripts live under `scripts/`: `scripts/build/` (SDK/env bootstrap,
variant builds), `scripts/depot/` (depot.json scraping and validation, plus the
`live_profiles/` region policies and `build_curated_live.py`),
`scripts/device/` (adb helpers), `scripts/desktop/` (deb packaging and desktop
launcher), plus `scripts/maintain.py` and the other test/publish helpers.
`tools/` holds image and proxy utilities. Kept at the repository root on purpose:
`build.sh` (Java/SDK bootstrap used by `~/.bin/apk-build`), the Gradle wrapper
files, and the runtime configs `updates.json`, `github_proxies.json`, and
`wuming-config.example.json`, which are read by app/desktop/gradle code by
root-relative path.
