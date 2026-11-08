# Captured appended-ad regression

Source: 醉梦 第02集, 视界 / YZ有广, stream `19140_b37d4650`, captured
2026-11-04. The desktop relay and provider media playlists were byte-identical.
`episode.m3u8` preserves all 717 segment basenames, durations and tags, replacing
only the URL prefix with `https://media.example/zuimeng-02/`.

Direct source decoding visually confirmed ordinary credits at segment 700
(46:43.68), 708 and the final programme card in 712. Segments 713–716 are a
separate gambling advertisement, 47:33.68–47:50.146666 (16.466666 seconds).
The first 713 segments, including every ending-credit segment, must survive.

`probes.json` contains ffprobe video dimensions, raw start PTS and frame rates,
plus extracted SPS/PPS/AUD codec parameters for the downloaded segments. Codec
parameters contain no video pictures. They are test inputs, never a matching
signature: production code does not read this resource. The test-fixture helper
generates MPEG-TS PMT/PES packets around these parameters and timestamps, so CI
exercises the actual shared decoder without storing video frames or using live
network services. Segment 0 transport, needed only by compatibility preparers,
is synthetic; the automatic detector requests measured segments 697, 711–716.

The optional `TV_AD_TAIL_FIXTURE_DIR` test replays original downloaded segment
bytes from `segment-697.ts`, `segment-711.ts` through `segment-716.ts`. They
are retained locally under the ignored artifact directory
`artifacts/ad-filter-investigation/2026-11-04-desktop-tail/`, together with
the original playlists, ffprobe JSON, decoded stills and captured desktop log.
These files are not included in the test fixture or application packages.

The credits-only control cuts the playlist at the visually verified end of
programme segment 712, preserving ENDLIST. Additional controls introduce an
ordinary discontinuity in those real credits, or independently vary reset,
timing, segmentation and probe failures. They establish the tested safeguards;
they do not prove that every independently encoded credit sequence in other
sources can be distinguished using transport structure alone.
