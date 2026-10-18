# Local source packages: implementation plan

This implements the 2026-09-28 collection review. Package inspection and import are offline operations; importing does not execute third-party providers or certify their services.

Status: all four deliverables implemented and locally verified. See [implementation results and remaining limits](2026-09-28-local-package-implementation.md). Android integration includes native ZIP import in addition to the paired browser.

## Deliverable 1: shared package policy

In `source-core`, add bounded ZIP inspection, entry-point discovery, artifact/content hashes, dependency diagnostics, source identity encoding and manifest/index models. Recognize JSON and the existing image/Base64/CBC dialect. Treat missing executable/config dependencies and hash mismatches as blocking; distinguish runtime warnings from structural errors. Preserve provider-specific extension strings and original site keys.

Acceptance: malformed/traversing/oversized archives are rejected, origin.json is not duplicated as another subscription, unknown guards and Python are reported, content updates retain stable package identity, and importing never loads a provider.

## Deliverable 2: installation and rollback

A shared library service owns inspection/install/rollback decisions through storage ports. Android and desktop supply private filesystem adapters with immutable version directories and atomically replaced indexes. Preview before installation; install one or more selected entries; unchanged content is idempotent. Store the original package tree, select an installed version independently of the current app configuration, and retain the previous version for rollback. No provider archives enter the APK or build graph.

Acceptance: failed validation/persistence leaves the active version unchanged; restart reconstructs installed packages; rollback restores the previous content; package identity is stable across versions.

## Deliverable 3: app integration

Android: extend the existing paired browser file-management surface with a package ZIP inspector/install/rollback view, serve installed package resources through a dedicated route, and include installed package entries in the depot screen. Existing ordinary ZIP uploads stay supported.

Desktop: add import/inspect/install and rollback controls to configuration settings, list installed packages alongside existing depot sources, and serve only registered package/local-config roots through a loopback resource adapter. Reuse the existing HTTP JS module/extension paths rather than opening unrestricted file access in GraalJS.

Acceptance: a user can import, select and load a package on both platforms, review structural/runtime diagnostics before installation, and roll back without editing JSON. No scripts run during preview/install. Imported source lookup uses package-scoped host keys while provider-facing keys retain their original value.

## Deliverable 4: compatibility and source selection

Resolve local files, HTTP-relative resources, integrity suffixes and compound resource arguments through shared policy. Preserve query strings, fragments, already encoded URL escapes and literal percent filenames. Explicitly exclude Python from the desktop CSP path and report the disabled Android Python build. Reject unknown guarded payloads from the plain-DEX classification.

Offer source selection during import so a package can start with a small enabled set. Keep the existing selected-package search/ranking behavior. Concurrent federation across independently configured packages is deliberately not introduced: it needs a separate parser/proxy execution-context contract before several packages can safely run together. Likewise do not infer cloud share links from opaque provider playback IDs or change built-in cloud account/cleanup semantics.

## Validation

Use synthetic offline fixtures for archive bounds, missing references, checksums, identity, URI grammar, install/update/rollback and local JS. Inspect the user's actual collection without executing binaries. Run relevant source-core/desktop regression suites, architecture guards, Android affected flavor compilation and the leanback check if UI integration changes. Record failures accurately and preserve existing working-tree changes. No Android device testing is authorized for this new task.

## Deferred provider work

FishGuard/wexguard emulation, opaque payload reconstruction, missing upstream files, live endpoint/playback audits and new cloud-provider behavior remain separate evidence-driven tasks. Neither offline import nor conversion establishes playback compatibility.
