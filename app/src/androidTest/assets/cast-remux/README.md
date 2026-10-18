# Native cast-remux fixture

Three MPEG-TS segments (~1 s each), generated from synthetic video and sine audio.
H.264 160×96 at 10 fps, no B-frames, one-second keyframes; AAC mono 48 kHz.
No user/provider content. `fixture.json` records tool version, sizes and hashes.

The three files are a single continuous 3-second TS split at 188-byte packet
boundaries, so the test's byte re-concatenation reproduces one valid stream. Do
NOT generate them with the `-f segment` muxer: it writes an independent
PAT/PMT/PCR per segment, and some vendor `MediaExtractor`s (measured on Vivo
Android 15) drop the audio track past the first boundary, collapsing the remuxed
timeline.

Recreate in an empty output directory with:

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=160x96:rate=10 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 3 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 10 -keyint_min 10 \
  -sc_threshold 0 -bf 0 -c:a aac -b:a 32k \
  -f mpegts full.ts
# split full.ts into three 188-byte-aligned pieces (packet counts 158/158/157):
python3 - <<'PY'
import os
PKT=188; data=open('full.ts','rb').read(); n=len(data)//PKT
sizes=[n//3+(1 if i<n%3 else 0) for i in range(3)]; off=0
for i,c in enumerate(sizes):
    open(f'segment-{i:02d}.ts','wb').write(data[off:off+c*PKT]); off+=c*PKT
assert off==len(data)
PY
# then record sizes + sha256 of each segment in fixture.json.
```

Output hashes can vary with encoder versions. Do not silently replace the fixture
without regenerating metadata and rerunning the native test. This fixture does
not cover encryption, discontinuity resets, B-frames, extra tracks, large files,
codec changes, HLS playlist rewriting, or renderer compatibility.
