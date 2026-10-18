#!/usr/bin/env python3
"""Combine completed analyzer outputs and Android meminfo snapshots as JSON."""
import argparse
import csv
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
parser.add_argument('labels', nargs='+', help='Explicit completed run labels to include')
args = parser.parse_args()
rows = []
for label in args.labels:
    for stage in ['baseline', 'active', 'cycle5', 'cycle10']:
        prefix = args.directory / (label + '-' + stage)
        summary_file = Path(str(prefix) + '-summary.txt')
        if not summary_file.exists():
            continue
        summary = summary_file.read_text()
        if 'application_leak_groups=' not in summary:
            raise RuntimeError('Incomplete analysis: ' + str(summary_file))
        row = {'run': label, 'stage': stage, **{
            k: int(v) for k, v in re.findall(r'(\w+)=(\d+)', summary.split('analysis_ms=')[0])}}
        mem = Path(str(prefix) + '-meminfo.txt').read_text()
        for key, regex in [
            ('total_pss_kb', r'(?:TOTAL PSS|TOTAL):\s+(\d+)'),
            ('native_pss_kb', r'Native Heap:\s+(\d+)'),
            ('java_pss_kb', r'Java Heap:\s+(\d+)'),
            ('graphics_pss_kb', r'Graphics:\s+(\d+)'),
            ('views', r'Views:\s+(\d+)'),
        ]:
            match = re.search(regex, mem)
            row[key] = int(match[1]) if match else None
        with Path(str(prefix) + '-classes.tsv').open() as stream:
            classes = {r['class']: int(r['instances']) for r in csv.DictReader(stream, delimiter='\t')}
        row['key_class_instances'] = {name: classes.get(name, 0) for name in [
            'com.fongmi.android.tv.service.PlaybackService',
            'com.fongmi.android.tv.player.PlayerManager',
            'androidx.media3.exoplayer.ExoPlayerImpl',
            'com.fongmi.android.tv.service.PlaybackService$LocalBinder',
            'com.fongmi.android.tv.ui.activity.VideoActivity',
            'android.view.SurfaceView',
        ]}
        rows.append(row)
print(json.dumps(rows, indent=2))
