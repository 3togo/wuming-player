#!/usr/bin/env python3
"""Reject removed SDK artifacts, imports, JNI libraries and APK DEX packages."""
import argparse
import io
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ENGINES = ('forcetech', 'jianpian', 'thunder', 'tvbus')
LIBRARIES = {'libjpa.so', 'libmitv.so', 'libp2p.so', 'libp3p.so', 'libxl_stat.so',
             'libxl_thunder_sdk.so', 'libtvcore.so'}
PACKAGES = ('com.forcetech.', 'com.gsoft.mitv.', 'com.p2p.P2PClass',
            'com.xunlei.downloadlib.', 'com.tvbus.engine.')
DEX_PACKAGES = tuple(('L' + name.replace('.', '/')).encode() for name in PACKAGES)


def archive(data, label, findings, depth=0):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for entry in z.infolist():
            name = entry.filename
            if Path(name).name in LIBRARIES:
                findings.append(f'{label}!{name}: removed native library')
            if name.endswith('.dex') and any(p in z.read(entry) for p in DEX_PACKAGES):
                findings.append(f'{label}!{name}: removed SDK package')
            if depth < 3 and name.endswith(('.jar', '.aar', '.zip')):
                child = z.read(entry)
                if zipfile.is_zipfile(io.BytesIO(child)):
                    archive(child, f'{label}!{name}', findings, depth + 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', action='append', type=Path, default=[])
    args = parser.parse_args()
    findings = []
    for engine in ENGINES:
        for path in [ROOT / f'app/libs/{engine}-release.aar', ROOT / f'isolated/vendor-snapshots/{engine}']:
            if path.exists(): findings.append(f'{path.relative_to(ROOT)}: removed SDK retained')
    paths = [ROOT / 'app/src/main', ROOT / 'app/src/mobile', ROOT / 'app/src/leanback']
    for path in paths:
        for file in path.rglob('*'):
            if file.name in LIBRARIES:
                findings.append(f'{file.relative_to(ROOT)}: removed native library')
            if file.suffix in ('.kt', '.java') and any(p in file.read_text() for p in PACKAGES):
                findings.append(f'{file.relative_to(ROOT)}: removed SDK reference')
    for file in (ROOT / 'app/libs').glob('*.aar'):
        archive(file.read_bytes(), str(file.relative_to(ROOT)), findings)
    for apk in args.apk:
        archive(apk.read_bytes(), apk.name, findings)
    if findings:
        raise SystemExit('\n'.join(findings))
    print(f'PASS: removed SDK source/artifact checks and {len(args.apk)} APK scans')


if __name__ == '__main__':
    main()
