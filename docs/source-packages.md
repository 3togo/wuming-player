# Local source packages

Local packages are optional, versioned subscriptions. They keep their own source keys, provider binaries, parsers and resource trees. They are stored outside the APK; importing a package does not execute its providers or change the currently active configuration.

## Android: phone and TV

1. Open Settings → Depot Sources → **Import source package ZIP**.
2. Choose a ZIP containing a configuration and its resources. A ZIP can contain several package directories. Choose which package to inspect.
3. Read the dependency/runtime report. Missing local files, checksum mismatches and invalid source keys block installation. Runtime warnings are separate from file validity.
4. Select the VOD sources to enable. For a new package, the first five entries are initially selected; an update with the same proposed name retains its previous selection. Review the selection because a package may place configuration/index providers first.
5. Give the package a name and install it. Reusing exactly the same name updates that installed package.
6. Choose its `(local)` entry in Depot Sources and use the existing configuration dialog to activate it.

**Roll back source package** restores the previous installation/selection. Reload the package configuration to use it. The TV depot has a horizontal footer and the import dialogs use the shared D-pad list/form helpers.

The paired browser's Local Files page also provides ZIP inspection, source selection, installation and rollback. Installed subscriptions use `package://<id>` URLs, which can be entered into the existing configuration field. Browser endpoints are covered by the existing remote-pairing gate; local imports do not depend on a browser.

## Linux desktop

In Settings → Configuration, choose **Import package…** and select either a folder or a ZIP. A collection folder can contain several packages; choose the one to inspect. Review diagnostics, edit its name, select sources, then install. Press **Load locally** to activate it. Installed packages also appear beside the bundled depot sources.

**Roll back package** operates on the installed package URL in the configuration field. Press **Load locally** afterward. Ordinary local config files are also supported: relative resources and JavaScript are served through a private loopback adapter limited to the registered directory. This does not enable arbitrary JavaScript filesystem access.

A read-only command-line inspection is available:

```bash
./gradlew-safe :desktop:run --args='--inspect-packages /path/to/package-or-collection'
```

This reports dependencies, runtime warnings and SHA-256 artifact fingerprints. It does not download missing resources, run provider scripts, or install packages.

## Storage and identity

- Android: app-private `files/source-packages`.
- Desktop: `$XDG_DATA_HOME/wuming/source-packages`, or `~/.local/share/wuming/source-packages` when XDG_DATA_HOME is absent.
- The trimmed package name produces a stable package ID. Renaming creates a separate subscription; use the existing name for an update.
- All original files, including `origin.json`, are preserved in immutable content-hashed version directories. An atomic index selects the current and previous revision, including enabled sources. Failed validation or index publication leaves the active revision unchanged.
- Old version files are retained for in-flight playback. This first implementation does not automatically prune them.
- Host settings, source caches and catalogue identities use `(package ID, original site key)`. Providers still receive their original key. Desktop JS storage identity survives loopback-port changes. Legacy subscriptions/preferences remain untouched; ambiguous old raw-key preferences are not guessed or reassigned.
- Provider-owned account state is still governed by each runtime/provider; namespaced host keys are not a new security sandbox or a promise of isolation for arbitrary native plugin internals.

Generated configurations contain only the selected VOD sites. An HTTP/Python-only VOD selection omits the root spider to avoid preloading an unrelated DEX plugin on Android. Original package files are retained, so reimporting the same name can change the selection. Package-specific parsers/live definitions remain in the configuration; their individual runtime dependencies still require verification.

ZIP imports are bounded to 2,048 entries, 32 MiB per expanded file and 128 MiB total. Traversal paths and duplicate archive file paths are rejected. Local directory imports reject symlinks. Hashes identify content and changes; they do not establish publisher identity.

## July 28 collection validation

The offline inspection of `本地包合集260728` found ten entry configurations, including the decoded image/Base64/CBC entry in `雷蒙/raw/source.txt`. The eight standard packages have no structural import errors. 雷蒙 and 雷蒙影视WP have unresolved local resources and are blocked until repaired. This is evidence about the supplied files, not endpoint availability or playback success.

Python providers now use the optional bundled [native CPython wrapper](native-python.md), enabled by default for Android packaging and supplied with desktop distributions. This supplies the interpreter and declared dependencies; it does not certify individual providers or arbitrary Java interop. FishGuard, wexguard and the observed woshinidie payload are not advertised as supported desktop plain DEX. A recognized ftyguard shape still requires exact artifact/ABI/runtime verification.

Concurrent cross-package search federation, new guard emulators, reconstruction of missing upstream files and cloud-account/cleanup changes are outside this implementation. Search and health ranking continue to operate through the existing selected-package workflow. Existing built-in OpenDrive behavior remains intact.
