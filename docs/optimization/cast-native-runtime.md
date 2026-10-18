# Casting Android staging/remux regression

Prepared 2026-09-11. This adds an Android correctness check for the managed staging
pipeline feeding the platform MediaExtractor/MediaMuxer path. No production code
is changed, no cast renderer is contacted, and it is not a timing benchmark.

## Fixture and checks

The committed fixture contains three generated one-second MPEG-TS segments:
H.264 160×96 at 10 fps, AAC mono 48 kHz. Host ffprobe confirms 30 video packets and
142 audio packets in the concatenation. The video pattern and sine audio are
synthetic; no provider or user media is included. Asset hashes, FFmpeg version and
regeneration command are in `app/src/androidTest/assets/cast-remux/`.

`CastRemuxInstrumentedTest` exercises:

- Three loopback HTTP requests through production upstream-call/header handling
  and the shared bounded downloader, with a delayed first segment.
- Exact concatenated bytes, preserved segment ordering and expected request count.
- The production private `remuxToMp4` path via reflection, without widening its API.
- Two tracks, unchanged extracted sample counts, expected H.264/AAC formats,
  dimensions, strictly increasing per-track timestamps and uncollapsed timelines.
- Malformed TS rejection through the same platform path.

Temporary files live in a newly created test-owned cache directory and are removed
in `finally`. Other cache files and provider/account state are untouched.

## Execution and limitations

Use only the authorized serial, awake/foreground control and installation procedure
from [instrumentation lessons](../android-instrumentation-lessons.md). The app APK
is unchanged; install the rebuilt matching `無名(test runner)` APK. Select these two
methods explicitly. Record full results and runner/fixture hashes before marking
this native-runtime slice verified.

This does not cover large-input RSS/disk high-water, cancellation during muxing,
encrypted inputs, discontinuity resets, B-frames, extra tracks, changing codecs,
playlist rewriting, full relay Range/HEAD responses, decoding or renderer playback.
Those gates remain open. A passing remux test alone cannot justify a new native
library or claim user-visible playback improvement.

## Current validation status

Mobile ARM64 runner assembly and leanback ARM64 instrumentation compilation passed.
Host fixture packet counts were verified with ffprobe. Device installation was
attempted on the authorized phone, but it was at the fingerprint lock screen and
the installer returned `INSTALL_FAILED_ABORTED`. Neither casting test ran. The
temporary stay-awake setting was restored to its original value (`0`). Device
execution is pending an unlocked phone; do not count compilation as a device pass.
