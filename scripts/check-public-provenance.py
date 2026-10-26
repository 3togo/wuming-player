#!/usr/bin/env python3
"""Check the reviewed public snapshot's bundled files and source-built vendor patches."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def check(root, private=False):
    marker = root / 'gradle/private-distribution.json'
    if marker.exists():
        if not private:
            raise ValueError('Private SDK extensions cannot pass the public distribution gate; use --private for private-only validation')
        import runpy
        runpy.run_path(str(root / 'scripts/check-private-distribution.py'))['check'](root)
    elif private:
        raise ValueError('Private validation requires the private distribution marker')
    report = json.loads((root / 'docs/public-third-party-inventory.json').read_text())
    if not report.get('stableReleaseReviewComplete') or report.get('releaseBlockers'):
        raise ValueError('Public provenance review has unresolved entries')
    manifest = json.loads((root / 'docs/public-provenance-manifest.json').read_text())
    composition = root / '.private-composition.json'
    if private and composition.is_file():
        # The private launcher records the exact public+extension inputs, including
        # ignored notices. Verify their bytes rather than inspecting the parent repo.
        records = json.loads(composition.read_text())['files']
        for name, expected in records.items():
            path = (root / name).resolve()
            if not path.is_relative_to(root.resolve()) or not path.is_file():
                raise ValueError('Missing composed input: ' + name)
            if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError('Composed input changed: ' + name)
        tracked = list(records)
    else:
        tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')[:-1]
    binary = {p for p in tracked if Path(p).suffix.lower() in
              {'.jar', '.aar', '.so', '.dex', '.mp4', '.png', '.webp', '.jpg', '.jpeg', '.ico', '.ttf', '.woff', '.woff2', '.ts', '.m4s'}}
    reviewed = {entry['path'] for entry in manifest['artifacts']}
    if binary != reviewed:
        raise ValueError('Bundled artifact inventory differs: ' + ', '.join(sorted(binary ^ reviewed)))
    for entry in manifest['artifacts']:
        path = (root / entry['path']).resolve()
        if not path.is_relative_to(root.resolve()): raise ValueError('Artifact path escapes repository')
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Unreviewed artifact change: ' + entry['path'])
        if not entry.get('origin') or not entry.get('license'): raise ValueError('Missing origin/license: ' + entry['path'])
    vendor = json.loads((root / 'vendor/cling/upstream/inventory.json').read_text())
    for entry in vendor['files']:
        path = root / entry['path']
        expected = entry.get('modifiedSha256', entry['upstreamSha256'])
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Unrecorded vendor source modification: ' + entry['path'])
    for entry in vendor.get('addedFiles', []):
        if hashlib.sha256((root / entry['path']).read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Unrecorded vendor patch change: ' + entry['path'])
    for entry in manifest.get('supportingArtifacts', []):
        if entry['path'] not in tracked:
            raise ValueError('Provenance input missing from Git snapshot: ' + entry['path'])
        if hashlib.sha256((root / entry['path']).read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Provenance inputs changed: ' + entry['path'])
    for record in json.loads((root / 'third_party/corresponding-source/inventory.json').read_text()):
        if hashlib.sha256((root / record['path']).read_bytes()).hexdigest() != record['sha256']:
            raise ValueError('Corresponding source archive changed: ' + record['path'])
    js = json.loads((root / 'third_party/javascript/inventory.json').read_text())
    for entry in js['artifacts']:
        if hashlib.sha256((root / entry['path']).read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Rebuild JavaScript evidence: ' + entry['path'])
    maven = json.loads((root / 'docs/public-maven-inventory.json').read_text())
    if maven['unresolved']: raise ValueError('Unresolved Maven license entries')
    for record in maven['components']:
        if not record.get('selectedLicense'): raise ValueError('Missing dependency license')
        for entry in record['notices']:
            if hashlib.sha256((root / entry['file']).read_bytes()).hexdigest() != entry['sha256']:
                raise ValueError('Dependency notice changed: ' + entry['file'])
    print(f'PASS: {len(binary)} reviewed bundled artifacts and {len(vendor["files"])} pinned vendor source files')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private', action='store_true')
    args = parser.parse_args()
    try: check(ROOT, args.private)
    except (ValueError, KeyError, OSError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
