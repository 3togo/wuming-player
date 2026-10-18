# Synthetic playback diagnostics fixture

Generated locally with FFmpeg testsrc2; no provider content. Six seconds of
640×360, 25fps, baseline H.264, yuv420p, no audio. One-second independently
seekable HLS segments permit deterministic network starvation. reset.mp4 is generated from the same mathematical sequence with the retained
`scripts/generate-synthetic-media.py` baseline-profile recipe. The larger frame size avoids a measured
Qualcomm hardware-decoder initialization failure with the older 160×96 fixture.

Generation:
```sh
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=25 -t 6 -an -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -g 25 -keyint_min 25 -sc_threshold 0 -f hls -hls_time 1 -hls_list_size 0 -hls_segment_filename segment-%02d.ts fixture.m3u8
ffmpeg -i fixture.m3u8 -c copy -movflags +faststart reset.mp4
```
