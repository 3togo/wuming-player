# Platform capability and evidence matrix

This table describes source-supported behavior, not live-provider availability. Update it when a
platform adapter or a shared contract changes. JVM tests do not establish hardware playback.

| Capability | Android mobile / TV | Linux desktop | Evidence boundary |
| --- | --- | --- | --- |
| Catalog browsing, search, detail | Supported | Supported for enabled provider runtimes | Unit contracts; real sources can change |
| Cloud account login | Provider-specific Android flows | Secret Service-backed QR flows | Provider/account tests require explicit authorization |
| Quark playback | Original and smart routes; narrow documented smart fallback | Shared-file original download resolver | Desktop resolver fixtures; decoded desktop playback not established by unit tests |
| Ali / UC / Baidu cloud playback | Provider-specific routes | Login exists; desktop resolver reports unsupported/account-required | Do not imply login establishes playback support |
| Quark save completion | Shared `QuarkSavePolicy` | Same policy | Pending/failed task IDs must never be accepted as complete |
| Saved-copy lifecycle | Android repository and active-player leases | Persistent save intents/tasks, recovery, reviewed maintenance and bounded retention | Desktop protects copies while recorded owner processes live and for 24 hours after last use; no folder sweep or recycle-bin purge |
| Video player | Android native engines | External mpv / VLC | Real codec and sustained-playback evidence is hardware-specific |
| TV remote navigation | Leanback focus contracts | Desktop keyboard controls | Static checker supplements authorized runtime navigation |
| Release artifacts | Mobile ARM64; TV ARM64 and ARM32 | JVM distribution | Android release acceptance assembles/checksums all three supported APK variants |

The desktop ledger stores hashed credential-session and share/file identities, destination IDs,
pending task IDs, last-use times and process IDs, never raw cookies, share tokens or media URLs. Changed credentials
are treated conservatively as a new session: older records are retained, not reassigned to another
account. At the ledger limit, new saves fail instead of deleting protected or untracked files.
Copies moved to the provider recycle bin are not permanently purged by desktop cleanup; quota
recovery is therefore not promised. An unavailable or corrupt ledger fails closed. Known interrupted
tasks can be reconciled through Accounts → Saved copies. An interrupted submission without a task
ID remains uncertain and blocks automatic retry. Forgetting a reviewed uncertain or old-session
record removes local tracking only and can leave a remote copy untracked.

## Release-candidate runtime evidence

Keep deterministic acceptance independent of device access. When a particular device is explicitly
authorized for release validation, record the exact APK hash/signature, ABI, device and build:

1. Install the intended signed/minified candidate using the established identity; preserve app data.
2. Verify cold launch, background return, settings and history restoration.
3. On TV, navigate catalog → detail → playback → Back with the D-pad and visible focus.
4. Play a known local fixture through first frame and progress, then seek and stop/reopen.
5. Exercise provider-specific login/cloud/cast behavior only when its account/device scope is authorized.

A verification-build smoke result is not a signed-release runtime result. Report missing runtime
prerequisites as not tested. Read `android-instrumentation-lessons.md` before instrumentation and
restore any temporary device power settings afterward.
