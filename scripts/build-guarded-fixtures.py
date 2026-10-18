#!/usr/bin/env python3
"""Rebuild project-authored DEX test fixtures with javac and Android D8."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    sdk = Path(os.environ['ANDROID_HOME'])
    tools = sdk / 'build-tools/36.0.0/lib/d8.jar'
    android = next((sdk / 'platforms' / api / 'android.jar' for api in ['android-37', 'android-37.0']
                    if (sdk / 'platforms' / api / 'android.jar').is_file()), None)
    if android is None: raise SystemExit('Android platform 37 is required')
    sources = ROOT / 'test-fixtures/guarded-providers'
    version = subprocess.check_output(['java', '-cp', str(tools), 'com.android.tools.r8.D8', '--version'], text=True).strip()
    artifacts = []
    with tempfile.TemporaryDirectory(prefix='guarded-fixtures-') as scratch:
        base = Path(scratch)
        classes = base / 'classes'
        classes.mkdir()
        subprocess.run(['javac', '--release', '8', '-g:source,lines', '-classpath', str(android),
                        '-d', str(classes), *map(str, sorted(sources.glob('**/*.java')))], check=True)
        for name in ['GmsProbe', 'WebViewProbe']:
            out = base / name
            out.mkdir()
            inputs = sorted((classes / 'com/github/catvod/spider').glob(name + '*.class'))
            subprocess.run(['java', '-cp', str(tools), 'com.android.tools.r8.D8', '--release', '--min-api', '24',
                            '--lib', str(android), '--classpath', str(classes), '--output', str(out),
                            *map(str, inputs)], check=True)
            dest = ROOT / f'desktop/src/test/resources/guarded/{name}-dexed.jar'
            info = zipfile.ZipInfo('classes.dex', date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with zipfile.ZipFile(dest, 'w') as archive:
                archive.writestr(info, (out / 'classes.dex').read_bytes())
            artifacts.append({'path': dest.relative_to(ROOT).as_posix(),
                              'sha256': hashlib.sha256(dest.read_bytes()).hexdigest()})
    report = {'origin': 'Project-authored Java fixtures and compile-only Spider stub; root GPL-3.0 license',
              'source': 'test-fixtures/guarded-providers', 'recipe': 'scripts/build-guarded-fixtures.py',
              'd8': version, 'androidPlatform': 37, 'minApi': 24, 'javacRelease': 8, 'artifacts': artifacts}
    (sources / 'provenance.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Rebuilt two synthetic fixtures; compile-only stubs and framework classes excluded.')

if __name__ == '__main__':
    main()
