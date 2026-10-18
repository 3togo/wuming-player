# Native CPython wrapper: implementation result

Date: 2026-09-29. Implemented the [plan](native-python-plan.md) to replace Chaquopy with a TV-owned native wrapper while retaining bundled Python and provider packages. [Usage and build guide](native-python.md).

## Delivered

- Shared `runtime-core` provider lifecycle, JSON JNI protocol, scoped dependency modules/cache and CatVod catalogue adapter.
- `libwuming_python.so` embedding CPython with isolated startup configuration, GIL handling, UTF-8 byte-array requests/results and explicit error propagation. Providers can overlap blocking I/O; calls within one provider are serialized. Interpreter finalization is deliberately avoided.
- Android's existing PyLoader now uses a lazy native Spider adapter. The active build graph no longer contains the Chaquopy plugin or `:chaquo` dependency. The old source tree remains inactive; retained Java facades are unchanged.
- Linux desktop recognizes `.py` sources, executes them through the shared catalogue adapter and provides a revocable, provider-specific loopback proxy.
- CPython 3.13.15 and native provider extensions built for Android ARM64 and ARM32, using API 26 and NDK 29.0.14206865. Linux bundles the local CPython 3.14 SDK runtime and dependencies in its distribution.
- Runtime packaging includes the standard library, declared pinned packages and license notices. Android extraction checks the ZIP hash and publishes a completed private runtime directory atomically. Both asset and JNI merge tasks depend on runtime preparation, with a build lock protecting shared cross-compilation inputs.
- HTTP/Python-only package selections omit unrelated root DEX preloading. JavaScript/CSP routing and built-in cloud-account behavior are not replaced by this work.

## Verification

All final checks passed:

| Check | Result |
| --- | --- |
| Native/core tests | 5 passed: actual JNI/provider identity, Unicode, binary proxy, package imports, error recovery, lifecycle, threading/concurrent blocking I/O, and bundle publication/path/hash checks |
| Python dispatcher tests | 7 passed: cleanup after failed initialization, scoped dependency modules, unsupported Java interop, invalid dependency paths, base64/JSON results, cache expiration and boolean truthiness |
| Shared source-core regression suite | 71 passed |
| Focused desktop regression suites | 19 passed, including local Python configuration, catalogue methods, binary proxy and revocation |
| Focused Android verification suites | 16 passed: JarLoader, package import and decoder |
| Android compile matrix | Mobile, leanback and leanback_lite on arm64_v8a and armeabi_v7a passed |
| Android APK packaging | Mobile debug APKs for both ABIs built; native CPython and wrapper libraries present; runtime ZIP checksums match manifests |
| Native ELF inspection | Both ABIs: 6 packaged JNI libraries plus 116 Python native extensions; architecture, dependency closure, JNI exports and 16 KiB LOAD-segment alignment passed |
| Chaquopy removal | No Chaquopy entries in the inspected Android runtime dependency graph, APK library paths or asset paths |
| Linux distribution | `:desktop:installDist` passed; Java smoke test from `/tmp` found the installed distribution's own libpython and imported its requests/lxml/AES packages through JNI |
| Architecture and retention | Generic-library, desktop, lifecycle and Java-retention Gradle guards passed; retention inventory passed |
| TV static checks | 125 effective layouts, 296 explicit focus targets, 26 action contracts, 155 listener checks and three app-label locales passed |
| Other | Python test/script execution and `git diff --check` passed |

These are 118 automated test cases plus the installed-distribution smoke test, builds and static inspections. The Linux test confirms bundled-library loading through `/proc/self/maps`, even with the host's LD_LIBRARY_PATH set; it does not simply rely on a system Python executable.

[Artifact measurements and SHA-256 values](native-python-artifacts.json) identify the generated APKs and runtime ZIPs. The Android runtime ZIP is approximately 9.7 MiB on ARM64 and 9.4 MiB on ARM32; the six native libraries total approximately 10.2 MiB and 6.5 MiB respectively, before APK packaging overhead. Full generated APKs are approximately 75.3 MiB and 68.1 MiB; those sizes include the rest of the app.

During validation, the cross-build was corrected to use target Python headers and target pointer width rather than the build machine's settings. In particular, ARM32 PyCryptodome now compiles with `SYS_BITS=32`. A Gradle implicit-dependency error was resolved by wiring JNI-folder/native-library merging to runtime preparation. Native libraries are stripped by the runtime builder and excluded from redundant AGP stripping.

To repeat the distribution smoke test after `:desktop:installDist`:

```sh
python3 scripts/python-runtime/tests/verify_distribution.py
```

## Limits and remaining validation

No Android device/emulator was accessed, no APK was installed, and no collection provider or live service was executed. Android compilation/ELF/APK checks do not establish on-device interpreter startup, provider playback or 16 KiB compatibility of unrelated pre-existing native libraries.

This is a bounded Python Spider compatibility layer, not Chaquopy's general Java reflection API. Providers requiring arbitrary Android/Java calls, unsupported module/package loading patterns or additional dependencies still need specific work. Python remains trusted in-process code; native crashes and non-cooperative infinite calls are not contained. Hard isolation/cancellation requires a process boundary. Old private runtime revisions are retained without automatic pruning. The Linux distribution retains the builder's architecture/glibc/system-library baseline.

Existing unrelated workspace changes were preserved. Nothing was committed, merged, deployed or installed on a device.
