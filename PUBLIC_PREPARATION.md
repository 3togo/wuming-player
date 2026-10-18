# Public source preparation

This is an independent source snapshot for a new repository. Original Git history,
remotes, private development state and account-owned test shares are omitted.
The fresh source snapshot is published at https://github.com/3togo/wuming-player.
The public update channel is configured for an explicitly marked prerelease;
TV/device checks remain pending.

## Privacy changes

- Removed local assistant memories, machine-specific Rust linker settings, raw device
  reports, screenshots, obsolete development artifacts and captured cloud-share runs.
- Removed the encrypted private-depot downloader and its startup registration. Its
  embedded password is absent, including from AES tests; those tests use an explicit
  public fixture password and continue to exercise encryption/decryption behavior.
- Replaced observed device identifiers, personal home directories and documentation
  LAN addresses with generic examples. Synthetic LAN addresses in offline tests are
  deliberate fixtures for private-network policies.
- Removed account-owned cloud-share URLs/extraction codes from test-runner assets.
  Create disposable test shares with `scripts/prepare-cloud-stream-fixtures.py`.
- Replaced the original publishing destination with `3togo/wuming-player` and
  its independent signing identity. The public release feed lists only verified
  public APKs. The debug feed is inactive; links to the older application's feed
  have been removed. Optional Gitee publishing requires explicit separate
  configuration. No private signing keys or publisher credentials are included.


## Catalog screening

The earlier anonymous-access screening did not establish redistribution rights
for the retained catalog contents. Those VOD/live source catalogs were subsequently
removed. The bundled live sample list now contains only an attributed Big Buck
Bunny link under CC BY 3.0; no movie is bundled. Fresh installations wait for an
explicit source selection. Availability and playback remain runtime checks.

The earlier [catalog verification record](docs/public-catalog-verification.json)
is historical evidence, not the final bundled catalog inventory. See the
[final review](docs/vendor-provenance-review.md) for the current rights decisions.

## Build without private signing material

No private signing key, passphrase, account backup or local build configuration is
included. An independent production certificate is pinned in `updates.json`; the private key is external. Public builds have their
own application ID, `com.wuming.android.tv.publicedition`, and cannot replace the
private application. Release signing requires an independent maintainer-owned key
and matching publisher/certificate configuration; preview verification remains isolated.

For a separately installed local verification app, use an isolated configuration:

```sh
export WUMING_CONFIG_FILE="$HOME/.config/wuming-public/config.json"
export ANDROID_HOME="/path/to/Android/Sdk"
python3 scripts/setup-signing.py --ephemeral
python3 scripts/user_config.py set buildPython /path/to/python3.10
./gradlew-safe -PwumingEphemeralSigning=true :app:assembleMobileArm64_v8aVerification
```

Run Gradle/lint with JDK 21 and retain JDK 17 for declared JVM toolchains; see
[the preview guide](docs/public-preview.md). Use the documented Android/Python toolchains. The generated configuration and
keys remain outside the source tree; ephemeral keys are for local verification and
cannot create release artifacts. The verification app is named `無名(testing)`.

## Scope of the audit

The final scan checks the known removed private values, personal device/home markers,
common credential-token patterns, and nested ZIP/JAR/AAR contents. Placeholder test
credentials and protocol parsing strings remain where they are part of behavior tests.
The former project-native prebuilts were removed; their diagnostic/source mismatch
is no longer shipped. The Kotlin fallbacks remain active. Hook, Google Cast, the
native desktop emulator chain and JUPnP have also been removed. DLNA uses a source
build of Cling/Seamless with the LGPL option and documented transport/security
patches. See `vendor/cling/README.md`.

A token-bearing catalog URL was omitted before network checks. Revoke/rotate that
token if it belongs to you and remains active.

The private depot's old client-embedded password should not be treated as a secure
server-side secret. If the old depot must remain confidential, rotate its access and
avoid relying on a password distributed inside client APKs. This snapshot does not
change the private application or rotate its credentials.

The private repository history must remain private. Publish this snapshot's new
history or its Git-generated source archive, rather than copying the old `.git` folder
or ignored build/configuration files.

## Optional native development

Rust sources remain available for future work. No project-native prebuilt is
shipped by this snapshot. `buildRustNativeAll` creates candidates under
`native/target/`; adoption requires a deliberate JNI/API and behavior review.

## Validation

See [the final provenance validation record](docs/public-provenance-validation.json)
for current checks, APK hashes and limits. Earlier validation records describe
their own revisions. The fresh public snapshot adds validation documentation and
completes external corresponding-source packaging without changing the tested
application code, resources, build recipes or packaged notices.

The completed bundled-artifact evidence review and unresolved redistribution conditions
are recorded in [docs/vendor-provenance-review.md](docs/vendor-provenance-review.md).
Original replacement branding is documented in [artwork/branding](artwork/branding/README.md).
