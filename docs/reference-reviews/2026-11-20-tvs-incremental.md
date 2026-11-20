# TVs incremental reference review — 2026-11-20 UTC

The earlier pull in this session updated TVboxOSC and reported the other four
checkouts current. This review reads the local snapshots from that run. The
[November 11 review](2026-11-11-tvs-ideas-and-contracts.md) supplies the baseline
for TVboxOSC and TVBoxOS. AVBox and TV-K get initial baselines here, so their
older history remains outside the review.

| Checkout | Scan point | Scope |
| --- | --- | --- |
| TVboxOSC | `793e60e6b4f1fb607f728909e8c410eccfdeb736` | Triaged nine commits after `0130b94a345347b40bba832189e8c1a832ca0b08`. Inspected the player/module, ASS and JS compatibility diffs in `600f5f3` and `b054127`, and the configuration quarantine file summary in `746a25a`. |
| TVBoxOS and q215613905 | `89ec1b7504426b3ff5e9d0996c3bbf50c9d2eff0` | Both match the recorded November 11 revision. No new changes. |
| AVBox | `a6da734ed2b14e9eb290e90bd5d7bddf29f23c58` | Six commits after `72e007e398d2a5e924f20e7039fd5fcd49c506f9`, selected as the pre-November 11 baseline. Commit and file summaries show dependency/wrapper updates and documentation and signing-material removals. No application behavior candidate. |
| TV-K | `73793d13a563a157a3400a602d83288a1decd064` | July 16 tip. The immediately preceding change adds FreeBox WebSocket search cancellation. No November delta. The FreeBox pairing protocol is outside the scope of Wuming's existing in-app search lifecycle. |

## Findings and decisions

| Candidate and observed contract | Mobile and leanback | Desktop | Decision |
| --- | --- | --- | --- |
| TVboxOSC QuickJS entry compatibility. Accepts a Spider subclass, a legacy default object or named callbacks. Legacy `detail` receives one ID, while `detailContent` receives a list. The adapter awaits callbacks and decodes JSON-string responses for structured methods. | Bundle-dependent. The public `JsLoader` delegates to a provider-loaded Spider. | Partial overlap. `GraalJsCatalogSourceExecutor` already selects a default object or named exports, invokes legacy `detail(id)` and accepts JSON strings. It calls legacy names such as `home`, `detail` and `play` directly. A modern-only export using `homeContent` or `detailContent` has no matching lookup on that route. | Best candidate for a bounded compatibility follow-up if a provider fixture needs it. Specify method precedence and argument shapes per callback and test legacy-only, modern-only and mixed exports. Keep QuickJS-specific class loading out of the Graal adapter. |
| Optional native player and ASS recovery. TVboxOSC checks the mpv module before probing the player, retries ASS availability after a missing module, and uses the default decoder when its libass route is unavailable. | Wuming's inspected public player factory builds Media3 ExoPlayer directly. The optional Android mpv/libass module lifecycle is outside that factory's scope. | The native mpv backend has a different launch and dependency boundary. | Keep the retry-after-install principle for any future optional native runtime. No port warranted for the current player factory. |
| Curated configuration quarantine. TVboxOSC removes an affected JAR from its catalogue and tightens audit matching for its curated configuration set. This is a release/catalogue process, with no new wire contract. | `ProviderJarCache` delegates artifact acceptance with a supplied digest. This inspection does not establish coverage across every Android source route. | `GuardedSourceTrustStore` records SHA-256 and ABI per approved source. This covers approval identity in the guarded route. | The worthwhile process idea is to audit curated inputs before distribution. Defer until Wuming has a defined curated input set and a demonstrated policy gap. |

TVboxOSC's release and documentation metadata, provider bundles and upstream build
layout are product-specific. The large 3.1.1 release delta and subsequent
configuration/runtime commits were triaged at commit and changed-path level.
The wider Python, networking, subscription and provider fixture deltas remain
pending deeper comparison. There was no runtime, live-provider or device
validation in this review. Application code is unchanged.

Continue by reading [status.json](status.json) and scanning commits after the
saved tip for each checkout. Use commit ancestry as the boundary, since merged
commits can carry author dates earlier than the last scan. If a tip has been
rewritten, check ancestry and inspect the changed history before advancing the
checkpoint. Use the pending range in status.json only when explicitly deepening this scan.

This scan followed the `tv-reference-abstraction` workflow.
