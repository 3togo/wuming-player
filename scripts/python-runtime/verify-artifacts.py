#!/usr/bin/env python3
"""Offline ELF and packaged-runtime validation. Does not claim Android execution."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SYSTEM = {'libc.so','libm.so','libdl.so','liblog.so','libz.so','libandroid.so'}
report = {}
for abi, arch in [('arm64_v8a', 'AArch64'), ('armeabi_v7a', 'ARM')]:
    root = ROOT / 'build/python-native/android' / abi
    manifest = json.loads((root / 'assets/python/runtime.json').read_text())
    archive = root / 'assets/python/runtime.zip'
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest['sha256']
    libraries = list((root / 'jniLibs').rglob('*.so'))
    extensions = list((root / 'home').rglob('*.so'))
    names = {path.name for path in libraries}
    assert 'libwuming_python.so' in names and 'libpython3.13.so' in names
    for path in libraries + extensions:
        header = subprocess.check_output(['readelf', '-h', str(path)], text=True)
        assert re.search(r'Machine:\s+' + arch + r'\s', header), path
        dynamic = subprocess.check_output(['readelf', '-d', str(path)], text=True)
        needs = set(re.findall(r'\(NEEDED\).*\[([^]]+)\]', dynamic))
        assert needs <= names | SYSTEM, (path, needs - names - SYSTEM)
        segments = subprocess.check_output(['readelf', '-lW', str(path)], text=True)
        for line in segments.splitlines():
            if line.strip().startswith('LOAD'):
                assert int(line.split()[-1], 16) >= 0x4000, (path, line)
    symbols = subprocess.check_output(['nm', '-D', str(next(p for p in libraries if p.name == 'libwuming_python.so'))], text=True)
    assert 'Java_com_wuming_tv_core_python_NativePythonBridge_startNative' in symbols
    assert 'Java_com_wuming_tv_core_python_NativePythonBridge_dispatchNative' in symbols
    with zipfile.ZipFile(archive) as z:
        entries = z.namelist()
        for required in ['bridge/wuming_bridge.py', 'bridge/base/spider.py', 'lib/python3.13/encodings/__init__.py',
                         'lib/python3.13/site-packages/requests/__init__.py', 'lib/python3.13/site-packages/Crypto/Cipher/AES.py']:
            assert required in entries, required
        assert any('/lxml/etree.' in path and path.endswith('.so') for path in entries)
        assert not any('chaquo' in path.lower() for path in entries)
    report[abi] = {'jniLibraries': len(libraries), 'nativeExtensions': len(extensions), 'runtimeZipBytes': archive.stat().st_size,
                   'jniLibraryBytes': sum(p.stat().st_size for p in libraries), 'sha256': manifest['sha256'],
                   'elfArchitectureDependenciesAnd16KiBAlignment': 'passed', 'deviceExecution': 'not tested'}
print(json.dumps(report, indent=2))
