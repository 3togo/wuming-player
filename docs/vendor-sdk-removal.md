# Public SDK removal — 2026-10-18

At the maintainer's request, ForceTech, JianPian, Thunder and TVBus are removed
rather than shipped with unresolved redistribution permissions.

Removed: the four AARs, frozen ForceTech/Thunder/TVBus source snapshots and bundled
native files; the JianPian JNI facade/extraction task; all four extractor adapters
and registrations; Thunder torrent/episode expansion and task helpers; dedicated
shared SDK link/session/cache helpers; Android link handlers and SDK keep rules;
TVBus's remote native download and local authentication response endpoint; and
TVBus-only live configuration fields and remote lookup code.

Android and desktop share `source-core`'s RemovedSdkProtocols policy. SDK-dependent
p2p–p9p, mitv, tvbus, jianpian/tvbox-xg/xg/xgplay, FTP, magnet, ed2k, thunder and torrent
inputs report unsupported playback before a download or player launch. Ordinary
HTTP(S) MP4/HLS/DASH, RTSP/RTMP and local media paths remain outside this rejection.
HTTP query text containing a protocol name is not treated as that protocol.
A catalog may still list an unavailable protocol; selecting it reports unsupported
playback. No SDK is fetched to enable it.

App cache clearing still uses the existing symlink-safe CacheTree utility. Engine
ownership/deferred-clear handling is removed because those engines no longer run.

`scripts/check-removed-sdks.py` checks the current source/AAR set and scans supplied
APKs for removed JNI filenames and DEX package prefixes. The manual release gate
runs it before building and against every final APK. Focused shared-policy and
desktop tests cover rejection before launch and preservation of ordinary media.
Tests solely exercising deleted SDK behavior are removed with that behavior.

DFM and Hook remain, together with two test-only probe JARs. Their recorded provenance
limits remain in the vendor inventory. This removal does not certify the full
transitive dependency closure, provider artwork, wallpapers or catalog contents.
TV playback, casting and D-pad checks are deferred by the maintainer.

Prior APKs and Git commits may still contain the removed SDKs. Use a fresh source
snapshot excluding Git history for any eventual public repository export; a working
tree deletion does not remove historical blobs. No public repository is configured
and no public upload is performed by this change.

## Rebuild evidence

Candidate `1be12f71c228182b84fd771af2e7a6da1c4c99d6` passed the full manual release
gate on Ubuntu 26.10 (Stonking Stingray, development branch), x86_64, OpenJDK
21.0.12.1: 210 Python tests; 8,139 JUnit executions with no failures/errors and
16 skipped; three release lint checks with no errors; and three optimized signed
APKs. Existing lint warnings remain. All APK signatures match the retained public
production certificate. Each final APK passed removed-SDK binary/package scans,
and its merged manifest contained none of the removed SDK components/link handlers.
Known personal home-prefix checks also found no matches within their recorded scope.

The earlier attempt stopped on a gate test fixture which did not copy the new
scanner. The fixture now exercises the actual scanner and proves that a removed
native library causes failure without a success receipt. The full gate then passed.

See [checksums, sizes and exact results](public-sdk-removal-validation.json).
These are offline build checks; no TV or phone runtime check was performed on
these optimized APKs. Subsequent documentation-only commits record this evidence.
