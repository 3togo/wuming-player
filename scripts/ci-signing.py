#!/usr/bin/env python3
"""Restore the shared signing identity from a private CI secret; never print credentials."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import sys

from user_config import config_path, read_config, write_config


def restore():
    bundle = json.loads(os.environ.pop('WUMING_SIGNING_BUNDLE'))
    if read_config().get('signing'):
        raise ValueError('Signing configuration already exists; refusing to replace it')
    directory = config_path().parent / 'keys'
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / 'project-signing.store'
    with path.open('xb') as out:
        out.write(base64.b64decode(bundle['keystore'], validate=True))
    path.chmod(0o600)
    settings = {key: bundle[key] for key in ('storePassword', 'keyPassword', 'keyAlias', 'storeType')}
    settings['storeFile'] = str(path.resolve())
    spec = importlib.util.spec_from_file_location('setup_signing', Path(__file__).with_name('setup-signing.py'))
    signing = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(signing)
    try:
        signing.validate(settings)
        signing.validate_project_identity(settings)
        data = read_config()
        data['signing'] = settings
        write_config(data)
    except Exception:
        path.unlink(missing_ok=True)
        raise
    print('Shared project signing identity restored and verified.')


if __name__ == '__main__':
    os.umask(0o077)
    try:
        restore()
    except Exception:
        sys.exit('Unable to restore the shared signing identity. Check the private CI secret.')
