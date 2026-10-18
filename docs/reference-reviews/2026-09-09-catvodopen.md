# CatVodOpen abstraction and standards-coverage review — 2026-09-09

This review used the `tv-reference-abstraction` workflow, beginning in review-only mode and moving
to a user-authorized bounded implementation. The clean reference at `references/CatVodOpen` was inspected at commit
`417012839c5fb168826e959581240b9c42d4de79` (2024-03-30); it was not pulled or modified.

## Question and scope

The review asks two equal questions: which CatVodOpen ideas or behaviors are worthwhile for TV,
and which parts of its configuration, JavaScript-provider, result-schema, proxy, sniffing, and
external-host contracts TV can consume today.

The review sampled CatVodOpen's configuration examples, `open/*_open.js` exports,
`open/wrapper/index.js`, `open/lib/cat.js`, and the Node router/runtime. Those contracts were
compared with TV's Android configuration parser and QuickJS host, shared result models, desktop
configuration parser and GraalJS executor, proxy/sniff paths, and desktop result adapters. Live
provider availability was deliberately excluded: the examples are a compatibility corpus, not a
claim that their target sites still work.

Coverage labels mean:

- **Covered:** the contract is implemented by TV and the sampled representation is accepted.
- **Partial:** an important subset works, with a concrete incompatibility noted below.
- **Bundle-dependent:** TV may receive the capability from an external spider bundle, but does not
  implement or guarantee it as part of its own host surface.
- **Not covered:** no compatible product/runtime path was found.

## Lens 1 — ideas and behavior

| Reference idea or behavior | TV comparison | Decision |
| --- | --- | --- |
| Executable examples as an offline provider-conformance corpus | TV supports much of the video dialect but lacked one fixture spanning entry discovery, initialization, asynchronous object results, result schemas, `homeVod`, and quality arrays | **Implemented as REF-008.** The observable contracts are covered by an original local fixture; no live scraper code was copied |
| Normalize provider values at the runtime boundary | Both TV runtimes assumed string results in paths where CatVodOpen permits objects; downstream parsers should not need to know which representation a provider chose | **Implemented.** Android now serializes JavaScript objects for content and CatVod proxy results; desktop settles immediate promises and serializes object results |
| Staged source self-test across home, category, detail, play, and search | TV already has `CatalogSourceAuditor` with staged results and cancellation | **Already covered conceptually.** Extend its fixtures only where the CatVod dialect adds a missing representation; do not copy the Node test runner |
| Capability-mediated proxy and sniff requests from an isolated provider runtime | TV already separates provider execution and host-controlled WebView/proxy capabilities, though the exposed contracts differ by platform | **Useful principle, existing architecture.** Preserve host ownership, URL policy, resource limits, and cancellation; do not transplant the Node-to-Dart transport |
| Ephemeral Node/Fastify provider sidecar | TV already has QuickJS/GraalJS execution and Android Binder provider isolation | **Rejected as REF-005.** A second runtime and wire ABI add packaging and trust cost without a demonstrated compatibility requirement |
| Electron/Puppeteer sniffer service | Demonstrates browser interception, but the sample exposes an unauthenticated `0.0.0.0` service and weak URL/lifecycle controls | **Rejected as REF-006.** Browser sniffing remains a capability question, not reusable implementation |
| Unified video/book/comic/music/pan registry | Makes media-family boundaries explicit, but TV has no corresponding non-video catalog workflows | **Intentionally deferred as REF-007/REF-010.** Revisit only with a product-scope decision |

The worthwhile abstraction is therefore small: use CatVodOpen as a conformance corpus, normalize
the provider boundary, and retain host-owned capability mediation. Its Node sidecar, sniffer
service, and multi-media registry are not suitable implementation transplants for current TV.

## Lens 2 — protocol and standards coverage

| Contract surface | CatVodOpen contract | TV Android | TV desktop |
| --- | --- | --- | --- |
| Configuration document | Media buckets such as `video.sites`, `read.sites`, `comic.sites`, `music.sites`, and `pan.sites`; type ranges select the media family | **Not covered.** TV reads a flat top-level `sites` list; its type values mean XML, JSON, spider, and encoded extension rather than media family | **Not covered.** Uses the same canonical flat TV configuration model |
| Configuration locations | HTTP(S), `assets://`, `github://`, and `gitee://` examples | **Partial.** HTTP(S), `assets://`, `file://`, and TV's `proxy://` work; no `github://` or `gitee://` conversion was found | **Partial.** Canonical URLs work; CatVodOpen's repository schemes are not recognized |
| JavaScript entry point | `__jsEvalReturn()` returns the source API object | **Covered.** QuickJS detects and invokes it; default export is also supported | **Covered.** GraalJS now invokes `__jsEvalReturn` and retains default-export compatibility |
| Initialization envelope | `init({skey, stype, ext})` | **Covered** for CatVod-style JavaScript entry, using the site key, type 3, and extension | **Covered.** CatVod exports receive `{skey, stype: 3, ext}` while default-export modules retain their previous extension argument |
| Video lifecycle | `init`, `home`, optional `homeVod`, `category`, `detail`, `play`, `search`, optional `proxy` | **Covered.** Android also exposes `live`, `sniffer`, `isVideo`, `action`, and `destroy` | **Partial.** Core lifecycle and `homeVod` merging are covered; provider `proxy` remains outside the desktop executor contract |
| Return representation | Methods may return either an object or a JSON string, commonly through `async` functions | **Covered.** Content and CatVod proxy paths normalize JavaScript objects to JSON while preserving strings | **Covered** for immediate results and promises settled by the synchronous bounded host bridge |
| Video result schema | `class`, `filters`, `list`, `vod_*`, `vod_play_from`, `vod_play_url`; play result `{parse,url,header}` | **Covered** for the sampled video schemas, with additional TV fields supported | **Covered** for the shared catalog model; desktop now delegates to the same platform-neutral CatVod codec instead of a narrower parser |
| Multiple play URLs | `url` may be a scalar or an alternating label/URL array | **Covered.** Android's URL adapter understands the alternating array | **Covered.** The shared codec selects the first valid HTTP(S) value rather than treating a quality label as the URL |
| HTTP request host API | `req` options include method, headers, data, JSON/form/multipart posting, redirects, timeout, text/bytes/base64, and streamed buffer mode | **Partial.** Text, bytes, base64, headers, redirect, timeout, JSON, and form paths exist; CatVodOpen's callback-driven streaming mode is not implemented | **Partial.** Synchronous bounded text HTTP with common headers/methods works; multipart, binary/base64 modes, and streaming callbacks do not |
| Storage and bundled libraries | `local.get/set`; `cat.js` exposes parsing, crypto, URL, template, date, and utility libraries | **Covered/strong** for local storage and the sampled bundled library/import surface | **Partial.** Parser/library support is broad, but local storage is process-memory only rather than durable |
| Crypto and timers | `md5X`, `aesX`, `desX`, `rsaX`, and timers | **Partial.** MD5, AES, RSA, and `setTimeout` exist; built-in DES and `clearTimeout` were not found | **Not covered** as injected CatVod globals |
| File and streaming proxy primitives | `JSFile`, `JSProxyStream`, `js2Proxy`; the `ffm3u8` example exercises streamed writes | **Bundle-dependent/partial.** `js2Proxy` and local proxy routing exist; `JSFile` and `JSProxyStream` are not built in and may only be supplied by a configured external DEX | **Not covered.** Proxy helpers are stubs and the file/stream primitives are absent |
| Sniff interaction | Provider/runtime can ask the host to sniff a URL with timeout and rules | **Partial.** Android has WebView sniffing plus provider `sniffer`/`isVideo` callbacks, but not CatVodOpen's Node-to-Dart message protocol | **Partial.** Guarded WebView parsing exists for TV's own workflow, not as the CatVodOpen JavaScript or Node host callback |
| Books, comics, and audio | `book_*`, `volumes`, `urls`, textual or image-array `content`, plus audio source families | **Not covered** as CatVod result schemas or product workflows | **Not covered** |
| Cloud-disk source ABI | `init`, `dir`, `file`, and `search` with directory/file records | **Not covered.** TV has separate cloud-drive features, not this CatVodOpen provider ABI | **Not covered** |
| External Node provider protocol | `/check`, `/config`, `/spider/{key}/{type}` lifecycle, proxy, pan, push, and test endpoints | **Not covered.** TV remote providers use Android Binder IPC or TV's type-4 HTTP contract, neither of which is this REST protocol | **Not covered** |

## Assessment

CatVodOpen is useful to TV chiefly as a **standards-coverage fixture set**.

Android has strong coverage of the classic CatVod video ABI: the entry point, initialization
envelope, lifecycle, object-or-string results, video fields, URL arrays, common requests, storage,
libraries, proxy routing, and sniffing concepts are present. It is not fully conformant to the
broader CatVodOpen host surface. The clearest remaining gaps are built-in DES, streamed request
callbacks, and the file/stream proxy primitives. The last group must not be reported as supported
merely because a third-party spider DEX might inject it.

Desktop now covers the classic-video entry point, initialization envelope, immediate and
asynchronous object-or-string results, `homeVod`, the shared result schema, and alternating
quality/URL arrays. Overall compatibility remains partial because provider proxy calls and much of
the extended host API are still absent.

Neither client consumes CatVodOpen's nested multi-media configuration, books/comics/audio result
models, pan-provider ABI, or Node external-provider REST protocol. Those are product-scope
boundaries, not automatically defects. They should be labelled unsupported unless TV deliberately
chooses to support those source families.

## Decisions across both lenses

- **REF-008 — bounded CatVod video ABI conformance fixture: completed.** An offline local module
  covers `__jsEvalReturn`, structured initialization, async object results, video lifecycle/schema,
  `homeVod`, and alternating quality/URL playback. Android content and CatVod proxy results now
  normalize objects to JSON. Extended request/file/stream primitives remain under REF-011.
- **REF-009 — desktop classic-video ABI parity: completed for the agreed core.** Desktop now covers
  `__jsEvalReturn`, the `{skey, stype, ext}` envelope, immediate and settled-promise object results,
  `homeVod`, shared CatVod decoding, and alternating quality/URL arrays. Provider proxy support was
  intentionally not added.
- **REF-010 — non-video families and Node REST protocol: deferred.** Record them as unsupported;
  do not add books, comics, audio, pan, or a Node sidecar without an explicit product decision.
- **REF-011 — extended Android host primitives: needs evidence.** DES, streamed `req`, `JSFile`,
  and `JSProxyStream` are real CatVodOpen surface area, but implementation should follow a known
  required provider or conformance target, with file and proxy security constraints specified.

The earlier Node-sidecar, Electron-sniffer, and generalized-registry ideas remain secondary. Their
implementations are not candidates for transplantation; the compatibility contracts above are the
useful evidence.

## Maintenance, security, and licensing notes

The upstream README says the project is not maintained, the latest commit is from 2024-03-30, and
there is no repository-root license file. The npm manifests identify their packages as ISC, which
does not establish provenance for all bundled/example source. Use the repository as behavioral
evidence and test inspiration; verify the applicable license before copying code.

Some reference defaults are unsuitable as production behavior, including globally disabled TLS
verification and a sniffer server exposed on `0.0.0.0` without adequate URL policy. Matching a
wire or JavaScript contract does not require adopting those security choices.

## Validation and remaining work

Focused catalog-core, runtime-core, and QuickJS unit tests passed. Desktop Kotlin compilation,
generic-library boundary checks, code-lifecycle isolation, and Android Java compilation passed for
leanback, leanback-lite, and mobile on arm64-v8a and armeabi-v7a. No device tests were performed.
The report and status JSON were syntax/whitespace checked.

Future CatVodOpen reviews should compare only new upstream commits in the covered contract files.
Provider bodies need review only when they demonstrate an additional ABI representation or a
specific compatibility failure.
