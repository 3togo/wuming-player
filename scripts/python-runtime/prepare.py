#!/usr/bin/env python3
"""Build TV's CPython JNI runtime. Downloads/builds are explicit, never Gradle configuration effects."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import sysconfig
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'build/python-native'
VERSION = '3.13.15'
HOSTS = {'arm64_v8a': 'aarch64-linux-android', 'armeabi_v7a': 'arm-linux-androideabi'}
ABIS = {'arm64_v8a': 'arm64-v8a', 'armeabi_v7a': 'armeabi-v7a'}


def run(*args, **kwargs):
    print('+', *map(str, args), flush=True)
    subprocess.run(list(map(str, args)), check=True, **kwargs)


def github_proxies():
    """Mirror hosts from github_proxies.json, in preference order."""
    cfg = ROOT / 'github_proxies.json'
    if not cfg.exists():
        return []
    try:
        data = json.loads(cfg.read_text())
    except Exception:
        return []
    return [str(p.get('host', '')).strip().strip('/') for p in data.get('proxies', [])
            if str(p.get('host', '')).strip()]


def download(url, dest):
    """Fetch a release archive, retrying GitHub URLs through the configured mirrors.

    GitHub blocks or times out from some networks; the mirror scheme (https://<host>/<url>)
    matches github_proxies.json so cross builds behave like the desktop client's fetches.
    """
    dest = Path(dest)
    candidates = [url]
    lowered = url.lower()
    if 'github.com' in lowered or 'raw.githubusercontent.com' in lowered:
        candidates += [f'https://{host}/{url}' for host in github_proxies()]
    dest.parent.mkdir(parents=True, exist_ok=True)
    partial = dest.with_name(dest.name + '.partial')
    last_error = None
    for candidate in candidates:
        try:
            run('curl', '-fL', '--retry', '2', '--connect-timeout', '15', '--max-time', '1200',
                candidate, '-o', partial)
            partial.replace(dest)
            return
        except subprocess.CalledProcessError as error:
            last_error = error
            partial.unlink(missing_ok=True)
    raise last_error


def copy_tree(source, target):
    shutil.copytree(source, target, dirs_exist_ok=True,
        ignore=shutil.ignore_patterns('__pycache__', '*.pyc', 'test', 'tests', 'idlelib', 'tkinter', 'ensurepip', 'config-*'))


def bundle(home, output):
    copy_tree(ROOT / 'libraries/python-runtime/src/main/python', home / 'bridge')
    copy_tree(ROOT / 'scripts/python-runtime/licenses', home / 'licenses')
    output.mkdir(parents=True, exist_ok=True)
    archive = output / 'runtime.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
        for path in sorted(home.rglob('*')):
            if path.is_file():
                entry = zipfile.ZipInfo(path.relative_to(home).as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                data = path.read_bytes()
                # CPython records build-time compiler paths in this metadata module.
                # Keep that metadata portable without editing runtime code or binaries.
                if path.name.startswith('_sysconfigdata') and path.suffix == '.py':
                    data = data.replace(str(ROOT).encode(), b'/build/source')
                    data = data.replace(str(Path.home()).encode(), b'/build/user')
                z.writestr(entry, data)
    metadata = {'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'python': VERSION,
                'bridge': 'wuming-jni-v1', 'dependencies': (ROOT / 'scripts/python-runtime/requirements.txt').read_text().splitlines()}
    (output / 'runtime.json').write_text(json.dumps(metadata, indent=2) + '\n')


def jni_includes():
    java = Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64'))
    return ['-I' + str(java / 'include'), '-I' + str(java / 'include/linux')]


def linux():
    version = f'{sys.version_info.major}.{sys.version_info.minor}'
    dest = WORK / 'linux'
    home = dest / 'home'
    lib = dest / 'lib'
    lib.mkdir(parents=True, exist_ok=True)
    stdlib = Path(sysconfig.get_path('stdlib'))
    target = home / f'lib/python{version}'
    # Do not accidentally bundle the machine's unrelated site packages.
    shutil.copytree(stdlib, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns(
        'site-packages', 'dist-packages', '__pycache__', '*.pyc', 'test', 'tests', 'idlelib', 'tkinter', 'ensurepip', 'config-*'))
    copy_tree(ROOT / 'libraries/python-runtime/src/main/python', home / 'bridge')
    copy_tree(ROOT / 'scripts/python-runtime/licenses', home / 'licenses')
    packages = target / 'site-packages'
    # Rebuilds must not depend on the package index: reinstall only when the pinned
    # requirements or this recipe change, exactly like the Android cross build does.
    marker = packages / '.wuming-requirements'
    policy = hashlib.sha256(((ROOT / 'scripts/python-runtime/requirements.txt').read_text()
        + Path(__file__).read_text()).encode()).hexdigest()
    if not (marker.is_file() and marker.read_text() == policy):
        # PIP_INDEX_URL (and pip config) decides the index; a regional mirror may be required.
        run(sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check', '--only-binary=:all:',
            '--retries', '3', '--timeout', '60',
            '--upgrade', '--no-deps', '--target', packages, '-r', ROOT / 'scripts/python-runtime/requirements.txt')
        packages.mkdir(parents=True, exist_ok=True)
        marker.write_text(policy)
    library = Path(sysconfig.get_config_var('LIBDIR')) / sysconfig.get_config_var('LDLIBRARY')
    shutil.copy2(library.resolve(), lib / library.name)
    soname = sysconfig.get_config_var('INSTSONAME')
    if soname != library.name:
        shutil.copy2(library.resolve(), lib / soname)
    run('cc', '-std=c11', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
        *jni_includes(), '-I' + sysconfig.get_path('include'), ROOT / 'native/python-runtime/bridge.c',
        '-L' + str(lib), '-lpython' + version, '-Wl,--disable-new-dtags,-rpath,$ORIGIN', '-lpthread', '-o', lib / 'libwuming_python.so')
    (dest / 'runtime.json').write_text(json.dumps({'python': version, 'home': 'home', 'library': 'lib/libwuming_python.so'}))


def android(abi, ndk):
    archive = WORK / f'Python-{VERSION}.tar.xz'
    if not archive.exists():
        download(f'https://www.python.org/ftp/python/{VERSION}/{archive.name}', archive)
    lock = ROOT / 'scripts/python-runtime/sources.json'
    if lock.exists():
        expected = json.loads(lock.read_text())['cpythonSha256']
        if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            raise RuntimeError('CPython archive checksum mismatch')
    else:
        raise RuntimeError('Missing CPython source checksum lock')
    source = WORK / f'Python-{VERSION}'
    if not source.exists():
        with tarfile.open(archive) as t: t.extractall(WORK, filter='data')
    env_script = source / 'Android/android-env.sh'
    text = env_script.read_text()
    import re
    text = re.sub(r'^ndk_version=.*$', 'ndk_version=' + ndk, text, flags=re.M)
    env_script.write_text(text)
    build_script = source / 'Android/android.py'
    text = build_script.read_text().replace('str(os.cpu_count())', 'str(min(8, os.cpu_count() or 1))')
    build_script.write_text(text)
    env = dict(os.environ, ANDROID_HOME=os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')), ANDROID_API_LEVEL='26')
    runner = [sys.executable, str(build_script)]
    if not (source / 'cross-build/build/python').exists():
        run(*runner, 'configure-build', env=env)
        run(*runner, 'make-build', env=env)
    host = HOSTS[abi]
    prefix = source / f'cross-build/{host}/prefix'
    dependencies = json.loads(lock.read_text())['androidDependencies']
    cache = source / 'cross-build/downloads'
    cache.mkdir(parents=True, exist_ok=True)
    for name, info in dependencies.items():
        if not name.endswith('-' + host + '.tar.gz'): continue
        archive_path = cache / name
        if not archive_path.exists():
            download(info['url'], archive_path)
        if hashlib.sha256(archive_path.read_bytes()).hexdigest() != info['sha256']:
            raise RuntimeError('CPython dependency checksum mismatch: ' + name)
    if not (prefix / 'lib/libpython3.13.so').exists():
        run(*runner, 'configure-host', host, env=env)
        run(*runner, 'make-host', host, env=env)
    run(sys.executable, ROOT / 'scripts/python-runtime/build-packages.py', '--source', source, '--host', host, env=env)
    dest = WORK / 'android' / abi
    home = dest / 'home'
    copy_tree(prefix / 'lib/python3.13', home / 'lib/python3.13')
    shutil.copy2(source / 'LICENSE', home / 'CPYTHON-LICENSE.txt')
    jni = dest / 'jniLibs' / ABIS[abi]
    jni.mkdir(parents=True, exist_ok=True)
    # Dependency archives contain aliases; package each SONAME once to avoid collisions.
    for old in jni.glob('*.so'): old.unlink()
    for path in (prefix / 'lib').glob('*.so*'):
        dynamic = subprocess.check_output(['readelf', '-d', str(path)], text=True)
        soname = re.search(r'\(SONAME\).*\[([^]]+)\]', dynamic)
        if soname: shutil.copy2(path.resolve(), jni / soname.group(1))
    sdk = Path(env['ANDROID_HOME'])
    tool = sdk / 'ndk' / ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    triple = 'armv7a-linux-androideabi' if abi == 'armeabi_v7a' else host
    run(tool / (triple + '26-clang'), '-std=c11', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
        *jni_includes(), '-I' + str(prefix / 'include/python3.13'), ROOT / 'native/python-runtime/bridge.c',
        '-L' + str(prefix / 'lib'), '-lpython3.13', '-ldl', '-Wl,-z,max-page-size=16384', '-Wl,--no-undefined',
        '-o', jni / 'libwuming_python.so')
    for binary in [*jni.glob('*.so'), *home.rglob('*.so')]:
        run(tool / 'llvm-strip', '--strip-unneeded', binary)
    bundle(home, dest / 'assets/python')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--linux', action='store_true')
    parser.add_argument('--android', choices=[*HOSTS, 'all'])
    parser.add_argument('--ndk', default='29.0.14206865')
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    if not args.linux and not args.android: parser.error('Select --linux or --android ABI')
    # Android ABI tasks share the host compiler and dependency caches.
    with (WORK / 'prepare.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.linux: linux()
        if args.android:
            for abi in HOSTS if args.android == 'all' else [args.android]: android(abi, args.ndk)


if __name__ == '__main__': main()
