# Local package integration: implementation result

Date: 2026-09-28. User requested a detailed plan and implementation following the collection review. The four deliverables in [the plan](2026-09-28-local-package-implementation-plan.md) are implemented. See [the user guide](../source-packages.md) for import/update/rollback instructions.

## Scope and evidence

Reference: `/home/developer/git/TVBoxOSC/推荐接口/本地包合集/本地包合集260728`, a supplied filesystem collection, not a Git revision. [The original inventory](2026-09-28-local-package-inventory.json) records individual file hashes. [The new executable inspector report](2026-09-28-local-package-validation.json) records configuration, diagnostics and artifact SHA-256 values. No reference repository was updated; no provider implementation was copied into TV or added to its build graph. Only synthetic fixture providers were executed.

The collection motivates preserving package trees and provider identities instead of flattening hundreds of sites into a single config. It also exposes real configuration dialects: compound extension references, integrity suffixes, percent-containing filenames, image/Base64/CBC envelopes, DEX archives with misleading extensions, and opaque guarded binaries. Integration now handles the configuration/storage boundaries while keeping runtime support explicit.

## Delivered behavior

1. `source-core` owns bounded ZIP inspection, configuration discovery/decryption, dependency and checksum diagnostics, content fingerprints, source identity and install/rollback policy. Origins remain stored without becoming duplicate subscriptions. Invalid paths, duplicate ZIP entries, invalid/duplicate site keys, missing local dependencies and checksum failures block installation.
2. Android and desktop filesystem adapters store immutable revisions and publish the selected index atomically. Failed publication leaves the previous selection active. Updates preserve the package identity; rollback restores both content and enabled sources. Existing revisions remain accessible to in-flight resource URLs. Automatic pruning is deferred.
3. Android provides native ZIP import and rollback in Depot Sources, including a leanback footer and existing D-pad dialog helpers. The paired browser provides the same inspection/selection/install workflow behind the existing pairing gate. Desktop accepts directories or ZIPs in Configuration and exposes an offline inspection CLI. Installation is separate from activation.
4. Source identities are scoped by package inside TV while original keys are passed to provider runtimes. Android JS/Python caches include configuration inputs. Desktop package JS storage survives loopback-port changes and switching between installed packages. Existing provider-owned native/account storage is not newly sandboxed by this feature.
5. Shared resource resolution preserves URI queries, fragments, checksum suffixes and compound arguments without replacing arbitrary embedded code. Desktop serves registered local roots through confined random loopback capabilities, allowing the existing HTTP JS runtime to load local modules/resources. Plain live playlists retain their existing decoder behavior.
6. Imports offer a curated enabled-source selection. A same-name update restores the previous selection when the proposed name matches; otherwise the first five are defaults for review. HTTP-only VOD selections omit unrelated root-spider preloading. Package parser/live definitions remain and may have their own runtime dependencies.

## Platform coverage for observed contracts

| Contract | Android | Linux desktop | Evidence and remaining boundary |
| --- | --- | --- | --- |
| Optional versioned package subscription | Covered | Covered | Native/browser or Swing import, immutable versions, atomic index, rollback fixtures |
| JSON, image/Base64 and observed `2423` CBC envelope | Covered | Covered | Shared decoder; synthetic CBC fixture and actual offline 雷蒙 entry decoding |
| Relative/compound resources and checksum suffixes | Covered for inspected dialect | Covered for inspected dialect | Shared resolver and dependency tests; arbitrary provider-generated nested paths are not an exhaustive conformance claim |
| Package-scoped host keys with raw provider keys | Covered | Covered | Android loader and JS/JVM runtime fixtures; one active configuration remains |
| Local JS transport | Existing Android runtime, bundle-dependent | Covered host transport; provider behavior remains bundle-dependent | Synthetic JS home result and extension read through confined loopback adapter |
| CatVod DEX and Android APIs | Bundle-dependent/external | Partial, artifact/ABI dependent | Import does not certify methods, host globals, native loading or playback |
| ftyguard | Bundle-dependent/external | Partial, existing adapter | Shape recognition is not exact-artifact execution validation |
| FishGuard, wexguard, observed woshinidie payload | Bundle-dependent/external | Not covered by this implementation | Unknown guarded payloads excluded from plain-DEX classification |
| Python providers | Not covered in current disabled build | Not covered | Diagnostics; Python no longer misrouted into desktop CSP activation |
| Cross-package concurrent search/parser/proxy contexts | Not covered | Not covered | Existing selected-package search retained; federation requires a separate execution-context contract |

## Collection results

The inspector finds ten entry configurations. Eight have zero structural errors: 嗷呜 (85 sites), 小米 (38), 摸鱼 (85), 无意云 (126), 王二小 (87), 肥猫 (39), 虎斑 (66), 饭太硬 (49). Runtime warnings remain on every package.

雷蒙/raw/source.txt decodes to 130 sites but has 69 unresolved local-resource diagnostics. 雷蒙影视WP has 130 sites and 36 such diagnostics. Both are blocked from installation until the missing package tree is repaired. The report counts diagnostic references, not necessarily distinct physical files. Zero structural errors does not establish service availability or playback success.

## Validation

- Shared source-core: 70 tests passed, including archive bounds/traversal, checksums, dependency resolution, identity, publication failure, tampering, update selection, rollback and CBC decoding.
- JS runtime: 9 tests; portable JVM runtime: 3; guarded runtime: 62. All passed, including raw provider-key propagation where applicable.
- Focused desktop suites: 29 tests passed across local-package execution/storage, configuration loading, artifact classification, startup and JS persistence.
- Focused Android verification suites: 21 tests passed across package endpoints, decoder regression, pairing protection, loader identity and built-in site compatibility.
- All six Android debug flavor/ABI Java/Kotlin compilations passed: mobile, leanback and leanback_lite on arm64_v8a and armeabi_v7a.
- `verifyGenericLibraryBoundaries`, `verifyDesktopBoundaries`, `verifyCodeLifecycleIsolation`, JavaScript syntax checking and `git diff --check` passed.
- `check-leanback-remote.py` passed: 125 effective layouts, 296 explicit focus targets, 26 action contracts, 155 listener checks and three app-label locales. A hardcoded zero layout dimension caught during validation was replaced with the existing dimension resource before the final successful run.
- Final offline CLI inspection of the supplied collection passed and generated the linked validation JSON. No collection providers, live endpoints or Android devices were tested. No APK was installed or deployed.

## Remaining work

Real-device D-pad/file-picker behavior and exact-provider playback remain unverified. Repairing absent upstream files, implementing guard emulators, cross-package federation and changes to cloud accounts/cleanup are deliberately deferred; these need separate evidence and runtime contracts. Old immutable revisions currently require deliberate storage management because there is no automatic retention policy. Unrelated pre-existing workspace edits were preserved.

Workflow: [tv-reference-abstraction skill](/home/developer/.codex/skills/tv-reference-abstraction/SKILL.md). A future invocation can be: “Use tv-reference-abstraction to review the updated local package collection, compare its inspector report with the 2026-09-28 baseline, and implement only newly evidenced compatibility gaps.”
