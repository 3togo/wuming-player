# CatVod / OpenDrive compatibility audit

Audit date: 2026-08-07

This document records evidence, not inferred provider behaviour. Cloud APIs are
stateful and undocumented; a plausible request is not accepted as a protocol
fact unless it is backed by working source or a reproducible device trace.

## Reference identity

### Runtime CatVod used on the test device

The connected device (`7e34269d`) currently resolves direct-mode cloud links
through this downloaded site:

- Config: `http://fty.888484.xyz/tv`
- Site key: `MDrive`
- API class: `csp_MyDriveGuard`
- Display name: `🗂我的云盘┃我配置`
- Declared jar MD5: `9d90c83bc7504392dd02c2236f08713a`
- Observed jar MD5: `9d90c83bc7504392dd02c2236f08713a`
- Observed jar SHA-256:
  `6a7442551dad7ac0bde979655fd95644ac695039848918a32240809146d7d15d`
- Jar timestamp: 2026-08-03

The runtime jar is native-guarded. It is therefore a black-box playback
baseline, not a reviewable protocol specification. Its identity is captured by
`CatVodRuntimeDiagnosticInstrumentedTest`; playback behaviour is captured by
`LinkRoutePlaybackSmokeInstrumentedTest` and transfer logs.

### Reviewable CatVod source

The reviewable reference is
[`lushunming/AndroidCatVodSpider`](https://github.com/lushunming/AndroidCatVodSpider)
at commit
[`ec6e36c76d2cc99274589ce4bb2aa887f91020de`](https://github.com/lushunming/AndroidCatVodSpider/commit/ec6e36c76d2cc99274589ce4bb2aa887f91020de)
(2026-08-02).

Critical snapshot hashes:

- `QuarkApi.java`: `d872179d516a028109b8a41117d97fe0148f10c532a5512ad9ec4e62781a7cd9`
- `UCApi.java`: `3abb66e723302f72c88db8bd75e118f902ca3aae79d45a2e1d7edcb273db8319`
- `DownloadMT.kt`: `6ab6ea020f6c99f5bafbe52010439de8f8ce73d56b32e928d06a4b0cb15c5981`

This pin matters: the downloaded guarded runtime is not assumed to contain the
same code merely because its route labels look similar.

## Evidence order

Use sources in this order when changing OpenDrive:

1. Reproducible traffic and playback from the exact runtime jar and account.
2. Version-pinned working source implementing the same operation.
3. Agreement between independent maintained implementations such as LinkSwift, AndroidCatVodSpider, AList and Gopeed extensions.
4. HTTP, HLS and media-container standards for transport semantics only.

Provider request order, cookies, user-agents, cleanup timing and fallback codes
must not be invented from standards or intuition.

## Provider matrix

| Provider | Pinned CatVod behaviour | OpenDrive status | Classification |
| --- | --- | --- | --- |
| Aliyun | Personal original, share original and preview; original paths use `ProxyVideo.proxyMultiThread` | Personal/share downloads and bounded parallel Range are implemented. OpenDrive does not copy a share merely to play it. | Compatible core; preview fallback is not yet device-proven. |
| Quark | Share token → list → save → task poll → personal `file/download`; transcode through `file/v2/play`; original uses `DownloadMT` | Same stateful lifecycle, saved-file reuse, signed-cookie snapshot, expiry refresh, MP4 envelope normalization and parallel Range. | Device-proven for original playback. Transcode selection is unit-tested; keep a live transcode fixture. |
| UC | Pinned source saves then downloads; original result is a signed URL. Other maintained implementations can use share download before saving. | Share download first, then proven save/personal fallback; signed cookie, PDS user-agent and parallel profile are preserved. | Device-proven; intentional superset of the pinned source. |
| Baidu | Share listing plus preview; original tries app download and then save-to-own-drive web download | Preview and original/save fallback are both implemented with isolated account cookies and provider user-agents. | Contract-covered; retain live fixtures because Baidu signing is account-sensitive. |
| 123 Netdisk | Public share listing; authenticated `share/download/info`; Android platform header; domains include `123592`, `123912`, `123865`, `123684` and `123pan` | Core flow implemented. This audit added the missing domains and corrected share-download headers to the pinned Android contract. | Confirmed gap fixed with JVM tests. |
| Tianyi 189 | Public share listing; account cookie; `getNewVlcVideoPlayUrl`; proxied playback with Cookie | Same first-party share and playback endpoints. Login uses the provider webpage instead of CatVod's password/QR helper. | Playback contract aligned; automated login difference is intentional. |
| China Mobile 139 | Share parsing and original/preview playback through `YunDrive` | No built-in OpenDrive provider. | Confirmed coverage gap; do not implement without a supplied live share fixture. |
| WebDAV | Separate CatVod spider, not part of the pinned `Cloud` share dispatcher | Built-in provider with encrypted credentials | OpenDrive-only capability. |

The pinned CatVod `Cloud` dispatcher therefore covers seven first-party cloud
share families: Aliyun, Quark, UC, Baidu, 123, Tianyi and China Mobile 139.
OpenDrive covers six of those plus WebDAV.

## Transport comparison

### Progressive original files

`DownloadMT.kt` probes Range support, splits a bounded window across
`availableProcessors * 2` requests, and joins the streams in byte order. It
uses a 16 MiB window up to 10 GiB and 32 MiB above 10 GiB.

OpenDrive records a parallel-range capability in internal playback headers and
removes those headers before upstream HTTP. Current profiles are:

- Quark: CatVod `DownloadMT` concurrency, with a 2 MiB default/minimum window that probes up to
  16 MiB.
- UC PDS: CatVod-style 16 MiB default window that can recover down to 4 MiB.
- Aliyun: four-request, 4 MiB default window, bounded from 2 MiB to 8 MiB; the default remains
  the independently measured device profile rather than an inference from CatVod.

All three validated profiles share the reversible recovery controller. Active-read throughput
controls growth, rollback and shrink; severely degraded exact ranges can be refreshed; player
backpressure is excluded from both signals. Exact `Content-Range` validation protects ordering,
and a provider that violates it falls back to sequential playback. This is capability-gated:
ordinary HTTP, HLS/DASH, live streams and local media do not opt into parallel recovery.
Media3 loader cancellation propagates as cancellation and never triggers sequential recovery.

Progressive VOD also uses a separate buffer-health controller. It estimates active loading margin
from buffered-media growth plus actual playhead movement against the media consumption required at
the selected playback speed. Intentional high-water loader pauses, user pauses, seeks and rebuffer
intervals are excluded; stale partial evidence expires. The user buffer preference maps to bounded
resume/target watermarks (9–12 seconds resume, 20–38 seconds normal target, at most 53 seconds).
Repeated weak active-loading windows or an actual rebuffer add reserve and move the resume
watermark upward by half that reserve, starting recovery sooner. Only fresh, sustained smooth
loading removes temporary reserve. Paused playback may fill to the adaptive target but not the hard
maximum. Controller state resets at media-period boundaries. Media3's allocator and maximum remain
the final time/memory boundary. HLS/DASH, live, local, RTSP/RTMP and peer-to-peer transports keep
Media3's normal load control.

HTTP 206 alone is not a pass. A transport test must verify ordered bytes, exact
EOF clamping, seeking and sustained transfer rate.

### HLS/transcode

Pinned Quark CatVod rewrites relative `.ts` lines when the selected URL is an
HLS playlist. Current OpenDrive Quark responses observed in tests are
progressive `fmp4` URLs. If a live account returns `.m3u8`, capture that exact
response and add a playlist/segment smoke test before changing proxy logic.

### State and cleanup

Quark and UC require personal-drive state for some paths. Cleanup must protect
every active stream and must not turn a successful save into a missing-file
error. OpenDrive intentionally differs from older CatVod cleanup code:

- Quark reuses source-fid → saved-fid entries and reclaims only the dedicated
  cache folder at its bounded threshold.
- UC preserves its reviewed cleanup contract and can refresh an expired signed
  download once.

These are lifecycle requirements, not general HTTP behaviour.

## Test coverage and remaining work

Existing automated evidence includes:

- route switching: CatVod → OpenDrive → CatVod;
- provider URI persistence and route-specific history;
- Quark original media envelope and parallel transfer tracing;
- UC signed-cookie/download-header contracts;
- Aliyun bounded parallel Range continuity;
- Baidu preview/original token and header parsing;
- share-domain rejection for lookalike and credential-bearing URLs;
- 123 domain and Android download-header contracts.

Remaining high-value fixtures:

1. A public China Mobile 139 share for a new provider implementation.
2. A Quark live transcode URL that is confirmed to be HLS rather than fMP4.
3. Logged-in 123 and Tianyi account fixtures for account-root listing and
   original playback.
4. A Baidu share that forces the save-to-own-drive fallback.
5. Per-provider seek and sustained-throughput thresholds, not just successful
   player initialization.

## Change rule

For every future CatVod-derived change, record:

- repository and exact commit or runtime jar hash;
- source file and method;
- exact request order, endpoint, headers and response fields;
- whether the difference is compatible, intentional, missing or unverified;
- a deterministic test and, for stateful playback, an on-device smoke result.

Do not promote an educated guess into provider code.
