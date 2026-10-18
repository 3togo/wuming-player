# AlisoGuard loader crash fix — 2026-09-16

Follow-up: [targeted cache recovery and working current-provider tests](native-provider-recovery-20260916.md).
The results below describe the earlier crash-containment stage.

## Cause and change

The TV's configured provider jar exposes `Init.init(Context)` and `Init.loader()`.
Inspection of its DEX shows that `init` stores the result of native
`DexNative.getLoader(context)` without checking for null. `Init.getSpider` passes
that value directly to native code. On this TV it is null, and native
`ClassLoader.loadClass` aborts the process with SIGABRT. Java exception handling
around the spider constructor cannot recover from that abort.

`JarLoader` now checks the optional public static `loader()` contract after
initialization. When a ClassLoader-returning accessor returns null, the jar is
rejected before publishing its loader or proxy, or constructing a spider. The
existing unavailable-provider path returns `SpiderNull`. Jars without Init or
without the optional loader accessor retain their existing behavior.

The Android-specific class-loading change belongs in `app/`; it does not add
portable provider policy. No provider credentials, account routing, configuration,
or downloaded provider files are changed. No encrypted provider binary is patched.

This fixes the host process crash. It does not repair the provider's native loader
or establish that AlisoGuard content/playback works. Its underlying native
initialization failure remains inside the external provider binary.

Provider jar SHA-256 observed on the TV:
`832faaf195f8e209ac464b4d6f07882f25c1d17e1cfa6410167e37da48fa87f9`.
The provider binary is not committed to this repository.

## Local validation

Seven `JarLoaderTest` tests passed: five initialization cases plus two existing
cache-identity cases. Initialization cases cover absent Init, legacy Init without
a loader accessor, a loader populated by init, silent null-loader failure, and a
thrown init failure. Both mobile ARM64 and leanback ARM32 verification apps and
instrumentation runners built successfully offline.

## Device reruns with the fix installed

Both authorized devices received updated `無名(testing)` and `無名(test runner)`
packages with data-preserving replacement installs. Normal app packages and data
were left intact.

| Device | Runtime tests | UI tests | Launch/recovery/restart |
| --- | --- | --- | --- |
| Phone `7e34269d`, Android 14, ARM64 | 7/7 passed | 4/4 passed | Passed |
| TV `192.0.2.10:5555`, Android 9, ARM32 | 7/7 passed | 13/13 passed | Passed |

Every selection completed in one invocation with exact method/status matching and
runner code `-1`. The TV passed the settings test that previously ended in native
process death. Device logs confirm that the new guard actually rejected the null
loader on this TV, rather than the problematic provider simply not being reached.

The earlier phone attempt after unlocking also passed before the fixed APK was
installed. The table reports the subsequent run on the fixed build.

[Exact selections, per-test output, APK hashes, and cleanup](../../PUBLIC_PREPARATION.md).
[Handled provider initialization failure](../../PUBLIC_PREPARATION.md).
The [initial failures](device-smoke-20260916.md) remain preserved separately.

## Extended standalone observation

The first post-fix extended observation was interrupted after ten nominal seconds:
a hardware key event (key code 850, device ID 8) coincided with the OEM hotkey
receiver starting `SkyTVMainActivity`. The app PID remained 11875. This was a
focus failure, not a new native crash; the host stopped the app during cleanup.
That attempt is preserved in `standalone-interrupted.json` and
`standalone-focus-interruption.txt` in the measurement directory.

One repeat passed the complete cold-start observation (ten samples, two-second
waits plus ADB command overhead) and background recovery. Its restart observation
was interrupted at nominal second 18 by another hardware key 850 opening the OEM
TV app; the application PID remained 12784. No additional retry was made.
`standalone-retry.json` preserves these mixed results. The shorter restart smoke
check passed, but the extended uninterrupted restart check is not claimed passed.
No new native abort appeared in either extended observation.

Wake loops stopped and both devices returned to their launchers. Saved power
settings were restored: phone `15`, TV `0`. No application data was cleared.
