#!/usr/bin/env python3
"""Generate original test patterns/tones; no downloaded images, footage or music."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TARGETS = [
    ('libraries/playback-core/src/main/resources/renderer-probes/avc.mp4', 'libx264', 320, 180, 15, 15, True),
    ('libraries/playback-core/src/main/resources/renderer-probes/hevc.mp4', 'libx265', 320, 180, 15, 15, True),
    ('app/src/androidTest/assets/cloud-smoke.mp4', 'libx264', 160, 90, 24, 5, False),
    ('app/src/androidTest/assets/playback-diagnostics/reset.mp4', 'libx264', 640, 360, 25, 6, False),
]

def main():
    outputs = []
    for path, codec, width, height, fps, seconds, audio in TARGETS:
        command = ['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi',
                   '-i', f'testsrc2=size={width}x{height}:rate={fps}']
        if audio: command += ['-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100']
        command += ['-t', str(seconds), '-c:v', codec, '-preset', 'fast', '-threads', '2', '-pix_fmt', 'yuv420p']
        if codec == 'libx264':
            gop = fps if width == 640 else fps * 2
            command += ['-profile:v', 'baseline', '-bf', '0', '-sc_threshold', '0', '-g', str(gop), '-keyint_min', str(gop)]
            if width == 320: command += ['-b:v', '160k']
            if width == 640: command += ['-level:v', '3.0']
        if codec == 'libx265': command += ['-x265-params', 'pools=2:frame-threads=2:log-level=error', '-tag:v', 'hvc1', '-b:v', '130k']
        if audio: command += ['-c:a', 'aac', '-ar', '44100', '-b:a', '48k']
        command += ['-map_metadata', '-1', '-movflags', '+faststart', str(ROOT / path)]
        subprocess.run(command, check=True)
        outputs.append({'path': path, 'sha256': hashlib.sha256((ROOT / path).read_bytes()).hexdigest(),
                        'videoEncoder': codec, 'width': width, 'height': height, 'fps': fps,
                        'profile': 'Constrained Baseline' if codec == 'libx264' else 'Main',
                        'durationSeconds': seconds, 'audioSampleRate': 44100 if audio else None, 'audio': '440 Hz sine / AAC' if audio else 'none'})
    checksums = ROOT / 'app/src/androidTest/assets/playback-diagnostics/SHA256SUMS'
    checksums.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n'
                        for p in sorted(checksums.parent.iterdir()) if p.suffix in ['.ts', '.mp4', '.m3u8']))
    report = {'origin': 'Project-generated mathematical test patterns and tones; no third-party media inputs',
              'license': 'GPL-3.0 under root project license', 'recipe': 'scripts/generate-synthetic-media.py',
              'ffmpeg': subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0], 'artifacts': outputs}
    retained = []
    for directory in ['libraries/playback-core/src/main/resources/renderer-probes',
                      'app/src/androidTest/assets/cast-remux', 'app/src/androidTest/assets/playback-diagnostics']:
        for path in sorted((ROOT / directory).iterdir()):
            if path.suffix in ['.ts', '.m4s']:
                retained.append({'path': path.relative_to(ROOT).as_posix(),
                                 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                                 'origin': 'Existing project-authored synthetic FFmpeg fixture; retained README recipe'})
    report['retainedSegmentedFixtures'] = retained
    (ROOT / 'test-fixtures/synthetic-media-provenance.json').write_text(json.dumps(report, indent=2) + '\n')

if __name__ == '__main__':
    main()
