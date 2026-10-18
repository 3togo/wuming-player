#!/usr/bin/env python3
"""Run each public cloud route independently on an explicitly selected Android device."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
APP = 'com.fongmi.android.tv'
TEST = APP + '.cloud.opendrive.OpenDriveLiveProviderInstrumentedTest#bundledPublicSharesCanStreamOriginalAndSmartRoutes'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicitly authorized adb serial')
    parser.add_argument('--provider', choices=['all', 'uc', 'quark', 'alipan', 'baidu'], default='all')
    parser.add_argument('--route', choices=['both', 'original', 'smart'], default='both')
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('--http-audit', action='store_true')
    parser.add_argument('--output-dir', type=Path, default=Path('/tmp/cloud-stream-audit'))
    args = parser.parse_args()
    if args.timeout < 10:
        parser.error('--timeout must be at least 10 seconds')
    base = ['adb', '-s', args.serial]

    def adb(*values):
        return subprocess.run(base + list(values), capture_output=True, text=True, timeout=15)

    inventory = json.loads((ROOT / 'test-fixtures/cloud-drive/public-share-candidates.json').read_text())
    fixtures = [x for x in inventory['shares'] if x.get('preferredForLiveAudit')
                and args.provider in ('all', x['provider'])]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    results = []
    for fixture in fixtures:
        for route in [args.route]:
            provider = fixture['provider']
            command = base + ['shell', 'am', 'instrument', '-w', '-r',
                              '-e', 'fixture_provider', provider, '-e', 'fixture_route', route,
                              '-e', 'fixture_url', fixture['url'], '-e', 'fixture_file', fixture['fileName'],
                              '-e', 'class', TEST]
            if args.http_audit:
                command += ['-e', 'http_audit', 'true']
            command += [APP + '.test/androidx.test.runner.AndroidJUnitRunner']
            print(f'START {provider} {route}', flush=True)
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            started = time.monotonic()
            foregrounded = False
            expired = False
            try:
                while process.poll() is None:
                    elapsed = time.monotonic() - started
                    if elapsed >= 2 and not foregrounded:
                        # The vivo phone froze instrumentation even with the screen awake.
                        # Foreground the app normally; never disable the device's freezer globally.
                        adb('shell', 'am', 'start', '-n', APP + '/.ui.activity.HomeActivity')
                        foregrounded = True
                    if elapsed >= args.timeout:
                        expired = True
                        adb('shell', 'am', 'force-stop', APP)
                        break
                    time.sleep(.5)
                output, _ = process.communicate(timeout=15)
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=15)
            # Both modes share the disposable copy until BOTH results are recorded.
            # Immediate delete/re-save caused Quark to replay a completed task whose
            # destination had just been deleted. A failed original still runs smart.
            output = re.sub(r'https?://\S+', '[url]', output)
            (args.output_dir / f'{provider}-{route}.txt').write_text(output)
            cleanup_match = re.search(r'INSTRUMENTATION_STATUS: fixtureCleanup=(\S+)', output)
            cleanup = cleanup_match.group(1) if cleanup_match else 'unknown'
            invocation_passed = 'OK (1 test)' in output
            for checked_route in (['original', 'smart'] if route == 'both' else [route]):
                key = 'fixture' + checked_route.capitalize()
                passed = f'INSTRUMENTATION_STATUS: {key}=passed' in output
                status = 'timeout' if expired else 'passed' if passed else 'failed'
                results.append({'provider': provider, 'route': checked_route, 'status': status,
                                'shareUrl': fixture['url'], 'fileName': fixture['fileName'],
                                'cleanupStatus': cleanup, 'invocationPassed': invocation_passed})
                print(f'{provider} {checked_route}: {status}', flush=True)
    (args.output_dir / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
    return 0 if results and all(x['status'] == 'passed' and x['cleanupStatus'] != 'failed'
                                  and x['invocationPassed'] for x in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
