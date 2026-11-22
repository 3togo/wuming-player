# Keep each source’s search cards visible in All

Searching for `愛的迫降` could show a card under a source filter while hiding it in
All. The fast-search adapters applied title/canonical-identity merging across
sources, keeping only one representative card. Remote input only delays sorting;
results still enter the adapter during that interval.

Display deduplication now uses source key plus video ID. Matching titles from
different sources, and different video IDs from the same source, remain separate.
Repeated deliveries of one source/video entry retain their original arrival order.
TV, phone, and desktop consume the same rule in `SearchResultEntries`. Existing
query-variant and media-browser title-merging behavior remains unchanged.

## Validation

On Ubuntu 26.10 (Stonking Stingray), x86_64, Gradle 9.7.1, Kotlin 2.4.10 and the
JDK 17 toolchain, all 89 selected test executions passed with no failures, errors
or skips: 79 search-core tests, plus five Android unit tests each for leanback and
mobile. The Android selections were `SearchResultMergerTest` and
`FastSearchCoordinatorTest`. The new cases cover matching titles across sources,
distinct IDs within a source, repeated batches, canonical identity, and agreement
between All and source-filtered entries.

Desktop Kotlin compilation and the generic-library, desktop and lifecycle boundary
checks passed. The existing TV navigation test now includes same-title cards from
different sources and multiple same-title entries within one source. Its Android
instrumentation Kotlin compilation passed; the test itself was not executed.

The tested candidate was an isolated export of
`2377994c8c6ad567fd85196d5f100dc4151efe27` with only the nine source/test files listed
in the [validation receipt](search-all-source-entries-20261122.json) overlaid.
The receipt records their SHA-256 hashes. Unrelated provider edits were excluded.

Initial validation stopped at offline plugin resolution, then at the configured
production certificate check. The successful run used normal repository resolution
and the project's existing ephemeral-signing option for verification tasks. No
signing keys or signing configuration were changed. No release artifact was built.

Nothing was installed, restarted, or controlled on the TV box. Live navigation,
focus behavior and playback coexistence remain unverified for this candidate.
