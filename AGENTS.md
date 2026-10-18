# Project preferences

## Java retention and language migration

- Before any Java/Kotlin/Rust migration, read [docs/language-boundaries.md](docs/language-boundaries.md) and [docs/java-retention.json](docs/java-retention.json). `language-required` facades stay Java under their recorded contracts; `compatibility-hold` files stay Java during routine migration until the recorded evidence permits release. Neither category permits new business logic in the facade.
- Follow `JAVA-RETENTION:` source/directory markers. Do not remove a marker, registry rule, or behavioral test merely to finish conversion. Change the contract and its evidence together when deliberately releasing a hold.
- Put `language-required` files in their owning module's `src/<source-set>/java-required/` directory, preserving JVM packages and registering the directory with Gradle. Only registered Java-required facades belong there; compatibility holds stay separate. Keep implementation in Kotlin outside that folder.
- Unlisted Java is **review-before-migration**, not proven Java-only and not automatically safe to convert. Prefer Kotlin for new JVM behavior; use Rust only for a justified native boundary with JNI linkage and behavior parity checks. Frozen vendor code follows [docs/code-lifecycle.md](docs/code-lifecycle.md).
- Run `python3 scripts/check-java-retention.py --inventory` and `./gradlew-safe verifyJavaRetention` after migration changes, plus affected behavioral/API tests. Historical migration reports are not the current retention register.

## Desktop/mobile feature sync

- All reusable non-GUI application behavior belongs in shared `libraries/*-core` modules and must be consumed by mobile, leanback, and desktop. This applies to existing behavior during refactoring as well as new features, not only features initially requested for desktop. Keep platform-neutral algorithms, parsing, policy, caches, request budgets, and workflows out of platform adapters; the adapters supply UI, framework lifecycle, player, persistence, and transport configuration ports.
- Before adding or refactoring a feature that should be shared with the Linux desktop client, read [docs/desktop-sync-architecture.md](docs/desktop-sync-architecture.md). Platform-neutral logic must live in a `libraries/*-core` module (`com.wuming.tv.core.*`); `app/` and `desktop/` only provide ports. The `verifyGenericLibraryBoundaries`, `verifyDesktopBoundaries`, and `verifyCodeLifecycleIsolation` Gradle tasks enforce this.

## Quark smart playback investigation

- Before changing `夸克智` / `夸父智` quality selection or resuming the middle-resolution investigation, read [docs/quark-smart-quality-lessons.md](docs/quark-smart-quality-lessons.md). It records the measured API failures, temporary-file cleanup trap, and the user's accepted low-resolution fallback.

## UC playback and cache cleanup

- Before changing UC/`优汐` playback, cloud-save quota handling, `TVBoxCache` cleanup, or recycle-bin cleanup, read [docs/uc-playback-cache-lessons.md](docs/uc-playback-cache-lessons.md). Preserve its quota-only direct-download fallback and cleanup safety boundaries.

## Ali playback and saved originals

- Before changing Ali original/smart routing, token refresh, or disposable-copy cleanup, read [docs/ali-playback-cache-lessons.md](docs/ali-playback-cache-lessons.md). Preserve separate route contracts, share-token header boundaries, and account-scoped tracked-file cleanup.

## Daily maintenance

- The maintenance GUI is `./maintain.sh` (root launcher that activates `~/venv` and runs `scripts/maintain.py`); read [docs/maintenance.md](docs/maintenance.md) before changing the build/post/depot pipeline it wraps.
- Keep maintenance scripts under `scripts/` (subfolders `build/`, `depot/`, `device/`, `desktop/`) or `tools/`; do not add new loose scripts, screenshots, or logs to the repository root. `build.sh`, Gradle wrapper files, and the root runtime configs (`updates.json`, `github_proxies.json`, `wuming-config.example.json`) stay at root by design.

## App names

- The normal application must be named `無名`.
- A separately installed verification/testing application must be named `無名(testing)`.
- Keep these launcher labels distinct in every supported locale and in both mobile and leanback variants. Never ship the normal and verification packages with indistinguishable names.
- The instrumentation APK is a separate test runner, not another user-facing app. Label it `無名(test runner)` so it is also recognizable in Android settings.
- When device testing needs an app APK and a test-runner APK, explain the two packages before installing them.

## Device access

- Read `docs/android-instrumentation-lessons.md` before instrumentation.
- Use only explicitly authorized devices and specify the exact serial for every ADB command.
- Save and restore temporary wake, lock and logging settings. Never remove a secure lock credential.

## Leanback UI

- Every actionable TV control must be reachable with the D-pad, show visible focus, and work with OK/Back. Forms must allow focus to leave text fields.
- Keep phone behavior unchanged when making TV-specific navigation fixes; prefer leanback resource overrides.
- Run `python3 scripts/check-leanback-remote.py` after changes to TV layouts, focus behavior, or application labels.

## Localized typography

- Treat English and Chinese text sizes separately. In the mobile landscape navigation rail, use smaller English labels to fit longer words while preserving the original Chinese label size in both Simplified and Traditional Chinese.
