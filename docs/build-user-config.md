# Private build configuration and APK signing

Use `$XDG_CONFIG_HOME/wuming/config.json`, defaulting to
`~/.config/wuming/config.json`. This user-owned location stays outside the checkout
and shares a stable signing identity across worktrees. Set `WUMING_CONFIG_FILE` to
an **absolute** path to use another config (for example, an isolated CI identity).
Gradle and Python/shell setup tools use the same lookup. Relative config paths are
rejected. Config and generated keystore files are written with permissions `0600`;
new private directories use `0700`.

This is build configuration. Android SDK discovery still uses `sdk.dir` in
`local.properties` or Android's SDK environment variables. Shared Gradle options
remain in `gradle.properties`. Playback/source configuration inside the installed
app is independent of build credentials; signing passwords are never app assets.

The signing script creates or refreshes two JSON files:

- `wuming-config.example.json` in the project root: a commit-safe template with
  placeholder paths/passwords and generation defaults. It never copies production
  credentials and is never loaded by Gradle.
- `~/.config/wuming/config.json` (or the configured override): the private production
  values, including the actual keystore path, alias, and passwords.

A repeat run recreates a missing template while preserving the production identity.
The generated template is maintained by the script; put personal values only in
production config.

## Public-preview setup

This snapshot has an independent production certificate pin and separate application ID.
For production, explicitly set `WUMING_CONFIG_FILE` to an external private location
such as `$HOME/.config/wuming-player/production/config.json` and restore the public
project identity using its encrypted recovery export. Do not reuse the private
application's default configuration or signing identity.

Use isolated `--ephemeral` instructions in [the preview guide](public-preview.md)
for verification builds. Ordinary setup validates the pinned identity and refuses
to create a replacement. For a new unpublished fork with an empty repository and
certificate pin only, `--initialize-project` creates and pins an independent key.
The generic examples below do not bypass a pinned project's identity checks.

## Setup

```bash
# Existing checkout with signing settings in local.properties:
python3 scripts/setup-signing.py --migrate-local

# Fresh checkout: generate a random password and a persistent RSA signing key:
python3 scripts/setup-signing.py

# Run again to validate the existing identity without replacing it:
python3 scripts/setup-signing.py
```

Migration validates the existing private key, copies the keystore into the private
config directory, and stores its absolute path and credentials in the user config.
It also migrates `buildPython`, if present. The original files remain available as
backups; Gradle no longer reads their signing or Python settings. Remove those
obsolete entries from `local.properties` after confirming your backup. A legacy
signing config prevents accidental fresh-key generation; use `--migrate-local`.

All application modules use this identity: mobile, leanback, leanback_lite, both
ABIs, debug, verification, release, guarded helper APKs, and instrumentation APKs.
Packaging fails when settings or the keystore are missing. Builds never generate
keys or fall back to the Android debug keystore. Existing release verification
requirements still apply.

## Import or rotate

```bash
# Register a pre-existing keystore; passwords are read through hidden prompts:
python3 scripts/setup-signing.py --import-keystore /absolute/path/release.jks \
  --alias release --store-type JKS

# Explicitly create a new signing identity and update the config:
python3 scripts/setup-signing.py --rotate
```

Rotation and import save a private backup of the previous config next to it.
Rotation also keeps the previous keystore. A different key cannot update an existing install signed
with the previous key. Restore the original key for such updates. Imported files
stay at their supplied absolute path; back up that file with the config.

The script validates store and private-key passwords before replacing settings.
Updates are atomic and preserve unrelated JSON fields. Concurrent signing setup
is rejected through a lock next to the config; remove a stale `.lock` only after
confirming no setup process is running. Passwords are never printed or passed as
command-line arguments. The former `setup-debug-keystore.sh` and
`ensure-release-keystore.sh` commands forward to this script.

The `signing` object contains `storeFile`, `storeType`, `keyAlias`, `storePassword`,
and `keyPassword`. Generated identities also record `keyAlgorithm`, `keySize`,
`validityDays`, and `distinguishedName`. Do not commit this file or its keystores.
`buildPython`, when used, is an absolute path at the top level; `scripts/build/ensure-python310.sh`
updates it in this same file.

For an isolated local/CI identity, export `WUMING_CONFIG_FILE` before **both** setup
and Gradle. A persistent device-test runner should provision and retain one config
and keystore, since changing certificates prevents in-place updates.

```bash
python3 -m unittest discover -s tests -p test_user_signing.py -v
./gradlew :app:signingReport :guarded-spider-helper:signingReport \
  :guarded-webview-helper:signingReport
```
