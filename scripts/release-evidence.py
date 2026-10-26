#!/usr/bin/env python3
"""Local candidate evidence ledger. Never installs an APK or contacts a device/provider."""
import argparse
import datetime
import hashlib
import importlib.util
import json
import runpy
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CHECKS = {
    'verification-build': ['production-smoke', 'deep-smoke'],
    'exact-release': ['cold-launch', 'background-return', 'restoration', 'first-frame', 'progress', 'seek', 'tv-navigation'],
    'live-provider': ['login', 'playback', 'cleanup'],
}


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def atomic_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(data, stream, indent=2)
            stream.write('\n')
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def render(path, data):
    lines = ['# Release candidate evidence', '', f"Commit: `{data['commit']}`", '',
             'Offline acceptance: **passed** (release gate receipt). Runtime results apply only to the recorded artifact.', '',
             *[f"{variant}: certificate SHA-256 `{', '.join(apk['certificate_sha256'])}`" for variant, apk in data['artifacts'].items()], '',
             '| Variant / SHA-256 | Evidence level | Check | Status | Device | Evidence reference |',
             '| --- | --- | --- | --- | --- | --- |']
    for variant, apk in data['artifacts'].items():
        for level, checks in apk['checks'].items():
            for check, result in checks.items():
                def cell(value):
                    return str(value).replace('|', '\\|').replace('\n', ' ')
                lines.append('| ' + ' | '.join(map(cell, [variant + ' / ' + apk['sha256'], level, check,
                    result['status'], result.get('device', '—'), result.get('evidence', '—')])) + ' |')
    path.with_suffix('.md').write_text('\n'.join(lines) + '\n')


def initialize(report, tools=None):
    commit = (report / 'unit-tested-commit.txt').read_text().strip()
    if not re.fullmatch(r'[0-9a-f]{40,64}', commit):
        raise ValueError('Invalid release receipt')
    if tools is None:
        spec = importlib.util.spec_from_file_location('publishing', ROOT / 'scripts/publish-updates.py')
        publishing = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(publishing)
        tools = publishing.build_tools()
    artifacts = {}
    for line in (report / 'SHA256SUMS').read_text().splitlines():
        expected, name = line.split(maxsplit=1)
        apk = Path(name.strip())
        if digest(apk) != expected:
            raise ValueError('APK differs from release receipt')
        output = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
        certs = sorted(set(re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', output)))
        if not certs:
            raise ValueError('Missing signing certificate')
        badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
        package = re.search(r"package: name='([^']+)'", badging)
        expected_package = runpy.run_path(str(ROOT / 'scripts/edition-config.py'))['application_id'](ROOT)
        if not package or package[1] != expected_package or 'application-debuggable' in badging:
            raise ValueError('Expected a non-debuggable normal release APK')
        abi = re.search(r"native-code: (.+)", badging)
        variant = apk.parent.parent.name
        if variant in artifacts:
            raise ValueError('Duplicate variant')
        artifacts[variant] = {'sha256': expected, 'certificate_sha256': certs, 'apk': str(apk),
            'application_id': package[1], 'abi': abi[1] if abi else 'not-reported',
            'checks': {level: {check: {'status': 'not-tested'} for check in checks} for level, checks in CHECKS.items()}}
    if not artifacts:
        raise ValueError('No candidate APKs')
    identity = hashlib.sha256(json.dumps(artifacts, sort_keys=True).encode()).hexdigest()[:16]
    path = report / 'evidence' / f'{commit}-{identity}.json'
    if not path.exists():
        atomic_json(path, {'version': 1, 'commit': commit, 'artifacts': artifacts})
    render(path, json.loads(path.read_text()))
    (report / 'runtime-evidence.md').write_text(f'[Candidate evidence](evidence/{path.stem}.md)\n')
    return path


def record(path, variant, level, check, status, device, evidence, apk, tested_apk=None):
    data = json.loads(path.read_text())
    artifact = data['artifacts'][variant]
    if digest(apk) != artifact['sha256']:
        raise ValueError('Result does not match this candidate APK')
    if level not in CHECKS or check not in CHECKS[level]:
        raise ValueError('Unknown check')
    if status not in ('passed', 'failed', 'not-tested'):
        raise ValueError('Invalid status')
    if status != 'not-tested' and (not device or not evidence or not Path(evidence).is_file()):
        raise ValueError('Runtime evidence needs an explicitly authorized device and an existing evidence file')
    if status != 'not-tested' and level == 'verification-build' and not tested_apk:
        raise ValueError('Verification evidence must identify the separately tested verification APK')
    result = {'status': status, 'recorded_at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    if status != 'not-tested':
        result.update(device=device, evidence=str(evidence), evidence_sha256=digest(evidence),
                      tested_apk_sha256=digest(tested_apk) if tested_apk else artifact['sha256'])
    # Preserve previous observations rather than silently overwriting release history.
    previous = artifact['checks'][level][check]
    artifact.setdefault('history', []).append({'level': level, 'check': check, 'result': previous})
    artifact['checks'][level][check] = result
    atomic_json(path, data)
    render(path, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    init = commands.add_parser('init')
    init.add_argument('--report', type=Path, default=ROOT / 'app/build/reports/acceptance-baseline')
    update = commands.add_parser('record')
    update.add_argument('ledger', type=Path)
    for field in ['variant', 'level', 'check', 'status', 'apk']:
        update.add_argument('--' + field, required=True)
    update.add_argument('--device')
    update.add_argument('--evidence')
    update.add_argument('--tested-apk', help='Required for verification-build results; exact tested verification artifact')
    args = parser.parse_args()
    if args.command == 'init':
        print(initialize(args.report))
    else:
        record(args.ledger, args.variant, args.level, args.check, args.status, args.device, args.evidence, args.apk, args.tested_apk)


if __name__ == '__main__':
    main()
