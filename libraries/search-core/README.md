# Search core

`search-core` owns platform-neutral title identity, normalization, query planning, result
annotation, strict matching, duplicate aggregation, relevance scoring and text-similarity policy.

The library does not choose a Chinese script conversion implementation. A client supplies a
`TitleScriptConverter`; Android currently adapts CatVod's converter, while a desktop client can
provide a JVM-native implementation. Client result models are mapped through `SearchResultAccess`,
so the library does not depend on Android's `Vod` bean. Network lookup, persistence and provider
execution remain outside this library.

Result merging uses per-call canonical-ID and normalized-title indexes. Anonymous
results still use ordered year/type compatibility checks within each title bucket
because blank metadata is a non-transitive wildcard. Merge callbacks must preserve
representative title/year/type fields during a call; the Android adapter does so.
No normalization state is retained between merge calls. See
[search indexing evidence](../../docs/optimization/search-indexing.md).
