#!/usr/bin/env python3
"""Cross-compile lxml/PyCryptodome for a CPython Android prefix; install pinned pure wheels."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'build/python-native'


def run(*args, **kwargs):
    print('+', *map(str, args), flush=True)
    subprocess.run(list(map(str, args)), check=True, **kwargs)


def download(url, target, expected=None):
    if not target.exists():
        run('curl', '-fL', '--retry', '3', url, '-o', target)
    if expected and hashlib.sha256(target.read_bytes()).hexdigest() != expected:
        raise RuntimeError('Source checksum mismatch: ' + str(target))
    return target


def source_package(name, version):
    info = json.loads((ROOT / 'scripts/python-runtime/sources.json').read_text())['pythonSources'][name]
    assert info['version'] == version
    target = WORK / 'package-sources' / info['filename']
    target.parent.mkdir(parents=True, exist_ok=True)
    download(info['url'], target, info['sha256'])
    return target


def extract(archive, target):
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as t:
        t.extractall(target, filter='data')
    return next(p for p in target.iterdir() if p.is_dir())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--host', required=True)
    args = parser.parse_args()
    prefix = args.source / 'cross-build' / args.host / 'prefix'
    target = prefix / 'lib/python3.13/site-packages'
    requirements = (ROOT / 'scripts/python-runtime/requirements.txt').read_text()
    marker = target / '.wuming-requirements'
    policy = hashlib.sha256((requirements + Path(__file__).read_text()).encode()).hexdigest()
    if marker.exists() and marker.read_text() == policy: return
    work = WORK / 'packages' / args.host
    work.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, HOST=args.host, PREFIX=str(prefix))
    script = args.source / 'Android/android-env.sh'
    # Source the official CPython cross environment and capture values without shell interpolation.
    raw = subprocess.check_output(['bash', '-c', 'source "$1"; env -0', 'bash', str(script)], env=env)
    env.update(dict(x.decode().split('=', 1) for x in raw.split(b'\0') if b'=' in x))
    tools = WORK / 'build-tools'
    if not (tools / 'setuptools').exists():
        run(sys.executable, '-m', 'pip', 'install', '--no-cache-dir', '--retries', '3', '--timeout', '60',
            '--target', tools, 'setuptools==80.9.0', 'wheel==0.45.1', 'packaging==25.0')
    pure = [line.strip() for line in requirements.splitlines() if line.strip() and not line.startswith(('lxml==', 'pycryptodome=='))]
    # All versions, including transitive packages, are pinned in requirements.txt.
    # PIP_INDEX_URL (and pip config) decides the index; a regional mirror may be required.
    run(sys.executable, '-m', 'pip', 'install', '--no-deps', '--only-binary=:all:', '--platform', 'any',
        '--python-version', '3.13', '--upgrade', '--retries', '3', '--timeout', '60', '--target', target, *pure)
    native_prefix = work / 'native'
    native_prefix.mkdir(exist_ok=True)
    for name, version, options in [
        ('libxml2', '2.13.8', ['--without-python', '--without-lzma', '--without-iconv', '--without-zlib']),
        ('libxslt', '1.1.43', ['--without-python', '--without-crypto', '--with-libxml-prefix=' + str(native_prefix)]),
    ]:
        if (native_prefix / 'lib' / ('libxml2.a' if name == 'libxml2' else 'libxslt.a')).exists(): continue
        archive = work / f'{name}-{version}.tar.xz'
        base = f'https://download.gnome.org/sources/{name}/{version.rsplit(".", 1)[0]}'
        checksum = work / f'{name}-{version}.sha256sum'
        download(f'{base}/{name}-{version}.sha256sum', checksum)
        expected = next(line.split()[0] for line in checksum.read_text().splitlines() if line.endswith(archive.name))
        download(f'{base}/{archive.name}', archive, expected)
        source = extract(archive, work / name)
        build_env = dict(env, CFLAGS=env['CFLAGS'] + ' -fPIC', PKG_CONFIG_LIBDIR=str(native_prefix / 'lib/pkgconfig'))
        run(source / 'configure', '--host=' + args.host, '--prefix=' + str(native_prefix), '--disable-shared', '--enable-static', *options, cwd=source, env=build_env)
        run('make', '-j4', cwd=source, env=build_env)
        run('make', 'install', cwd=source, env=build_env)
    host_python = args.source / 'cross-build/build/python'
    sysconfig_data = next((prefix / 'lib/python3.13').glob('_sysconfigdata_*.py')).stem
    env.update(PYTHONPATH=os.pathsep.join([str(tools), str(prefix / 'lib/python3.13')]),
        _PYTHON_SYSCONFIGDATA_NAME=sysconfig_data, _PYTHON_HOST_PLATFORM='android',
        LDSHARED=env['CC'] + ' -shared', CC=env['CC'],
        CFLAGS='-I' + str(prefix / 'include/python3.13') + ' ' + env['CFLAGS'] + ' -fPIC -O2',
        LDFLAGS=env['LDFLAGS'] + ' -lpython3.13',
        LXML_STATIC_INCLUDE_DIRS=os.pathsep.join([str(native_prefix / 'include/libxml2'), str(native_prefix / 'include')]),
        LXML_STATIC_LIBRARY_DIRS=str(native_prefix / 'lib'),
        LIBRARY=str(native_prefix / 'lib'), INCLUDE=str(native_prefix / 'include/libxml2'),
        LXML_STATIC_BINARIES=os.pathsep.join(str(native_prefix / 'lib' / name) for name in ['libexslt.a', 'libxslt.a', 'libxml2.a']),
        XML2_CONFIG=str(native_prefix / 'bin/xml2-config'), XSLT_CONFIG=str(native_prefix / 'bin/xslt-config'))
    for name, version in [('lxml', '6.0.2'), ('pycryptodome', '3.23.0')]:
        source = extract(source_package(name, version), work / name)
        # Linux build host must not add librt to Android extension linkage.
        if name == 'lxml':
            info = source / 'setupinfo.py'
            info.write_text(info.read_text().replace("standard_libs.append('rt')", 'pass'))
        if name == 'pycryptodome':
            options = source / 'compiler_opt.py'
            options.write_text(options.read_text().replace('8 * struct.calcsize("P")',
                '8 * int(__import__("sysconfig").get_config_var("SIZEOF_VOID_P"))'))
        command = [host_python, 'setup.py'] + (['--static'] if name == 'lxml' else []) + ['build', '--force', '--build-base=' + str(work / (name + '-build'))]
        run(*command, cwd=source, env=env)
        built = next((work / (name + '-build')).glob('lib.*'))
        shutil.copytree(built, target, dirs_exist_ok=True)
        for license in source.glob('*LICENSE*'):
            if license.is_file(): shutil.copy2(license, target / (name + '-' + license.name))
    marker.write_text(policy)


if __name__ == '__main__': main()
