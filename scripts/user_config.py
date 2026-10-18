#!/usr/bin/env python3
"""Shared location and atomic storage for private build settings."""
import json
import copy
import os
from pathlib import Path
import tempfile


def config_path():
    override = os.environ.get('WUMING_CONFIG_FILE')
    base = os.environ.get('XDG_CONFIG_HOME') or str(Path.home() / '.config')
    path = Path(override) if override else Path(base) / 'wuming/config.json'
    if not path.is_absolute():
        raise ValueError('WUMING_CONFIG_FILE and XDG_CONFIG_HOME must be absolute paths')
    return path


def read_config():
    path = config_path()
    data = json.loads(path.read_text()) if path.exists() else {}
    if not isinstance(data, dict):
        raise ValueError('User config must be a JSON object')
    return map_config_paths(data, lambda value: str(Path(value).expanduser()))


def map_config_paths(data, transform):
    """Convert only known path fields; never touch passwords or other settings."""
    data = copy.deepcopy(data)
    if isinstance(data.get('buildPython'), str) and data['buildPython']:
        data['buildPython'] = transform(data['buildPython'])
    signing = data.get('signing')
    if isinstance(signing, dict) and isinstance(signing.get('storeFile'), str) and signing['storeFile']:
        signing['storeFile'] = transform(signing['storeFile'])
    return data


def portable_path(value):
    path = Path(value).expanduser()
    try:
        relative = path.relative_to(Path.home())
        return '~' if not relative.parts else '~/' + relative.as_posix()
    except ValueError:
        return value


def write_config(data):
    data = map_config_paths(data, portable_path)
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, name = tempfile.mkstemp(prefix='.config-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as out:
            json.dump(data, out, indent=2, ensure_ascii=False)
            out.write('\n')
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['path', 'get', 'set'])
    parser.add_argument('key', nargs='?', choices=['buildPython'])
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    if args.action == 'path':
        print(config_path())
    elif args.key is None:
        parser.error('key is required')
    elif args.action == 'get':
        print(read_config().get(args.key, ''))
    else:
        if args.value is None:
            parser.error('value is required')
        data = read_config()
        data[args.key] = args.value
        write_config(data)
