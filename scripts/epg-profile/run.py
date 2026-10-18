#!/usr/bin/env python3
"""Opt-in, offline host benchmark of actual legacy/current Android EPG importers."""
import argparse
import datetime
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASELINE = '0c0bcf9db'  # Before the streaming importer; resolved and recorded in each report.
SOURCE = 'app/src/main/java/com/fongmi/android/tv/api/parser/EpgParser.java'


def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--forks', type=int, default=2)
    parser.add_argument('--warmups', type=int, default=2)
    parser.add_argument('--samples', type=int, default=3)
    parser.add_argument('--quick', action='store_true', help='Only the small fixture, for harness validation')
    args = parser.parse_args()
    if min(args.forks, args.samples) < 1 or args.warmups < 0:
        parser.error('forks/samples must be positive and warmups nonnegative')
    directory = ROOT / 'app/build/reports/epg-profile'
    directory.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    output = directory / stamp
    output.mkdir()
    with (output / 'build.log').open('w') as log:
        run(['./gradlew', '--offline', '--no-configuration-cache', '--max-workers=2',
             '-I', 'scripts/epg-profile/runtime.gradle', ':app:writeEpgProfileRuntime', '--console=plain'],
            stdout=log, stderr=subprocess.STDOUT)
    runtime = json.loads((directory / 'runtime.json').read_text())
    java_home = Path(runtime['javaHome'])
    baseline_ref = run(['git', 'rev-parse', BASELINE], capture_output=True).stdout.strip()
    baseline = run(['git', 'show', f'{baseline_ref}:{SOURCE}'], capture_output=True).stdout
    generated = output / 'LegacyEpgParser.java'
    generated.write_text(re.sub(r'\bEpgParser\b', 'LegacyEpgParser', baseline))
    classes = output / 'classes'
    classes.mkdir()
    run([str(java_home / 'bin/javac'), '-cp', runtime['classpath'], '-d', str(classes),
         str(generated), str(ROOT / 'scripts/epg-profile/EpgImportBenchmark.java')], capture_output=True)
    tracked = [SOURCE, 'app/src/main/java/com/fongmi/android/tv/utils/Formatters.java',
               'libraries/epg-core/src/main/java/com/fongmi/tv/core/epg/XmlTvStreamReader.java',
               'scripts/epg-profile/EpgImportBenchmark.java', 'scripts/epg-profile/run.py']
    metadata = {
        'baselineCommit': baseline_ref,
        'baselineSourceSha256': hashlib.sha256(baseline.encode()).hexdigest(),
        'head': run(['git', 'rev-parse', 'HEAD'], capture_output=True).stdout.strip(),
        'workingTreeStatus': run(['git', 'status', '--short'], capture_output=True).stdout,
        'sourceSha256': {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in tracked},
        'host': platform.platform(), 'javaHome': str(java_home), 'arguments': vars(args),
        'method': 'Fresh JVM per algorithm/fork/case, equal -Xms64m -Xmx512m, warmup excluded; GC before each import',
    }
    (output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    cases = [('small', 1000, 10)]
    if not args.quick:
        cases += [('large-sparse', 100000, 100), ('large-dense', 100000, 1)]
    rows = []
    for case, count, every in cases:
        xml = output / f'{case}.xml'
        with xml.open('w') as stream:
            stream.write('<?xml version="1.0" encoding="UTF-8"?><tv>')
            for i in range(count):
                channel = 'local' if i % every == 0 else 'other'
                stream.write(f'<programme channel="{channel}" start="20260911120000 +0800" stop="20260911133000 +0800">'
                             f'<title>Programme {i} 新闻</title><desc>{"Ignored description. " * 4}</desc></programme>')
            stream.write('<channel id="local"><display-name>News</display-name><icon src="fixture-logo"/></channel></tv>')
        zipped = output / f'{case}.gz'
        with xml.open('rb') as source, gzip.open(zipped, 'wb') as target:
            import shutil
            shutil.copyfileobj(source, target)
        for encoding in ['xml', 'gzip-refresh', 'gzip-cache']:
            for fork in range(args.forks):
                # Alternate order to reduce systematic host-temperature/order bias.
                algorithms = ['legacy', 'stream'] if fork % 2 == 0 else ['stream', 'legacy']
                for algorithm in algorithms:
                    fixture = xml if encoding == 'xml' else zipped
                    print(f'{case} {encoding} fork={fork + 1}/{args.forks} {algorithm}', flush=True)
                    process = run([str(java_home / 'bin/java'), '-Xms64m', '-Xmx512m', '-cp',
                                   str(classes) + os.pathsep + runtime['classpath'],
                                   'com.fongmi.android.tv.api.parser.EpgImportBenchmark', algorithm, str(fixture),
                                   encoding, str(args.warmups), str(args.samples)], capture_output=True)
                    (output / f'{case}-{encoding}-{fork}-{algorithm}.log').write_text(process.stdout + process.stderr)
                    samples = [json.loads(line) for line in process.stdout.splitlines() if line.startswith('{')]
                    if len(samples) != args.samples:
                        raise RuntimeError(f'Expected {args.samples} samples, got {len(samples)}')
                    for sample in samples:
                        sample.update(case=case, fork=fork, inputBytes=fixture.stat().st_size, sourceProgrammes=count)
                        if sample['retainedProgrammes'] != (count + every - 1) // every:
                            raise RuntimeError('Selected programme count mismatch')
                    rows.extend(samples)
                    (output / 'samples.json').write_text(json.dumps(rows, indent=2) + '\n')
    for case, _, _ in cases:
        fingerprints = {row['fingerprint'] for row in rows if row['case'] == case}
        if len(fingerprints) != 1:
            raise RuntimeError(f'Output mismatch in {case}')
    summary = []
    for case, _, _ in cases:
        for encoding in ['xml', 'gzip-refresh', 'gzip-cache']:
            for algorithm in ['legacy', 'stream']:
                values = [row for row in rows if (row['case'], row['encoding'], row['algorithm']) == (case, encoding, algorithm)]
                item = dict(case=case, encoding=encoding, algorithm=algorithm, samples=len(values))
                for key in ['elapsedMs', 'allocatedBytes', 'sampledPeakHeapBytes', 'postGcHeapBytes']:
                    measurements = [v[key] for v in values if v.get(key) is not None]
                    item[key] = None if not measurements else {
                        'median': statistics.median(measurements), 'min': min(measurements), 'max': max(measurements)}
                summary.append(item)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f'Report: {output}', flush=True)


if __name__ == '__main__':
    main()
