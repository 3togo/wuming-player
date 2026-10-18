# Public provenance review — 2026-10-18

The known provenance gaps in the audited source snapshot and its resolved runtime
components have been closed by replacement, removal or retained license evidence.
This review supports publishing the source snapshot with its notices. It does not
establish TV/device readiness. Runtime checks and public release-channel setup
remain separate release conditions.

The [component inventory](public-third-party-inventory.json),
[Maven inventory](public-maven-inventory.json), [Python inventory](public-python-inventory.json),
[JavaScript inventory](../third_party/javascript/inventory.json) and
[bundled-file manifest](public-provenance-manifest.json) retain origins, versions,
license selections and hashes. Android and desktop package the collected notices.

| Area | Final disposition |
| --- | --- |
| ForceTech, JianPian, Thunder, TVBus | Removed artifacts, snapshots, JNI facades and loading/download paths. |
| Hook | Removed obsolete AAR, frozen source and application hook. |
| JUPnP | Removed CDDL runtime. DLNA uses source-built Cling/Seamless under their LGPL option. |
| Cling/Seamless | Pinned production sources, original file hashes, full licenses and local patch hashes retained. Android adapters are separate from shared protocol code. No Jetty/CDI/servlet runtime. |
| Callback security | Restrict incoming subscription callbacks to the subscribing connection's literal address and bound callback lists/headers; offline regression coverage retained. |
| Google Cast and Play Services client SDK | Removed Cast backend, registration metadata and helper dependency. GMS-dependent providers are unsupported unless they supply a separately reviewed dependency path. |
| Native desktop emulator | Removed Unidbg and the native-emulator chain with unresolved GPL compatibility. Native-protected desktop providers return an explicit unsupported result. |
| Custom Rust prebuilts | Removed mismatched prebuilt libraries; existing Kotlin fallback paths remain. Rust source is retained for future development. |
| DanmakuFlameMaster | Matching public artifact, version 0.9.25, Apache-2.0 upstream evidence and Chen Hui attribution retained. Exact binary reproduction is not established. |
| Charset detector | Replaced old MPL-1.1-only metadata artifact with the maintained, explicitly tri-licensed fork; LGPL option and matching sources retained. |
| Minified JavaScript | Rebuilt from pinned npm archives, with editable entries, lockfile integrity and complete collected package notices. Original compatibility helpers replace the opaque similarity implementation. |
| Test DEX JARs | Rebuilt from retained project-authored Java sources and a compile-only ABI stub using javac/D8. Framework and stub classes are excluded. |
| Empty-state animation and web script | Replaced untraceable Lottie geometry with an original W/dot pulse. jQuery 3.7.1 matches the official script and its full MIT license is packaged. |
| Images and synthetic media | Original geometric branding, vectors, home tiles and wallpapers; generated test patterns/tones with baseline AVC/Main HEVC profiles and retained synthetic TS/DASH segments. Editable sources and recipes retained. |
| Catalogs | Removed catalogs whose anonymous availability did not establish content rights. Retained an attributed Big Buck Bunny CC BY 3.0 sample link; no movie is bundled. |
| Gradle wrapper | Replaced inherited wrapper with the official 9.7.1 artifact matching its published SHA-256. |

The Maven review covers resolved application, desktop and helper runtime graphs,
including transitive components. Publisher POM declarations are retained; Nanojson
and StAX gaps are resolved by their exact source headers, and TextDrawable by its
upstream MIT license. Extracted notices and source-header notices preserve
copyrights. LibAVIF's upstream combined license includes its decoder notices.
The four native AAR families were also inspected beyond their wrapper metadata:
LibAVIF includes its combined decoder notices; QuickJS is MIT; GIFLib/SKIA notices
are retained; the Apache RTMP wrapper embeds LGPL-2.1-or-later librtmp. Its pinned
source/build recipe declares version 3.2.0 and NO_CRYPTO, and corresponding sources
are retained. NDK libc++ license/legacy notices are included. See the
[native dependency inventory](public-native-dependency-inventory.json).
The GraalJS upstream third-party notice bundle is retained, and the resolved
shaded ICU VersionInfo identifies ICU 76.1; its full Unicode license is included.
The notice bundle is intentionally broader than the used Java engine and may
mention upstream Node.js components that are not packaged here.
The legacy XPP3 dependency is excluded; Android supplies XML pull APIs and desktop
uses StAX. Missing license/notice entries are not accepted as implicitly cleared.

Cling's copied specification schemas were not imported. Project-authored minimal
schemas validate the event/InstanceID envelope; its existing typed event parsers
interpret values. Its optional servlet, container and mock code is omitted. The
local callback patch addresses the redirection described in
[upstream issue 253](https://github.com/4thline/cling/issues/253). Cling is an
unmaintained upstream, so these are maintained local changes, with deferred real-TV
compatibility checks still required.

Matching NewPipeExtractor, Rhino, charset-detector, Certifi and librtmp sources accompany
this snapshot under [corresponding-source](../third_party/corresponding-source/).
The NewPipe source archive excludes scraped test fixtures and media while
retaining production/generator source, short relative-time phrase inputs and the
complete build metadata, including its Gradle version catalog. Preserve the
complete matching application snapshot and these notices alongside public APKs.
Private signing material and machine configuration are excluded.

The review does not approve future remote providers, third-party content fetched
at runtime, or new dependency versions. Changes to reviewed bundled artifacts and
vendor sources must update evidence deliberately; `scripts/check-public-provenance.py`
is part of the manual release gate. Historical validation reports describe their
recorded revisions and do not validate this final candidate.
