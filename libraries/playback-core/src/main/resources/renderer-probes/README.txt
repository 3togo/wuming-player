Project-authored synthetic fixtures generated locally with FFmpeg; no third-party media.
15 seconds, 320x180, 15 fps moving testsrc2 pattern and 440 Hz sine tone.
Current MP4 recipe: scripts/generate-synthetic-media.py, root GPL-3.0 license.
AVC: libx264 constrained baseline, yuv420p, 160 kbps, GOP 30; AAC mono 44.1 kHz, 48 kbps.
MP4 uses faststart. HEVC: libx265 Main, 130 kbps, hvc1 MP4 tag, same AAC settings.
The retained project-authored HLS/DASH segments use these same small test patterns.
HLS: remux AVC/AAC to 2-second MPEG-TS segments (hls-%02d.ts).
DASH: remux AVC/AAC to 2-second fragmented MP4 segments, static MPD, two streams.
Original segment boundaries and content are preserved; hashes are recorded in
 test-fixtures/synthetic-media-provenance.json and docs/public-provenance-manifest.json.
These samples validate only these specific profiles and low resolution.
They do not prove 4K, all codec levels, multichannel audio, DRM, seeking or recasting.
