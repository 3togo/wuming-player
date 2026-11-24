#!/usr/bin/env python3
"""Copy the newest encrypted desktop credential backup to an authorized ADB device.

This stages a backup for manual import; it never imports or replaces saved accounts.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import uuid

SUFFIX = '.tvbox-credentials.json'
PACKAGE = re.compile(r'com\.wuming\.android\.tv(?:\.(?:publicedition|privateedition))?(?:\.verification)?\Z')


def newest_backup(directory):
    files = [p for p in directory.glob('*' + SUFFIX) if p.is_file() and not p.is_symlink()]
    if not files:
        raise ValueError(f'No credential backups in {directory}')
    return max(files, key=lambda p: (p.stat().st_mtime_ns, p.name))


def read_backup(path):
    if not path.is_file() or path.is_symlink():
        raise ValueError('Backup must be a regular file, not a symlink')
    with path.open('rb') as stream:
        data = stream.read(2 * 1024 * 1024 + 1)
    if not 128 <= len(data) <= 2 * 1024 * 1024:
        raise ValueError('Invalid encrypted backup size')
    try:
        envelope = json.loads(data)
        valid = (
            envelope['format'] in ('tvbox-account-credentials', 'tvbox-opendrive-credentials')
            and envelope['version'] in (1, 2)
            and envelope['kdf'] == 'PBKDF2WithHmacSHA256'
            and envelope['iterations'] == 210000
            and len(base64.b64decode(envelope['salt'], validate=True)) == 16
            and len(base64.b64decode(envelope['iv'], validate=True)) == 12
            and len(base64.b64decode(envelope['ciphertext'], validate=True)) > 16
        )
    except (ValueError, KeyError, TypeError):
        valid = False
    if not valid:
        raise ValueError('Not a supported encrypted credential backup')
    return data


def select_one(values, requested, label, option):
    if requested:
        if requested not in values:
            raise ValueError(f'{label} {requested} is not available')
        return requested
    if len(values) != 1:
        raise ValueError(f'Expected one {label}; found {values}. Specify {option}.')
    return values[0]


def destination(sdk, package):
    if sdk >= 30:
        return f'/sdcard/Android/data/{package}/files/credentials'
    label = '無名(testing)' if package.endswith('.verification') else '無名'
    return f'/sdcard/{label}/credentials'


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=60).stdout.strip()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('-s', '--serial', help='Authorized device serial; required if several are attached')
    parser.add_argument('--package', help='Installed app package; required if several editions are installed')
    parser.add_argument('--backup-dir', type=Path, default=Path(os.environ.get('XDG_CONFIG_HOME') or Path.home() / '.config') / '無名/credentials')
    parser.add_argument('--file', type=Path, help='Use this encrypted backup instead of the newest one')
    parser.add_argument('--dry-run', action='store_true', help='Inspect device and show the plan without copying')
    args = parser.parse_args(argv)
    try:
        source = args.file or newest_backup(args.backup_dir.expanduser())
        source = source.expanduser().absolute()
        data = read_backup(source)
        devices = [line.split()[0] for line in run('adb', 'devices').splitlines()[1:]
                   if len(line.split()) >= 2 and line.split()[1] == 'device']
        serial = select_one(devices, args.serial, 'authorized device', '--serial')
        adb = ['adb', '-s', serial]
        def shell(*parts):
            return run(*adb, 'shell', shlex.join(parts))
        packages = [line.removeprefix('package:') for line in shell('pm', 'list', 'packages').splitlines()]
        packages = [p for p in packages if PACKAGE.fullmatch(p)]
        package = select_one(packages, args.package, 'Wuming app', '--package')
        sdk = int(shell('getprop', 'ro.build.version.sdk'))
        if sdk < 26:
            raise ValueError('This Android version is below the supported minimum (Android 8)')
        folder = destination(sdk, package)
        target = folder + '/' + source.name
        digest = hashlib.sha256(data).hexdigest()
        print(f'Backup: {source}\nDevice: {serial}\nApp: {package}\nAndroid API: {sdk}\nDestination: {target}\nSHA-256: {digest}')
        if sdk >= 30:
            print('Requires the scoped-storage credential picker fix to list this app-owned folder.')
        if args.dry_run:
            print('Dry run: no files changed.')
            return 0
        shell('mkdir', '-p', folder)
        # An existing backup is never overwritten, even if it has the same filename.
        if shell('sh', '-c', 'if [ -e "$1" ]; then echo exists; fi', 'sh', target) == 'exists':
            raise ValueError('Destination already exists; existing backups are preserved')
        staging = folder + '/.credential-transfer-' + uuid.uuid4().hex + '.partial'
        try:
            with tempfile.TemporaryDirectory(prefix='wuming-credential-transfer-') as temporary:
                upload = Path(temporary) / 'encrypted-backup.json'
                upload.write_bytes(data)
                upload.chmod(0o600)
                run(*adb, 'push', str(upload), staging)
                verified = Path(temporary) / 'verified.json'
                run(*adb, 'pull', staging, str(verified))
                if hashlib.sha256(verified.read_bytes()).hexdigest() != digest:
                    raise ValueError('Transferred backup failed SHA-256 verification')
            # Emulated storage does not support hard links; publish with no-clobber mv.
            shell('mv', '-n', staging, target)
            if shell('sh', '-c', 'if [ -e "$1" ]; then echo exists; fi', 'sh', staging) == 'exists':
                raise ValueError('Destination appeared during transfer; existing backup preserved')
        finally:
            shell('rm', '-f', staging)
        print('Copied and verified. Open Cloud accounts → Import accounts and enter the backup password.')
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'Credential copy failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
