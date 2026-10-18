#!/usr/bin/env python3
"""Generate, import, migrate, or validate the shared APK signing identity."""
import argparse
import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import uuid

from user_config import config_path, read_config, write_config

ROOT = Path(__file__).resolve().parent.parent
FIELDS = ('storeFile', 'storePassword', 'keyAlias', 'keyPassword', 'storeType')



def write_template():
    """Publish a deterministic, credential-free template in the project root."""
    template = {
        'buildPython': '',
        'signing': {
            'storeFile': '/absolute/path/to/signing-key.p12',
            'storeType': 'PKCS12',
            'keyAlias': 'wuming',
            'storePassword': 'REPLACE_WITH_PRIVATE_STORE_PASSWORD',
            'keyPassword': 'REPLACE_WITH_PRIVATE_KEY_PASSWORD',
            'keyAlgorithm': 'RSA',
            'keySize': 3072,
            'validityDays': 10000,
            'distinguishedName': 'CN=Wuming, OU=Android, O=Wuming',
        },
    }
    target = ROOT / 'wuming-config.example.json'
    content = json.dumps(template, indent=2) + '\n'
    if target.exists() and target.read_text() == content:
        return
    fd, temporary = tempfile.mkstemp(prefix='.config-template-', dir=ROOT)
    try:
        with os.fdopen(fd, 'w') as out:
            out.write(content)
        os.chmod(temporary, 0o644)
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def keytool(settings, *args):
    env = os.environ.copy()
    env['WUMING_STORE_PASSWORD'] = settings['storePassword']
    env['WUMING_KEY_PASSWORD'] = settings['keyPassword']
    result = subprocess.run([
        'keytool', *args, '-keystore', settings['storeFile'],
        '-storetype', settings['storeType'], '-alias', settings['keyAlias'],
        '-storepass:env', 'WUMING_STORE_PASSWORD',
        '-keypass:env', 'WUMING_KEY_PASSWORD',
    ], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    if result.returncode:
        # Do not print subprocess output: providers can include supplied values.
        raise ValueError('keytool failed; check the keystore, alias, passwords and store type')


def validate(settings):
    if any(not isinstance(settings.get(k), str) or not settings[k] for k in FIELDS):
        raise ValueError('Signing configuration is incomplete')
    if not Path(settings['storeFile']).is_absolute() or not Path(settings['storeFile']).is_file():
        raise ValueError('Configured keystore must be an existing absolute path; restore it from backup')
    keytool(settings, '-certreq')  # Validates private-key access, not just the store password.


def validate_project_identity(settings):
    policy = ROOT / 'updates.json'
    if not policy.exists():
        return
    env = os.environ.copy()
    env['WUMING_STORE_PASSWORD'] = settings['storePassword']
    result = subprocess.run(['keytool', '-exportcert', '-keystore', settings['storeFile'],
        '-storetype', settings['storeType'], '-alias', settings['keyAlias'],
        '-storepass:env', 'WUMING_STORE_PASSWORD'], env=env, capture_output=True)
    expected = json.loads(policy.read_text())['certificateSha256']
    if result.returncode or hashlib.sha256(result.stdout).hexdigest() != expected:
        raise ValueError('Wrong project signing identity; import the configured project key')


def initialize_project_identity(data, path, args):
    """Bootstrap an unpublished project's independent key; never replace an identity."""
    if args.ephemeral:
        raise ValueError('Production initialization cannot use --ephemeral')
    if path.resolve() == ROOT.resolve() or ROOT.resolve() in path.resolve().parents:
        raise ValueError('Production configuration must be outside the source repository')
    policy_path = ROOT / 'updates.json'
    original = policy_path.read_bytes()
    policy = json.loads(original)
    if policy.get('certificateSha256') or policy.get('repository'):
        raise ValueError('Initialization requires an unpublished project with an empty certificate pin and repository')
    if data.get('signing'):
        raise ValueError('Signing already exists; initialization never replaces or adopts a test identity')
    if (ROOT / 'local.properties').exists():
        props = legacy_properties(ROOT / 'local.properties')
        if any(props.get(k) for k in FIELDS):
            raise ValueError('Legacy signing settings exist; initialization refuses to replace an identity')
    directory = path.parent / 'keys'
    directory.mkdir(mode=0o700, exist_ok=True)
    password = secrets.token_urlsafe(32)
    settings = dict(storeFile=str(directory / (uuid.uuid4().hex + '.p12')),
                    keyAlias=args.alias, storePassword=password, keyPassword=password,
                    storeType=args.store_type, keyAlgorithm='RSA', keySize=3072,
                    validityDays=10000,
                    distinguishedName='CN=Wuming Player, OU=Android, O=Wuming')
    keytool(settings, '-genkeypair', '-keyalg', settings['keyAlgorithm'],
            '-keysize', str(settings['keySize']), '-validity', str(settings['validityDays']),
            '-dname', settings['distinguishedName'], '-noprompt')
    validate(settings)
    env = os.environ.copy()
    env['WUMING_STORE_PASSWORD'] = settings['storePassword']
    certificate = subprocess.run(['keytool', '-exportcert', '-keystore', settings['storeFile'],
        '-storetype', settings['storeType'], '-alias', settings['keyAlias'],
        '-storepass:env', 'WUMING_STORE_PASSWORD'], env=env, capture_output=True)
    if certificate.returncode:
        raise ValueError('Cannot export the new certificate; retain the generated key for recovery')
    fingerprint = hashlib.sha256(certificate.stdout).hexdigest()
    # Save the private identity first. If publishing the pin fails, retain this
    # key/config for recovery rather than silently generating another identity.
    data['signing'] = settings
    data['signingPurpose'] = 'production'
    write_config(data)
    if policy_path.read_bytes() != original:
        raise ValueError('Project policy changed during initialization; new key retained in private configuration')
    policy['certificateSha256'] = fingerprint
    fd, temporary = tempfile.mkstemp(prefix='.certificate-pin-', dir=ROOT)
    try:
        with os.fdopen(fd, 'w') as out:
            json.dump(policy, out, indent=2)
            out.write('\n')
        os.chmod(temporary, 0o644)
        os.replace(temporary, policy_path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    validate_project_identity(settings)
    write_template()
    print('Independent production identity initialized. Back up the private config and keystore before distribution.')
    print('Public certificate SHA-256: ' + fingerprint)


def legacy_properties(path):
    # Use the JDK parser, including escaped keys, Unicode and continuation lines.
    with tempfile.TemporaryDirectory() as folder:
        source = Path(folder) / 'ReadProperties.java'
        source.write_text('''import java.util.*; import java.nio.file.*;
class ReadProperties { public static void main(String[] args) throws Exception {
 Properties p = new Properties(); try (var in = Files.newInputStream(Path.of(args[0]))) { p.load(in); }
 var b = Base64.getEncoder(); for (String k : p.stringPropertyNames())
 System.out.println(b.encodeToString(k.getBytes("UTF-8")) + ":" + b.encodeToString(p.getProperty(k).getBytes("UTF-8")));
}}''')
        result = subprocess.run(['java', str(source), str(path)], check=True, capture_output=True, text=True)
    return {base64.b64decode(k).decode(): base64.b64decode(v).decode()
            for k, v in (line.split(':') for line in result.stdout.splitlines())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--initialize-project', action='store_true', help='Initialize an unpublished project with an empty pin using a new independent production identity')
    mode.add_argument('--rotate', action='store_true', help='Create a NEW identity; keep the old keystore. Existing installs require their old key.')
    mode.add_argument('--import-keystore', type=Path, help='Register an existing key (password prompts)')
    mode.add_argument('--migrate-local', action='store_true', help='Copy legacy local.properties settings, preserving the existing key')
    parser.add_argument('--ephemeral', action='store_true', help='Generate an isolated CI test identity; never publish these APKs')
    parser.add_argument('--alias', default='wuming')
    parser.add_argument('--store-type', choices=['JKS', 'PKCS12'], default='PKCS12')
    args = parser.parse_args()
    if config_path().resolve() == (ROOT / 'wuming-config.example.json').resolve():
        raise ValueError('Production config must be separate from the project template')
    os.umask(0o077)
    if not shutil.which('keytool'):
        raise ValueError('keytool is required; install a JDK')
    data = read_config()
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Exclusive lock prevents two setup processes from replacing each other's identity.
    lock = path.with_name(path.name + '.lock')
    fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        os.close(fd)
        data = read_config()
        if args.initialize_project:
            initialize_project_identity(data, path, args)
            return
        if args.migrate_local:
            if data.get('signing'):
                raise ValueError('User config already has signing settings; refusing to replace them during migration')
            props = legacy_properties(ROOT / 'local.properties')
            settings = {key: props.get(key, '') for key in FIELDS}
            settings['keyPassword'] = settings['keyPassword'] or settings['storePassword']
            settings['storeType'] = settings['storeType'] or 'JKS'
            if not settings['storeFile']:
                raise ValueError('local.properties has no storeFile')
            original = (ROOT / 'app' / settings['storeFile']).resolve()
            settings['storeFile'] = str(original)
            validate(settings)
            destination = path.parent / 'keys' / (uuid.uuid4().hex + original.suffix)
            destination.parent.mkdir(mode=0o700, exist_ok=True)
            shutil.copyfile(original, destination)
            destination.chmod(0o600)
            settings['storeFile'] = str(destination)
            if props.get('buildPython'):
                data['buildPython'] = str((ROOT / props['buildPython']).resolve())
        elif args.import_keystore:
            password = getpass.getpass('Keystore password: ')
            settings = dict(storeFile=str(args.import_keystore.expanduser().resolve()), keyAlias=args.alias,
                            storePassword=password, keyPassword=getpass.getpass('Key password (Enter = store password): ') or password,
                            storeType=args.store_type)
        elif data.get('signing') and not args.rotate:
            validate(data['signing'])
            if not args.ephemeral:
                validate_project_identity(data['signing'])
            path.chmod(0o600)
            write_template()
            print(f'Signing key validated; unchanged. Config: {path}')
            print(f'Template: {ROOT / "wuming-config.example.json"}')
            return
        else:
            if (ROOT / 'updates.json').exists() and not args.ephemeral:
                raise ValueError('This project has a shared signing identity. Import it; generating or rotating a developer key would break updates')
            if not args.rotate and (ROOT / 'local.properties').exists():
                props = legacy_properties(ROOT / 'local.properties')
                if any(props.get(k) for k in FIELDS):
                    raise ValueError('Legacy signing settings found. Run with --migrate-local to preserve the existing identity')
            directory = path.parent / 'keys'
            directory.mkdir(mode=0o700, exist_ok=True)
            password = secrets.token_urlsafe(32)
            settings = dict(storeFile=str(directory / (uuid.uuid4().hex + '.p12')),
                            keyAlias=args.alias, storePassword=password, keyPassword=password,
                            storeType=args.store_type, keyAlgorithm='RSA', keySize=3072,
                            validityDays=10000, distinguishedName='CN=Wuming, OU=Android, O=Wuming')
            keytool(settings, '-genkeypair', '-keyalg', settings['keyAlgorithm'],
                    '-keysize', str(settings['keySize']), '-validity', str(settings['validityDays']),
                    '-dname', settings['distinguishedName'], '-noprompt')
        validate(settings)
        if not args.ephemeral:
            validate_project_identity(settings)
        if data.get('signing') and (args.rotate or args.import_keystore):
            backup = path.with_name(path.name + '.' + uuid.uuid4().hex + '.bak')
            shutil.copyfile(path, backup)
            backup.chmod(0o600)
            print(f'Previous config backed up: {backup}')
        data['signing'] = settings
        write_config(data)
        write_template()
        print(f'Template: {ROOT / "wuming-config.example.json"}')
        print(f'Signing configured for all APKs. Config: {path}')
        print('Back up the config and keystore together. Keep the previous key for updates to existing installs.')
    finally:
        lock.unlink()


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        print(f'Signing setup failed: {error}', file=sys.stderr)
        sys.exit(1)
    except (OSError, subprocess.CalledProcessError):
        # No settings or subprocess output in diagnostics.
        print('Signing setup failed. Check configuration/key access; use --migrate-local for legacy settings. '
              'A missing configured key must be restored, not regenerated. If another setup is running, wait for it.', file=sys.stderr)
        sys.exit(1)
