# Native provider recovery — 2026-09-16

## Outcome

The TV's configured home provider is working again in `無名(testing)`: the native
loader initializes, the provider initializes, and `homeContent(false)` returns
valid JSON. The same direct test passes on the phone. The production crash guard
remains installed. This follow-up corrects the earlier broad implication that the
current provider requires an upstream binary repair.

AlisoGuard itself is an unused wrapper in the current source configuration. Its
internal implementation is missing/nonfunctional in this JAR; it was not repaired
or silently replaced. The current home source uses `csp_DouDouGuard`, which works.
The class name in the earlier native backtrace alone did not establish that the
current configuration selected AlisoGuard.

## Controlled investigation

All device work used the explicitly authorized serials and the verification
package, with data-preserving runner installation, bounded instrumentation, wake
control, and power restoration.

1. The identical provider JAR initialized on the phone (Android 14/ARM64), but
   returned a null loader on the TV (Android 9/ARM32). Constructor-only tests were
   initially successful after loader initialization; they did not prove provider
   functionality, as later initialization tests demonstrated.
2. The TV had a read-only, 1,000,029-byte native extraction artifact at
   `code_cache/sharedb/config.db`. This is generated provider code, not the app's
   Room database. Its ZIP passed integrity checks. No corrupt archive conclusion
   is claimed.
3. Temporarily moving the provider extraction directory aside made the TV loader
   initialize. The original directory was then restored for a narrower test.
4. Moving **only** `config.db` aside, retaining the original oat directory, also
   restored initialization. The old file remains at
   `code_cache/sharedb/config.db.loader-recovery-backup` for recovery/audit.
   Successful native initialization removes its temporary extraction file.
5. After that repair, explicit AlisoGuard initialization failed on **both**
   devices: its wrapper's internal Spider was null. Merely constructing the
   wrapper had hidden this second problem.
6. Read the currently selected configuration endpoint
   `http://fty.888484.xyz/tv`. Its home API is `csp_DouDouGuard` and it contains no
   Aliso references. A fresh download of its provider JAR is byte-identical to
   the installed cached JAR (SHA-256 below). The extracted payload has DouDou,
   but no `com.github.catvod.spider.Aliso` implementation.
7. Tested the configured DouDouGuard through loader initialization, construction,
   `init(context, "")`, and `homeContent(false)`. Both devices passed; the response
   parsed as JSON containing `class` or `list`. Response contents were not logged.

The presence of the stale extraction file is the experimentally verified trigger
for the TV loader failure. The precise exception swallowed inside the native
binary was not exposed. This does **not** establish an Android 9 or ARM32
incompatibility: the same TV and binary work after the targeted repair.

Provider JAR SHA-256:
`832faaf195f8e209ac464b4d6f07882f25c1d17e1cfa6410167e37da48fa87f9`.
No provider binaries, decrypted payloads, configuration secrets, or copied device
databases are committed. The temporary copied database was removed after inspection.

## Verification

| Device | Configured-provider diagnostic | Runner result |
| --- | --- | --- |
| `7e34269d` | Loader, init, and valid home JSON passed | 1/1, 1.170 s, final code -1 |
| `192.0.2.10:5555` | Loader, init, and valid home JSON passed | 1/1, 4.369 s, final code -1 |

The opt-in `NativeProviderLoaderInstrumentedTest` is retained for reproducing the
problem without disabling the production guard. Stage the chosen provider JAR at
`cache/native-loader-probe/provider.jar` in the verification package, then select
that test with `native_loader_probe=true`. `native_loader_spider` selects a wrapper
class name (default `DouDouGuard`). Ordinary test runs skip it. Host execution
must follow the device authorization, wake/foreground, timeout, and cleanup rules
in `docs/android-instrumentation-lessons.md`. The fixture is not bundled.

Both mobile ARM64 and leanback ARM32 runner builds passed offline. The earlier
11 phone and 20 TV smoke cases remain recorded in the
[crash-guard report](aliso-loader-fix-20260916.md); this follow-up adds direct
provider functionality checks rather than claiming those UI tests covered it.

## Cleanup and limits

Only the generated extraction file was quarantined. Provider settings, account
credentials, source selections, and the normal installed app were unchanged.
The temporary fixture directories and fresh-cache diagnostic directory were
removed. Testing processes stopped and the devices returned to their launchers.
Saved stay-awake values were restored: phone `15`, TV `0`.

The cache repair is targeted and one-time, not a new automatic cache-deletion
policy. If native initialization is interrupted and leaves another unusable file,
the crash guard will still refuse the null loader; it does not repair arbitrary
provider-owned caches. This test does not establish successful search, account
login, or decoded video playback. Those require separate provider-specific tests.

[Exact attempts and results](../../PUBLIC_PREPARATION.md).
