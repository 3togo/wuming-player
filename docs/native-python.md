# Bundled Python through TV's native wrapper

TV's active Python path no longer uses Chaquopy. `libwuming_python.so` embeds CPython behind a JSON JNI interface. Python and provider dependencies remain bundled. The old `chaquo/` tree is retained as an inactive reference; it is excluded from settings.gradle and app dependencies.

## Architecture and behavior

`libraries/runtime-core` owns provider lifecycle, the JNI declaration, Python method dispatch, module/cache identities and the shared catalogue adapter. `native/python-runtime/bridge.c` owns CPython initialization, GIL acquisition and byte-array transport. Android supplies asset extraction, source transport and a CatVod Spider adapter; desktop supplies resource loading, runtime location and a revocable loopback proxy.

The interpreter starts on the first provider call. It is process-owned and deliberately never finalized/restarted: native Python extensions may retain global state. Each provider instance has its own module namespace; sibling dependencies loaded through `getDependence`, relative imports or `loadModule` stay in that namespace. Third-party Python packages remain shared interpreter modules. Host source identities and cache directories use the package-scoped key while the provider receives its original `siteKey`.

The bridge supports home/home-video, category, search (including page), detail, player, live content, action, video-format checks, proxy, getName and destroy methods. Trailing positional arguments a provider's own signature cannot accept are dropped, so providers that predate the search page argument still work; `destroy` is likewise optional. JSON objects or already serialized JSON responses are accepted. Proxy text, bytes and the legacy base64 flag are normalized without corrupting binary bodies. Errors are returned to the host; `SystemExit` is converted to a provider error. This does not protect against `os._exit`, native crashes, infinite loops or hostile Python code.

Android proxy URLs include the owning siteKey. Desktop endpoints are provider-specific loopback capabilities revoked on close. The base Spider cache helper uses provider-scoped files with expiration and atomic writes.

## Bundled dependencies

Android uses CPython 3.13.15 built from its checksummed release archive for API 26, on arm64-v8a and armeabi-v7a. CPython's official Android build script supplies OpenSSL, libffi, bzip2, SQLite and XZ dependencies; their archives are also hash-locked. The build uses NDK 29.0.14206865 by default and requests 16 KiB ELF segment alignment.

Pinned Python dependencies are in [requirements.txt](../scripts/python-runtime/requirements.txt): requests, urllib3, charset-normalizer, idna, certifi, cachetools, BeautifulSoup, soupsieve, typing_extensions, pyquery, cssselect, lxml and PyCryptodome. Android lxml and PyCryptodome are cross-compiled; lxml statically links libxml2/libxslt. Their target Python headers and pointer width must be used even on a 64-bit Linux builder. Notices are included in the runtime bundle.

Linux bundles a private Python home, packages, libpython and the JNI bridge using the local development SDK. No Python executable is required at runtime, but the distribution retains the builder's architecture/glibc/system-library requirements. It is not a universal Linux binary.

## Build and validation

Prerequisites: Linux C compiler, Python development headers and pip, JDK 17 headers, GNU make/autotools build prerequisites, curl, readelf, and the Android SDK/NDK for Android targets. Gradle prefers `~/venv/bin/python` for the Linux runtime when it exists and is executable, falling back to `python3`. The selected interpreter needs pip. The first build downloads pinned dependencies and compiles CPython/extensions; later builds reuse their outputs. Pip follows `PIP_INDEX_URL` and any pip configuration, so hosts that cannot reach PyPI must select a mirror before the first build (for example `PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple ~/venv/bin/python scripts/python-runtime/prepare.py --linux`). Later Linux rebuilds skip pip entirely while the pinned requirements and recipe are unchanged, so they work without any index access. No device or emulator is accessed by these scripts.

```sh
~/venv/bin/python scripts/python-runtime/prepare.py --linux
python3 scripts/python-runtime/prepare.py --android all
python3 scripts/python-runtime/verify-artifacts.py
python3 -m unittest discover -s scripts/python-runtime/tests -t scripts/python-runtime/tests -v
./gradlew-safe :libraries:runtime-core:test
./gradlew-safe :desktop:test --tests '*DesktopPythonRuntimeTest'
./gradlew-safe :desktop:installDist
python3 scripts/python-runtime/tests/verify_distribution.py
```

Android asset-merge tasks depend on preparing the selected ABI; Gradle configuration itself does not run installers. The default is `nativePythonEnabled=true`. `-PnativePythonEnabled=false` omits Android runtime binaries/assets for builds that do not need Python; it does not reactivate Chaquopy. Desktop run/distribution tasks prepare and include the Linux runtime. For a separately staged desktop runtime, use `WUMING_PYTHON_RUNTIME` or the `wuming.python.runtime` JVM property.

Android bundles live under `build/python-native/android/<flavor-abi>/`: `jniLibs/<android-abi>/` holds shared runtime libraries and `assets/python/runtime.zip` holds the standard library, extensions and bridge. A checksum manifest controls extraction into immutable app-private `files/python-runtime/<sha256>` directories. Old runtime versions are retained; no automatic pruning is implemented. Provider data is stored under `files/python-providers`. Desktop data uses `$XDG_DATA_HOME/wuming/python-providers` or `~/.local/share/wuming/python-providers`.

## Compatibility limits

This is trusted, in-process Python execution, not a sandbox or a general replacement for Chaquopy's Java reflection. Providers importing arbitrary Java/Android classes or `com.chaquo.python` need a specific host adapter. The supplied base Spider helpers and explicit `com.github.catvod.Proxy` compatibility calls cover a bounded API. Nested Python package imports, dynamically generated dependencies and additional native packages require separate compatibility work. Dependencies must be supplied; enabling the runtime cannot repair missing package files.

Provider source loading is capped at 4 MiB per module, 16 MiB per dependency scope and 32 modules. HTTP dependency loading uses timeouts and verified TLS. The provider's own Python code can perform unrestricted process-permitted I/O. Calls to one provider are serialized; CPython’s GIL protects interpreter access while blocking I/O can overlap across providers; cancellation cannot forcibly terminate a blocked/native Python call. Hard isolation and preemptive cancellation would require a separate runtime process.

Real Android loading, device navigation and actual third-party service playback are distinct from cross-compilation, offline ELF inspection and synthetic Linux JNI tests. See [the implementation result](native-python-validation.md) for measured validation and outstanding limits.
