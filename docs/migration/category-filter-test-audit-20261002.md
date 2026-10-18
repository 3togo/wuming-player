# Category filter regression test audit — 2026-10-02

Scope: the recent missing-thumbnails incident, including filter defaults, category
requests, configuration decoding, and guarded provider loading. This is not a
whole-application coverage claim.

## Findings

The original filter test expected a missing `init` to read as an empty string.
That expectation encoded the bug: providers distinguish an absent selection from
an explicit empty selection, which may mean All. More tests using the same wrong
expectation would not have prevented this incident.

`FilterValueContractTest` now covers omitted and explicit-null defaults, explicit
empty All, nonempty defaults, independent copies, activation, and JSON round trips.
Reintroducing null-to-empty normalization fails these tests.

This audit added two `FilterCacheTest` cases for category isolation, default
preservation through cache/copy, independent activation, unknown categories, and
clearing/replacing a source. It also added a `SiteViewModelTest` case verifying the
actual provider gateway receives omitted keys, explicit empty selections, nonempty
selections, and a subsequent empty request without stale selections. This test
does not construct a request from filters; it verifies forwarding only.

Configuration decoder tests cover leading comments, comment braces, quoted braces,
encoded envelopes, BOMs, malformed/non-object input, and trailing hosted content.
Guarded loader JVM tests cover unknown/missing payload rejection. Successful Android
DEX loading is covered by the opt-in device test, not by these JVM tests.

## Gaps identified in the initial audit

- Neither fragment's private `getExtend()` is directly exercised by a JVM test.
  Thus a future caller change that inserts empty defaults independently of the
  getter could pass the model/cache/forwarding tests. A deterministic Android
  fixture should assert both phone and TV initial request maps.
- No automated scenario in this audit switches through several category tabs and
  verifies provider results and rendered posters. The prior device verification
  is useful evidence but is not a repeatable synthetic regression test.
- Parcel instrumentation checks missing defaults, but lacks a paired explicit-empty
  default round trip. JVM JSON tests cannot establish Android Parcel behavior.
- Offline tests cannot guarantee hosted providers remain available or compatible.

These gaps prevented declaring the entire incident path covered by automated tests
at the time of the initial audit. The follow-up below addresses the three actionable
regression gaps; provider availability remains an external dependency.

## Follow-up: Android regression fixtures

Added `CategoryFilterFixture`, executed by both
`MobileCategoryFilterInstrumentedTest` and `LeanbackCategoryFilterInstrumentedTest`:

- Directly invokes each production folder fragment's private `getExtend()` against
  omitted, explicit-null, empty All, and nonempty defaults. Reflection is confined
  to tests; production visibility and behavior are unchanged.
- Clicks the real category adapter controls for movie → TV → variety → All → selected
  → TV, through real content fragments, injected `SiteViewModel` gateways, adapters/presenters, and image
  loading. Each transition uses the production request builder. Expected maps are
  independently specified, not derived from the builder's result.
- Checks that provider calls retain the expected defaults, visible titles match the
  new category, and displayed poster pixels match unique locally generated PNGs.
  Merely having an ImageView or placeholder cannot satisfy the assertion.
- Uses bounded waits and single-page provider results, and removes only its own
  temporary poster directory. It clears the verification process's filter cache.

`StateRestorationInstrumentedTest` now checks explicit empty All and nonempty defaults,
activation, and independent restored values through real Android Parcel marshalling.
The existing missing-default Parcel test remains paired with this new test.

Both verification application and runner APKs compiled. On explicitly authorized
ARM64 device `192.0.2.10:43263`, each flavor passed the same four selected tests
in one final run: request builder, category rendering, missing-default Parcel, and
explicit-empty/nonempty Parcel. Mobile: 4/4 in 4.704 s. Leanback: 4/4 in 5.873 s.
No tests were skipped in either selected device run. Saved power setting `15` was
restored after testing; app data was preserved.

The first two mobile attempts each passed 3/4 tests. Rendering failed only on the
long selected-category title because production `PosterTitle.wrapAtSpace` inserts a
newline. Diagnostics established that the poster and provider arguments were correct.
The assertion now normalizes whitespace while retaining the exact title words.
Poster pixel checks allow at most two channel levels of renderer rounding.
Raw attempt and final runner output is retained outside the repository at
`/tmp/tv-category-regression-20261002/`.

The fixture mounts production category adapters, invokes their bound view click handlers,
and replaces real content fragments in an isolated host. It covers the regression
from category selection and filter defaults to provider arguments and visible content.
It does not exercise the full Home/VOD pager shell or physical touch/D-pad navigation,
actual-TV hardware behavior, or live host availability.
Both flavors ran on the same authorized Android device.

## Validation and CI

`./gradlew-safe :app:testKeyLogic` passed. Current XML reports contain:

| Variant | Reported tests | Failures/errors | Skipped |
| --- | ---: | ---: | ---: |
| Mobile ARM64 verification | 976 | 0 | 1 |
| Leanback ARM64 verification | 983 | 0 | 1 |

Each flavor includes 6 filter contract tests, 2 cache tests, and 16 ViewModel tests.
These counts describe this run, not a coverage percentage. The test README's older
counts and selected-logic coverage numbers must not be read as current whole-app
coverage evidence.

`testKeyLogic` runs both complete app JVM suites. CI invokes
`unitAcceptanceBaseline` and `testKeyLogicCoverage`; the newly added shared tests
are discovered without an explicit per-class allowlist. Instrumentation compilation
in CI does not prove execution on a device. This audit ran no new device tests.
