#!/usr/bin/env python3
"""Create a private AES-256 ZIP and standalone installer for another developer."""
import argparse
import base64
import getpass
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile

from user_config import read_config


def render_installer(root):
    pin = json.loads((root / 'updates.json').read_text())['certificateSha256']
    if not isinstance(pin, str) or len(pin) != 64 or any(c not in '0123456789abcdef' for c in pin):
        raise ValueError('A pinned project certificate is required for recovery export')
    template = (root / 'scripts/install-signing.sh').read_text()
    marker = '__WUMING_CERTIFICATE_SHA256__'
    if template.count(marker) != 1:
        raise ValueError('Installer certificate marker is missing or ambiguous')
    return template.replace(marker, pin)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path, help='New private output directory outside the source repository')
    parser.add_argument('--generate-password', action='store_true', help='Save a random password separately as zip-password.txt')
    args = parser.parse_args()
    os.umask(0o077)
    root = Path(__file__).resolve().parent.parent
    destination = args.directory.expanduser().resolve()
    if destination == root or root in destination.parents:
        parser.error('Choose a directory outside the source repository')
    sevenzip = shutil.which('7zz') or shutil.which('7z')
    if not sevenzip:
        parser.error('7-Zip is required')
    spec = importlib.util.spec_from_file_location('setup_signing', root / 'scripts/setup-signing.py')
    signing = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(signing)
    settings = read_config()['signing']
    signing.validate(settings)
    signing.validate_project_identity(settings)
    password = secrets.token_urlsafe(32) if args.generate_password else getpass.getpass('New ZIP password: ')
    if len(password) < 16 or '\n' in password or '\r' in password:
        parser.error('Use a password of at least 16 characters, without newlines')
    if not args.generate_password and password != getpass.getpass('Confirm ZIP password: '):
        parser.error('Passwords do not match')
    installer = render_installer(root)
    destination.mkdir(parents=True, mode=0o700, exist_ok=False)
    bundle = {k: settings[k] for k in ('storePassword', 'keyPassword', 'keyAlias', 'storeType')}
    bundle['keystore'] = base64.b64encode(Path(settings['storeFile']).read_bytes()).decode('ascii')
    with tempfile.TemporaryDirectory(prefix='wuming-signing-') as temporary:
        Path(temporary, 'signing-bundle.json').write_text(json.dumps(bundle))
        Path(temporary, 'install-signing.sh').write_text(installer)
        result = subprocess.run([sevenzip, 'a', '-tzip', '-mem=AES256', '-p',
                                 str(destination / 'wuming-signing.zip'),
                                 'signing-bundle.json', 'install-signing.sh'],
                                cwd=temporary, input=password + '\n' + password + '\n',
                                text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError('Encrypted ZIP creation failed')
    (destination / 'install-signing.sh').write_text(installer)
    (destination / 'install-signing.sh').chmod(0o700)
    if args.generate_password:
        (destination / 'zip-password.txt').write_text(password + '\n')
    (destination / 'README.txt').write_text(
        'Requirements: Bash, Python 3, a JDK (keytool), and 7-Zip (7zz or 7z).\n'
        'Run: bash install-signing.sh wuming-signing.zip\n'
        'Enter the ZIP password when prompted. Do not run with sudo.\n'
        'Defaults to ~/.config/wuming-player/production/config.json.\n'
        'Set absolute WUMING_CONFIG_FILE to restore to another private location.\n'
        'Preserves unrelated settings and refuses to replace existing signing settings.\n'
        'The installer is also inside the ZIP if extracting with an AES ZIP application.\n'
        'Transfer the ZIP password separately from the ZIP. Keep all signing files private.\n')
    print(f'Private signing package created: {destination}')


if __name__ == '__main__':
    main()
