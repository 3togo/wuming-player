# Documentation index

This directory holds reliable feature, configuration, and protocol documentation. The
README is an entry point only; implementation details belong in their topic files, not
duplicated across multiple places.

Engineering notes describe a specific point in time and do not necessarily represent
currently shipped features. When a note conflicts with a topic file or verified code
behavior, update the topic file and treat verified code as authoritative.

**Naming convention:** all files use kebab-case (lowercase with hyphens).

## Improvement reviews

| File | Content |
| --- | --- |
| [reviews/app-improvements-2026-09-25.md](reviews/app-improvements-2026-09-25.md) | Prioritized Android/desktop improvements, stable APP-001–APP-011 IDs, source evidence, acceptance criteria and validation limits |

## Architecture and lifecycle

| File | Content |
| --- | --- |
| [architecture-boundaries.md](architecture-boundaries.md) | Platform architecture: pure-JVM core, Android adapters, desktop, and dependency directions |
| [code-lifecycle.md](code-lifecycle.md) | Code lifecycle and isolation: production, compatibility, incubating, and archived code boundaries |
| [language-boundaries.md](language-boundaries.md) | Current Java retention decisions, Kotlin/Rust migration criteria, enforcement and review findings |
| [desktop-sync-architecture.md](desktop-sync-architecture.md) | Desktop/mobile feature sync architecture and shared-library rules |
| [platform-capabilities.md](platform-capabilities.md) | Platform capability matrix and release-candidate runtime evidence checklist |

## Usage and configuration

| File | Content |
| --- | --- |
| [config.md](config.md) | Vod/Live configuration JSON structure and field definitions |
| [spider.md](spider.md) | Spider API specification: methods, parameters, and return formats |
| [live.md](live.md) | M3U, TXT, and JSON live source formats |
| [local.md](local.md) | Local HTTP API and remote control endpoints |
| [search.md](search.md) | Multi-language title search, strict mode, and maintenance |
| [cloud-login.md](cloud-login.md) | Cloud drive QR login, official app handoff, and MDrive trust boundary |
| [cloud-storage.md](cloud-storage.md) | Cloud cache management and trusted-delete adapter boundary |
| [opendrive.md](opendrive.md) | Built-in OpenDrive routing, provider interface, security boundaries |
| [opendrive-115-review.md](opendrive-115-review.md) | 115 protocol library review and Open Platform integration conditions |
| [opendrive-uri-standard.md](opendrive-uri-standard.md) | OpenDrive URI standard specification |
| [opendrive-investigation.md](opendrive-investigation.md) | OpenDrive investigation record |
| [catvod-opendrive-audit.md](catvod-opendrive-audit.md) | CatVod OpenDrive audit |
| [database-recovery.md](database-recovery.md) | Portable restore table policy, atomic backups and non-destructive database recovery |
| [build-user-config.md](build-user-config.md) | User build configuration and APK signing |
| [android-updates.md](android-updates.md) | Maintainer update publishing and signing |
| [ui-testing.md](ui-testing.md) | Page crawling, launch resilience, and UX consistency testing |
| [tv-remote-keys.md](tv-remote-keys.md) | TV remote key mapping and custom button assignments |
| [freebox-abstractions.md](freebox-abstractions.md) | FreeBox investigation and adopted abstractions |
| [remote-depot-sync.md](../PUBLIC_PREPARATION.md) | Remote recommended-source list: AES encryption, automatic merge, community maintenance |

## Casting and network

| File | Content |
| --- | --- |
| [projection-protocols.md](projection-protocols.md) | Currently supported and unsupported casting protocols |
| [dlna-pairing.md](dlna-pairing.md) | DLNA QR pairing flow and access enforcement |
| [cast-auto-next.md](cast-auto-next.md) | DLNA auto-next-episode lifecycle and device compatibility |
| [google-cast-decision.md](google-cast-decision.md) | Google Cast adoption decision (deferred) and reconsideration criteria |
| [implementation-summary.md](implementation-summary.md) | DLNA casting implementation summary |
| [network-security.md](network-security.md) | Network security boundary |
| [remote-access-security.md](remote-access-security.md) | Remote access verification and risk control |

Root-level [QUICK_START.md](../QUICK_START.md) is a quick DLNA casting guide for phone users.

## Cloud drive playback

| File | Content |
| --- | --- |
| [cloud-drive-completion-plan.md](cloud-drive-completion-plan.md) | Cloud-drive improvement batch: scope, acceptance, and completion record |
| [cloud-drive-offline-streaming-tests.md](cloud-drive-offline-streaming-tests.md) | Offline cloud-drive streaming test contracts and locations |
| [cloud-drive-production-diagnostics.md](cloud-drive-production-diagnostics.md) | Cloud diagnostics and cleanup outcomes |
| [cloud-drive-public-sample-candidates.md](cloud-drive-public-sample-candidates.md) | Public cloud video sample candidates for testing |
| [cloud-login-consolidation-design.md](cloud-login-consolidation-design.md) | Cloud login consolidation design (design only, not implemented) |
| [ali-playback-cache-lessons.md](ali-playback-cache-lessons.md) | Ali playback and cache lessons: routing, token refresh, cleanup |
| [uc-playback-cache-lessons.md](uc-playback-cache-lessons.md) | UC playback and cache lessons: quota-only fallback, cleanup safety |
| [quark-smart-quality-lessons.md](quark-smart-quality-lessons.md) | Quark smart quality investigation, low-resolution fallback decision |
| [hls-ad-filtering.md](hls-ad-filtering.md) | HLS ad filtering contracts and resolution/timestamp confirmation |

## Desktop

| File | Content |
| --- | --- |
| [desktop-build-test.md](desktop-build-test.md) | Desktop build, test, and debug commands |
| [desktop-companion-api.md](desktop-companion-api.md) | Linux desktop and Android TV pairing, search, detail, and playback protocol |
| [desktop-future-work.md](desktop-future-work.md) | Desktop feasibility background, companion architecture, and future conditions |
| [desktop-site-verification.md](desktop-site-verification.md) | Linux desktop site verification method and results |
| [remote-catalog-future-work.md](remote-catalog-future-work.md) | Remote catalog API future-work handoff (proposed, not implemented) |

## Engineering notes and lessons

| File | Content |
| --- | --- |
| [lesson-learned.md](lesson-learned.md) | Standing rules and historical refactor/UI regression lessons |
| [provider-protocol-evidence.md](provider-protocol-evidence.md) | Provider protocol evidence and implementation lessons (UC, Quark) |
| [optimization-report.md](optimization-report.md) | Performance improvement record and recommendations |
| [crash-memory-hardening.md](crash-memory-hardening.md) | Crash and memory hardening review: shared ownership conventions |
| [leanback-remote-audit.md](leanback-remote-audit.md) | Leanback remote audit: D-pad focus, dialog, and editor contracts |
| [android-instrumentation-lessons.md](android-instrumentation-lessons.md) | Android instrumentation lessons: Vivo background-freeze, ADB wake procedure |
| [toast-relocation-lessons.md](toast-relocation-lessons.md) | Toast relocation implementation experience |
| [hongguo-testing-notes.md](hongguo-testing-notes.md) | HongGuo on-device validation status (blocked, deferred) |
| [android-memory-plan.md](android-memory-plan.md) | Android memory ownership plan, device classes and remaining audit limits |
| [production-heap-profile.md](production-heap-profile.md) | Production heap findings behind the detail-cache and playback-service regressions |
| [android-refactor.md](android-refactor.md) | Android refactor notes |
| [flavor-sharing.md](flavor-sharing.md) | Mobile/leanback flavor source-set sharing rules |

## Test strategy

| File | Content |
| --- | --- |
| [test-strategy.md](test-strategy.md) | Mandatory offline acceptance, production smoke, deep smoke, and coverage limits |

## Optimization

| File | Content |
| --- | --- |
| [optimization/native-optimization-task.md](optimization/native-optimization-task.md) | Phased EPG, casting, search, range-playback, danmaku, and QuickJS work |
| [optimization/native-decision.md](optimization/native-decision.md) | Native code decision: C/C++/Rust selection |
| [optimization/android-playback-refactor.md](optimization/android-playback-refactor.md) | Android playback refactor |
| [optimization/cast-staging.md](optimization/cast-staging.md) | Cast staging implementation |
| [optimization/cast-staging-measurements.md](optimization/cast-staging-measurements.md) | Cast staging measurements |
| [optimization/cast-fair-scheduling.md](optimization/cast-fair-scheduling.md) | Cast fair scheduling |
| [optimization/cast-probes.md](optimization/cast-probes.md) | Cast probes |
| [optimization/cast-native-runtime.md](optimization/cast-native-runtime.md) | Cast native runtime |
| [optimization/android-runtime-validation.md](optimization/android-runtime-validation.md) | Android runtime validation |
| [optimization/search-indexing.md](optimization/search-indexing.md) | Search indexing optimization |
| [optimization/search-relevance-measurements.md](optimization/search-relevance-measurements.md) | Search relevance measurements |
| [optimization/range-progress.md](optimization/range-progress.md) | Range playback progress |
| [optimization/danmaku-buffer-measurements.md](optimization/danmaku-buffer-measurements.md) | Danmaku buffer measurements |
| [optimization/epg-streaming-measurements.md](optimization/epg-streaming-measurements.md) | EPG streaming measurements |
| [optimization/quickjs-conversion-ownership.md](optimization/quickjs-conversion-ownership.md) | QuickJS conversion ownership |

## Migration (Java to Kotlin)

| File | Content |
| --- | --- |
| [migration/java-to-kotlin-audit.md](migration/java-to-kotlin-audit.md) | Java to Kotlin migration audit |
| [migration/kotlin-opportunity-review-20260916.md](migration/kotlin-opportunity-review-20260916.md) | Kotlin opportunity review |
| [migration/recommended-conversions-20260916.md](migration/recommended-conversions-20260916.md) | Recommended conversions |
| [migration/targeted-conversions-20260916.md](migration/targeted-conversions-20260916.md) | Targeted conversions |
| [migration/remaining-kotlin-opportunities-20260916.md](migration/remaining-kotlin-opportunities-20260916.md) | Remaining Kotlin opportunities |
| [migration/helper-conversions-20260916.md](migration/helper-conversions-20260916.md) | Helper conversions |
| [migration/model-desktop-adapters-20260916.md](migration/model-desktop-adapters-20260916.md) | Model/desktop adapter conversions |
| [migration/persistence-casting-followup-20260916.md](migration/persistence-casting-followup-20260916.md) | Persistence/casting follow-up |
| [migration/native-provider-recovery-20260916.md](migration/native-provider-recovery-20260916.md) | Native provider recovery |
| [migration/aliso-loader-fix-20260916.md](migration/aliso-loader-fix-20260916.md) | Aliso loader fix |
| [migration/stabilization-20260916.md](migration/stabilization-20260916.md) | Stabilization |
| [migration/tv-stabilization-20260916.md](migration/tv-stabilization-20260916.md) | TV stabilization |
| [migration/device-smoke-20260916.md](migration/device-smoke-20260916.md) | Device smoke test |

## Reference reviews

| File | Content |
| --- | --- |
| [reference-reviews/2026-09-09.md](reference-reviews/2026-09-09.md) | Initial reference review outcomes and provider recovery |
| [reference-reviews/2026-09-09-all-references-dual-lens.md](reference-reviews/2026-09-09-all-references-dual-lens.md) | All-reference dual-lens review: Android/desktop protocol coverage |
| [reference-reviews/2026-09-09-catvod-npm-and-desktop-compatibility.md](reference-reviews/2026-09-09-catvod-npm-and-desktop-compatibility.md) | catvod npm follow-up: pinned artifact boundary and desktop fixes |
| [reference-reviews/2026-09-09-catvodopen.md](reference-reviews/2026-09-09-catvodopen.md) | CatVodOpen review |
| [reference-reviews/2026-09-09-run2.md](reference-reviews/2026-09-09-run2.md) | Second-round reference review run |
| [reference-reviews/2026-09-11-ali-download-ramp.md](reference-reviews/2026-09-11-ali-download-ramp.md) | Ali download ramp review |
| [native-python.md](native-python.md) | Bundled CPython JNI runtime, build workflow and compatibility limits |
| [source-packages.md](source-packages.md) | Local package import, source selection, updates and rollback |
| [reference-reviews/2026-09-28-local-package-implementation.md](reference-reviews/2026-09-28-local-package-implementation.md) | Collection integration plan, implementation and compatibility validation |

## Archive (point-in-time snapshots)

The [archive/](../PUBLIC_PREPARATION.md) subdirectory holds dated working records that are preserved for
historical context but are not actively maintained. They document specific investigation
sessions, device smoke tests, and maintenance snapshots.

| File | Content |
| --- | --- |
| [archive/maintenance-opportunities-20260916.md](../PUBLIC_PREPARATION.md) | Home recommendation delivery fix (2026-09-16) |
| [archive/project-review-followup-20260916.md](../PUBLIC_PREPARATION.md) | Project review follow-up: Quark, desktop, catalog, release validation (2026-09-16) |
| [archive/cloud-drive-smoke-7e34269d.md](../PUBLIC_PREPARATION.md) | Cloud-drive smoke checks on device 7e34269d |
| [archive/debug-dlna-cast-no-play.md](../PUBLIC_PREPARATION.md) | Debug session: DLNA cast connects but video does not play |

## Related documentation outside docs/

| File | Content |
| --- | --- |
| [../README.md](../README.md) | Project developer documentation (root) |
| [../AGENTS.md](../AGENTS.md) | AI agent project preferences and rules |
| [../QUICK_START.md](../QUICK_START.md) | DLNA casting quick start guide |
| [../app/src/test/README.md](../app/src/test/README.md) | Test layering, commands, and coverage |

- [SMOKE-002: real-provider playback and Leanback remote-input follow-up](reviews/extended-smoke-2026-09-25.md)

- [APP-012–APP-015 follow-up implementation and device evidence](reviews/followups-2026-09-25.md)

- [APP-016–APP-017: adaptive cache routing and playback failure evidence](reviews/adaptive-cache-2026-09-25.md)

- [APP-018–APP-021: deeper persistence and playback review](reviews/deep-review-2026-09-25.md)
