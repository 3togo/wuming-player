# TVboxOSC Python, networking and subscription follow-up — 2026-11-20 UTC

Reviewed selected source changes in the saved range
`0130b94a345347b40bba832189e8c1a832ca0b08..793e60e6b4f1fb607f728909e8c410eccfdeb736`.
This review used the checkout already recorded in [status.json](status.json).

| Domain | Wuming adaptation and coverage |
| --- | --- |
| Python provider compatibility | Compared `dd6a6c1` in `engine/chaquo/src/main/python/{app.py,base/spider.py}` and its SDK fixture cases against Wuming's shared Python bridge. The bridge now normalizes native lists, JSON-array strings and null for `homeVideoContent` into the CatVod list envelope. Shared `base.spider` now accepts positional POST data, provides `postJson` and `xpText`, and supports optional cache duration in seconds with backwards-readable files and atomic replacement. Wuming already adapts legacy search arity and preserves internal provider errors. Mobile, leanback and desktop consume the shared bridge. The Chaquopy-specific host network, async and streaming SDK remains a different runtime contract. |
| Networking | Compared `OkProxySelector.java` and `ProxyRedirectInterceptor.java`, plus the `Net.java` session and cache API surface, in the saved tip. Wuming's CatVod selector now publishes initialized sorted rules atomically and uses a single snapshot for each selection. It no longer logs raw request URIs. Mobile and leanback use this selector. Desktop has its own transport. Wuming's GitHub-only configured proxy policy precludes adopting upstream's cross-host proxy inheritance. The upstream domain quarantine and session/socket/cache SDK are tied to its runtime and curated catalogue. They were not ported. |
| Subscription configuration | Compared `372c648` in `ConfigCompat.java` and `SourceDefaults.java`. Added shared normalization for ZyPlayer `sites.data`, `parses.data`, `lives.data`, and the fallback `iptv.data` and `analyzes.data` aliases. Canonical fields win. Android's JSON resource rewrite and desktop's configuration loader use the shared rule before decoding. The shared warehouse picker accepts `storeHouse` entries alongside `urls`, with existing safe URL checks, relative resolution and deduplication. This covers the warehouse picker path. TVboxOSC's migration of its retired bundled subscriptions is specific to its old asset URLs. |
| Provider verification fixtures | Inspected the test families in `scripts/test_python_sources.py` and the staging and report entry points in `scripts/verify_source_compatibility.py`. Reused the shared bridge and local configuration harnesses for offline controls. Upstream provider-specific repaired URLs, binary artifacts and live compatibility claims were not imported. |

Validation passed for 13 Python bridge tests, 157 source-core tests, 87 CatVod
tests and 23 desktop configuration-loader tests. Mobile and leanback ARM64
verification Kotlin compilation, generic-library, desktop and code-lifecycle
checks, and diff whitespace checks passed. No Python distribution archive was
rebuilt and no physical Android device or live provider was exercised.
