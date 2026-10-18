# Search optimization: indexed merging and source ranks

Date: 2026-09-11. This is the first implementation slice of phase 3. It removes
whole-result-list scans for unrelated titles and known canonical IDs, and replaces
source-list scans during fast-search sorting. The subsequent relevance implementation and host profiling are documented in
[search relevance measurements](search-relevance-measurements.md).

## Identity and ordering contracts

The old merge policy is not ordinary title deduplication:

- If either result has a canonical ID, both must have the same nonempty ID.
  An identified result never merges with an unidentified result merely because
  their titles match. Known IDs can merge across different titles/year/type values.
- Otherwise, titles must have the same nonempty `TitleNormalizer.key()`. This
  includes the existing script conversion, NFKC normalization, punctuation removal
  and space removal. Empty or punctuation-only normalized titles never match.
- Year and media type are compatible when either original value is blank/null, or
  their nonempty normalized keys match. Punctuation-only metadata is not the same
  as blank metadata.
- The first compatible representative in encounter order wins. Blank metadata is
  a wildcard, making compatibility non-transitive: a missing-year result must merge
  into the first compatible year, not combine all versions of the title.
- Preserve representative objects/order, source provenance, alias order/deduplication,
  first available artwork, and original/matched-query fill-in behavior.

## Implementation

`SearchResultPolicy.merge` builds two local indexes: a direct canonical-ID lookup
and ordered buckets for unidentified normalized titles. Canonical results never
enter anonymous title buckets. Candidate comparisons still scan the applicable
title bucket in encounter order and apply year/type compatibility. Empty title
keys bypass the index and remain separate.

A normalization map lives only for the current merge call and is discarded on
return. It stores title/year/type keys, not mutable result snapshots. A subsequent
incoming batch rebuilds indexes, so changed result metadata is observed. The current
Android access adapter only changes provenance, identity annotations and artwork
through merge callbacks; it does not mutate representative titles/year/type during
merging. The index depends on that contract and on stable script conversion within
a call; future adapters must preserve it.

`FastSearchCoordinator.begin` builds a source-key-to-rank map from the session's
ranked sites. `putIfAbsent` retains the first position for duplicate keys, matching
the former linear lookup. Unknown sources still rank last, equal ranks still
compare equal, and stable sorting retains their incoming order. `reset` clears the
map. Source IDs/rank order are treated as session configuration between `begin`
calls; no global cache is added. Source naming and insertion-position logic remain
in their existing paths.

Both mobile and leanback fast-search adapters already call the shared merger and
coordinator; no layout, focus, launcher label or provider code changes are needed.

## Regression evidence

The test-only `LegacySearchResultPolicy` is a frozen copy of the actual old policy,
renamed to avoid replacing production code. Its original source SHA-256 is
`61c4bd81d61d16bf5133bb2052646a48e69dcfc90f08a06293b5b6413d18bfbc`; it matches the
file introduced by commit `e4ad4ee867fdff517795945e3b628b98d88d0bf3` and present at
the baseline HEAD. It is never packaged into the application.

Differential tests use 80 deterministic batches of 80 results (6,400 input items),
including canonical-ID conflicts, traditional/simplified Chinese, full-width forms,
space/punctuation variants, blank/null metadata, wildcard years/types, aliases,
query annotations, artwork and distinct source provenance. Independently cloned
inputs produce identical full result snapshots under old and indexed policies.
Explicit tests also check representative object identity, first-match wildcard
behavior, empty titles and rebuilding indexes after metadata changes.

The coordinator regression compares the indexed comparator with a linear lookup
across 80 source entries, duplicate source keys, tied results, unknown sources,
reset and a new session. It checks exact object order rather than `Vod.equals`,
which can regard results from different sources with the same ID as equal.

## Measured work, not elapsed speed

An instrumented script converter counts normalization calls while the old and new
policies merge increasing sets of distinct anonymous titles:

| Input results | Old normalization calls | Indexed normalization calls |
| ---: | ---: | ---: |
| 10 | 90 | 10 |
| 100 | 9,900 | 100 |
| 1,000 | 999,000 | 1,000 |

The old scan compares every earlier title, normalizing both titles each time. The
new path normalizes each distinct title once and finds an empty bucket directly.
These deterministic checks demonstrate the removed work, not Android wall-time,
CPU-time, allocation or RSS improvements. No device was accessed.

Expected lookup work is linear for distinct title keys or canonical IDs, plus
client merge costs. Worst-case compatibility scans remain quadratic inside a large
title bucket with mutually incompatible metadata. Provenance accumulation may also
have its own cost. The indexes add temporary maps proportional to the current input
and its distinct strings. Small batches may therefore allocate more than before;
this tradeoff needs profiling rather than an unconditional speedup claim.

## Validation and next slice

Local validation passed: **18 search-core tests** and **1,510 mobile/leanback app
tests**, with two app tests skipped. Generic-library boundary checks and
leanback-lite ARMv7 compilation passed. Scoped whitespace checks passed.

```sh
./gradlew --offline --max-workers=2 :libraries:search-core:check \
  :app:testKeyLogic :app:compileLeanback_liteArmeabi_v7aVerificationJavaWithJavac \
  --console=plain
```

No device was accessed and no APK installed.

Next: prepare relevance scores and normalized query/title values once per sort,
while preserving persisted user-rank priority and stable ties in browse/collection
callers. Then compare elapsed time and allocations for complete merge/ranking
workloads, including duplicate-heavy and ambiguous-title cases. Native conversion
remains deferred until optimized managed workloads demonstrate a remaining CPU
bottleneck. Earlier casting Android/native-media release gates remain open.

Follow-up completed: see [relevance preparation and measured results](search-relevance-measurements.md). The validation counts above describe the earlier slice.
