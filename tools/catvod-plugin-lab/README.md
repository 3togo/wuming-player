# CatVod plugin lab

Offline research harness for the project's **OpenDrive** cloud pipeline.

It answers one question systematically: **what do the third-party CatVod plugin packages
that the fleet of public configurations ships actually do for cloud drives, and where does
OpenDrive differ?**

Everything here is *static analysis*. Nothing in this tree ever loads, dexes, initializes
or otherwise executes a downloaded plugin: an encrypted/guarded package is untrusted
machine code from an unknown author, and running it would hand it our process.

## Pipeline

```text
app/src/main/assets/depot.json
        │  (1) fetch   : every config URL in the depot, vod + live
        ▼
   config snapshots ── decode (BOM / base64 envelope) ──> sites + `spider` field
        │  (2) fetch   : resolve every jar reference (absolute, page-relative, `;md5;`)
        ▼
   plugin artifacts   (.jar .zip .apk .so .dex — extension is irrelevant)
        │  (3) inventory : container shape, dex entries, ELF, embedded dex/zip carving
        │  (4) scan      : class names + string pool per dex; netdisk family evidence
        │  (5) improve   : extract per-family content into OpenDrive improvement leads
        ▼
   corpus report + append-only evidence ledger
```

Commands mirror the stages:

```sh
python3 -m catvod_lab doctor                       # toolchain + dependency check
python3 -m catvod_lab fetch     --limit 20         # stages 1 + 2 (network)
python3 -m catvod_lab inventory                    # stage 3     (offline)
python3 -m catvod_lab scan                         # stage 4     (offline)
python3 -m catvod_lab report                       # roll-up     (offline)
python3 -m catvod_lab improve                      # stage 5     (offline)
python3 -m catvod_lab run --limit 10               # all of the above
python3 -m pytest tools/catvod-plugin-lab/tests    # offline unit tests
```

`fetch` is the only command that touches the network, so every later stage is
deterministic and re-runnable without it.

## Storage

Default work root is `<repo>/.cache/catvod-plugin-lab`, which is already covered by the
repository `.gitignore` rule for `.cache/`. Override with `--work`.

```text
.cache/catvod-plugin-lab/
  configs/<host>-<sha256[:8]>.bin   raw config response (reproducibility)
  configs.jsonl                     one record per depot config (url, status, final url, hash)
  refs.jsonl                        one record per plugin reference a config declared
  artifacts/<aa>/<sha256>.<ext>     raw artifact bytes, deduplicated by SHA-256
  artifacts.jsonl                   provenance: who served it, HTTP status, md5, size, mirrors
  inventory.jsonl                   container shape + dex/ELF facts per artifact
  scan.jsonl                        class names, families, endpoint evidence per artifact
  report/corpus.json                aggregated corpus roll-up
  report/corpus.md                  human digest
  report/opendrive-leads.json       per-family improvement leads (machine readable)
  report/opendrive-leads.md         per-family improvement leads (human digest)
  report/evidence-ledger.jsonl      append-only findings (stable ids, never silently rewritten)
```

## Why each parsing rule exists

The extractors deliberately copy production semantics, otherwise the corpus drifts from
what the app would really download:

| Rule | Production source |
| --- | --- |
| BOM strip, `XXXXXXXX**` base64 envelope | `libraries/source-core/.../TvBoxConfigPayloadDecoder.kt` |
| `spider` field and per-site `jar`/`ext` fallback | `libraries/source-core/.../TvBoxConfigDecoder.kt` |
| `type` 3 + non-`.js` api ⇒ spider runtime required | `libraries/source-core/.../TvBoxSourceRuntimePolicy.kt` |
| `;md5;<md5>` suffix, md5 may itself be a URL | `app/.../api/loader/JarLoader.parseJar()` |
| relative jar references resolve against the **final** config URL after redirects | observed: `./jar/pg.jar`, `../jar/pro.jar` |
| `assets://` / `file://` references are skipped, not fetched | they only exist inside an installed app |

Extension names are noise. CatVod plugin packages have been observed as `.jar`, `.zip`,
`.apk`, `.php`, extension-less blobs and — the case this lab was built for — **`.so`**
files, where the real content is either an ELF with an embedded dex, a ZIP renamed to
`.so`, or a straight dex. All detection is therefore done from magic bytes, never from
the URL suffix.

## Evidence rules

Copied from [`docs/catvod-opendrive-audit.md`](../../docs/catvod-opendrive-audit.md)
`## Change rule`, because a corpus is only useful if it cannot manufacture permission to
guess:

- every finding records the artifact SHA-256 (identity), where it came from, and the
  exact string/class it rests on;
- every finding is created with `classification = unverified`. Nothing here upgrades a
  finding to a fact, and nothing here edits provider code;
- a request order is only recorded from readable source evidence, never inferred from an
  endpoint name;
- endpoints absent from our own sources are reported as *gaps to investigate*, never as
  "OpenDrive is missing X" — absence of a literal is not proof of a missing capability.

## Safety

- No plugin code is ever executed: no JVM, no `DexClassLoader`, no decompiler invocation
  on untrusted input from inside this tree.
- Hard limits on download size, request time and request count per run.
- Only `http(s)` is fetched automatically; `file://` and `assets://` references are
  recorded and skipped.
- Downloaded artifacts are third-party binaries. Keep them inside the ignored work root;
  do **not** commit them, and only derived facts (identifiers, class names, endpoint
  strings) are allowed to reach `docs/`.
- Config snapshots may contain your own subscription tokens if you point the harness at a
  private configuration. Keep the work root outside version control — it already is.

## Tooling

Required: `python3`, `requests`. Optional and *not* invoked automatically:

- `jadx`, `apktool`, `baksmali`, `dexdump` — use them by hand on a specific artifact if a
  finding needs readable pseudocode. Their absence does not block any stage; the built-in
  dex reader covers class lists and string pools.
- `java` — same reason. `catvod_lab doctor` reports availability.
