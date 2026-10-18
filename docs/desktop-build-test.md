# Desktop Version — Build & Test Guide

This document covers how to build, run, and test the Linux desktop client.

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | 17+ | Must include AWT/Swing (`libawt_xawt.so`) |
| Gradle | Wrapper (bundled) | `./gradlew` in project root |
| OS | Linux x86-64 or arm64 | GUI requires `DISPLAY` or `WAYLAND_DISPLAY` |
| secret-tool | `libsecret-tools` | Required for cloud login persistence (optional for CLI) |

### Install Java 17 on Debian/Ubuntu

```bash
sudo apt-get install openjdk-17-jre
```

### Install secret-tool (cloud login persistence)

```bash
sudo apt-get install libsecret-tools
```

## Quick Start

The project-root launcher handles Java detection, installation, Gradle build, and private runtime snapshot:

```bash
./scripts/desktop/run-tvbox-desktop.sh
```

This installs dependencies if missing, runs `:desktop:installDist`, copies the distribution to a private temp directory, and launches the GUI.

## Build Commands

### Compile only

```bash
./gradlew :desktop:compileKotlin
```

### Compile and run tests

```bash
./gradlew :desktop:test
```

### Build installable distribution

```bash
./gradlew :desktop:installDist
```

This produces the distribution under `desktop/build/install/tvbox-desktop/`:

```
desktop/build/install/tvbox-desktop/
├── bin/
│   └── tvbox-desktop          # Launch script
└── lib/
    ├── tvbox-desktop-*.jar    # Application JARs
    └── ...                    # Dependency JARs
```

### Run directly (without install)

```bash
./gradlew :desktop:run
```

### Run with arguments

```bash
./gradlew :desktop:run --args='--smoke'
```

## Run the Installed Distribution

After `installDist`, launch directly:

```bash
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop
```

With arguments:

```bash
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop --inspect-config 'http://www.饭太硬.cc/tv'
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop --test-live-source 'http://www.饭太硬.cc/tv'
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop --test-js-source 'http://www.饭太硬.cc/tv' '英雄联盟'
./desktop/build/install/tvbox-desktop/bin/tvbox-desktop --test-cloud-login
```

## Desktop appearance

Settings → Appearance offers **System**, **Light**, and **Dark**. New profiles use
System; existing explicit Light/Dark choices are preserved. System reads the
[XDG Settings portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Settings.html)
color-scheme preference, falling back to GNOME color-scheme and GTK theme settings
when the portal is unavailable or reports no preference. If none are available,
it uses light appearance. `gdbus` and `gsettings` are optional detection helpers.

The desktop checks for changes in the background every three seconds and updates
open windows and dialogs without restarting playback. Controls use a consistent
Swing palette rather than native GTK painting, so sliders, spinners, file choosers,
password fields, menus, disabled controls, and custom cards follow the same theme.
Text input, selection, and caret position survive an appearance change.

Offline widget rendering and live-refresh regression checks:

```bash
./gradlew-safe :desktop:test --tests 'com.wuming.tv.desktop.ui.*'
```

These tests render both palettes; they do not constitute a live Linux desktop test.

## CLI Diagnostic Commands

These commands run headless (no GUI required):

| Command | Purpose |
|---|---|
| `--smoke` | Run headless architecture smoke check |
| `--inspect-config URL` | Download, decode, and inspect a TVBox configuration |
| `--test-live-source URL` | Probe live playlist streams for reachability |
| `--test-js-source URL QUERY` | Test a direct-JavaScript source with a search query |
| `--test-cloud-login` | Test cloud QR login flow (Aliyun, Baidu, Quark, UC) |

### JVM Spider Commands

```bash
# Test a conventional JVM CatVod JAR
./gradlew :desktop:run --args='--test-jvm-spider CONFIG_URL QUERY SOURCE_KEY SHA256'

# Fingerprint a spider artifact without executing it
./gradlew :desktop:run --args='--fingerprint-spider-artifact CONFIG_URL SOURCE_KEY'

# Probe a guarded (DEX/native) spider
./gradlew :desktop:run --args='--probe-guarded-spider ARTIFACT_URL SHA256 armeabi-v7a'

# Prepare a guarded spider for reuse
./gradlew :desktop:run --args='--prepare-guarded-spider ARTIFACT_URL SHA256 arm64-8a'

# Test a guarded spider end-to-end
./gradlew :desktop:run --args='--test-guarded-spider CONFIG_URL QUERY SOURCE_KEY SHA256 armeabi-v7a [LOCAL_ARTIFACT]'

# Diagnose a guarded spider
./gradlew :desktop:run --args='--diagnose-guarded-spider CONFIG_URL QUERY SOURCE_KEY SHA256 armeabi-v7a [LOCAL_ARTIFACT]'
```

## Test Commands

### Run all desktop tests

```bash
./gradlew :desktop:test
```

### Run a specific test class

```bash
./gradlew :desktop:test --tests 'com.wuming.tv.desktop.catalog.DesktopCompanionClientTest'
```

### Run all library tests (including clock-core)

```bash
./gradlew :libraries:clock-core:test
./gradlew :libraries:catalog-core:test
./gradlew :libraries:live-core:test
```

### Run all tests

```bash
./gradlew test
```

## Build Verification Tasks

The desktop build includes automatic boundary verification on `check`:

```bash
./gradlew :desktop:check
```

This runs:
- `verifyDesktopBoundaries` — ensures desktop does not depend on Android modules
- `verifyCodeLifecycleIsolation` — enforces code lifecycle boundaries
- `verifyGenericLibraryBoundaries` — ensures generic libraries have no Android dependencies

## Architecture

The desktop client follows a **ports-and-adapters** pattern:

```
┌──────────────────────────────────────────┐
│         libraries/*-core/                 │
│  (Platform-neutral contracts & models)   │
│  clock-core, catalog-core, live-core,    │
│  epg-core, media-core, playback-core,    │
│  search-core, source-core,               │
│  user-library-core, cloud-core, etc.     │
└──────────┬───────────────────┬───────────┘
           │                   │
    ┌──────▼──────┐    ┌───────▼──────────┐
    │     app/     │    │    desktop/      │
    │ (Android)    │    │  (Kotlin/JVM)    │
    │ - Media3     │    │ - External player│
    │ - Room       │    │ - OkHttp         │
    │ - Activities │    │ - Swing UI       │
    │ - AndroidX   │    │ - File storage   │
    └──────────────┘    └──────────────────┘
```

Key rules:
- `desktop/` depends **only** on `libraries/*-core/`, never on `app/`
- `libraries/*-core/` are pure JVM — no Android dependencies
- `app/` consumes libraries through Gradle `implementation project(':libraries:...')`

## Adding a New Feature to Desktop

Follow the abstraction pattern:

1. **Identify platform-neutral logic** in `app/` (models, interfaces, business rules)
2. **Create or extend a library** under `libraries/{feature}-core/` with `com.wuming.tv.core.{feature}.*`
3. **Define ports** (interfaces) that both platforms will implement
4. **Implement Android adapter** in `app/` using Room, Media3, Context, etc.
5. **Implement desktop adapter** in `desktop/` using OkHttp, file I/O, external players, etc.
6. **Write JVM tests** for the library module
7. **Register the library** in:
   - `settings.gradle` — `include ':libraries:{feature}-core'`
   - `build.gradle` — add to `genericLibraryPackages` map
   - `app/build.gradle` — `implementation project(':libraries:{feature}-core')`
   - `desktop/build.gradle` — `implementation project(':libraries:{feature}-core')`

See the `clock-core` extraction as a reference example.

## Logging

Persistent redacted traces are written under:

```bash
./scripts/desktop/run-tvbox-desktop.sh --log-directory
```

Logs include configuration fingerprints, cache decisions, catalogue counts, and playback decisions. Queries, episode IDs, and playback URLs are represented only by lengths and short SHA-256 tags.

## Troubleshooting

| Problem | Solution |
|---|---|
| `no graphical session was detected` | Run from a desktop terminal, or use `--smoke` for headless mode |
| `desktop launcher was not produced` | Run `./gradlew :desktop:installDist` first |
| `secret-tool is still unavailable` | Install `libsecret-tools`; cloud login will be memory-only |
| `Java AWT/Swing is unavailable` | Install `openjdk-17-jre` with X11 support |
| Build fails on boundary check | Ensure no Android imports in `libraries/` or `desktop/` |
## Library privacy and live-TV continuity (APP-005–APP-007)

The catalogue's **Private viewing** switch suppresses progress and recommendation
writes for the current session; turning it off starts recording with the next
session. History exposes delete-selected and clear-all actions. Both remove the
corresponding recommendation signals, retain explicit favorites, and invalidate
active session writes so closing a player cannot recreate deleted records.

Recommendation watch time uses bounded, progressing player observations. Startup,
pauses, buffering, seek jumps and disconnected feedback gaps do not count. A player
without usable feedback contributes no assumed watch duration.

Live TV supports source-scoped favorites and remembers the selected channel/route.
Reloading a playlist restores selection without autoplay. Channel identities use
`tvg-id` where available, otherwise name/tvg-name; duplicate identifiers are
ambiguous and resolve to the first matching entry. Reordering is safe; renaming a
channel without a stable ID may require selecting it again. Removed or changed route URLs fall
back to the first route. The persistence port stores hashes, not signed media URLs.
