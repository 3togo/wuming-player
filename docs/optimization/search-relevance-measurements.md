# Search relevance preparation and host measurements

Date: 2026-09-11. Completes the offline search implementation and profiling slice.

The shared policy now prepares rank and relevance lazily once per object per sort.
Identity-keyed maps prevent equal IDs from conflating different source objects.
Normalized strings and query values are cached only for the sort; later changes
in titles, ranks or queries are observed. Persisted ranks still precede relevance,
and equal scores retain encounter order. Browse and collection sorting use this
path through `VodScorer`; scalar comparison remains available unchanged.

Differential coverage compares exact object ordering against the frozen old policy
for 96 generated sorts (7,680 inputs), including blank/punctuation-only queries,
aliases, canonical query annotations and multilingual names. Separate tests check
cache lifetime, identity collisions and persisted-rank integration. A 120-item
fixture performs 120 rank reads and 121 normalizations.

## Host results

Two fresh JVM forks per workload/algorithm, two warmups then three measured samples
per fork; 72 measured outputs matched. Fixture creation, GC and output digests are
outside timing. Allocations are caller-thread JVM bytes, not peak heap or RSS.
The host was not isolated; timing ranges show considerable variation.

| Fixture | Work | Algorithm | Median ms (min–max) | Median allocated MiB |
| --- | --- | --- | ---: | ---: |
| unique 100 | merge | legacy | 21.25 (6.50–45.64) | 2.248 |
| unique 100 | merge | prepared | 2.30 (1.18–2.59) | 0.044 |
| unique 100 | sort | legacy | 8.80 (5.42–18.29) | 0.577 |
| unique 100 | sort | prepared | 5.44 (2.51–6.83) | 0.128 |
| unique 1000 | merge | legacy | 326.68 (236.71–394.36) | 219.293 |
| unique 1000 | merge | prepared | 5.06 (4.12–9.96) | 0.448 |
| unique 1000 | sort | legacy | 28.09 (18.78–73.36) | 6.126 |
| unique 1000 | sort | prepared | 16.62 (9.31–30.28) | 1.264 |
| mixed 400 | merge | legacy | 17.48 (12.61–22.52) | 3.379 |
| mixed 400 | merge | prepared | 6.70 (2.91–12.93) | 0.242 |
| mixed 400 | sort | legacy | 37.56 (26.20–59.26) | 4.096 |
| mixed 400 | sort | prepared | 11.30 (6.29–13.05) | 0.276 |

The mixed fixture includes duplicate/ambiguous titles and canonical IDs. The
1,000-result distinct merge removes the dominant repeated normalization cost;
prepared sorting reduces allocation in all measured fixtures. This benchmark does
not include Android's persisted-rank storage, UI publication, providers or JNI.
It supports keeping the managed changes, not a promised Android speedup. It does
not establish worst-case performance for arbitrarily large ambiguous-title buckets.

[Raw samples, source hashes and environment](../../PUBLIC_PREPARATION.md).
[Reproduction](../../scripts/search-profile/README.md).

Validation: 21 search-core tests and 1,520 mobile/leanback app tests passed, with
two app tests skipped; leanback-lite ARMv7 compilation passed. No device run.
Native search conversion is deferred: these results do not identify a remaining
end-to-end CPU bottleneck that justifies JNI and another build/runtime boundary.
