#!/usr/bin/env python3
"""Offline host comparison of Git baseline and current casting segment staging."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
BASELINE = '0c0bcf9db9c4192ada0d37480d8a01d0618cadae'
SOURCE = 'app/src/main/java/com/fongmi/android/tv/server/process/CastMediaRelay.java'


def run(command, **kwargs):
    try:
        return subprocess.run(command, cwd=ROOT, check=True, text=True, **kwargs)
    except subprocess.CalledProcessError as error:
        print(error.stdout or "", file=sys.stderr)
        print(error.stderr or "", file=sys.stderr)
        raise RuntimeError(f"{command[0]} failed with exit code {error.returncode}") from None


def current_source(path):
    """Historical baseline paths stay fixed; fingerprint the current production implementation."""
    name = path.replace('com/fongmi/', 'com/wuming/')
    candidate = ROOT / name
    if candidate.is_file():
        return candidate
    if candidate.suffix == '.java' and candidate.with_suffix('.kt').is_file():
        return candidate.with_suffix('.kt')
    raise FileNotFoundError(f'Current profiling input missing: {name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--forks', type=int, default=2)
    parser.add_argument('--samples', type=int, default=3)
    parser.add_argument('--warmups', type=int, default=1)
    parser.add_argument('--quick', action='store_true')
    parser.add_argument('--algorithms', nargs='+', choices=['legacy', 'bounded', 'fair'],
                        default=['bounded', 'fair'])
    args = parser.parse_args()
    if len(set(args.algorithms)) != len(args.algorithms):
        parser.error('algorithms must be distinct')
    if min(args.forks, args.samples, args.warmups) < 1:
        parser.error('forks, samples and warmups must be positive')
    report = ROOT / 'app/build/reports/cast-profile'
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    output = report / stamp
    output.mkdir(parents=True)
    with (output / 'build.log').open('w') as log:
        run(['./gradlew', '--offline', '--no-configuration-cache', '--max-workers=2',
             '-I', 'scripts/cast-profile/runtime.gradle', ':app:writeCastProfileRuntime', '--console=plain'],
            stdout=log, stderr=subprocess.STDOUT)
    runtime = json.loads((report / 'runtime.json').read_text())
    java = Path(runtime['javaHome']) / 'bin'
    source = run(['git', 'show', f'{BASELINE}:{SOURCE}'], capture_output=True).stdout
    start = source.index('    private static void downloadSegments(')
    end = source.index('\n    /**', start)
    methods = source[start:end]
    if 'private static byte[] downloadSegmentBytes' not in methods:
        raise RuntimeError('Baseline extraction incomplete')
    generated = output / 'LegacyCastStage.java'
    generated.write_text('''package com.fongmi.android.tv.server.process;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import okhttp3.*;
final class LegacyCastStage {
    private static IntFunction<Call> calls;
    static File run(File directory, long[] sizes, IntFunction<Call> factory) throws IOException {
        calls = factory; // Caller holds the original global staging lock.
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) segments.add(Integer.toString(i));
        File out = File.createTempFile("legacy-", ".ts", directory);
        downloadSegments(new AggregatedMedia(segments, Arrays.stream(sizes).sum()), Map.of(), out);
        return out;
    }
    private record AggregatedMedia(List<String> segments, long total) {}
    private static Response fetchUpstream(String url, Map<String,String> headers) throws IOException {
        return calls.apply(Integer.parseInt(url)).execute();
    }
''' + methods + '\n}\n')
    fixed = ROOT / 'scripts/cast-profile/fixtures/FixedCastSegmentDownloader.java'
    fixed_hash = hashlib.sha256(fixed.read_text().replace('FixedCastSegmentDownloader', 'CastSegmentDownloader').encode()).hexdigest()
    if fixed_hash != '7d6976ba8d9f728973b921885205ee0a54bcef6a881492fc24144f8625d2ccbd':
        raise RuntimeError('Fixed-worker baseline changed')
    classes = output / 'classes'; classes.mkdir()
    harness = ROOT / 'scripts/cast-profile/CastStageBenchmark.java'
    run([str(java / 'javac'), '-cp', runtime['classpath'], '-d', str(classes), str(generated), str(harness),
         str(ROOT / 'scripts/cast-profile/fixtures/FixedCastSegmentDownloader.java')],
        capture_output=True)
    paths = [SOURCE, 'app/src/main/java/com/fongmi/android/tv/server/process/CastSegmentDownloader.java',
             'app/src/main/java/com/fongmi/android/tv/server/process/CastRemuxCache.java',
             'app/src/main/java/com/wuming/android/tv/server/process/CastRemuxCacheEngine.kt',
             'app/src/main/java/com/fongmi/android/tv/server/process/CastWorkScheduler.java',
             'app/src/main/java/com/fongmi/android/tv/server/process/CastCallScope.java',
             'scripts/cast-profile/CastStageBenchmark.java', 'scripts/cast-profile/run.py',
             'scripts/cast-profile/runtime.gradle',
             'scripts/cast-profile/fixtures/FixedCastSegmentDownloader.java']
    metadata = dict(baseline=BASELINE, fixedBaselineSourceSha256=fixed_hash, baselineMethodsSha256=hashlib.sha256(methods.encode()).hexdigest(),
                    head=run(['git', 'rev-parse', 'HEAD'], capture_output=True).stdout.strip(),
                    sourceSha256={str(current_source(p).relative_to(ROOT)): hashlib.sha256(current_source(p).read_bytes()).hexdigest() for p in paths},
                    host=platform.platform(), logicalCpus=os.cpu_count(), arguments=vars(args), runtime=runtime,
                    workingTreeStatus=run(['git', 'status', '--short'], capture_output=True).stdout,
                    method='Fresh JVM per case/algorithm/fork, -Xms64m -Xmx512m, common pool parallelism fixed at 4; '
                           'warmups excluded, GC before sample; 1ms heap/logical-disk sampler; '
                           'live-thread allocation deltas include harness/transport/sampler; generated streams, no sockets/remux')
    (output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    cases = [('8MiB', 32, 262144, 0, 1)]
    if not args.quick:
        cases += [('32MiB', 128, 262144, 0, 1), ('8MiB-delay', 32, 262144, 20, 1),
                  ('two-8MiB-delay', 32, 262144, 20, 2)]
    rows = []
    for case, segments, size, delay, jobs in cases:
        expected = hashlib.sha256()
        for i in range(segments):
            expected.update(bytes([i * 31 % 256]) * size)
        for fork in range(args.forks):
            for algorithm in (args.algorithms if fork % 2 == 0 else list(reversed(args.algorithms))):
                stage = output / f'{case}-{fork}-{algorithm}'; stage.mkdir()
                print(f'{case} fork={fork + 1}/{args.forks} {algorithm}', flush=True)
                process = run([str(java / 'java'), '-Xms64m', '-Xmx512m',
                               '-Djava.util.concurrent.ForkJoinPool.common.parallelism=4', '-cp',
                               str(classes) + os.pathsep + runtime['classpath'],
                               'com.fongmi.android.tv.server.process.CastStageBenchmark', algorithm, str(stage),
                               str(segments), str(size), str(delay), str(jobs), str(args.warmups), str(args.samples)],
                              capture_output=True, timeout=120)
                (output / f'{case}-{fork}-{algorithm}.log').write_text(process.stdout + process.stderr)
                samples = [json.loads(line) for line in process.stdout.splitlines() if line.startswith('{')]
                if len(samples) != args.samples:
                    raise RuntimeError('Incorrect sample count')
                for sample in samples:
                    if sample['fingerprint'] != expected.hexdigest() or sample['outputBytes'] != segments * size * jobs:
                        raise RuntimeError('Output mismatch against independent fixture digest')
                    if sample['allocatedBytes'] < 0:
                        raise RuntimeError('Allocation accounting invalidated by thread lifetime')
                    sample.update(case=case, fork=fork, jobs=jobs, segmentDelayMs=delay)
                if any(stage.iterdir()):
                    raise RuntimeError('Staging output not cleaned up')
                rows.extend(samples)
                (output / 'samples.json').write_text(json.dumps(rows, indent=2) + '\n')
    summary = []
    for case, *_ in cases:
        for algorithm in args.algorithms:
            values = [r for r in rows if (r['case'], r['algorithm']) == (case, algorithm)]
            row = dict(case=case, algorithm=algorithm, samples=len(values))
            for metric in ['elapsedMs', 'allocatedBytes', 'sampledPeakHeapBytes', 'sampledPeakDiskBytes', 'peakCalls']:
                nums = [r[metric] for r in values]
                row[metric] = dict(median=statistics.median(nums), min=min(nums), max=max(nums))
            summary.append(row)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f'Report: {output}', flush=True)


if __name__ == '__main__':
    main()
