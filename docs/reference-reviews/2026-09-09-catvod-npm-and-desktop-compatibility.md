# `catvod` npm reference and desktop compatibility implementation — 2026-09-09

This follow-up adds the npm package `catvod` to the managed reference set and implements the three
desktop compatibility defects identified by the all-reference dual-lens review. The package was
reviewed as inert evidence: none of its scripts, JavaScript, Python, or JAR files was executed.

## Reproducible reference boundary

The npm package has no repository metadata, so it is represented as a pinned artifact rather than
as a fictitious Git checkout:

| Field | Value |
| --- | --- |
| Package/version | `catvod@1.5.7` |
| Registry artifact | `https://registry.npmjs.org/catvod/-/catvod-1.5.7.tgz` |
| Integrity | `sha512-9OyYx4Qkv7Fy9/CIq1LsPZi1v03sworfn2yteVAGybYZMERIG0EeSOdtb+eaq+1F7ETX63ejSATWCCHKFh1qMA==` |
| Local path | `references/catvod-npm` |
| License declared by package | ISC |

Workspace setup downloads only from the official npm registry, verifies the pinned SHA-512,
rejects traversal, links, device nodes and oversized archives, validates package name/version, and
extracts without running npm lifecycle scripts. `.tv-reference.json` records the installed
identity. Existing or mismatched directories are never overwritten.

The bounded package review sampled `package.json`, `README.md`, `index.js`, the site/parse/live
shapes in `index.json` and `lite.jsonc`, the complete two-level file inventory, and the `local`
contract in `js/kunyu77.js`. Other JavaScript/Python files and opaque JAR internals were inventoried
by family but not claimed as deeply reviewed. No live provider endpoint was invoked.

## Lens 1 — worthwhile ideas

The package is principally a distribution snapshot, not a runtime architecture to import. Its
useful contribution is breadth of concrete fixtures: flat and JSONC TVBox configurations, Java,
JavaScript and Python provider declarations, M3U/TXT live lists, rule JSON, and CatVod `local`
state usage. That breadth strengthens the case for contract fixtures and explicit capability
classification. It does not justify bundling its opaque JARs or provider catalogue.

`index.js` is specifically unsuitable as application logic: loading it downloads a mutable remote
configuration and spider artifact and rewrites local files. TV therefore treats the complete npm
payload as untrusted, non-executable reference material.

## Lens 2 — platform protocol/standards coverage

The table distinguishes evidence in this package from compatibility fixes carried forward from the
prior all-reference review. In particular, the sampled package configuration has no type-4 site and
its readable JavaScript does not call `js2Proxy`; those fixes remain justified by the other managed
references rather than being newly attributed to this package.

| Surface evidenced by `catvod@1.5.7` | TV Android | TV desktop after this change | Classification |
| --- | --- | --- | --- |
| Flat TVBox JSON/JSONC and relative resources | Covered | Covered | Built-in compatibility |
| MacCMS collection APIs (`type` 0/1) | Covered | Covered | Built-in compatibility |
| Remote Spider HTTP (`type` 4) and CatVod result JSON (carried finding; absent from this package's site list) | Covered | Covered: home/category/search/detail/play, URL-safe filter JSON, headers, `extend`, and URL-valued extension fetch | **Compatibility defect fixed (REF-015)** |
| Classic JavaScript video ABI and object/string results | Covered/partial | Covered/partial through sandboxed GraalJS | Built-in host; provider body externally supplied |
| JavaScript `js2Proxy` → provider `proxy` callback, ordinary text/base64 response (carried finding; absent from sampled package JS) | Covered/partial | Covered through a bounded loopback capability relay | **Compatibility defect fixed (REF-016)** |
| CatVod `local` key/value state | Covered | Durable, provider-scoped and quota-limited | **Compatibility defect fixed (REF-017)** |
| Provider Java/JAR/DEX and Python bodies | Host support depends on configured bundle | JVM/guarded profiles only; Python absent | **Externally supplied capability** |
| M3U/TXT live playlists and common metadata | Covered | Covered | Built-in data compatibility; stream availability external |
| Provider websites, cookies, cloud APIs and opaque JAR behavior | Not built in | Not built in | **Externally supplied capability** |
| `JSFile`, streamed `req`, streamed proxy buffer mode 3 | Provider/runtime dependent | Explicitly rejected | **Intentionally unsupported host capability pending evidence and a file/stream security design** |
| Books, comics, music, pan registry, Node REST host, browser sniffer | Not part of current TV video workflow | Not part of current TV video workflow | **Intentionally unsupported product scope** |

## Implemented compatibility fixes

### REF-015 — remote Spider HTTP

The shared source classifier now recognizes type 4, including configuration-relative endpoint
paths. Desktop resolves the endpoint, creates a core `RemoteSpiderCatalogSourceExecutor`, and uses
its existing bounded HTTP transport. The executor mirrors Android's query dialect and decodes all
responses through the shared CatVod codec. A URL-valued extension is fetched during home loading,
matching Android's `Site.fetchExt()` behavior.

### REF-016 — JavaScript proxy lifecycle

The Graal host now implements classic five-argument `js2Proxy` and invokes the provider's `proxy`
callback through a host-owned server bound only to loopback. Each URL contains a random server
secret and route token, carries an immutable provider path/header capability, expires after six
hours, and is revoked when the source closes. Only GET/HEAD are accepted; hop-by-hop response
headers are removed and response bodies are capped at 24 MiB. The third request-header argument is
passed to providers that need range or conditional request data. Text and base64 buffer mode 2 are
supported; file/stream modes fail explicitly.

### REF-017 — scoped durable JavaScript storage

The host bridge now depends on a storage interface. Desktop stores each site-key/module-URL
identity in a private file under `XDG_CONFIG_HOME` (or `~/.config`), with atomic replacement,
owner-only POSIX permissions where available, namespace/key limits, 512 entries, 256 KiB per value,
and 1 MiB total. State survives runtime recreation and application restart, but is deleted when a
configuration removes that provider identity. This store is separate from cloud account secrets.

## Boundaries retained

- No npm package code, provider script, Python file or JAR is executed by workspace setup or by
  this review.
- Provider correctness, licensing of embedded content, remote endpoint availability, credentials,
  and proprietary cloud behavior remain externally supplied.
- The implementation does not add Node, Python, a browser-sniff service, arbitrary local listeners,
  arbitrary provider filesystem access, or streamed proxy objects.
- REF-012 (machine-readable provider profiles) and the remaining wider REF-013 fixture matrix stay
  follow-up work; this change adds focused offline fixtures for type 4, proxy and persistence.

## Validation

- All eight local workspace-manager integration tests passed; a 17-entry setup dry-run recognized
  the pinned npm reference without changing it.
- `catalog-core`, `runtime-core`, and `source-core` tests passed, including the new type-4,
  proxy, unsupported-stream-mode, and persistent/scoped-storage fixtures.
- Desktop configuration/proxy/storage integration tests and `:desktop:compileKotlin` passed.
- `verifyGenericLibraryBoundaries` and `verifyCodeLifecycleIsolation` passed.
- Debug Java compilation passed for Leanback, Leanback Lite, and Mobile on both `arm64-v8a` and
  `armeabi-v7a`.
- JSON syntax and `git diff --check` passed. No device test, merge, deployment, or package-content
  execution was performed.
