#!/usr/bin/env bash
# Restore the shared identity from the privately distributed AES-encrypted ZIP.
set -euo pipefail
umask 077
archive="${1:-$(dirname -- "$0")/wuming-signing.zip}"
[[ -f "$archive" ]] || { echo "Signing ZIP not found: $archive" >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3 is required.' >&2; exit 1; }
command -v keytool >/dev/null || { echo 'Install a JDK providing keytool first.' >&2; exit 1; }
sevenzip="$(command -v 7zz || command -v 7z || true)"
[[ -n "$sevenzip" ]] || { echo 'Install 7-Zip (7zz or 7z) first; AES ZIP support is required.' >&2; exit 1; }
staging="$(mktemp -d)"
trap 'rm -rf -- "$staging"' EXIT
# Extract only data, never run scripts supplied by an archive.
"$sevenzip" e -y "-o$staging" -- "$archive" signing-bundle.json
python3 - "$staging/signing-bundle.json" <<'PY'
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

EXPECTED = '__WUMING_CERTIFICATE_SHA256__'
if len(EXPECTED) != 64 or any(c not in '0123456789abcdef' for c in EXPECTED):
    raise ValueError('Use the installer rendered by export-signing.py for this project')
os.umask(0o077)
config = Path(os.environ.get('WUMING_CONFIG_FILE', str(Path.home() / '.config/wuming-player/production/config.json'))).expanduser()
if not config.is_absolute():
    raise ValueError('WUMING_CONFIG_FILE must be absolute')
directory = config.parent
lock = config.with_name(config.name + '.lock')
key = None
scratch = None
locked = False
try:
    bundle = json.loads(Path(sys.argv[1]).read_text())
    settings = {k: bundle[k] for k in ('storePassword', 'keyPassword', 'keyAlias', 'storeType')}
    if not all(isinstance(v, str) and v for v in settings.values()):
        raise ValueError('Incomplete signing bundle')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if config.is_symlink():
        raise ValueError('Refusing a symlinked config')
    fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    locked = True
    data = json.loads(config.read_text()) if config.exists() else {}
    if not isinstance(data, dict):
        raise ValueError('Existing configuration must be a JSON object')
    if data.get('signing'):
        raise ValueError('Signing is already configured; existing identity was left unchanged')
    keys = directory / 'keys'
    keys.mkdir(mode=0o700, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='shared-', suffix='.jks', dir=keys)
    key = Path(name)
    with os.fdopen(fd, 'wb') as out:
        out.write(base64.b64decode(bundle['keystore'], validate=True))
    settings['storeFile'] = str(key)
    env = os.environ.copy()
    env['WUMING_STORE_PASSWORD'] = settings['storePassword']
    env['WUMING_KEY_PASSWORD'] = settings['keyPassword']
    command = ['keytool', '-keystore', str(key), '-storetype', settings['storeType'],
               '-alias', settings['keyAlias'], '-storepass:env', 'WUMING_STORE_PASSWORD']
    cert = subprocess.run(command + ['-exportcert'], env=env, capture_output=True)
    if cert.returncode or hashlib.sha256(cert.stdout).hexdigest() != EXPECTED:
        raise ValueError('Certificate does not match the shared project identity')
    result = subprocess.run(command + ['-certreq', '-keypass:env', 'WUMING_KEY_PASSWORD'],
                            env=env, capture_output=True)
    if result.returncode:
        raise ValueError('Unable to access the signing private key')
    data['signing'] = settings
    data['signingPurpose'] = 'production'
    if isinstance(data.get('buildPython'), str):
        try:
            relative = Path(data['buildPython']).expanduser().relative_to(Path.home())
            data['buildPython'] = '~/' + relative.as_posix()
        except ValueError:
            pass
    fd, scratch = tempfile.mkstemp(prefix='.config-', dir=directory)
    with os.fdopen(fd, 'w') as out:
        json.dump(data, out, indent=2)
        out.write('\n')
    os.replace(scratch, config)
    key = None  # Ownership has transferred to the installed configuration.
    print(f'Shared signing identity verified and installed: {config}')
except (ValueError, KeyError, OSError) as error:
    # Do not print provider output or bundle values containing credentials.
    if isinstance(error, FileExistsError):
        sys.exit('Signing setup is locked; another installer may be running.')
    if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError):
        sys.exit(str(error))
    sys.exit('Unable to install signing configuration; check the bundle and filesystem permissions.')
finally:
    if key is not None:
        key.unlink(missing_ok=True)
    if scratch is not None:
        Path(scratch).unlink(missing_ok=True)
    if locked:
        lock.unlink(missing_ok=True)
PY
