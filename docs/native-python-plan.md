# Native CPython wrapper implementation plan

Status: implemented and locally verified; see [results and remaining limits](native-python-validation.md).

The user authorized replacing Chaquopy with a TV-owned JNI wrapper while continuing to bundle Python and its packages. No device testing is authorized for this task.

1. Add `runtime-core`: JSON JNI protocol, provider lifecycle, scoped module/cache identity and shared CatVod catalogue adapter. Preserve original provider keys. Use C at the CPython/JNI boundary; keep orchestration in Kotlin/Python.
2. Build `libwuming_python.so` and bundle CPython, standard library, requests, lxml, pyquery, cachetools, BeautifulSoup and PyCryptodome. Android uses pinned CPython source with ARM64/ARM32 cross builds; Linux uses the local CPython development SDK to produce a private runtime home. Builds/downloads are explicit, never Gradle configuration side effects.
3. Replace Android PyLoader's reflective Chaquopy bridge with a lazy native Spider adapter. Serve proxy results through the existing provider route. Wire desktop Python sources into the shared catalogue adapter with scoped loopback proxy endpoints.
4. Remove Chaquopy from the active Gradle graph. Keep its old source as an inactive reference until runtime parity can be established; do not alter retained Java Spider facades. Package runtime assets per ABI with checksummed, atomic extraction.
5. Validate actual Linux JNI linkage and provider behavior, binary proxy responses, Unicode, errors, scoped state, multi-thread calls, dependency imports and bundle extraction. Build Android ARM64/ARM32 libraries and affected app variants; inspect APK packaging and native dependencies. Device/provider-service playback remains unverified until separately authorized.

Compatibility boundaries: this is an in-process trusted Python runtime, not a sandbox. Arbitrary `java`, Android or `com.chaquo.python` reflection is not implemented. The base Spider helper API and an explicit Proxy compatibility surface replace the known host calls. Providers needing other host APIs report an error. Third-party scripts and cloud-account behavior are not modified.
